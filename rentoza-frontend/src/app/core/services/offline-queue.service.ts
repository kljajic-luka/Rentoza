/**
 * Offline Queue Service
 *
 * Persists failed uploads to IndexedDB for retry when connectivity restores.
 * Implements the "Basement Problem" solution - photos taken offline can be
 * uploaded later without data loss.
 *
 * ## Architecture Decision
 * Uses IndexedDB via idb-keyval pattern for simplicity.
 * Falls back to in-memory queue if IndexedDB is unavailable.
 *
 * ## Sync Strategy
 * - Manual "Retry All" button in UI
 * - Background sync when online event fires (if supported)
 */

import { Injectable, signal, computed, OnDestroy } from '@angular/core';
import { QueuedUpload, CheckInPhotoType } from '@core/models/check-in.model';

const DB_NAME = 'rentoza-offline-queue';
const STORE_NAME = 'pending-uploads';
const DB_VERSION = 1;

@Injectable({ providedIn: 'root' })
export class OfflineQueueService implements OnDestroy {
  // Reactive state
  private readonly _queue = signal<QueuedUpload[]>([]);
  private readonly _isOnline = signal(navigator.onLine);
  private readonly _isSyncing = signal(false);
  private readonly _lastSyncError = signal<string | null>(null);

  readonly queue = this._queue.asReadonly();
  readonly isOnline = this._isOnline.asReadonly();
  readonly isSyncing = this._isSyncing.asReadonly();
  readonly lastSyncError = this._lastSyncError.asReadonly();

  readonly queueCount = computed(() => this._queue().length);
  readonly queueLength = computed(() => this._queue().length);
  readonly hasQueuedItems = computed(() => this._queue().length > 0);
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
    const id = crypto.randomUUID();

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

    console.log(`[OfflineQueue] Enqueued: ${photoType} for booking ${bookingId}`);
    return id;
  }

  /**
   * Remove an item from the queue (after successful upload).
   */
  async dequeue(id: string): Promise<void> {
    await this.removeFromDb(id);
    this._queue.update((q) => q.filter((item) => item.id !== id));
    console.log(`[OfflineQueue] Dequeued: ${id}`);
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
    console.log(`[OfflineQueue] Cleared all items for booking ${bookingId}`);
  }

  /**
   * Process the queue - attempt to upload all pending items.
   * Returns array of successfully synced item IDs.
   */
  async processQueue(uploadFn: (item: QueuedUpload) => Promise<boolean>): Promise<string[]> {
    if (this._isSyncing()) {
      console.log('[OfflineQueue] Sync already in progress');
      return [];
    }

    if (!this._isOnline()) {
      console.log('[OfflineQueue] Offline - skipping sync');
      return [];
    }

    this._isSyncing.set(true);
    this._lastSyncError.set(null);
    const synced: string[] = [];

    try {
      const items = [...this._queue()];

      for (const item of items) {
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
          await this.updateItem(item.id, {
            retryCount: item.retryCount + 1,
            lastAttempt: new Date().toISOString(),
            error: errorMessage,
          });
        }
      }

      console.log(`[OfflineQueue] Sync complete: ${synced.length}/${items.length} successful`);
    } catch (error) {
      const errorMessage = error instanceof Error ? error.message : 'Sync failed';
      this._lastSyncError.set(errorMessage);
      console.error('[OfflineQueue] Sync error:', error);
    } finally {
      this._isSyncing.set(false);
    }

    return synced;
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

  // ========== PRIVATE METHODS ==========

  private async initDatabase(): Promise<void> {
    if (!('indexedDB' in window)) {
      console.warn('[OfflineQueue] IndexedDB not supported, using memory only');
      return;
    }

    return new Promise((resolve, reject) => {
      const request = indexedDB.open(DB_NAME, DB_VERSION);

      request.onerror = () => {
        console.error('[OfflineQueue] Failed to open database:', request.error);
        reject(request.error);
      };

      request.onsuccess = () => {
        this.db = request.result;
        this.loadFromDb().then(resolve);
      };

      request.onupgradeneeded = (event) => {
        const db = (event.target as IDBOpenDBRequest).result;

        if (!db.objectStoreNames.contains(STORE_NAME)) {
          const store = db.createObjectStore(STORE_NAME, { keyPath: 'id' });
          store.createIndex('bookingId', 'bookingId', { unique: false });
          store.createIndex('createdAt', 'createdAt', { unique: false });
        }
      };
    });
  }

  private async loadFromDb(): Promise<void> {
    if (!this.db) return;

    return new Promise((resolve, reject) => {
      const transaction = this.db!.transaction(STORE_NAME, 'readonly');
      const store = transaction.objectStore(STORE_NAME);
      const request = store.getAll();

      request.onsuccess = () => {
        this._queue.set(request.result || []);
        console.log(`[OfflineQueue] Loaded ${request.result?.length || 0} items from IndexedDB`);
        resolve();
      };

      request.onerror = () => {
        console.error('[OfflineQueue] Failed to load from database:', request.error);
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
    console.log('[OfflineQueue] Network restored');
    // Could auto-sync here, but we use manual sync for better UX control
  }

  private handleOffline(): void {
    this._isOnline.set(false);
    console.log('[OfflineQueue] Network lost');
  }
}
