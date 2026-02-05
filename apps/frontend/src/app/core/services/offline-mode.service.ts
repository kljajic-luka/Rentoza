import { Injectable, signal, computed, effect } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { fromEvent, merge, of, timer } from 'rxjs';
import { map, switchMap, distinctUntilChanged, shareReplay } from 'rxjs/operators';

/**
 * Network status and offline mode support service.
 *
 * Provides:
 * - Real-time network status monitoring
 * - Offline data queue for pending operations
 * - Automatic retry when connection restores
 * - Local storage fallback for critical data
 *
 * @since Phase 9.0 - UX Edge Cases
 */
@Injectable({
  providedIn: 'root',
})
export class OfflineModeService {
  // ==================== NETWORK STATUS SIGNALS ====================

  /** Current online status */
  private readonly _isOnline = signal<boolean>(navigator.onLine);

  /** Whether we're currently syncing queued operations */
  private readonly _isSyncing = signal<boolean>(false);

  /** Number of operations in the offline queue */
  private readonly _pendingOperations = signal<number>(0);

  /** Last successful sync timestamp */
  private readonly _lastSyncTime = signal<Date | null>(null);

  // ==================== PUBLIC SIGNALS ====================

  /** Reactive online status */
  readonly isOnline = this._isOnline.asReadonly();

  /** Reactive syncing status */
  readonly isSyncing = this._isSyncing.asReadonly();

  /** Number of pending offline operations */
  readonly pendingOperations = this._pendingOperations.asReadonly();

  /** Last sync timestamp */
  readonly lastSyncTime = this._lastSyncTime.asReadonly();

  /** Computed: Has pending operations to sync */
  readonly hasPendingSync = computed(() => this._pendingOperations() > 0);

  /** Computed: Connection quality status */
  readonly connectionStatus = computed(() => {
    if (!this._isOnline()) return 'offline';
    if (this._isSyncing()) return 'syncing';
    if (this.hasPendingSync()) return 'pending';
    return 'online';
  });

  // ==================== STORAGE KEYS ====================

  private readonly OFFLINE_QUEUE_KEY = 'rentoza_offline_queue';
  private readonly CACHED_DATA_KEY = 'rentoza_cached_data';
  private readonly SYNC_STATUS_KEY = 'rentoza_sync_status';

  // ==================== INITIALIZATION ====================

  constructor() {
    this.initNetworkListeners();
    this.loadOfflineQueue();
    this.setupAutoSync();
  }

  /**
   * Initialize network status event listeners.
   */
  private initNetworkListeners(): void {
    // Listen for online/offline events
    const online$ = fromEvent(window, 'online').pipe(map(() => true));
    const offline$ = fromEvent(window, 'offline').pipe(map(() => false));

    merge(online$, offline$)
      .pipe(distinctUntilChanged())
      .subscribe((isOnline) => {
        this._isOnline.set(isOnline);

        if (isOnline) {
          console.log('[OfflineMode] Network restored, starting sync...');
          this.syncOfflineQueue();
        } else {
          console.log('[OfflineMode] Network lost, enabling offline mode');
        }
      });
  }

  /**
   * Load offline queue from localStorage on startup.
   */
  private loadOfflineQueue(): void {
    try {
      const queue = localStorage.getItem(this.OFFLINE_QUEUE_KEY);
      if (queue) {
        const operations = JSON.parse(queue) as OfflineOperation[];
        this._pendingOperations.set(operations.length);
      }
    } catch (error) {
      console.error('[OfflineMode] Failed to load offline queue:', error);
    }
  }

  /**
   * Setup automatic sync when connection is restored.
   */
  private setupAutoSync(): void {
    // Periodically check for pending operations when online
    effect(() => {
      if (this._isOnline() && this.hasPendingSync() && !this._isSyncing()) {
        // Debounce sync attempts
        setTimeout(() => this.syncOfflineQueue(), 2000);
      }
    });
  }

  // ==================== PUBLIC METHODS ====================

  /**
   * Queue an operation for later execution when offline.
   *
   * @param operation The operation to queue
   * @returns true if queued, false if online (not queued)
   */
  queueOperation(operation: OfflineOperation): boolean {
    if (this._isOnline()) {
      return false; // Execute immediately, don't queue
    }

    try {
      const queue = this.getOfflineQueue();
      queue.push({
        ...operation,
        id: crypto.randomUUID(),
        timestamp: new Date().toISOString(),
        retryCount: 0,
      });

      localStorage.setItem(this.OFFLINE_QUEUE_KEY, JSON.stringify(queue));
      this._pendingOperations.set(queue.length);

      console.log('[OfflineMode] Operation queued:', operation.type);
      return true;
    } catch (error) {
      console.error('[OfflineMode] Failed to queue operation:', error);
      return false;
    }
  }

  /**
   * Get all pending offline operations.
   */
  getOfflineQueue(): OfflineOperation[] {
    try {
      const queue = localStorage.getItem(this.OFFLINE_QUEUE_KEY);
      return queue ? JSON.parse(queue) : [];
    } catch {
      return [];
    }
  }

