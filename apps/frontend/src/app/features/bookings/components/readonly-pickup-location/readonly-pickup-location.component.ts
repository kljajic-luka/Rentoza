/**
 * ReadOnly Pickup Location Component
 *
 * Displays pickup location in read-only mode across booking UI.
 * Supports three display modes for different contexts:
 * - compact: Inline address with icon (for tight spaces)
 * - standard: Card with mini map (for dialogs)
 * - detailed: Full map with variance indicators (for check-in)
 *
 * @since 2.4.0 (Geospatial Location Migration)
 */
import { Component, Input, ChangeDetectionStrategy, computed, signal, input } from '@angular/core';
import { CommonModule, DecimalPipe } from '@angular/common';
import { MatCardModule } from '@angular/material/card';
import { MatIconModule } from '@angular/material/icon';
import { MatButtonModule } from '@angular/material/button';
import { MatTooltipModule } from '@angular/material/tooltip';
import { LocationPickerComponent } from '@shared/components/location-picker/location-picker.component';
import { LocationVarianceStatus, PickupLocationData } from '@core/models/booking-details.model';

/**
 * Display mode for the component.
 * - compact: Address + icon (inline, minimal)
 * - standard: Address + mini map in card
 * - detailed: Full context with variance badge and comparison maps
 */
export type PickupLocationDisplayMode = 'compact' | 'standard' | 'detailed';

/**
 * Car location data for variance comparison (check-in phase).
 */
export interface CarLocationData {
  latitude: number;
  longitude: number;
  address?: string;
}

