/**
 * Pickup Location Selector Component
 *
 * A component for selecting the pickup location during booking.
 * Shows car's location on map and allows guest to select a different
 * pickup point (if host offers delivery).
 *
 * FEATURES:
 * - Display car's location (obfuscated for privacy)
 * - Allow guest to select different pickup location
 * - Calculate delivery fee for selected location
 * - Validate pickup is within host's delivery radius
 *
 * @example
 * <app-pickup-location-selector
 *   [carId]="car.id"
 *   [carLocation]="car.locationGeoPoint"
 *   [deliveryMaxRadius]="car.deliveryMaxRadius"
 *   (pickupLocationChanged)="onPickupChanged($event)"
 *   (deliveryFeeCalculated)="onDeliveryFee($event)">
 * </app-pickup-location-selector>
 *
 * @since 2.4.0 (Geospatial Location Migration)
 */
import {
  Component,
  Input,
  Output,
  EventEmitter,
  OnInit,
  OnChanges,
  SimpleChanges,
  inject,
  signal,
  computed,
  ChangeDetectionStrategy,
} from '@angular/core';
import { CommonModule } from '@angular/common';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatRadioModule } from '@angular/material/radio';
import { FormsModule } from '@angular/forms';
import { HttpClient } from '@angular/common/http';
import { catchError, of } from 'rxjs';

import { LocationService, GeoPointDTO, DeliveryFeeResult } from '@core/services/location.service';
import { LocationPickerComponent, LocationCoordinates } from '@shared/components/location-picker';
import { environment } from '../../../../../environments/environment';

/** Pickup option type */
export type PickupOption = 'car-location' | 'custom-location';

/** Pickup location data emitted to parent */
export interface PickupLocationData {
  latitude: number;
  longitude: number;
  address?: string;
  city?: string;
  /** True if using car's original location */
  isCarLocation: boolean;
}

