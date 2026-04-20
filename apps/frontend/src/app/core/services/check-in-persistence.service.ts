/**
 * Check-In Persistence Service
 *
 * Persists captured photos and form state to IndexedDB to survive page refreshes.
 * This is CRITICAL for the guest check-in flow where users spend 5-15 minutes
 * capturing 8+ photos - losing that progress is catastrophic UX.
 *
 * ## Architecture
 * - IndexedDB for Blob storage (photos as base64)
 * - localStorage for lightweight form data
 * - BroadcastChannel for multi-tab coordination
 * - TTL-based cleanup (24h) to prevent stale data accumulation
 *
 * ## Multi-Tab Coordination
 * Uses BroadcastChannel to implement per-bookingId locking:
 * - Only one tab can actively capture photos for a booking
 * - Other tabs see "Session active in another tab" warning
 * - Supports graceful takeover with user confirmation
 *
 * ## Storage Quota Handling
 * Detects quota errors and degrades gracefully:
 * - Logs to monitoring system
 * - Falls back to immediate upload mode without local caching
 * - Shows user-friendly warning
 */

import { Injectable, signal, OnDestroy, inject } from '@angular/core';
import { CheckInPhotoType } from '@core/models/check-in.model';
import { generateUUID } from '../utils/uuid';

// IndexedDB Configuration
const DB_NAME = 'rentoza-checkin-persistence';
const DB_VERSION = 1;
const CAPTURE_STATE_STORE = 'capture-state';
const FORM_STATE_STORE = 'form-state';

// TTL: 24 hours in milliseconds
const STATE_TTL_MS = 24 * 60 * 60 * 1000;

// BroadcastChannel name for multi-tab coordination
const BROADCAST_CHANNEL_NAME = 'rentoza-checkin-sync';

/**
 * Captured photo data for persistence.
 * Stores as base64 since Blobs can't be directly serialized.
 */
export interface PersistedPhoto {
  photoType: CheckInPhotoType;
  base64Data: string;
  mimeType: string;
  capturedAt: string;
  verified: boolean;
  width?: number;
  height?: number;
}

/**
 * Full capture state for a check-in session.
 */
export interface CaptureState {
  id: string;
  bookingId: number;
  mode: 'guest-checkin' | 'host-checkin' | 'host-checkout';
  currentIndex: number;
  currentPhase: 'guidance' | 'captured';
  capturedPhotos: PersistedPhoto[];
  savedAt: string;
  version: number;
  tabId: string; // For multi-tab detection
}

/**
 * Form state for check-in (odometer, fuel, etc.)
 */
export interface FormState {
  id: string;
  bookingId: number;
  mode: 'guest-checkin' | 'host-checkin' | 'host-checkout';
  odometerReading?: number;
  fuelLevelPercent?: number;
  lockboxCode?: string;
  conditionAccepted?: boolean;
  conditionComment?: string;
  savedAt: string;
}

/**
 * Multi-tab coordination message types.
 */
type TabMessage =
  | { type: 'LOCK_REQUEST'; bookingId: number; tabId: string }
  | { type: 'LOCK_ACQUIRED'; bookingId: number; tabId: string }
  | { type: 'LOCK_RELEASED'; bookingId: number; tabId: string }
  | { type: 'TAKEOVER_REQUEST'; bookingId: number; tabId: string }
  | { type: 'TAKEOVER_DENIED'; bookingId: number; tabId: string }
  | { type: 'STATE_UPDATED'; bookingId: number; tabId: string };

/**
 * Result of checking for a saved session.
 */
export interface SavedSessionInfo {
  exists: boolean;
  bookingId?: number;
  mode?: string;
  photoCount?: number;
  totalPhotos?: number;
  savedAt?: Date;
  minutesAgo?: number;
  isOwnedByOtherTab?: boolean;
  otherTabId?: string;
}

@Injectable({ providedIn: 'root' })
export class CheckInPersistenceService implements OnDestroy {
  // Reactive state
  private readonly _isInitialized = signal(false);
  private readonly _hasQuotaError = signal(false);
  private readonly _activeTabLock = signal<{ bookingId: number; tabId: string } | null>(null);
  private readonly _otherTabActive = signal(false);

