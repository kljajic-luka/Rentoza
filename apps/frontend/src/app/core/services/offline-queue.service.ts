/**
 * Offline Queue Service
 *
 * Persists failed uploads and form submissions to IndexedDB for retry when connectivity restores.
 * Implements the "Basement Problem" solution - photos taken offline can be
 * uploaded later without data loss.
 *
 * ## Architecture Decision
 * Uses IndexedDB via idb-keyval pattern for simplicity.
 * Falls back to in-memory queue if IndexedDB is unavailable.
 *
 * ## Sync Strategy (Phase 3: Real-time + Offline)
 * - **Exponential Backoff**: Prevents server flooding during spotty 4G
 *   - Base delay: 1s → 2s → 4s → 8s → 16s → max 32s
 *   - Jitter: ±20% randomization to prevent thundering herd
 * - Manual "Retry All" button in UI
 * - Background sync when online event fires (if supported)
 * - Supports both photo uploads and form submissions
 *
 * ## Rate Limiting
 * - Max 3 concurrent uploads
 * - Respects 429 (Too Many Requests) responses
 */

import { Injectable, signal, computed, OnDestroy, inject } from '@angular/core';
import {
  QueuedUpload,
  CheckInPhotoType,
  GuestConditionAcknowledgmentDTO,
  HandshakeConfirmationDTO,
  HostCheckInSubmissionDTO,
} from '@core/models/check-in.model';
import { generateUUID } from '../utils/uuid';
import { LoggerService } from './logger.service';

const DB_NAME = 'rentoza-offline-queue';
const STORE_NAME = 'pending-uploads';
const FORM_STORE_NAME = 'pending-forms';
const DB_VERSION = 2; // Bumped for new store

// Exponential backoff configuration
const BACKOFF_CONFIG = {
  baseDelayMs: 1000, // 1 second
  maxDelayMs: 32000, // 32 seconds max
  maxRetries: 10, // Give up after 10 retries
  jitterFactor: 0.2, // ±20% randomization
};

/**
 * Queued form submission for offline processing.
 */
export type QueuedFormSubmissionType = 'HOST_CHECK_IN' | 'GUEST_ACKNOWLEDGE' | 'HANDSHAKE';

export interface QueuedFormPayloadMap {
  HOST_CHECK_IN: HostCheckInSubmissionDTO;
  GUEST_ACKNOWLEDGE: GuestConditionAcknowledgmentDTO;
  HANDSHAKE: HandshakeConfirmationDTO;
}

export interface QueuedFormSubmission<TType extends QueuedFormSubmissionType = QueuedFormSubmissionType> {
  id: string;
  bookingId: number;
  type: TType;
  payload: QueuedFormPayloadMap[TType];
  retryCount: number;
  createdAt: string;
  lastAttempt?: string;
  error?: string;
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null;
}

function isFiniteNumber(value: unknown): value is number {
  return typeof value === 'number' && Number.isFinite(value);
}

function isOptionalFiniteNumber(value: unknown): value is number | undefined {
  return value === undefined || isFiniteNumber(value);
}

function isOptionalString(value: unknown): value is string | undefined {
  return value === undefined || typeof value === 'string';
}

function isNumberArray(value: unknown): value is number[] {
  return Array.isArray(value) && value.every(isFiniteNumber);
}

function isHotspotArray(value: unknown): value is GuestConditionAcknowledgmentDTO['hotspots'] {
  return (
    Array.isArray(value) &&
    value.every(
      (item) =>
        isRecord(item) &&
        typeof item['location'] === 'string' &&
        typeof item['description'] === 'string' &&
        isOptionalFiniteNumber(item['photoId'])
    )
  );
}

export function isHostCheckInSubmissionPayload(payload: unknown): payload is HostCheckInSubmissionDTO {
  return (
    isRecord(payload) &&
    isFiniteNumber(payload['bookingId']) &&
    isFiniteNumber(payload['odometerReading']) &&
    isFiniteNumber(payload['fuelLevelPercent']) &&
    isNumberArray(payload['photoIds']) &&
    isOptionalString(payload['lockboxCode']) &&
    isOptionalFiniteNumber(payload['hostLatitude']) &&
    isOptionalFiniteNumber(payload['hostLongitude'])
  );
}