@Component({
  selector: 'app-pickup-location-selector',
  standalone: true,
  imports: [
    CommonModule,
    FormsModule,
    MatFormFieldModule,
    MatInputModule,
    MatButtonModule,
    MatIconModule,
    MatProgressSpinnerModule,
    MatRadioModule,
    LocationPickerComponent,
  ],
  templateUrl: './pickup-location-selector.component.html',
  styleUrls: ['./pickup-location-selector.component.scss'],
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class PickupLocationSelectorComponent implements OnInit, OnChanges {
  private readonly http = inject(HttpClient);
  private readonly locationService = inject(LocationService);

  // === INPUTS ===

  /** Car ID for delivery fee calculation */
  @Input() carId!: string | number;

  /** Car's location (may be obfuscated) */
  @Input() carLocation!: GeoPointDTO;

  /** Whether host offers delivery */
  @Input() deliveryAvailable = false;

  /** Maximum delivery radius in km */
  @Input() deliveryMaxRadius = 25;

  /** Initial pickup location (if preset) */
  @Input() initialPickupLocation?: PickupLocationData;

  // === OUTPUTS ===

  /** Emitted when pickup location changes */
  @Output() pickupLocationChanged = new EventEmitter<PickupLocationData>();

  /** Emitted when delivery fee is calculated */
  @Output() deliveryFeeCalculated = new EventEmitter<DeliveryFeeResult>();

  /** Emitted when pickup validity changes */
  @Output() validityChanged = new EventEmitter<boolean>();

  // === REACTIVE STATE ===

  protected readonly pickupOption = signal<PickupOption>('car-location');
  protected readonly customLocation = signal<GeoPointDTO | null>(null);
  protected readonly isCalculatingFee = signal(false);
  protected readonly showMap = signal(false);
  protected readonly deliveryFee = signal<DeliveryFeeResult | null>(null);

  protected readonly currentPickupLocation = computed<GeoPointDTO>(() => {
    if (this.pickupOption() === 'custom-location' && this.customLocation()) {
      return this.customLocation()!;
    }
    return this.carLocation;
  });

  protected readonly isValidPickup = computed(() => {
    const fee = this.deliveryFee();
    if (this.pickupOption() === 'car-location') {
      return true;
    }
    return fee?.available ?? false;
  });

  protected readonly distanceFromCar = computed(() => {
    const custom = this.customLocation();
    if (!custom || !this.carLocation) return null;

    // Haversine distance calculation
    const R = 6371; // km
    const dLat = this.toRad(custom.latitude - this.carLocation.latitude);
    const dLon = this.toRad(custom.longitude - this.carLocation.longitude);
    const lat1 = this.toRad(this.carLocation.latitude);
    const lat2 = this.toRad(custom.latitude);

    const a =
      Math.sin(dLat / 2) * Math.sin(dLat / 2) +
      Math.sin(dLon / 2) * Math.sin(dLon / 2) * Math.cos(lat1) * Math.cos(lat2);
    const c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));

    return R * c;
  });

  ngOnInit(): void {
    // Set initial pickup location if provided
    if (this.initialPickupLocation) {
      if (!this.initialPickupLocation.isCarLocation) {
        this.pickupOption.set('custom-location');
        this.customLocation.set({
          latitude: this.initialPickupLocation.latitude,
          longitude: this.initialPickupLocation.longitude,
          address: this.initialPickupLocation.address,
          city: this.initialPickupLocation.city,
        });
      }
    }

    this.emitPickupLocation();
  }

  ngOnChanges(changes: SimpleChanges): void {
    if (changes['carLocation'] && !changes['carLocation'].firstChange) {
      this.emitPickupLocation();
    }
  }

  /**
   * Handle pickup option change
   */
  protected onPickupOptionChange(option: PickupOption): void {
    this.pickupOption.set(option);

    if (option === 'car-location') {
      this.deliveryFee.set(null);
      this.showMap.set(false);
    } else {
      this.showMap.set(true);
      if (this.customLocation()) {
        this.calculateDeliveryFee();
      }
    }

    this.emitPickupLocation();
    this.validityChanged.emit(this.isValidPickup());
  }

  /**
   * Handle map location change
   */
  protected onMapLocationChanged(coords: LocationCoordinates): void {
    // Reverse geocode to get address
    this.locationService.reverseGeocode(coords.latitude, coords.longitude).subscribe({
      next: (result) => {
        this.customLocation.set({
          latitude: coords.latitude,
          longitude: coords.longitude,
          address: result.address,
          city: result.city,
        });

        this.calculateDeliveryFee();
        this.emitPickupLocation();
      },
      error: () => {
        this.customLocation.set({
          latitude: coords.latitude,
          longitude: coords.longitude,
        });
        this.calculateDeliveryFee();
        this.emitPickupLocation();
      },
    });
  }

  /**
   * Calculate delivery fee for custom location
   */
  private calculateDeliveryFee(): void {
    const custom = this.customLocation();
    if (!custom || !this.carId) return;

    this.isCalculatingFee.set(true);

    this.http
      .get<DeliveryFeeResult>(`${environment.baseApiUrl}/delivery/calculate`, {
        params: {
          carId: this.carId.toString(),
          pickupLatitude: custom.latitude.toString(),
          pickupLongitude: custom.longitude.toString(),
        },
      })
      .pipe(
        catchError((error) => {
          console.error('Delivery fee calculation failed:', error);
          return of<DeliveryFeeResult>({
            available: false,
            unavailableReason: 'Greška pri izračunu naknade za dostavu',
          });
        })
      )
      .subscribe({
        next: (result) => {
          this.deliveryFee.set(result);
          this.deliveryFeeCalculated.emit(result);
          this.validityChanged.emit(result.available ?? false);
          this.isCalculatingFee.set(false);
        },
      });
  }

  /**
   * Emit current pickup location to parent
   */
  private emitPickupLocation(): void {
    const loc = this.currentPickupLocation();

    this.pickupLocationChanged.emit({
      latitude: loc.latitude,
      longitude: loc.longitude,
      address: loc.address,
      city: loc.city,
      isCarLocation: this.pickupOption() === 'car-location',
    });
  }

  /**
   * Convert degrees to radians
   */
  private toRad(deg: number): number {
    return deg * (Math.PI / 180);
  }

  /**
   * Get the current pickup location data
   */
  public getPickupLocation(): PickupLocationData {
    const loc = this.currentPickupLocation();
    return {
      latitude: loc.latitude,
      longitude: loc.longitude,
      address: loc.address,
      city: loc.city,
      isCarLocation: this.pickupOption() === 'car-location',
    };
  }

  /**
   * Check if the current selection is valid
   */
  public isValid(): boolean {
    return this.isValidPickup();
  }
}