  readonly isInitialized = this._isInitialized.asReadonly();
  readonly hasQuotaError = this._hasQuotaError.asReadonly();
  readonly otherTabActive = this._otherTabActive.asReadonly();

  // Internal state
  private db: IDBDatabase | null = null;
  private broadcastChannel: BroadcastChannel | null = null;
  private readonly tabId = generateUUID();
  private activeLocks = new Map<number, string>(); // bookingId -> tabId

  // Takeover callback for multi-tab coordination
  private _takeoverCallback: ((denied: boolean) => void) | null = null;

  // Promise that resolves when database is ready
  private dbReadyPromise: Promise<void>;
  private dbReadyResolve!: () => void;

  constructor() {
    // Create a promise that will resolve when DB is ready
    this.dbReadyPromise = new Promise((resolve) => {
      this.dbReadyResolve = resolve;
    });

    this.initDatabase();
    this.initBroadcastChannel();
    this.cleanupExpiredStates();
  }

  ngOnDestroy(): void {
    this.releaseLock();
    this.broadcastChannel?.close();
    this.db?.close();
  }

  // ========== PUBLIC API: INITIALIZATION ==========

  /**
   * Wait for the database to be fully initialized.
   * MUST be called before any database operations in components.
   * This ensures race conditions don't cause silent failures.
   */
  async waitForReady(): Promise<void> {
    await this.dbReadyPromise;
  }

  /**
   * Check if the database is ready synchronously.
   * Useful for quick checks but waitForReady() is preferred.
   */
  get isReady(): boolean {
    return this._isInitialized() && this.db !== null;
  }

  // ========== PUBLIC API: CAPTURE STATE ==========

  /**
   * Save capture state (photos + progress) to IndexedDB.
   * Called after each photo capture.
   */
  async saveCaptureState(
    bookingId: number,
    mode: CaptureState['mode'],
    currentIndex: number,
    currentPhase: CaptureState['currentPhase'],
    capturedPhotos: PersistedPhoto[],
  ): Promise<boolean> {
    if (this._hasQuotaError()) {
      console.warn('[Persistence] Quota error - skipping save');
      return false;
    }

    const state: CaptureState = {
      id: `capture-${bookingId}-${mode}`,
      bookingId,
      mode,
      currentIndex,
      currentPhase,
      capturedPhotos,
      savedAt: new Date().toISOString(),
      version: 1,
      tabId: this.tabId,
    };

    try {
      await this.saveToStore(CAPTURE_STATE_STORE, state);
      this.broadcastStateUpdate(bookingId);
      console.log(
        `[Persistence] Saved capture state: ${capturedPhotos.length} photos for booking ${bookingId}`,
      );
      return true;
    } catch (error) {
      if (this.isQuotaError(error)) {
        this._hasQuotaError.set(true);
        console.error('[Persistence] Storage quota exceeded:', error);
        this.logQuotaError(bookingId, error);
        return false;
      }
      throw error;
    }
  }

  /**
   * Load capture state from IndexedDB.
   */
  async loadCaptureState(
    bookingId: number,
    mode: CaptureState['mode'],
  ): Promise<CaptureState | null> {
    const id = `capture-${bookingId}-${mode}`;
    const state = await this.loadFromStore<CaptureState>(CAPTURE_STATE_STORE, id);

    if (!state) return null;

    // Check if expired
    const savedAt = new Date(state.savedAt);
    if (Date.now() - savedAt.getTime() > STATE_TTL_MS) {
      console.log(`[Persistence] Capture state expired for booking ${bookingId}`);
      await this.deleteCaptureState(bookingId, mode);
      return null;
    }

    return state;
  }

  /**
   * Delete capture state (after successful submission or user discard).
   */
  async deleteCaptureState(bookingId: number, mode: CaptureState['mode']): Promise<void> {
    const id = `capture-${bookingId}-${mode}`;
    await this.deleteFromStore(CAPTURE_STATE_STORE, id);
    console.log(`[Persistence] Deleted capture state for booking ${bookingId}`);
  }