export function isGuestConditionAcknowledgmentPayload(
  payload: unknown
): payload is GuestConditionAcknowledgmentDTO {
  return (
    isRecord(payload) &&
    isFiniteNumber(payload['bookingId']) &&
    typeof payload['conditionAccepted'] === 'boolean' &&
    isFiniteNumber(payload['guestLatitude']) &&
    isFiniteNumber(payload['guestLongitude']) &&
    isOptionalString(payload['conditionComment']) &&
    (payload['hotspots'] === undefined || isHotspotArray(payload['hotspots'])) &&
    (payload['disputePreExistingDamage'] === undefined ||
      typeof payload['disputePreExistingDamage'] === 'boolean') &&
    isOptionalString(payload['damageDisputeDescription']) &&
    (payload['disputedPhotoIds'] === undefined || isNumberArray(payload['disputedPhotoIds'])) &&
    isOptionalString(payload['disputeType'])
  );
}

export function isHandshakeConfirmationPayload(payload: unknown): payload is HandshakeConfirmationDTO {
  return (
    isRecord(payload) &&
    isFiniteNumber(payload['bookingId']) &&
    typeof payload['confirmed'] === 'boolean' &&
    (payload['hostVerifiedPhysicalId'] === undefined ||
      typeof payload['hostVerifiedPhysicalId'] === 'boolean') &&
    isOptionalFiniteNumber(payload['latitude']) &&
    isOptionalFiniteNumber(payload['longitude']) &&
    (payload['isMockLocation'] === undefined || typeof payload['isMockLocation'] === 'boolean') &&
    isOptionalFiniteNumber(payload['horizontalAccuracy']) &&
    (payload['platform'] === undefined ||
      payload['platform'] === 'ANDROID' ||
      payload['platform'] === 'IOS' ||
      payload['platform'] === 'WEB') &&
    isOptionalString(payload['deviceFingerprint'])
  );
}

export function isQueuedFormSubmission(value: unknown): value is QueuedFormSubmission {
  if (!isRecord(value)) {
    return false;
  }

  const commonFieldsValid =
    typeof value['id'] === 'string' &&
    isFiniteNumber(value['bookingId']) &&
    isFiniteNumber(value['retryCount']) &&
    typeof value['createdAt'] === 'string' &&
    isOptionalString(value['lastAttempt']) &&
    isOptionalString(value['error']);

  if (!commonFieldsValid || typeof value['type'] !== 'string') {
    return false;
  }

  switch (value['type']) {
    case 'HOST_CHECK_IN':
      return isHostCheckInSubmissionPayload(value['payload']);
    case 'GUEST_ACKNOWLEDGE':
      return isGuestConditionAcknowledgmentPayload(value['payload']);
    case 'HANDSHAKE':
      return isHandshakeConfirmationPayload(value['payload']);
    default:
      return false;
  }
}

export function isQueuedFormPayloadForType<TType extends QueuedFormSubmissionType>(
  type: TType,
  payload: unknown
): payload is QueuedFormPayloadMap[TType] {
  switch (type) {
    case 'HOST_CHECK_IN':
      return isHostCheckInSubmissionPayload(payload);
    case 'GUEST_ACKNOWLEDGE':
      return isGuestConditionAcknowledgmentPayload(payload);
    case 'HANDSHAKE':
      return isHandshakeConfirmationPayload(payload);
    default:
      return false;
  }
}

@Injectable({ providedIn: 'root' })
export class OfflineQueueService implements OnDestroy {
  private readonly logger = inject(LoggerService);

  // Reactive state for photo uploads
  private readonly _queue = signal<QueuedUpload[]>([]);
  private readonly _isOnline = signal(navigator.onLine);
  private readonly _isSyncing = signal(false);
  private readonly _lastSyncError = signal<string | null>(null);

  // Reactive state for form submissions
  private readonly _formQueue = signal<QueuedFormSubmission[]>([]);

  readonly queue = this._queue.asReadonly();
  readonly formQueue = this._formQueue.asReadonly();
  readonly isOnline = this._isOnline.asReadonly();
  readonly isSyncing = this._isSyncing.asReadonly();
  readonly lastSyncError = this._lastSyncError.asReadonly();

  readonly queueCount = computed(() => this._queue().length);
  readonly queueLength = computed(() => this._queue().length);
  readonly formQueueLength = computed(() => this._formQueue().length);
  readonly hasQueuedItems = computed(
    () => this._queue().length > 0 || this._formQueue().length > 0
  );
  readonly isProcessing = this._isSyncing.asReadonly();

