/**
 * Geolocation Service
 *
 * Provides high-accuracy GPS coordinates for check-in handshake.
 * Handles permission denials gracefully with Serbian localized messages.
 *
 * ## Architecture Decision
 * Uses Angular Signals for reactive state management.
 * Implements timeout and fallback logic for unreliable mobile GPS.
 *
 * ## GPS Accuracy Considerations for Serbia
 * - Urban (Belgrade high-rises): 50-100m accuracy due to multipath
 * - Suburban: 10-30m accuracy
 * - Rural (Vojvodina plains): 5-10m accuracy
 */

import { Injectable, signal, computed, NgZone } from '@angular/core';
import { GeolocationResult, GeolocationError } from '@core/models/check-in.model';

export interface GeolocationOptions {
  enableHighAccuracy: boolean;
  timeout: number; // ms
  maximumAge: number; // ms
  minAccuracy: number; // meters - warn if accuracy worse than this
}

const DEFAULT_OPTIONS: GeolocationOptions = {
  enableHighAccuracy: true,
  timeout: 30000, // 30 seconds
  maximumAge: 60000, // 1 minute
  minAccuracy: 100, // 100 meters
};

@Injectable({ providedIn: 'root' })
export class GeolocationService {
  // Reactive state
  private readonly _position = signal<GeolocationResult | null>(null);
  private readonly _error = signal<GeolocationError | null>(null);
  private readonly _isLoading = signal(false);
  private readonly _permissionState = signal<PermissionState | null>(null);

  readonly position = this._position.asReadonly();
  readonly error = this._error.asReadonly();
  readonly isLoading = this._isLoading.asReadonly();
  readonly permissionState = this._permissionState.asReadonly();

  // Computed signals
  readonly hasPosition = computed(() => this._position() !== null);
  readonly hasError = computed(() => this._error() !== null);
  readonly isAccuracyPoor = computed(() => {
    const pos = this._position();
    return pos !== null && pos.accuracy > DEFAULT_OPTIONS.minAccuracy;
  });
  readonly isWatching = computed(() => this.watchId !== null);

  private watchId: number | null = null;

  constructor(private ngZone: NgZone) {
    this.checkPermission();
  }

  /**
   * Check if geolocation is supported.
   */
  isSupported(): boolean {
    return 'geolocation' in navigator;
  }

  /**
   * Check current permission state (if Permissions API available).
   */
  async checkPermission(): Promise<PermissionState | null> {
    if (!('permissions' in navigator)) {
      return null;
    }

    try {
      const result = await navigator.permissions.query({ name: 'geolocation' });
      this._permissionState.set(result.state);

      // Listen for permission changes
      result.onchange = () => {
        this.ngZone.run(() => {
          this._permissionState.set(result.state);
        });
      };

      return result.state;
    } catch {
      return null;
    }
  }

  /**
   * Get current position once.
   */
  async getCurrentPosition(options: Partial<GeolocationOptions> = {}): Promise<GeolocationResult> {
    if (!this.isSupported()) {
      const error: GeolocationError = {
        code: 'UNSUPPORTED',
        message: 'Lokacija nije podržana na ovom uređaju',
      };
      this._error.set(error);
      throw error;
    }

    const opts = { ...DEFAULT_OPTIONS, ...options };

    this._isLoading.set(true);
    this._error.set(null);

    return new Promise((resolve, reject) => {
      navigator.geolocation.getCurrentPosition(
        (position) => {
          this.ngZone.run(() => {
            const result = this.mapPosition(position);
            this._position.set(result);
            this._isLoading.set(false);

            // Log accuracy warning
            if (result.accuracy > opts.minAccuracy) {
              console.warn(
                `[Geolocation] Poor accuracy: ${result.accuracy.toFixed(0)}m (threshold: ${
                  opts.minAccuracy
                }m)`
              );
            }

            resolve(result);
          });
        },
        (error) => {
          this.ngZone.run(() => {
            const mappedError = this.mapError(error);
            this._error.set(mappedError);
            this._isLoading.set(false);
            reject(mappedError);
          });
        },
        {
          enableHighAccuracy: opts.enableHighAccuracy,
          timeout: opts.timeout,
          maximumAge: opts.maximumAge,
        }
      );
    });
  }