  /**
   * Check if a saved session exists for a booking.
   * This is called on component init to detect and offer session recovery.
   */
  async checkForSavedSession(
    bookingId: number,
    mode: CaptureState['mode'],
  ): Promise<SavedSessionInfo> {
    console.log(`[Persistence] Checking for saved session: booking=${bookingId}, mode=${mode}`);

    const state = await this.loadCaptureState(bookingId, mode);

    if (!state) {
      console.log(`[Persistence] No saved state found for booking ${bookingId}`);
      return { exists: false };
    }

    if (state.capturedPhotos.length === 0) {
      console.log(`[Persistence] Saved state has no photos for booking ${bookingId}`);
      return { exists: false };
    }

    const savedAt = new Date(state.savedAt);
    const minutesAgo = Math.round((Date.now() - savedAt.getTime()) / 60000);

    // Check if another tab owns this session
    const isOwnedByOtherTab =
      state.tabId !== this.tabId && this.activeLocks.get(bookingId) === state.tabId;

    console.log(
      `[Persistence] Found saved session: ${state.capturedPhotos.length} photos, ${minutesAgo} min ago`,
    );

    return {
      exists: true,
      bookingId: state.bookingId,
      mode: state.mode,
      photoCount: state.capturedPhotos.length,
      totalPhotos: this.getTotalPhotosForMode(mode),
      savedAt,
      minutesAgo,
      isOwnedByOtherTab,
      otherTabId: isOwnedByOtherTab ? state.tabId : undefined,
    };
  }

  // ========== PUBLIC API: FORM STATE ==========

  /**
   * Save form state to IndexedDB.
   * Uses debouncing in the component - this just saves.
   */
  async saveFormState(
    bookingId: number,
    mode: FormState['mode'],
    formData: Partial<Omit<FormState, 'id' | 'bookingId' | 'mode' | 'savedAt'>>,
  ): Promise<void> {
    const state: FormState = {
      id: `form-${bookingId}-${mode}`,
      bookingId,
      mode,
      ...formData,
      savedAt: new Date().toISOString(),
    };

    try {
      await this.saveToStore(FORM_STATE_STORE, state);
      console.log(`[Persistence] Saved form state for booking ${bookingId}`);
    } catch (error) {
      console.error('[Persistence] Failed to save form state:', error);
    }
  }

  /**
   * Load form state from IndexedDB.
   */
  async loadFormState(bookingId: number, mode: FormState['mode']): Promise<FormState | null> {
    const id = `form-${bookingId}-${mode}`;
    return this.loadFromStore<FormState>(FORM_STATE_STORE, id);
  }

  /**
   * Delete form state.
   */
  async deleteFormState(bookingId: number, mode: FormState['mode']): Promise<void> {
    const id = `form-${bookingId}-${mode}`;
    await this.deleteFromStore(FORM_STATE_STORE, id);
  }

  // ========== PUBLIC API: MULTI-TAB COORDINATION ==========

  /**
   * Acquire a lock for a booking (prevents other tabs from capturing).
   * Returns true if lock acquired, false if another tab has it.
   */
  async acquireLock(bookingId: number): Promise<boolean> {
    // Check if another tab has the lock
    const existingLock = this.activeLocks.get(bookingId);
    if (existingLock && existingLock !== this.tabId) {
      this._otherTabActive.set(true);
      return false;
    }

    // Broadcast lock request
    this.broadcastMessage({ type: 'LOCK_REQUEST', bookingId, tabId: this.tabId });

    // Wait for potential conflicts (other tabs respond)
    await this.sleep(100);

    // If no conflicts, acquire lock
    if (!this.activeLocks.has(bookingId) || this.activeLocks.get(bookingId) === this.tabId) {
      this.activeLocks.set(bookingId, this.tabId);
      this._activeTabLock.set({ bookingId, tabId: this.tabId });
      this._otherTabActive.set(false);
      this.broadcastMessage({ type: 'LOCK_ACQUIRED', bookingId, tabId: this.tabId });
      console.log(`[Persistence] Lock acquired for booking ${bookingId}`);
      return true;
    }

    this._otherTabActive.set(true);
    return false;
  }