  private db: IDBDatabase | null = null;
  private onlineHandler = () => this.handleOnline();
  private offlineHandler = () => this.handleOffline();

  constructor() {
    this.initDatabase();
    this.setupNetworkListeners();
  }

  ngOnDestroy(): void {
    window.removeEventListener('online', this.onlineHandler);
    window.removeEventListener('offline', this.offlineHandler);
    this.db?.close();
  }

  /**
   * Add an upload to the offline queue.
   */
  async enqueue(
    bookingId: number,
    photoType: CheckInPhotoType,
    file: Blob,
    clientTimestamp: string
  ): Promise<string> {
    const id = generateUUID();

    const item: QueuedUpload = {
      id,
      bookingId,
      photoType,
      file,
      clientTimestamp,
      retryCount: 0,
      createdAt: new Date().toISOString(),
    };

    await this.saveToDb(item);
    this._queue.update((q) => [...q, item]);

    this.logger.log(`[OfflineQueue] Enqueued: ${photoType} for booking ${bookingId}`);
    return id;
  }

  /**
   * Remove an item from the queue (after successful upload).
   */
  async dequeue(id: string): Promise<void> {
    await this.removeFromDb(id);
    this._queue.update((q) => q.filter((item) => item.id !== id));
    this.logger.log(`[OfflineQueue] Dequeued: ${id}`);
  }

  /**
   * Update an item (e.g., increment retry count).
   */
  async updateItem(id: string, updates: Partial<QueuedUpload>): Promise<void> {
    const item = this._queue().find((i) => i.id === id);
    if (!item) return;

    const updated = { ...item, ...updates };
    await this.saveToDb(updated);
    this._queue.update((q) => q.map((i) => (i.id === id ? updated : i)));
  }

  /**
   * Get all queued items for a specific booking.
   */
  getItemsForBooking(bookingId: number): QueuedUpload[] {
    return this._queue().filter((item) => item.bookingId === bookingId);
  }

  /**
   * Clear all items for a booking (e.g., after successful check-in).
   */
  async clearBooking(bookingId: number): Promise<void> {
    const items = this.getItemsForBooking(bookingId);
    for (const item of items) {
      await this.dequeue(item.id);
    }
    this.logger.log(`[OfflineQueue] Cleared all items for booking ${bookingId}`);
  }

  /**
   * Process the queue - attempt to upload all pending items with exponential backoff.
   * Returns array of successfully synced item IDs.
   */
  async processQueue(uploadFn: (item: QueuedUpload) => Promise<boolean>): Promise<string[]> {
    if (this._isSyncing()) {
      this.logger.log('[OfflineQueue] Sync already in progress');
      return [];
    }

    if (!this._isOnline()) {
      this.logger.log('[OfflineQueue] Offline - skipping sync');
      return [];
    }

    this._isSyncing.set(true);
    this._lastSyncError.set(null);
    const synced: string[] = [];

    try {
      const items = [...this._queue()];

      for (const item of items) {
        // Check if max retries exceeded
        if (item.retryCount >= BACKOFF_CONFIG.maxRetries) {
          this.logger.warn(`[OfflineQueue] Max retries exceeded for ${item.id}, skipping`);
          continue;
        }

        // Apply exponential backoff delay before retry
        if (item.retryCount > 0) {
          const delay = this.calculateBackoffDelay(item.retryCount);
          this.logger.log(`[OfflineQueue] Waiting ${delay}ms before retry #${item.retryCount + 1}`);
          await this.sleep(delay);

          // Re-check online status after delay
          if (!this._isOnline()) {
            this.logger.log('[OfflineQueue] Lost connection during backoff, stopping sync');
            break;
          }
        }

        try {
          const success = await uploadFn(item);

          if (success) {
            await this.dequeue(item.id);
            synced.push(item.id);
          } else {
            await this.updateItem(item.id, {
              retryCount: item.retryCount + 1,
              lastAttempt: new Date().toISOString(),
              error: 'Upload failed',
            });
          }
        } catch (error) {
          const errorMessage = error instanceof Error ? error.message : 'Unknown error';

          // Check for rate limiting
          if (this.isRateLimitError(error)) {
            this.logger.warn('[OfflineQueue] Rate limited, applying longer backoff');
            await this.sleep(BACKOFF_CONFIG.maxDelayMs);
          }

          await this.updateItem(item.id, {
            retryCount: item.retryCount + 1,
            lastAttempt: new Date().toISOString(),
            error: errorMessage,
          });
        }
      }

      this.logger.log(`[OfflineQueue] Sync complete: ${synced.length}/${items.length} successful`);
    } catch (error) {
      const errorMessage = error instanceof Error ? error.message : 'Sync failed';
      this._lastSyncError.set(errorMessage);
      this.logger.error('[OfflineQueue] Sync error:', error);
    } finally {
      this._isSyncing.set(false);
    }

    return synced;
  }