@Component({
  selector: 'app-readonly-pickup-location',
  standalone: true,
  imports: [
    CommonModule,
    MatCardModule,
    MatIconModule,
    MatButtonModule,
    MatTooltipModule,
    LocationPickerComponent,
    DecimalPipe,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <!-- COMPACT MODE: Single line for tight spaces -->
    @if (mode() === 'compact') {
    <div class="readonly-pickup-location compact">
      <mat-icon>location_on</mat-icon>
      <span class="address">{{ displayAddress() }}</span>
      @if (pickupLocation()?.isEstimated) {
      <mat-icon
        class="estimated-icon"
        matTooltip="Procenjena lokacija (lokacija vozila)"
        matTooltipPosition="above"
      >
        info
      </mat-icon>
      }
    </div>
    }

    <!-- STANDARD MODE: Modern Turo-style card (for dialogs) -->
    @if (mode() === 'standard') {
    <div class="readonly-pickup-location standard turo-style">
      <!-- Section Header -->
      <div class="section-header">
        <div class="header-icon">
          <mat-icon>place</mat-icon>
        </div>
        <div class="header-content">
          <h3 class="section-title">Lokacija preuzimanja</h3>
          <p class="section-subtitle">Ovde preuzimate vozilo</p>
        </div>
        @if (pickupLocation()?.isEstimated) {
        <span class="estimated-chip">
          <mat-icon>info_outline</mat-icon>
          Približno
        </span>
        }
      </div>

      <!-- Location Card -->
      <div class="location-content">
        <!-- Address Display -->
        <div class="address-display">
          <div class="address-icon">
            <mat-icon>location_on</mat-icon>
          </div>
          <div class="address-text">
            <span class="address-main">{{ displayAddress() }}</span>
            @if (pickupLocation()?.city && pickupLocation()?.address) {
            <span class="address-secondary">
              {{ pickupLocation()?.city }}@if (pickupLocation()?.zipCode) {,
              {{ pickupLocation()?.zipCode }}}
            </span>
            }
          </div>
          @if (hasCoordinates()) {
          <button
            mat-icon-button
            class="directions-btn"
            matTooltip="Otvori u Google Maps"
            (click)="openInMaps()"
          >
            <mat-icon>directions</mat-icon>
          </button>
          }
        </div>

        <!-- Mini Map -->
        @if (hasCoordinates()) {
        <div class="map-wrapper" [class.expanded]="showMap()">
          @if (showMap()) {
          <app-location-picker
            [latitude]="pickupLocation()?.latitude ?? null"
            [longitude]="pickupLocation()?.longitude ?? null"
            [editable]="false"
            [showGeolocationButton]="false"
            [height]="'180px'"
            [zoom]="15"
            [markerColor]="'#593CFB'"
          />
          } @else {
          <button class="map-preview" (click)="toggleMap()">
            <div class="map-preview-content">
              <mat-icon>map</mat-icon>
              <span>Prikaži mapu</span>
            </div>
            <mat-icon class="expand-icon">expand_more</mat-icon>
          </button>
          }
        </div>
        }

        <!-- Delivery Info Pills -->
        @if (showDeliveryInfo() && hasDelivery()) {
        <div class="delivery-pills">
          <div class="pill delivery-distance">
            <mat-icon>route</mat-icon>
            <span>{{ deliveryDistance() | number : '1.1-1' }} km dostava</span>
          </div>
          @if (deliveryFee() && deliveryFee()! > 0) {
          <div class="pill delivery-fee">
            <mat-icon>payments</mat-icon>
            <span>{{ deliveryFee() | number : '1.0-0' }} RSD</span>
          </div>
          } @else {
          <div class="pill delivery-free">
            <mat-icon>check_circle</mat-icon>
            <span>Besplatna dostava</span>
          </div>
          }
        </div>
        }
      </div>
    </div>
    }

    <!-- DETAILED MODE: Full context with variance (for check-in) -->
    @if (mode() === 'detailed') {
    <div class="readonly-pickup-location detailed">
      <!-- Variance Badge (if applicable) -->
      @if (varianceStatus() && varianceStatus() !== 'NONE') {
      <div class="variance-badge" [class]="varianceStatus()?.toLowerCase()">
        @switch (varianceStatus()) { @case ('WARNING') {
        <mat-icon>warning</mat-icon>
        <span>Vozilo je {{ varianceMeters() | number : '1.0-0' }}m od dogovorene lokacije</span>
        } @case ('BLOCKING') {
        <mat-icon>error</mat-icon>
        <span
          >Vozilo je {{ varianceMeters() | number : '1.0-0' }}m od dogovorene lokacije (prelazi
          2km)</span
        >
        } }
      </div>
      } @else if (hasCoordinates() && varianceMeters() !== null && varianceMeters() !== undefined) {
      <div class="variance-badge none">
        <mat-icon>check_circle</mat-icon>
        <span>Vozilo je na dogovorenoj lokaciji</span>
      </div>
      }

      <!-- Agreed Pickup Location Card -->
      <mat-card class="location-card" appearance="outlined">
        <mat-card-header>
          <mat-icon mat-card-avatar>location_on</mat-icon>
          <mat-card-title>Dogovorena lokacija preuzimanja</mat-card-title>
        </mat-card-header>
        <mat-card-content>
          <div class="address-block">
            <p class="address-line">{{ displayAddress() }}</p>
            @if (pickupLocation()?.city && pickupLocation()?.address) {
            <p class="city-zip">
              {{ pickupLocation()?.city }}
              @if (pickupLocation()?.zipCode) { , {{ pickupLocation()?.zipCode }}
              }
            </p>
            }
          </div>

          @if (hasCoordinates()) {
          <app-location-picker
            [latitude]="pickupLocation()?.latitude ?? null"
            [longitude]="pickupLocation()?.longitude ?? null"
            [editable]="false"
            [showGeolocationButton]="false"
            [height]="'250px'"
            [zoom]="15"
            [markerColor]="'#1976d2'"
          />
          }
        </mat-card-content>
      </mat-card>

      <!-- Actual Car Location Card (if variance exists) -->
      @if (carLocation() && varianceStatus() && varianceStatus() !== 'NONE') {
      <mat-card class="location-card car-location-card" appearance="outlined">
        <mat-card-header>
          <mat-icon mat-card-avatar>directions_car</mat-icon>
          <mat-card-title>Stvarna lokacija vozila</mat-card-title>
        </mat-card-header>
        <mat-card-content>
          <div class="address-block">
            <p class="address-line">{{ carLocation()?.address || 'Koordinate dostupne' }}</p>
          </div>

          <app-location-picker
            [latitude]="carLocation()?.latitude ?? null"
            [longitude]="carLocation()?.longitude ?? null"
            [editable]="false"
            [showGeolocationButton]="false"
            [height]="'250px'"
            [zoom]="15"
            [markerColor]="'#f44336'"
          />
        </mat-card-content>
      </mat-card>
      }
    </div>
    }
  `,
  styles: [
    `
      /* ============================================
       TURO-STYLE PICKUP LOCATION COMPONENT
       Modern, clean design with smooth interactions
       ============================================ */
      :host {
        /* Turo brand colors */
        --turo-purple: #593cfb;
        --turo-purple-light: rgba(89, 60, 251, 0.08);
        --turo-green: #00a699;
        --turo-green-light: rgba(0, 166, 153, 0.08);

        /* Text colors - adapt to light/dark mode */
        --pickup-text-primary: var(--mat-app-text-color, var(--mdc-theme-on-surface, #222222));
        --pickup-text-secondary: var(--mat-app-on-surface-variant, #717171);

        /* Surface colors */
        --pickup-surface: var(--mat-app-surface, var(--mdc-theme-surface, #ffffff));
        --pickup-surface-variant: var(--mat-app-surface-variant, #f7f7f7);
        --pickup-surface-elevated: var(--mat-app-surface, #ffffff);

        /* Borders */
        --pickup-divider: var(--mat-app-outline-variant, #ebebeb);
        --pickup-border-radius: 12px;

        /* Primary colors */
        --pickup-primary: var(--turo-purple);
        --pickup-primary-container: var(--turo-purple-light);

        /* Status colors */
        --pickup-success-bg: var(--turo-green-light);
        --pickup-success-text: var(--turo-green);
        --pickup-warn-bg: rgba(255, 152, 0, 0.08);
        --pickup-warn-text: #c77700;
        --pickup-error-bg: rgba(244, 67, 54, 0.08);
        --pickup-error-text: #d32f2f;
      }

      /* Dark mode overrides */
      @media (prefers-color-scheme: dark) {
        :host {
          --pickup-surface-variant: #2a2a2a;
          --pickup-surface-elevated: #1e1e1e;
          --pickup-divider: #3d3d3d;
          --pickup-primary-container: rgba(89, 60, 251, 0.15);
          --pickup-success-bg: rgba(0, 166, 153, 0.15);
          --pickup-success-text: #4dd0c7;
          --pickup-warn-bg: rgba(255, 152, 0, 0.12);
          --pickup-warn-text: #ffb74d;
          --pickup-error-bg: rgba(244, 67, 54, 0.12);
          --pickup-error-text: #ef9a9a;
        }
      }

      :host-context(.dark-theme),
      :host-context(.theme-dark) {
        --pickup-surface-variant: #2a2a2a;
        --pickup-surface-elevated: #1e1e1e;
        --pickup-divider: #3d3d3d;
        --pickup-primary-container: rgba(89, 60, 251, 0.15);
        --pickup-success-bg: rgba(0, 166, 153, 0.15);
        --pickup-success-text: #4dd0c7;
        --pickup-warn-bg: rgba(255, 152, 0, 0.12);
        --pickup-warn-text: #ffb74d;
        --pickup-error-bg: rgba(244, 67, 54, 0.12);
        --pickup-error-text: #ef9a9a;
      }

      .readonly-pickup-location {
        font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, Oxygen, Ubuntu,
          sans-serif;
      }

      /* COMPACT MODE */
      .compact {
        display: flex;
        align-items: center;
        gap: 8px;
        font-size: 14px;

        mat-icon {
          color: var(--pickup-primary);
          font-size: 18px;
          width: 18px;
          height: 18px;
        }

        .address {
          font-weight: 500;
          color: var(--pickup-text-primary);
        }

        .estimated-icon {
          color: var(--pickup-text-secondary);
          font-size: 16px;
          width: 16px;
          height: 16px;
          cursor: help;
        }
      }

      /* ============================================
       TURO-STYLE STANDARD MODE
       ============================================ */
      .turo-style {
        /* Section Header */
        .section-header {
          display: flex;
          align-items: flex-start;
          gap: 12px;
          margin-bottom: 16px;

          .header-icon {
            width: 40px;
            height: 40px;
            border-radius: 50%;
            background: var(--pickup-primary-container);
            display: flex;
            align-items: center;
            justify-content: center;
            flex-shrink: 0;

            mat-icon {
              color: var(--pickup-primary);
              font-size: 22px;
              width: 22px;
              height: 22px;
            }
          }

          .header-content {
            flex: 1;
            min-width: 0;

            .section-title {
              margin: 0;
              font-size: 18px;
              font-weight: 600;
              color: var(--pickup-text-primary);
              line-height: 1.3;
            }

            .section-subtitle {
              margin: 2px 0 0;
              font-size: 14px;
              color: var(--pickup-text-secondary);
              line-height: 1.4;
            }
          }

          .estimated-chip {
            display: inline-flex;
            align-items: center;
            gap: 4px;
            padding: 4px 10px;
            background: var(--pickup-warn-bg);
            color: var(--pickup-warn-text);
            border-radius: 16px;
            font-size: 12px;
            font-weight: 500;
            flex-shrink: 0;

            mat-icon {
              font-size: 14px;
              width: 14px;
              height: 14px;
            }
          }
        }

        /* Location Content Card */
        .location-content {
          background: var(--pickup-surface-elevated);
          border: 1px solid var(--pickup-divider);
          border-radius: var(--pickup-border-radius);
          overflow: hidden;
          transition: box-shadow 0.2s ease;

          &:hover {
            box-shadow: 0 4px 12px rgba(0, 0, 0, 0.08);
          }
        }

        /* Address Display */
        .address-display {
          display: flex;
          align-items: center;
          gap: 12px;
          padding: 16px;
          border-bottom: 1px solid var(--pickup-divider);

          .address-icon {
            width: 36px;
            height: 36px;
            border-radius: 8px;
            background: var(--pickup-surface-variant);
            display: flex;
            align-items: center;
            justify-content: center;
            flex-shrink: 0;

            mat-icon {
              color: var(--pickup-primary);
              font-size: 20px;
              width: 20px;
              height: 20px;
            }
          }

          .address-text {
            flex: 1;
            min-width: 0;
            display: flex;
            flex-direction: column;
            gap: 2px;

            .address-main {
              font-size: 15px;
              font-weight: 500;
              color: var(--pickup-text-primary);
              line-height: 1.4;
              word-break: break-word;
            }

            .address-secondary {
              font-size: 13px;
              color: var(--pickup-text-secondary);
              line-height: 1.3;
            }
          }

          .directions-btn {
            flex-shrink: 0;
            color: var(--pickup-primary);
            background: var(--pickup-primary-container);
            transition: all 0.2s ease;

            &:hover {
              background: var(--pickup-primary);
              color: white;
            }
          }
        }

        /* Map Wrapper */
        .map-wrapper {
          transition: all 0.3s ease;

          &.expanded {
            border-top: none;
          }

          app-location-picker {
            display: block;
          }
        }

        .map-preview {
          width: 100%;
          display: flex;
          align-items: center;
          justify-content: space-between;
          padding: 14px 16px;
          background: var(--pickup-surface-variant);
          border: none;
          cursor: pointer;
          transition: background 0.2s ease;

          &:hover {
            background: var(--pickup-divider);
          }

          .map-preview-content {
            display: flex;
            align-items: center;
            gap: 8px;
            color: var(--pickup-text-secondary);
            font-size: 14px;
            font-weight: 500;

            mat-icon {
              font-size: 20px;
              width: 20px;
              height: 20px;
              color: var(--pickup-primary);
            }
          }

          .expand-icon {
            color: var(--pickup-text-secondary);
            transition: transform 0.2s ease;
          }

          &:hover .expand-icon {
            transform: translateY(2px);
          }
        }

        /* Delivery Pills */
        .delivery-pills {
          display: flex;
          gap: 8px;
          padding: 12px 16px;
          background: var(--pickup-surface-variant);
          flex-wrap: wrap;

          .pill {
            display: inline-flex;
            align-items: center;
            gap: 6px;
            padding: 6px 12px;
            border-radius: 20px;
            font-size: 13px;
            font-weight: 500;

            mat-icon {
              font-size: 16px;
              width: 16px;
              height: 16px;
            }

            &.delivery-distance {
              background: var(--pickup-surface-elevated);
              color: var(--pickup-text-primary);
              border: 1px solid var(--pickup-divider);

              mat-icon {
                color: var(--pickup-primary);
              }
            }

            &.delivery-fee {
              background: var(--pickup-primary-container);
              color: var(--pickup-primary);

              mat-icon {
                color: var(--pickup-primary);
              }
            }

            &.delivery-free {
              background: var(--pickup-success-bg);
              color: var(--pickup-success-text);

              mat-icon {
                color: var(--pickup-success-text);
              }
            }
          }
        }
      }

      /* ============================================
       DETAILED MODE (Check-in context)
       ============================================ */
      .detailed {
        display: flex;
        flex-direction: column;
        gap: 16px;

        .location-card {
          background: var(--pickup-surface);
          border-radius: var(--pickup-border-radius);

          mat-card-header {
            padding-bottom: 12px;

            mat-icon[mat-card-avatar] {
              color: var(--pickup-primary);
              background: var(--pickup-primary-container);
              border-radius: 50%;
              padding: 8px;
              width: 40px;
              height: 40px;
              font-size: 24px;
            }

            mat-card-title {
              font-size: 16px;
              font-weight: 600;
              color: var(--pickup-text-primary);
            }
          }

          mat-card-content {
            .address-block {
              margin-bottom: 16px;

              .address-line {
                margin: 0 0 4px 0;
                font-weight: 500;
                font-size: 15px;
                color: var(--pickup-text-primary);
              }

              .city-zip {
                margin: 0;
                color: var(--pickup-text-secondary);
                font-size: 13px;
              }
            }
          }
        }

        .car-location-card {
          mat-icon[mat-card-avatar] {
            color: var(--pickup-error-text);
            background: var(--pickup-error-bg);
          }
        }

        /* Variance Badge */
        .variance-badge {
          display: flex;
          align-items: center;
          gap: 10px;
          padding: 14px 16px;
          border-radius: var(--pickup-border-radius);
          font-size: 14px;
          font-weight: 500;

          mat-icon {
            font-size: 22px;
            width: 22px;
            height: 22px;
          }

          &.none {
            background-color: var(--pickup-success-bg);
            color: var(--pickup-success-text);

            mat-icon {
              color: var(--pickup-success-text);
            }
          }

          &.warning {
            background-color: var(--pickup-warn-bg);
            color: var(--pickup-warn-text);

            mat-icon {
              color: var(--pickup-warn-text);
            }
          }

          &.blocking {
            background-color: var(--pickup-error-bg);
            color: var(--pickup-error-text);

            mat-icon {
              color: var(--pickup-error-text);
            }
          }
        }
      }

      /* Responsive */
      @media (max-width: 599px) {
        .turo-style {
          .section-header {
            .header-icon {
              width: 36px;
              height: 36px;

              mat-icon {
                font-size: 20px;
                width: 20px;
                height: 20px;
              }
            }

            .section-title {
              font-size: 16px;
            }
          }

          .delivery-pills {
            .pill {
              font-size: 12px;
              padding: 5px 10px;
            }
          }
        }
      }
    `,
  ],
})
export class ReadOnlyPickupLocationComponent {
  // === INPUTS (Signal-based for modern Angular) ===

  /** Agreed pickup location data */
  pickupLocation = input<PickupLocationData | null>(null);

  /** Actual car location at check-in (for variance comparison) */
  carLocation = input<CarLocationData | null>(null);

  /** Display mode: compact, standard, or detailed */
  mode = input<PickupLocationDisplayMode>('standard');

  /** Variance status enum from backend */
  varianceStatus = input<LocationVarianceStatus | null>(null);

  /** Variance in meters (for display) */
  varianceMeters = input<number | null>(null);

  /** Whether to show delivery info section */
  showDeliveryInfo = input<boolean>(false);

  /** Delivery distance in km */
  deliveryDistance = input<number | null>(null);

  /** Delivery fee in RSD */
  deliveryFee = input<number | null>(null);

  // === INTERNAL STATE ===

  /** Whether map is expanded (lazy loading for performance) */
  private _showMap = signal(false);
  showMap = this._showMap.asReadonly();

  // === COMPUTED ===

  /** Check if we have valid coordinates */
  hasCoordinates = computed(() => {
    const loc = this.pickupLocation();
    return loc !== null && loc.latitude !== undefined && loc.longitude !== undefined;
  });

  /** Check if there's delivery info to show */
  hasDelivery = computed(() => {
    const dist = this.deliveryDistance();
    return dist !== null && dist !== undefined && dist > 0;
  });

  /** Display address (with intelligent fallback) */
  displayAddress = computed(() => {
    const loc = this.pickupLocation();
    if (!loc) return 'Lokacija nije dostupna';

    // Priority: address > city with zipCode > city only > coordinates > generic fallback
    if (loc.address) {
      return loc.address;
    }
    if (loc.city && loc.zipCode) {
      return `${loc.city}, ${loc.zipCode}`;
    }
    if (loc.city) {
      return loc.city;
    }
    if (loc.latitude && loc.longitude) {
      // Format coordinates nicely (5 decimal places for ~1m accuracy)
      return `${loc.latitude.toFixed(5)}, ${loc.longitude.toFixed(5)}`;
    }
    return 'Lokacija dostupna na mapi';
  });

  /** Short address for compact mode (address line only, no city/zip) */
  shortAddress = computed(() => {
    const loc = this.pickupLocation();
    if (!loc) return 'Lokacija nije dostupna';

    if (loc.address) return loc.address;
    if (loc.city) return loc.city;
    if (loc.latitude && loc.longitude) {
      return `📍 ${loc.latitude.toFixed(4)}, ${loc.longitude.toFixed(4)}`;
    }
    return 'Pogledaj na mapi';
  });

  // === METHODS ===

  /** Toggle map visibility (lazy loading) */
  toggleMap(): void {
    this._showMap.update((v) => !v);
  }

  /** Open location in Google Maps (external navigation) */
  openInMaps(): void {
    const loc = this.pickupLocation();
    if (!loc || !loc.latitude || !loc.longitude) return;

    const url = `https://www.google.com/maps/dir/?api=1&destination=${loc.latitude},${loc.longitude}`;
    window.open(url, '_blank', 'noopener,noreferrer');
  }
}