  /**
   * Release the lock for a booking.
   */
  releaseLock(bookingId?: number): void {
    const lock = this._activeTabLock();
    if (!lock) return;

    if (bookingId === undefined || lock.bookingId === bookingId) {
      this.activeLocks.delete(lock.bookingId);
      this._activeTabLock.set(null);
      this.broadcastMessage({
        type: 'LOCK_RELEASED',
        bookingId: lock.bookingId,
        tabId: this.tabId,
      });
      console.log(`[Persistence] Lock released for booking ${lock.bookingId}`);
    }
  }

  /**
   * Request takeover of a session from another tab.
   */
  async requestTakeover(bookingId: number): Promise<boolean> {
    this.broadcastMessage({ type: 'TAKEOVER_REQUEST', bookingId, tabId: this.tabId });

    // Wait for response
    return new Promise((resolve) => {
      const timeout = setTimeout(() => {
        // No response = takeover granted
        this.activeLocks.set(bookingId, this.tabId);
        this._activeTabLock.set({ bookingId, tabId: this.tabId });
        this._otherTabActive.set(false);
        resolve(true);
      }, 2000);

      // Store callback to handle denial
      this._takeoverCallback = (denied: boolean) => {
        clearTimeout(timeout);
        resolve(!denied);
      };
    });
  }

  // ========== PUBLIC API: CLEANUP ==========

  /**
   * Clear all persisted data for a booking (after successful submission).
   */
  async clearBookingData(bookingId: number, mode: CaptureState['mode']): Promise<void> {
    await this.deleteCaptureState(bookingId, mode);
    await this.deleteFormState(bookingId, mode);
    this.releaseLock(bookingId);
    console.log(`[Persistence] Cleared all data for booking ${bookingId}`);
  }

  /**
   * Clear ALL persisted check-in data (for privacy settings).
   */
  async clearAllData(): Promise<void> {
    if (!this.db) return;

    await this.clearStore(CAPTURE_STATE_STORE);
    await this.clearStore(FORM_STATE_STORE);
    this.activeLocks.clear();
    this._activeTabLock.set(null);
    console.log('[Persistence] Cleared all check-in persistence data');
  }

  // ========== UTILITY: BLOB <-> BASE64 CONVERSION ==========

  /**
   * Convert a Blob to base64 string for storage.
   */
  async blobToBase64(blob: Blob): Promise<string> {
    return new Promise((resolve, reject) => {
      const reader = new FileReader();
      reader.onloadend = () => {
        const base64 = (reader.result as string).split(',')[1];
        resolve(base64);
      };
      reader.onerror = reject;
      reader.readAsDataURL(blob);
    });
  }

  /**
   * Convert base64 string back to Blob.
   */
  base64ToBlob(base64: string, mimeType: string): Blob {
    const byteCharacters = atob(base64);
    const byteNumbers = new Array(byteCharacters.length);
    for (let i = 0; i < byteCharacters.length; i++) {
      byteNumbers[i] = byteCharacters.charCodeAt(i);
    }
    const byteArray = new Uint8Array(byteNumbers);
    return new Blob([byteArray], { type: mimeType });
  }

  /**
   * Create object URL from persisted photo.
   */
  createPreviewUrl(photo: PersistedPhoto): string {
    const blob = this.base64ToBlob(photo.base64Data, photo.mimeType);
    return URL.createObjectURL(blob);
  }

  // ========== PRIVATE: DATABASE OPERATIONS ==========