  /**
   * Calculate exponential backoff delay with jitter.
   * Formula: min(baseDelay * 2^retryCount, maxDelay) * (1 ± jitter)
   */
  private calculateBackoffDelay(retryCount: number): number {
    const exponentialDelay = Math.min(
      BACKOFF_CONFIG.baseDelayMs * Math.pow(2, retryCount),
      BACKOFF_CONFIG.maxDelayMs
    );

    // Add jitter: random value between -jitter and +jitter
    const jitter = 1 + (Math.random() * 2 - 1) * BACKOFF_CONFIG.jitterFactor;

    return Math.round(exponentialDelay * jitter);
  }

  /**
   * Check if error is a rate limit (429) response.
   */
  private isRateLimitError(error: unknown): boolean {
    if (error && typeof error === 'object' && 'status' in error) {
      return (error as { status: number }).status === 429;
    }
    return false;
  }

  /**
   * Sleep helper for backoff delays.
   */
  private sleep(ms: number): Promise<void> {
    return new Promise((resolve) => setTimeout(resolve, ms));
  }

  /**
   * Get queue statistics.
   */
  getStats(): { total: number; failed: number; pending: number } {
    const items = this._queue();
    return {
      total: items.length,
      failed: items.filter((i) => i.retryCount > 0).length,
      pending: items.filter((i) => i.retryCount === 0).length,
    };
  }

  // ========== FORM SUBMISSION QUEUE METHODS ==========

  /**
   * Add a form submission to the offline queue.
   * Used for host check-in completion, guest acknowledgment, and handshake.
   */
  async enqueueFormSubmission<TType extends QueuedFormSubmissionType>(
    bookingId: number,
    type: TType,
    payload: QueuedFormPayloadMap[TType]
  ): Promise<string> {
    if (!isQueuedFormPayloadForType(type, payload)) {
      throw new Error(`Invalid offline form payload for type ${type}`);
    }

    if (payload.bookingId !== bookingId) {
      throw new Error(`Offline form payload bookingId mismatch for type ${type}`);
    }

    const id = generateUUID();

    const item: QueuedFormSubmission = {
      id,
      bookingId,
      type,
      payload,
      retryCount: 0,
      createdAt: new Date().toISOString(),
    };

    await this.saveFormToDb(item);
    this._formQueue.update((q) => [...q, item]);

    this.logger.log(`[OfflineQueue] Enqueued form: ${type} for booking ${bookingId}`);
    return id;
  }

  /**
   * Remove a form submission from the queue (after successful submission).
   */
  async dequeueForm(id: string): Promise<void> {
    await this.removeFormFromDb(id);
    this._formQueue.update((q) => q.filter((item) => item.id !== id));
    this.logger.log(`[OfflineQueue] Dequeued form: ${id}`);
  }

  /**
   * Update a form submission (e.g., increment retry count).
   */
  async updateFormItem(id: string, updates: Partial<QueuedFormSubmission>): Promise<void> {
    const item = this._formQueue().find((i) => i.id === id);
    if (!item) return;

    const updated = { ...item, ...updates };
    await this.saveFormToDb(updated);
    this._formQueue.update((q) => q.map((i) => (i.id === id ? updated : i)));
  }

  /**
   * Get all queued form submissions for a specific booking.
   */
  getFormSubmissionsForBooking(bookingId: number): QueuedFormSubmission[] {
    return this._formQueue().filter((item) => item.bookingId === bookingId);
  }

  /**
   * Clear all form submissions for a booking.
   */
  async clearFormSubmissionsForBooking(bookingId: number): Promise<void> {
    const items = this.getFormSubmissionsForBooking(bookingId);
    for (const item of items) {
      await this.dequeueForm(item.id);
    }
    this.logger.log(`[OfflineQueue] Cleared all form submissions for booking ${bookingId}`);
  }