  /**
   * Sync all offline operations when network is restored.
   *
   * @returns Promise that resolves when sync is complete
   */
  async syncOfflineQueue(): Promise<SyncResult> {
    if (!this._isOnline() || this._isSyncing()) {
      return { success: false, synced: 0, failed: 0, remaining: this._pendingOperations() };
    }

    this._isSyncing.set(true);
    const queue = this.getOfflineQueue();
    let synced = 0;
    let failed = 0;
    const failedOperations: OfflineOperation[] = [];

    console.log(`[OfflineMode] Starting sync of ${queue.length} operations`);

    for (const operation of queue) {
      try {
        await this.executeOperation(operation);
        synced++;
      } catch (error) {
        console.error('[OfflineMode] Operation failed:', operation.type, error);

        operation.retryCount = (operation.retryCount || 0) + 1;
        operation.lastError = error instanceof Error ? error.message : 'Unknown error';

        // Keep failed operations for retry (max 3 retries)
        if (operation.retryCount < 3) {
          failedOperations.push(operation);
        }
        failed++;
      }
    }

    // Update queue with failed operations
    localStorage.setItem(this.OFFLINE_QUEUE_KEY, JSON.stringify(failedOperations));
    this._pendingOperations.set(failedOperations.length);
    this._lastSyncTime.set(new Date());
    this._isSyncing.set(false);

    console.log(`[OfflineMode] Sync complete: ${synced} synced, ${failed} failed`);

    return {
      success: failed === 0,
      synced,
      failed,
      remaining: failedOperations.length,
    };
  }

  /**
   * Execute a single offline operation.
   */
  private async executeOperation(operation: OfflineOperation): Promise<void> {
    const { type, endpoint, method, data } = operation;

    console.log(`[OfflineMode] Executing ${type}: ${method} ${endpoint}`);

    const response = await fetch(endpoint, {
      method,
      headers: {
        'Content-Type': 'application/json',
        // Auth token will be added by HTTP interceptor
      },
      body: data ? JSON.stringify(data) : undefined,
    });

    if (!response.ok) {
      throw new Error(`HTTP ${response.status}: ${response.statusText}`);
    }
  }

  /**
   * Clear all offline data (use with caution).
   */
  clearOfflineData(): void {
    localStorage.removeItem(this.OFFLINE_QUEUE_KEY);
    localStorage.removeItem(this.CACHED_DATA_KEY);
    this._pendingOperations.set(0);
    console.log('[OfflineMode] Offline data cleared');
  }

  // ==================== CACHING METHODS ====================

  /**
   * Cache data for offline access.
   *
   * @param key Cache key
   * @param data Data to cache
   * @param ttlMs Time-to-live in milliseconds (default: 1 hour)
   */
  cacheData<T>(key: string, data: T, ttlMs: number = 3600000): void {
    try {
      const cached = this.getCachedDataStore();
      cached[key] = {
        data,
        timestamp: Date.now(),
        expiresAt: Date.now() + ttlMs,
      };
      localStorage.setItem(this.CACHED_DATA_KEY, JSON.stringify(cached));
    } catch (error) {
      console.error('[OfflineMode] Failed to cache data:', error);
    }
  }

  /**
   * Get cached data.
   *
   * @param key Cache key
   * @returns Cached data or null if not found/expired
   */
  getCachedData<T>(key: string): T | null {
    try {
      const cached = this.getCachedDataStore();
      const entry = cached[key];

      if (!entry) return null;

      // Check expiration
      if (Date.now() > entry.expiresAt) {
        delete cached[key];
        localStorage.setItem(this.CACHED_DATA_KEY, JSON.stringify(cached));
        return null;
      }

      return entry.data as T;
    } catch {
      return null;
    }
  }

  /**
   * Check if cached data exists and is valid.
   */
  hasCachedData(key: string): boolean {
    return this.getCachedData(key) !== null;
  }

  /**
   * Get the cache data store.
   */
  private getCachedDataStore(): CacheStore {
    try {
      const cached = localStorage.getItem(this.CACHED_DATA_KEY);
      return cached ? JSON.parse(cached) : {};
    } catch {
      return {};
    }
  }

  // ==================== CRITICAL DATA PRESERVATION ====================

  /**
   * Save critical form data to prevent data loss on network failure.
   *
   * @param formId Unique form identifier
   * @param formData Form data to preserve
   */
  preserveFormData(formId: string, formData: unknown): void {
    const key = `rentoza_form_${formId}`;
    try {
      localStorage.setItem(
        key,
        JSON.stringify({
          data: formData,
          timestamp: new Date().toISOString(),
        }),
      );
    } catch (error) {
      console.error('[OfflineMode] Failed to preserve form data:', error);
    }
  }

  /**
   * Restore preserved form data.
   */
  restoreFormData<T>(formId: string): { data: T; timestamp: string } | null {
    const key = `rentoza_form_${formId}`;
    try {
      const stored = localStorage.getItem(key);
      return stored ? JSON.parse(stored) : null;
    } catch {
      return null;
    }
  }

  /**
   * Clear preserved form data after successful submission.
   */
  clearFormData(formId: string): void {
    localStorage.removeItem(`rentoza_form_${formId}`);
  }
}

// ==================== TYPES ====================

export interface OfflineOperation {
  id?: string;
  type: 'CREATE_BOOKING' | 'UPDATE_PROFILE' | 'SUBMIT_REVIEW' | 'SEND_MESSAGE' | 'UPLOAD_PHOTO';
  endpoint: string;
  method: 'GET' | 'POST' | 'PUT' | 'DELETE' | 'PATCH';
  data?: unknown;
  timestamp?: string;
  retryCount?: number;
  lastError?: string;
}

export interface SyncResult {
  success: boolean;
  synced: number;
  failed: number;
  remaining: number;
}

interface CacheEntry {
  data: unknown;
  timestamp: number;
  expiresAt: number;
}

interface CacheStore {
  [key: string]: CacheEntry;
}