  private async initDatabase(): Promise<void> {
    if (!('indexedDB' in window)) {
      console.warn('[Persistence] IndexedDB not supported');
      this._isInitialized.set(true);
      this.dbReadyResolve(); // Resolve even if not supported
      return;
    }

    return new Promise((resolve) => {
      const request = indexedDB.open(DB_NAME, DB_VERSION);

      request.onerror = () => {
        console.error('[Persistence] Failed to open database:', request.error);
        this._isInitialized.set(true);
        this.dbReadyResolve(); // Resolve even on error (graceful degradation)
        resolve();
      };

      request.onsuccess = () => {
        this.db = request.result;
        this._isInitialized.set(true);
        this.dbReadyResolve(); // Signal that DB is ready
        console.log('[Persistence] Database initialized successfully');
        resolve();
      };

      request.onupgradeneeded = (event) => {
        const db = (event.target as IDBOpenDBRequest).result;

        // Capture state store (photos + progress)
        if (!db.objectStoreNames.contains(CAPTURE_STATE_STORE)) {
          const store = db.createObjectStore(CAPTURE_STATE_STORE, { keyPath: 'id' });
          store.createIndex('bookingId', 'bookingId', { unique: false });
          store.createIndex('savedAt', 'savedAt', { unique: false });
        }

        // Form state store
        if (!db.objectStoreNames.contains(FORM_STATE_STORE)) {
          const store = db.createObjectStore(FORM_STATE_STORE, { keyPath: 'id' });
          store.createIndex('bookingId', 'bookingId', { unique: false });
        }
      };
    });
  }

  private async saveToStore<T extends { id: string }>(storeName: string, data: T): Promise<void> {
    // Wait for DB to be ready before writing
    await this.dbReadyPromise;
    if (!this.db) {
      console.warn('[Persistence] Database not available for save');
      return;
    }

    return new Promise((resolve, reject) => {
      const transaction = this.db!.transaction(storeName, 'readwrite');
      const store = transaction.objectStore(storeName);
      const request = store.put(data);

      request.onsuccess = () => resolve();
      request.onerror = () => reject(request.error);
    });
  }

  private async loadFromStore<T>(storeName: string, id: string): Promise<T | null> {
    // Wait for DB to be ready before reading
    await this.dbReadyPromise;
    if (!this.db) {
      console.warn('[Persistence] Database not available for load');
      return null;
    }

    return new Promise((resolve, reject) => {
      const transaction = this.db!.transaction(storeName, 'readonly');
      const store = transaction.objectStore(storeName);
      const request = store.get(id);

      request.onsuccess = () => resolve(request.result || null);
      request.onerror = () => reject(request.error);
    });
  }

  private async deleteFromStore(storeName: string, id: string): Promise<void> {
    await this.dbReadyPromise;
    if (!this.db) return;

    return new Promise((resolve, reject) => {
      const transaction = this.db!.transaction(storeName, 'readwrite');
      const store = transaction.objectStore(storeName);
      const request = store.delete(id);

      request.onsuccess = () => resolve();
      request.onerror = () => reject(request.error);
    });
  }

  private async clearStore(storeName: string): Promise<void> {
    await this.dbReadyPromise;
    if (!this.db) return;

    return new Promise((resolve, reject) => {
      const transaction = this.db!.transaction(storeName, 'readwrite');
      const store = transaction.objectStore(storeName);
      const request = store.clear();

      request.onsuccess = () => resolve();
      request.onerror = () => reject(request.error);
    });
  }

  // ========== PRIVATE: BROADCAST CHANNEL ==========

  private initBroadcastChannel(): void {
    if (!('BroadcastChannel' in window)) {
      console.warn('[Persistence] BroadcastChannel not supported, using localStorage fallback');
      this.initLocalStorageFallback();
      return;
    }

    this.broadcastChannel = new BroadcastChannel(BROADCAST_CHANNEL_NAME);
    this.broadcastChannel.onmessage = (event) => this.handleBroadcastMessage(event.data);
  }

  private initLocalStorageFallback(): void {
    // Use localStorage events as fallback for multi-tab coordination
    window.addEventListener('storage', (event) => {
      if (event.key === BROADCAST_CHANNEL_NAME && event.newValue) {
        try {
          const message = JSON.parse(event.newValue) as TabMessage;
          this.handleBroadcastMessage(message);
        } catch (e) {
          // Ignore parse errors
        }
      }
    });
  }