  /**
   * Process the form submission queue with exponential backoff.
   */
  async processFormQueue(
    submitFn: (item: QueuedFormSubmission) => Promise<boolean>
  ): Promise<string[]> {
    if (this._isSyncing()) {
      this.logger.log('[OfflineQueue] Sync already in progress');
      return [];
    }

    if (!this._isOnline()) {
      this.logger.log('[OfflineQueue] Offline - skipping form sync');
      return [];
    }

    this._isSyncing.set(true);
    this._lastSyncError.set(null);
    const synced: string[] = [];

    try {
      const items = [...this._formQueue()];

      for (const item of items) {
        if (item.retryCount >= BACKOFF_CONFIG.maxRetries) {
          this.logger.warn(`[OfflineQueue] Max retries exceeded for form ${item.id}, skipping`);
          continue;
        }

        if (!isQueuedFormPayloadForType(item.type, item.payload)) {
          this.logger.warn(`[OfflineQueue] Dropping invalid form payload ${item.id}`);
          await this.dequeueForm(item.id);
          continue;
        }

        if (item.retryCount > 0) {
          const delay = this.calculateBackoffDelay(item.retryCount);
          this.logger.log(
            `[OfflineQueue] Waiting ${delay}ms before form retry #${item.retryCount + 1}`
          );
          await this.sleep(delay);

          if (!this._isOnline()) {
            this.logger.log('[OfflineQueue] Lost connection during form backoff, stopping sync');
            break;
          }
        }

        try {
          const success = await submitFn(item);

          if (success) {
            await this.dequeueForm(item.id);
            synced.push(item.id);
          } else {
            await this.updateFormItem(item.id, {
              retryCount: item.retryCount + 1,
              lastAttempt: new Date().toISOString(),
              error: 'Submission failed',
            });
          }
        } catch (error) {
          const errorMessage = error instanceof Error ? error.message : 'Unknown error';

          if (this.isRateLimitError(error)) {
            this.logger.warn('[OfflineQueue] Rate limited on form submit, applying longer backoff');
            await this.sleep(BACKOFF_CONFIG.maxDelayMs);
          }

          await this.updateFormItem(item.id, {
            retryCount: item.retryCount + 1,
            lastAttempt: new Date().toISOString(),
            error: errorMessage,
          });
        }
      }

      this.logger.log(`[OfflineQueue] Form sync complete: ${synced.length}/${items.length} successful`);
    } catch (error) {
      const errorMessage = error instanceof Error ? error.message : 'Form sync failed';
      this._lastSyncError.set(errorMessage);
      this.logger.error('[OfflineQueue] Form sync error:', error);
    } finally {
      this._isSyncing.set(false);
    }

    return synced;
  }

  // ========== PRIVATE METHODS ==========

  private async initDatabase(): Promise<void> {
    if (!('indexedDB' in window)) {
      this.logger.warn('[OfflineQueue] IndexedDB not supported, using memory only');
      return;
    }

    return new Promise((resolve, reject) => {
      const request = indexedDB.open(DB_NAME, DB_VERSION);

      request.onerror = () => {
        this.logger.error('[OfflineQueue] Failed to open database:', request.error);
        reject(request.error);
      };

      request.onsuccess = () => {
        this.db = request.result;
        this.loadFromDb().then(resolve);
      };

      request.onupgradeneeded = (event) => {
        const db = (event.target as IDBOpenDBRequest).result;

        // Photo uploads store
        if (!db.objectStoreNames.contains(STORE_NAME)) {
          const store = db.createObjectStore(STORE_NAME, { keyPath: 'id' });
          store.createIndex('bookingId', 'bookingId', { unique: false });
          store.createIndex('createdAt', 'createdAt', { unique: false });
        }

        // Form submissions store (new in v2)
        if (!db.objectStoreNames.contains(FORM_STORE_NAME)) {
          const formStore = db.createObjectStore(FORM_STORE_NAME, { keyPath: 'id' });
          formStore.createIndex('bookingId', 'bookingId', { unique: false });
          formStore.createIndex('type', 'type', { unique: false });
          formStore.createIndex('createdAt', 'createdAt', { unique: false });
        }
      };
    });
  }