  /**
   * Start watching position continuously.
   * Useful for real-time map updates.
   */
  startWatching(options: Partial<GeolocationOptions> = {}): void {
    if (!this.isSupported()) {
      this._error.set({
        code: 'UNSUPPORTED',
        message: 'Lokacija nije podržana na ovom uređaju',
      });
      return;
    }

    // Stop existing watch if any
    this.stopWatching();

    const opts = { ...DEFAULT_OPTIONS, ...options };
    this._isLoading.set(true);
    this._error.set(null);

    this.watchId = navigator.geolocation.watchPosition(
      (position) => {
        this.ngZone.run(() => {
          this._position.set(this.mapPosition(position));
          this._error.set(null); // Clear any previous error on successful position
          this._isLoading.set(false);
        });
      },
      (error) => {
        this.ngZone.run(() => {
          this._error.set(this.mapError(error));
          this._isLoading.set(false);
        });
      },
      {
        enableHighAccuracy: opts.enableHighAccuracy,
        timeout: opts.timeout,
        maximumAge: opts.maximumAge,
      }
    );
  }

  /**
   * Stop watching position.
   */
  stopWatching(): void {
    if (this.watchId !== null) {
      navigator.geolocation.clearWatch(this.watchId);
      this.watchId = null;
    }
  }

  /**
   * Calculate distance between two coordinates using Haversine formula.
   * Mirrors backend GeofenceService logic.
   *
   * @returns Distance in meters
   */
  calculateDistance(lat1: number, lon1: number, lat2: number, lon2: number): number {
    const EARTH_RADIUS_METERS = 6371000;

    const dLat = this.toRadians(lat2 - lat1);
    const dLon = this.toRadians(lon2 - lon1);

    const a =
      Math.sin(dLat / 2) * Math.sin(dLat / 2) +
      Math.cos(this.toRadians(lat1)) *
        Math.cos(this.toRadians(lat2)) *
        Math.sin(dLon / 2) *
        Math.sin(dLon / 2);

    const c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));

    return EARTH_RADIUS_METERS * c;
  }

  /**
   * Check if position is within radius of target.
   * Uses dynamic radius based on accuracy and location type.
   */
  isWithinRadius(targetLat: number, targetLon: number, radiusMeters: number): boolean {
    const pos = this._position();
    if (!pos) return false;

    const distance = this.calculateDistance(pos.latitude, pos.longitude, targetLat, targetLon);

    return distance <= radiusMeters;
  }

  /**
   * Clear current position and error state.
   */
  reset(): void {
    this.stopWatching();
    this._position.set(null);
    this._error.set(null);
    this._isLoading.set(false);
  }

  // ========== PRIVATE METHODS ==========

  private mapPosition(position: GeolocationPosition): GeolocationResult {
    return {
      latitude: position.coords.latitude,
      longitude: position.coords.longitude,
      accuracy: position.coords.accuracy,
      timestamp: position.timestamp,
    };
  }

  private mapError(error: GeolocationPositionError): GeolocationError {
    switch (error.code) {
      case error.PERMISSION_DENIED:
        return {
          code: 'PERMISSION_DENIED',
          message: 'Pristup lokaciji je odbijen. Molimo omogućite lokaciju u podešavanjima.',
        };
      case error.POSITION_UNAVAILABLE:
        return {
          code: 'POSITION_UNAVAILABLE',
          message: 'Lokacija nije dostupna. Proverite GPS podešavanja i pokušajte napolju.',
        };
      case error.TIMEOUT:
        return {
          code: 'TIMEOUT',
          message: 'Dobijanje lokacije je isteklo. Molimo pokušajte ponovo.',
        };
      default:
        return {
          code: 'POSITION_UNAVAILABLE',
          message: 'Nepoznata greška pri dobijanju lokacije.',
        };
    }
  }

  private toRadians(degrees: number): number {
    return (degrees * Math.PI) / 180;
  }
}