  private broadcastMessage(message: TabMessage): void {
    if (this.broadcastChannel) {
      this.broadcastChannel.postMessage(message);
    } else {
      // localStorage fallback
      localStorage.setItem(
        BROADCAST_CHANNEL_NAME,
        JSON.stringify({ ...message, timestamp: Date.now() }),
      );
    }
  }

  private handleBroadcastMessage(message: TabMessage): void {
    if (message.tabId === this.tabId) return; // Ignore own messages

    switch (message.type) {
      case 'LOCK_REQUEST':
        // Another tab wants to lock this booking
        const currentLock = this.activeLocks.get(message.bookingId);
        if (currentLock === this.tabId) {
          // We have the lock - broadcast that we have it
          this.broadcastMessage({
            type: 'LOCK_ACQUIRED',
            bookingId: message.bookingId,
            tabId: this.tabId,
          });
        }
        break;

      case 'LOCK_ACQUIRED':
        // Another tab has the lock
        this.activeLocks.set(message.bookingId, message.tabId);
        if (this._activeTabLock()?.bookingId === message.bookingId) {
          this._otherTabActive.set(true);
        }
        break;

      case 'LOCK_RELEASED':
        // Another tab released the lock
        if (this.activeLocks.get(message.bookingId) === message.tabId) {
          this.activeLocks.delete(message.bookingId);
        }
        this._otherTabActive.set(false);
        break;

      case 'TAKEOVER_REQUEST':
        // Another tab wants to take over our session
        if (this._activeTabLock()?.bookingId === message.bookingId) {
          // Deny takeover - we're actively using it
          this.broadcastMessage({
            type: 'TAKEOVER_DENIED',
            bookingId: message.bookingId,
            tabId: this.tabId,
          });
        }
        break;

      case 'TAKEOVER_DENIED':
        // Our takeover request was denied
        if (this._takeoverCallback) {
          this._takeoverCallback(true);
          this._takeoverCallback = null;
        }
        break;

      case 'STATE_UPDATED':
        // Another tab updated the state
        console.log(`[Persistence] State updated by tab ${message.tabId}`);
        break;
    }
  }

  private broadcastStateUpdate(bookingId: number): void {
    this.broadcastMessage({ type: 'STATE_UPDATED', bookingId, tabId: this.tabId });
  }

  // ========== PRIVATE: CLEANUP ==========

  private async cleanupExpiredStates(): Promise<void> {
    if (!this.db) {
      // Retry after DB init
      setTimeout(() => this.cleanupExpiredStates(), 1000);
      return;
    }

    try {
      const transaction = this.db.transaction(CAPTURE_STATE_STORE, 'readwrite');
      const store = transaction.objectStore(CAPTURE_STATE_STORE);
      const index = store.index('savedAt');
      const request = index.openCursor();

      request.onsuccess = () => {
        const cursor = request.result;
        if (cursor) {
          const state = cursor.value as CaptureState;
          const savedAt = new Date(state.savedAt);
          if (Date.now() - savedAt.getTime() > STATE_TTL_MS) {
            cursor.delete();
            console.log(`[Persistence] Cleaned up expired state for booking ${state.bookingId}`);
          }
          cursor.continue();
        }
      };
    } catch (error) {
      console.error('[Persistence] Cleanup error:', error);
    }
  }

  // ========== PRIVATE: HELPERS ==========

  private getTotalPhotosForMode(mode: CaptureState['mode']): number {
    // 8 standard check-in photos
    return 8;
  }

  private isQuotaError(error: unknown): boolean {
    if (error instanceof DOMException) {
      return error.name === 'QuotaExceededError' || error.code === 22;
    }
    return false;
  }

  private logQuotaError(bookingId: number, error: unknown): void {
    // TODO: Send to monitoring service (Sentry, etc.)
    console.error('[Persistence] QUOTA ERROR', {
      bookingId,
      error,
      userAgent: navigator.userAgent,
      timestamp: new Date().toISOString(),
    });
  }

  private sleep(ms: number): Promise<void> {
    return new Promise((resolve) => setTimeout(resolve, ms));
  }
}