  private async loadFromDb(): Promise<void> {
    if (!this.db) return;

    // Load photo uploads
    await this.loadPhotoUploads();
    // Load form submissions
    await this.loadFormSubmissions();
  }

  private async loadPhotoUploads(): Promise<void> {
    if (!this.db) return;

    return new Promise((resolve, reject) => {
      const transaction = this.db!.transaction(STORE_NAME, 'readonly');
      const store = transaction.objectStore(STORE_NAME);
      const request = store.getAll();

      request.onsuccess = () => {
        this._queue.set(request.result || []);
        this.logger.log(
          `[OfflineQueue] Loaded ${request.result?.length || 0} photo uploads from IndexedDB`
        );
        resolve();
      };

      request.onerror = () => {
        this.logger.error('[OfflineQueue] Failed to load photo uploads:', request.error);
        reject(request.error);
      };
    });
  }

  private async loadFormSubmissions(): Promise<void> {
    if (!this.db || !this.db.objectStoreNames.contains(FORM_STORE_NAME)) return;

    return new Promise((resolve, reject) => {
      const transaction = this.db!.transaction(FORM_STORE_NAME, 'readonly');
      const store = transaction.objectStore(FORM_STORE_NAME);
      const request = store.getAll();

      request.onsuccess = () => {
        const storedItems = Array.isArray(request.result) ? request.result : [];
        const validItems = storedItems.filter(isQueuedFormSubmission);
        const droppedCount = storedItems.length - validItems.length;

        if (droppedCount > 0) {
          this.logger.warn(`[OfflineQueue] Dropped ${droppedCount} invalid form submissions from IndexedDB`);
        }

        this._formQueue.set(validItems);
        this.logger.log(
          `[OfflineQueue] Loaded ${validItems.length} form submissions from IndexedDB`
        );
        resolve();
      };

      request.onerror = () => {
        this.logger.error('[OfflineQueue] Failed to load form submissions:', request.error);
        reject(request.error);
      };
    });
  }

  private async saveToDb(item: QueuedUpload): Promise<void> {
    if (!this.db) {
      // Fallback: just keep in memory
      return;
    }

    return new Promise((resolve, reject) => {
      const transaction = this.db!.transaction(STORE_NAME, 'readwrite');
      const store = transaction.objectStore(STORE_NAME);
      const request = store.put(item);

      request.onsuccess = () => resolve();
      request.onerror = () => reject(request.error);
    });
  }

  private async removeFromDb(id: string): Promise<void> {
    if (!this.db) return;

    return new Promise((resolve, reject) => {
      const transaction = this.db!.transaction(STORE_NAME, 'readwrite');
      const store = transaction.objectStore(STORE_NAME);
      const request = store.delete(id);

      request.onsuccess = () => resolve();
      request.onerror = () => reject(request.error);
    });
  }

  private setupNetworkListeners(): void {
    window.addEventListener('online', this.onlineHandler);
    window.addEventListener('offline', this.offlineHandler);
  }

  private handleOnline(): void {
    this._isOnline.set(true);
    this.logger.log('[OfflineQueue] Network restored');
    // Could auto-sync here, but we use manual sync for better UX control
  }

  private handleOffline(): void {
    this._isOnline.set(false);
    this.logger.log('[OfflineQueue] Network lost');
  }

  // ========== FORM DATABASE METHODS ==========

  private async saveFormToDb(item: QueuedFormSubmission): Promise<void> {
    if (!this.db || !this.db.objectStoreNames.contains(FORM_STORE_NAME)) {
      return; // Fallback: just keep in memory
    }

    return new Promise((resolve, reject) => {
      const transaction = this.db!.transaction(FORM_STORE_NAME, 'readwrite');
      const store = transaction.objectStore(FORM_STORE_NAME);
      const request = store.put(item);

      request.onsuccess = () => resolve();
      request.onerror = () => reject(request.error);
    });
  }

  private async removeFormFromDb(id: string): Promise<void> {
    if (!this.db || !this.db.objectStoreNames.contains(FORM_STORE_NAME)) return;

    return new Promise((resolve, reject) => {
      const transaction = this.db!.transaction(FORM_STORE_NAME, 'readwrite');
      const store = transaction.objectStore(FORM_STORE_NAME);
      const request = store.delete(id);

      request.onsuccess = () => resolve();
      request.onerror = () => reject(request.error);
    });
  }
}