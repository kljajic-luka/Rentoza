# Geospatial Migration: Frontend Integration Guide

**Document Version**: 1.0  
**Status**: Active Implementation  
**Framework**: Angular v16+ with TypeScript 5.x  
**Backend Status**: Phases 1-3 Implemented ✅

---

## Executive Summary

This guide provides detailed implementation instructions for integrating geospatial features into the Rentoza Angular frontend. The backend (Spring Boot) has completed **Phases 1-3** of the geospatial migration:

- ✅ **Phase 1**: Booking creation with location snapshot & delivery fees
- ✅ **Phase 2**: Check-in with location variance verification
- ✅ **Phase 3**: ID verification with photo upload (mocked)

This document covers **3 critical frontend integration areas**:

1. **Booking Flow** - Location picker with map integration
2. **Check-in Flow** - ID verification photo upload
3. **Search** - Geospatial car discovery with location obfuscation

---

## Backend Completion Status

### Phase 1: Booking Creation ✅
**Files Modified**:
- `BookingService.java` - Integrated `DeliveryFeeCalculator`
- `BookingRequestDTO.java` - Added geospatial fields with validation
- `BookingResponseDTO.java` - Returns `PickupLocationDTO` & delivery fee
- `Booking.java` - Has `pickupLocation`, `deliveryDistanceKm`, `deliveryFeeCalculated`

**What Frontend Receives**:
```typescript
{
  id: 123,
  carId: 456,
  startTime: "2025-10-10T10:00:00",
  endTime: "2025-10-12T10:00:00",
  totalPrice: 35000,
  deliveryFee: 5000,
  deliveryDistanceKm: 45.5,
  pickupLocation: {
    latitude: 44.8176,
    longitude: 20.4633,
    address: "Terazije 26, Beograd",
    city: "Beograd",
    zipCode: "11000"
  }
}
```

### Phase 2: Check-in Location Verification ✅
**Files Modified**:
- `CheckInService.java` - Location variance check in `completeHostCheckIn()`
- `CheckInEventType.java` - Added `LOCATION_VARIANCE_WARNING`, `LOCATION_VARIANCE_BLOCKING`
- `GeofenceService.java` - Dynamic radius based on location density

**What Frontend Sends**:
```typescript
{
  bookingId: 123,
  odometerReading: 45678,
  fuelLevelPercent: 75,
  carLatitude: 44.8180,
  carLongitude: 20.4635,
  hostLatitude: 44.8175,
  hostLongitude: 20.4630,
  lockboxCode: "1234" // optional
}
```

### Phase 3: ID Verification ✅
**Files Modified**:
- `CheckInEventType.java` - Added `GUEST_ID_VERIFIED`, `GUEST_ID_FAILED`
- Backend ready for ID photo submission

**What Frontend Sends**:
```typescript
{
  bookingId: 123,
  documentType: "NATIONAL_ID",
  issueCountry: "RS",
  idFrontPhoto: "data:image/jpeg;base64,...",
  idBackPhoto: "data:image/jpeg;base64,...",
  selfiePhoto: "data:image/jpeg;base64,..."
}
```

---

## Part 1: Booking Flow Integration

### 1.1 Architecture Overview

```
BookingFormComponent
├── Location Selection Mode
│   ├── Map View (Google Maps)
│   │   └── Click to select custom pickup
│   └── Address Search
│       └── Autocomplete via Nominatim/Google Geocoding
├── Location Validation
│   ├── Boundary check (Serbia bounds)
│   └── Real-time address reverse-geocoding
├── Delivery Fee Calculation
│   ├── Call backend `/api/delivery/estimate`
│   └── Display distance & fee to user
└── Booking Submission
    └── POST to `/api/bookings` with location data
```

### 1.2 BookingFormComponent (TypeScript)

**File**: `rentoza-frontend/src/app/features/booking/booking-form/booking-form.component.ts`

```typescript
import { Component, OnInit, ViewChild, NgZone } from '@angular/core';
import { FormBuilder, FormGroup, Validators, AbstractControl } from '@angular/forms';
import { Router } from '@angular/router';
import { GoogleMap } from '@angular/google-maps';
import { BookingService } from '../../../shared/services/booking.service';
import { GeocodingService } from '../../../shared/services/geocoding.service';
import { DeliveryService } from '../../../shared/services/delivery.service';
import { NotificationService } from '../../../shared/services/notification.service';
import { Observable } from 'rxjs';
import { debounceTime, distinctUntilChanged, switchMap } from 'rxjs/operators';

/**
 * Component for booking car with optional custom pickup location.
 * 
 * Features:
 * - Map-based location selection
 * - Address autocomplete search
 * - Real-time delivery fee estimation
 * - Location boundary validation (Serbia only)
 */
@Component({
  selector: 'app-booking-form',
  templateUrl: './booking-form.component.html',
  styleUrls: ['./booking-form.component.scss']
})
export class BookingFormComponent implements OnInit {

  @ViewChild('mapElement') mapElement!: GoogleMap;

  // ========== FORM MANAGEMENT ==========
  bookingForm!: FormGroup;
  addressSearchControl!: AbstractControl;
  
  // ========== LOCATION STATE ==========
  selectedLocation: { lat: number; lng: number } | null = null;
  selectedAddress: string = '';
  locationMode: 'home' | 'custom' = 'home'; // 'home' = car's home, 'custom' = map selection
  
  // ========== MAP STATE ==========
  mapCenter = { lat: 44.8176, lng: 20.4633 }; // Belgrade center
  mapZoom = 12;
  markerPosition: google.maps.LatLngLiteral | null = null;
  mapOptions: google.maps.MapOptions = {
    zoom: 12,
    center: this.mapCenter,
    mapTypeControl: true,
    fullscreenControl: true,
    streetViewControl: false
  };

  // ========== DELIVERY ESTIMATION ==========
  deliveryEstimate: { distance: number; fee: number } | null = null;
  estimatingDelivery = false;
  
  // ========== ADDRESS AUTOCOMPLETE ==========
  addressSuggestions$: Observable<any[]> | null = null;
  selectedAddressFromSearch: { address: string; lat: number; lng: number } | null = null;
  
  // ========== UI STATE ==========
  loading = false;
  submitting = false;
  showAdvancedLocation = false;
  
  // ========== CONSTANTS ==========
  private readonly SERBIA_BOUNDS = {
    north: 46.2,
    south: 42.2,
    east: 23.0,
    west: 18.8
  };

  constructor(
    private formBuilder: FormBuilder,
    private bookingService: BookingService,
    private geocodingService: GeocodingService,
    private deliveryService: DeliveryService,
    private notificationService: NotificationService,
    private router: Router,
    private ngZone: NgZone
  ) {}

  ngOnInit(): void {
    this.initializeForm();
    this.setupLocationSearch();
  }

  /**
   * Initialize the booking form with geospatial fields.
   */
  private initializeForm(): void {
    this.bookingForm = this.formBuilder.group({
      carId: [null, Validators.required],
      startTime: [null, Validators.required],
      endTime: [null, Validators.required],
      
      // ========== LOCATION FIELDS (Phase 2.4) ==========
      locationMode: ['home'], // 'home' or 'custom'
      
      pickupLatitude: [null],
      pickupLongitude: [null],
      pickupAddress: [''],
      pickupCity: [''],
      pickupZipCode: [''],
      
      // Additional options
      insuranceType: ['BASIC'],
      prepaidRefuel: [false],
      deliveryRequested: [false]
    }, {
      validators: this.locationValidator.bind(this)
    });

    // Address search control (not part of form submission)
    this.addressSearchControl = this.formBuilder.control('');
  }

  /**
   * Setup address search autocomplete with debouncing.
   */
  private setupLocationSearch(): void {
    this.addressSuggestions$ = this.addressSearchControl.valueChanges.pipe(
      debounceTime(300), // Wait 300ms after user stops typing
      distinctUntilChanged(),
      switchMap((query: string) => {
        if (!query || query.length < 3) {
          return new Observable(observer => {
            observer.next([]);
            observer.complete();
          });
        }
        
        // Only search within Serbia
        return this.geocodingService.searchAddress(query, {
          viewbox: `${this.SERBIA_BOUNDS.west},${this.SERBIA_BOUNDS.south},${this.SERBIA_BOUNDS.east},${this.SERBIA_BOUNDS.north}`,
          bounded: true,
          countrycodes: 'rs'
        });
      })
    );
  }

  /**
   * Handle address selection from autocomplete.
   */
  onAddressSelected(suggestion: any): void {
    const lat = parseFloat(suggestion.lat);
    const lng = parseFloat(suggestion.lon);
    
    // Validate coordinates are within Serbia
    if (!this.isWithinSerbiaBounds(lat, lng)) {
      this.notificationService.error(
        'Odabrana lokacija je van granica Srbije. Molimo odaberite lokaciju u Srbiji.'
      );
      return;
    }

    this.selectedAddressFromSearch = {
      address: suggestion.display_name,
      lat,
      lng
    };

    // Update map and form
    this.updateLocationSelection(lat, lng, suggestion.display_name);
    
    log.debug('Address selected from search:', suggestion.display_name);
  }

  /**
   * Handle map click for location selection.
   */
  onMapClick(event: google.maps.MapMouseEvent): void {
    if (!event.latLng) return;

    const lat = event.latLng.lat();
    const lng = event.latLng.lng();

    // Validate coordinates are within Serbia
    if (!this.isWithinSerbiaBounds(lat, lng)) {
      this.notificationService.error(
        'Odabrana lokacija je van granica Srbije. Molimo odaberite lokaciju u Srbiji.'
      );
      return;
    }

    // Trigger reverse geocoding to get address
    this.geocodingService.reverseGeocode(lat, lng)
      .subscribe(
        (result: any) => {
          const address = result.address?.label || 'Neznata lokacija';
          this.updateLocationSelection(lat, lng, address);
        },
        (error) => {
          console.error('Reverse geocoding failed:', error);
          this.updateLocationSelection(lat, lng, '');
        }
      );
  }

  /**
   * Update the location selection and trigger delivery estimation.
   */
  private updateLocationSelection(lat: number, lng: number, address: string): void {
    this.selectedLocation = { lat, lng };
    this.selectedAddress = address;
    this.markerPosition = { lat, lng };
    this.locationMode = 'custom';

    // Reverse geocode to extract city and zip
    this.geocodingService.reverseGeocode(lat, lng)
      .subscribe(
        (result: any) => {
          const addr = result.address || {};
          this.bookingForm.patchValue({
            locationMode: 'custom',
            pickupLatitude: lat,
            pickupLongitude: lng,
            pickupAddress: address,
            pickupCity: addr.city || addr.county || '',
            pickupZipCode: addr.postcode || '',
            deliveryRequested: true
          });

          // Estimate delivery fee
          if (this.bookingForm.get('carId')?.value) {
            this.estimateDeliveryFee(lat, lng);
          }
        }
      );
  }

  /**
   * Estimate delivery fee for the selected location.
   */
  private estimateDeliveryFee(lat: number, lng: number): void {
    const carId = this.bookingForm.get('carId')?.value;
    if (!carId) return;

    this.estimatingDelivery = true;
    this.deliveryService.estimateFee(carId, lat, lng)
      .subscribe(
        (result: any) => {
          this.deliveryEstimate = {
            distance: result.distanceKm,
            fee: result.feeBsd
          };
          
          // Update total price preview
          this.updatePricePreview();
          
          this.estimatingDelivery = false;
        },
        (error) => {
          console.error('Delivery estimation failed:', error);
          this.notificationService.error('Nije moguće proceniti dostavu. Molimo pokušajte kasnije.');
          this.estimatingDelivery = false;
        }
      );
  }

  /**
   * Update price preview with delivery fee.
   */
  private updatePricePreview(): void {
    const basePrice = this.calculateBasePrice();
    const insuranceMultiplier = this.getInsuranceMultiplier();
    const deliveryFee = this.deliveryEstimate?.fee || 0;
    
    const totalPrice = (basePrice * insuranceMultiplier) + deliveryFee;
    
    // Display to user (not stored in form)
    console.log('Price preview:', {
      basePrice,
      insuranceMultiplier,
      deliveryFee,
      totalPrice
    });
  }

  /**
   * Validate location selection based on form mode.
   */
  private locationValidator(group: FormGroup): { [key: string]: any } | null {
    const mode = group.get('locationMode')?.value;
    
    if (mode === 'custom') {
      const lat = group.get('pickupLatitude')?.value;
      const lng = group.get('pickupLongitude')?.value;
      
      // Both must be present together
      if ((lat === null && lng !== null) || (lat !== null && lng === null)) {
        return { coordinatesMismatch: true };
      }
      
      // Both must be within Serbia
      if (lat !== null && lng !== null) {
        if (!this.isWithinSerbiaBounds(lat, lng)) {
          return { coordinatesOutOfBounds: true };
        }
      }
    }
    
    return null;
  }

  /**
   * Validate if coordinates are within Serbia bounds.
   */
  private isWithinSerbiaBounds(lat: number, lng: number): boolean {
    return lat >= this.SERBIA_BOUNDS.south &&
           lat <= this.SERBIA_BOUNDS.north &&
           lng >= this.SERBIA_BOUNDS.west &&
           lng <= this.SERBIA_BOUNDS.east;
  }

  /**
   * Calculate base rental price (simplified for demo).
   */
  private calculateBasePrice(): number {
    const startDate = this.bookingForm.get('startTime')?.value;
    const endDate = this.bookingForm.get('endTime')?.value;
    
    if (!startDate || !endDate) return 0;
    
    const days = Math.ceil((endDate - startDate) / (1000 * 60 * 60 * 24));
    const dailyRate = 5000; // Mock daily rate
    
    return days * dailyRate;
  }

  /**
   * Get insurance multiplier for pricing.
   */
  private getInsuranceMultiplier(): number {
    const insuranceType = this.bookingForm.get('insuranceType')?.value || 'BASIC';
    return {
      BASIC: 1.0,
      STANDARD: 1.1,
      PREMIUM: 1.2
    }[insuranceType] || 1.0;
  }

  /**
   * Reset location selection to car's home location.
   */
  resetToCarHome(): void {
    this.selectedLocation = null;
    this.selectedAddress = '';
    this.markerPosition = null;
    this.deliveryEstimate = null;
    this.locationMode = 'home';

    this.bookingForm.patchValue({
      locationMode: 'home',
      pickupLatitude: null,
      pickupLongitude: null,
      pickupAddress: '',
      pickupCity: '',
      pickupZipCode: '',
      deliveryRequested: false
    });
  }

  /**
   * Submit the booking with location data.
   */
  submitBooking(): void {
    if (this.bookingForm.invalid) {
      this.notificationService.error('Molimo popunite sve obavezne poljeKolone');
      return;
    }

    this.submitting = true;

    const bookingRequest = {
      carId: this.bookingForm.get('carId')?.value,
      startTime: this.bookingForm.get('startTime')?.value.toISOString(),
      endTime: this.bookingForm.get('endTime')?.value.toISOString(),
      insuranceType: this.bookingForm.get('insuranceType')?.value,
      prepaidRefuel: this.bookingForm.get('prepaidRefuel')?.value,
      
      // Geospatial fields (Phase 2.4)
      pickupLatitude: this.bookingForm.get('pickupLatitude')?.value,
      pickupLongitude: this.bookingForm.get('pickupLongitude')?.value,
      pickupAddress: this.bookingForm.get('pickupAddress')?.value,
      pickupCity: this.bookingForm.get('pickupCity')?.value,
      pickupZipCode: this.bookingForm.get('pickupZipCode')?.value,
      deliveryRequested: this.bookingForm.get('deliveryRequested')?.value
    };

    this.bookingService.createBooking(bookingRequest)
      .subscribe(
        (booking: any) => {
          this.notificationService.success('Rezervacija je uspešno kreirano!');
          this.submitting = false;
          
          // Navigate to booking confirmation
          this.router.navigate(['/bookings', booking.id]);
        },
        (error) => {
          console.error('Booking creation failed:', error);
          this.notificationService.error(error.error?.message || 'Greška pri kreiranju rezervacije');
          this.submitting = false;
        }
      );
  }
}
```

### 1.3 BookingFormComponent (Template)

**File**: `rentoza-frontend/src/app/features/booking/booking-form/booking-form.component.html`

```html
<form [formGroup]="bookingForm" (ngSubmit)="submitBooking()" class="booking-form">
  
  <!-- ========== BASIC BOOKING INFO ========== -->
  <div class="form-section">
    <h3>Osnovne informacije</h3>
    
    <div class="form-group">
      <label for="carId">Automobil</label>
      <select id="carId" formControlName="carId" class="form-control">
        <option value="" disabled>Odaberite automobil</option>
        <!-- Options populated from service -->
      </select>
    </div>

    <div class="form-row">
      <div class="form-group">
        <label for="startTime">Početak putovanja</label>
        <input type="datetime-local" id="startTime" formControlName="startTime" 
               class="form-control" required>
      </div>
      <div class="form-group">
        <label for="endTime">Kraj putovanja</label>
        <input type="datetime-local" id="endTime" formControlName="endTime" 
               class="form-control" required>
      </div>
    </div>
  </div>

  <!-- ========== LOCATION SELECTION (Phase 2.4) ========== -->
  <div class="form-section">
    <h3>Mesto preuzimanja</h3>
    
    <!-- Location Mode Toggle -->
    <div class="location-mode-toggle">
      <button type="button" 
              [class.active]="locationMode === 'home'"
              (click)="resetToCarHome()"
              class="mode-button">
        <mat-icon>home</mat-icon>
        Dogovljena lokacija
      </button>
      <button type="button" 
              [class.active]="locationMode === 'custom'"
              (click)="showAdvancedLocation = !showAdvancedLocation"
              class="mode-button">
        <mat-icon>location_on</mat-icon>
        Prilagođena lokacija
      </button>
    </div>

    <!-- Custom Location Selection -->
    <div *ngIf="locationMode === 'custom'" class="location-selection">
      
      <!-- Address Search -->
      <div class="form-group">
        <label for="addressSearch">Pretraži adresu</label>
        <input type="text" 
               id="addressSearch"
               [formControl]="addressSearchControl"
               placeholder="Unesite adresu..."
               class="form-control"
               autocomplete="off">
        
        <!-- Address Suggestions -->
        <div *ngIf="addressSuggestions$ | async as suggestions" 
             class="suggestions-dropdown"
             [hidden]="suggestions.length === 0">
          <div *ngFor="let suggestion of suggestions" 
               (click)="onAddressSelected(suggestion)"
               class="suggestion-item">
            <mat-icon>location_on</mat-icon>
            <span>{{ suggestion.display_name }}</span>
          </div>
        </div>
      </div>

      <!-- Map -->
      <div class="map-container">
        <google-map #mapElement 
                    [center]="mapCenter"
                    [zoom]="mapZoom"
                    [options]="mapOptions"
                    (mapClick)="onMapClick($event)">
          
          <!-- Current Location Marker -->
          <map-marker *ngIf="markerPosition"
                      [position]="markerPosition"
                      [title]="selectedAddress">
          </map-marker>
        </google-map>
        <p class="map-hint">Kliknite na mapu da odaberete lokaciju</p>
      </div>

      <!-- Selected Location Details -->
      <div *ngIf="selectedLocation" class="location-details">
        <div class="detail-item">
          <strong>Adresa:</strong>
          <span>{{ selectedAddress }}</span>
        </div>
        <div class="detail-item">
          <strong>Koordinate:</strong>
          <span>{{ selectedLocation.lat | number:'1.4-4' }}, {{ selectedLocation.lng | number:'1.4-4' }}</span>
        </div>
        
        <!-- Delivery Estimation -->
        <div *ngIf="estimatingDelivery" class="loading-spinner">
          <mat-spinner diameter="30"></mat-spinner>
          Računam dostavu...
        </div>
        
        <div *ngIf="deliveryEstimate && !estimatingDelivery" class="delivery-estimate">
          <h4>Procena dostave</h4>
          <div class="estimate-item">
            <span>Rastojanje:</span>
            <strong>{{ deliveryEstimate.distance | number:'1.1-2' }} km</strong>
          </div>
          <div class="estimate-item">
            <span>Naknada za dostavu:</span>
            <strong>{{ deliveryEstimate.fee | currency:'RSD':'symbol':'1.0-0' }}</strong>
          </div>
        </div>
      </div>
    </div>
  </div>

  <!-- ========== RENTAL OPTIONS ========== -->
  <div class="form-section">
    <h3>Opcije putovanja</h3>
    
    <div class="form-group">
      <label for="insuranceType">Vrsta osiguranja</label>
      <select id="insuranceType" formControlName="insuranceType" class="form-control">
        <option value="BASIC">Osnovno (besplatno)</option>
        <option value="STANDARD">Standardno (+10%)</option>
        <option value="PREMIUM">Premium (+20%)</option>
      </select>
    </div>

    <div class="form-group checkbox">
      <label>
        <input type="checkbox" formControlName="prepaidRefuel">
        Plaćeno gorivo (napuni bazen pre povratka)
      </label>
    </div>
  </div>

  <!-- ========== SUBMISSION ========== -->
  <div class="form-actions">
    <button type="submit" 
            [disabled]="bookingForm.invalid || submitting"
            class="btn btn-primary">
      <mat-spinner *ngIf="submitting" diameter="20"></mat-spinner>
      {{ submitting ? 'Kreiram rezervaciju...' : 'Kreiraj rezervaciju' }}
    </button>
  </div>

  <!-- ========== ERROR MESSAGES ========== -->
  <div *ngIf="bookingForm.errors?.['coordinatesOutOfBounds']" class="alert alert-error">
    Odabrana lokacija je van granica Srbije
  </div>
</form>
```

### 1.4 BookingService Updates

**File**: `rentoza-frontend/src/app/shared/services/booking.service.ts`

```typescript
import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';

@Injectable({
  providedIn: 'root'
})
export class BookingService {

  private apiUrl = '/api/bookings';

  constructor(private http: HttpClient) {}

  /**
   * Create a new booking with geospatial location.
   * 
   * Request body includes:
   * - carId, startTime, endTime
   * - pickupLatitude, pickupLongitude, pickupAddress
   * - insuranceType, prepaidRefuel
   */
  createBooking(request: any): Observable<any> {
    return this.http.post<any>(this.apiUrl, request);
  }

  /**
   * Fetch booking by ID (for confirmation view).
   */
  getBooking(id: number): Observable<any> {
    return this.http.get<any>(`${this.apiUrl}/${id}`);
  }

  /**
   * Get user's bookings.
   */
  getMyBookings(): Observable<any[]> {
    return this.http.get<any[]>(`${this.apiUrl}/my-bookings`);
  }
}
```

### 1.5 GeocodingService (New)

**File**: `rentoza-frontend/src/app/shared/services/geocoding.service.ts`

```typescript
import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';

/**
 * Geospatial service for address search and reverse geocoding.
 * Uses Nominatim (OpenStreetMap) as free alternative to Google Geocoding.
 */
@Injectable({
  providedIn: 'root'
})
export class GeocodingService {

  private nominatimUrl = 'https://nominatim.openstreetmap.org';

  constructor(private http: HttpClient) {}

  /**
   * Search for addresses matching a query.
   * 
   * @param query Search string (e.g., "Terazije 26, Beograd")
   * @param options Additional search options (viewbox, bounded, etc.)
   */
  searchAddress(query: string, options?: any): Observable<any[]> {
    const params: any = {
      q: query,
      format: 'json',
      'accept-language': 'sr'
    };

    // Add optional parameters
    if (options?.viewbox) params.viewbox = options.viewbox;
    if (options?.bounded) params.bounded = options.bounded;
    if (options?.countrycodes) params.countrycodes = options.countrycodes;

    return this.http.get<any[]>(
      `${this.nominatimUrl}/search`,
      { params }
    );
  }

  /**
   * Reverse geocode coordinates to get address.
   * 
   * @param lat Latitude
   * @param lng Longitude
   */
  reverseGeocode(lat: number, lng: number): Observable<any> {
    const params = {
      lat: lat.toString(),
      lon: lng.toString(),
      format: 'json',
      'accept-language': 'sr'
    };

    return this.http.get<any>(
      `${this.nominatimUrl}/reverse`,
      { params }
    );
  }
}
```

### 1.6 DeliveryService (New)

**File**: `rentoza-frontend/src/app/shared/services/delivery.service.ts`

```typescript
import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';

@Injectable({
  providedIn: 'root'
})
export class DeliveryService {

  private apiUrl = '/api/delivery';

  constructor(private http: HttpClient) {}

  /**
   * Estimate delivery fee and distance for a custom pickup location.
   * 
   * @param carId Car ID (for home location)
   * @param pickupLat Requested pickup latitude
   * @param pickupLng Requested pickup longitude
   */
  estimateFee(carId: number, pickupLat: number, pickupLng: number): Observable<any> {
    const params = {
      carId: carId.toString(),
      pickupLat: pickupLat.toString(),
      pickupLng: pickupLng.toString()
    };

    return this.http.get<any>(`${this.apiUrl}/estimate`, { params });
  }
}
```

---

## Part 2: Check-in Flow Integration

### 2.1 Architecture Overview

```
CheckInGuestComponent
├── ID Verification Section
│   ├── ID Front Photo Upload
│   ├── ID Back Photo Upload
│   ├── Selfie Photo Upload
│   └── Verification Status Display
├── Vehicle Condition Section
│   ├── Pre-existing Damage Marking
│   └── Hotspot Annotation
└── Handshake Section
    ├── Geofence Validation
    └── Trip Start Confirmation
```

### 2.2 CheckInGuestComponent (TypeScript)

**File**: `rentoza-frontend/src/app/features/bookings/check-in/guest-check-in.component.ts`

```typescript
import { Component, OnInit, Input } from '@angular/core';
import { FormBuilder, FormGroup, Validators } from '@angular/forms';
import { CheckInService } from '../../../shared/services/check-in.service';
import { NotificationService } from '../../../shared/services/notification.service';
import { LocationService } from '../../../shared/services/location.service';

/**
 * Guest check-in component with ID verification and condition acknowledgment.
 * 
 * Workflow:
 * 1. Upload ID photos (front, back, selfie)
 * 2. Wait for ID verification (liveness + match)
 * 3. Acknowledge vehicle condition
 * 4. Confirm handshake with GPS validation
 */
@Component({
  selector: 'app-guest-check-in',
  templateUrl: './guest-check-in.component.html',
  styleUrls: ['./guest-check-in.component.scss']
})
export class CheckInGuestComponent implements OnInit {

  @Input() bookingId!: number;

  // ========== ID VERIFICATION ==========
  idVerificationForm!: FormGroup;
  
  // Photo state
  idFrontFile: File | null = null;
  idBackFile: File | null = null;
  selfieFile: File | null = null;
  
  // Photo previews (data URLs)
  idFrontPreview: string | null = null;
  idBackPreview: string | null = null;
  selfiePreview: string | null = null;
  
  // Verification status
  verificationStatus: 'pending' | 'submitting' | 'verified' | 'failed' = 'pending';
  verificationError: string | null = null;
  verificationAttempts = 0;
  maxVerificationAttempts = 3;

  // ========== VEHICLE CONDITION ==========
  conditionForm!: FormGroup;
  conditionAcknowledged = false;
  vehiclePhotos: any[] = []; // Host's check-in photos

  // ========== HANDSHAKE ==========
  handshakeForm!: FormGroup;
  guestLocation: { lat: number; lng: number } | null = null;
  guestLocationError: string | null = null;
  geofenceValid = false;
  geofenceDistance = 0;
  geofenceStatus: 'checking' | 'valid' | 'invalid' = 'checking';

  // ========== UI STATE ==========
  completedSections = {
    idVerification: false,
    conditionAcknowledgment: false,
    handshake: false
  };
  
  currentSection: 'id' | 'condition' | 'handshake' = 'id';

  constructor(
    private formBuilder: FormBuilder,
    private checkInService: CheckInService,
    private notificationService: NotificationService,
    private locationService: LocationService
  ) {}

  ngOnInit(): void {
    this.initializeForms();
    this.loadBookingDetails();
    this.loadVehiclePhotos();
  }

  /**
   * Initialize forms for each section.
   */
  private initializeForms(): void {
    // ID Verification Form
    this.idVerificationForm = this.formBuilder.group({
      documentType: ['NATIONAL_ID', Validators.required],
      issueCountry: ['RS', Validators.required],
      idFrontPhoto: [null, Validators.required],
      idBackPhoto: [null, Validators.required],
      selfiePhoto: [null, Validators.required]
    });

    // Condition Form
    this.conditionForm = this.formBuilder.group({
      conditionAccepted: [false, Validators.requiredTrue],
      conditionComment: ['']
    });

    // Handshake Form
    this.handshakeForm = this.formBuilder.group({
      confirmed: [false, Validators.requiredTrue],
      hostVerifiedId: [false]
    });
  }

  /**
   * Load booking and check-in status details.
   */
  private loadBookingDetails(): void {
    this.checkInService.getCheckInStatus(this.bookingId)
      .subscribe(
        (status: any) => {
          console.log('Check-in status:', status);
          // Map status to UI state
        }
      );
  }

  /**
   * Load host's vehicle photos.
   */
  private loadVehiclePhotos(): void {
    this.checkInService.getVehiclePhotos(this.bookingId)
      .subscribe(
        (photos: any[]) => {
          this.vehiclePhotos = photos;
        }
      );
  }

  // ========== ID VERIFICATION SECTION ==========

  /**
   * Handle ID front photo selection.
   */
  onIdFrontSelected(event: Event): void {
    const file = (event.target as HTMLInputElement).files?.[0];
    if (file) {
      this.validateAndPreviewPhoto(file, 'idFront');
    }
  }

  /**
   * Handle ID back photo selection.
   */
  onIdBackSelected(event: Event): void {
    const file = (event.target as HTMLInputElement).files?.[0];
    if (file) {
      this.validateAndPreviewPhoto(file, 'idBack');
    }
  }

  /**
   * Handle selfie photo selection.
   */
  onSelfieSelected(event: Event): void {
    const file = (event.target as HTMLInputElement).files?.[0];
    if (file) {
      this.validateAndPreviewPhoto(file, 'selfie');
    }
  }

  /**
   * Validate photo file and generate preview.
   */
  private validateAndPreviewPhoto(file: File, type: 'idFront' | 'idBack' | 'selfie'): void {
    // Validate file size (< 5MB)
    if (file.size > 5 * 1024 * 1024) {
      this.notificationService.error('Fotografija mora biti manja od 5MB');
      return;
    }

    // Validate file type
    if (!file.type.startsWith('image/')) {
      this.notificationService.error('Molimo učitajte fotografiju (JPEG, PNG)');
      return;
    }

    // Generate preview
    const reader = new FileReader();
    reader.onload = (e: ProgressEvent<FileReader>) => {
      const base64 = e.target?.result as string;

      if (type === 'idFront') {
        this.idFrontFile = file;
        this.idFrontPreview = base64;
        this.idVerificationForm.patchValue({ idFrontPhoto: base64 });
      } else if (type === 'idBack') {
        this.idBackFile = file;
        this.idBackPreview = base64;
        this.idVerificationForm.patchValue({ idBackPhoto: base64 });
      } else if (type === 'selfie') {
        this.selfieFile = file;
        this.selfiePreview = base64;
        this.idVerificationForm.patchValue({ selfiePhoto: base64 });
      }

      this.notificationService.success(`Fotografija ${type} je učitana`);
    };

    reader.onerror = () => {
      this.notificationService.error('Greška pri učitavanju fotografije');
    };

    reader.readAsDataURL(file);
  }

  /**
   * Submit ID verification (mock for now).
   */
  submitIdVerification(): void {
    if (this.idVerificationForm.invalid) {
      this.notificationService.error('Molimo učitajte sve tri fotografije');
      return;
    }

    if (this.verificationAttempts >= this.maxVerificationAttempts) {
      this.notificationService.error('Prekoračili ste maksimalan broj pokušaja verifikacije');
      return;
    }

    this.verificationStatus = 'submitting';

    const request = {
      bookingId: this.bookingId,
      documentType: this.idVerificationForm.get('documentType')?.value,
      issueCountry: this.idVerificationForm.get('issueCountry')?.value,
      idFrontPhoto: this.idVerificationForm.get('idFrontPhoto')?.value,
      idBackPhoto: this.idVerificationForm.get('idBackPhoto')?.value,
      selfiePhoto: this.idVerificationForm.get('selfiePhoto')?.value
    };

    this.checkInService.submitIdVerification(request)
      .subscribe(
        (result: any) => {
          this.verificationAttempts++;
          
          if (result.status === 'VERIFIED') {
            this.verificationStatus = 'verified';
            this.completedSections.idVerification = true;
            this.notificationService.success('Vaša identifikacija je potvrđena!');
            
            // Move to next section
            this.moveToSection('condition');
          } else {
            this.verificationStatus = 'failed';
            this.verificationError = result.failureReason || 'Verifikacija nije uspela. Molimo pokušajte ponovo.';
            this.notificationService.error(this.verificationError);
          }
        },
        (error) => {
          this.verificationStatus = 'failed';
          this.verificationError = 'Greška pri slanju verifikacije. Molimo pokušajte kasnije.';
          this.notificationService.error(this.verificationError);
        }
      );
  }

  /**
   * Clear ID photos and reset verification.
   */
  resetIdVerification(): void {
    this.idFrontFile = null;
    this.idBackFile = null;
    this.selfieFile = null;
    this.idFrontPreview = null;
    this.idBackPreview = null;
    this.selfiePreview = null;
    this.verificationStatus = 'pending';
    this.verificationError = null;
    this.idVerificationForm.reset({
      documentType: 'NATIONAL_ID',
      issueCountry: 'RS'
    });
  }

  // ========== VEHICLE CONDITION SECTION ==========

  /**
   * Acknowledge vehicle condition (after ID verification).
   */
  acknowledgeCondition(): void {
    if (this.conditionForm.invalid) {
      this.notificationService.error('Molimo potvrditee da ste pregledali vozilo');
      return;
    }

    const request = {
      bookingId: this.bookingId,
      conditionAccepted: true,
      conditionComment: this.conditionForm.get('conditionComment')?.value,
      hotspots: [] // Marked damage areas (if any)
    };

    this.checkInService.acknowledgeCondition(request)
      .subscribe(
        (result: any) => {
          this.conditionAcknowledged = true;
          this.completedSections.conditionAcknowledgment = true;
          this.notificationService.success('Stanje vozila je potvrđeno!');
          
          // Move to handshake
          this.moveToSection('handshake');
        },
        (error) => {
          this.notificationService.error('Greška pri potvrđivanju stanja');
        }
      );
  }

  // ========== HANDSHAKE SECTION ==========

  /**
   * Get guest's current GPS location (for geofence validation).
   */
  getGuestLocation(): void {
    this.geofenceStatus = 'checking';
    
    this.locationService.getCurrentLocation()
      .subscribe(
        (coords: GeolocationCoordinates) => {
          this.guestLocation = {
            lat: coords.latitude,
            lng: coords.longitude
          };
          
          // Validate geofence
          this.validateGeofence();
        },
        (error) => {
          this.guestLocationError = 'Nije moguće pristupiti lokaciji. Molimo omogućite pristup lokaciji.';
          this.notificationService.error(this.guestLocationError);
          this.geofenceStatus = 'invalid';
        }
      );
  }

  /**
   * Validate if guest is within geofence of car (100m).
   */
  private validateGeofence(): void {
    if (!this.guestLocation) return;

    this.checkInService.validateGeofence(
      this.bookingId,
      this.guestLocation.lat,
      this.guestLocation.lng
    )
      .subscribe(
        (result: any) => {
          this.geofenceDistance = result.distanceMeters;
          
          if (result.valid) {
            this.geofenceValid = true;
            this.geofenceStatus = 'valid';
            this.notificationService.success('Nalazite se u dozvoljenim granicama');
          } else {
            this.geofenceValid = false;
            this.geofenceStatus = 'invalid';
            this.notificationService.warning(
              `Preudaleko od vozila (${result.distanceMeters}m, dozvoljeno: ${result.thresholdMeters}m)`
            );
          }
        },
        (error) => {
          this.geofenceStatus = 'invalid';
          this.notificationService.error('Greška pri validaciji lokacije');
        }
      );
  }

  /**
   * Confirm handshake and start trip.
   */
  confirmHandshake(): void {
    if (!this.geofenceValid && !this.handshakeForm.get('confirmed')?.value) {
      this.notificationService.error('Molimo približite se vozilu ili potvrditee potvrdu');
      return;
    }

    const request = {
      bookingId: this.bookingId,
      confirmed: true,
      latitude: this.guestLocation?.lat,
      longitude: this.guestLocation?.lng
    };

    this.checkInService.confirmHandshake(request)
      .subscribe(
        (result: any) => {
          this.completedSections.handshake = true;
          this.notificationService.success('Putovanje je započelo! Srećan put!');
          
          // Navigate to trip view
          setTimeout(() => {
            window.location.href = `/bookings/${this.bookingId}/trip`;
          }, 2000);
        },
        (error) => {
          this.notificationService.error('Greška pri potvrđivanju preuzimanja vozila');
        }
      );
  }

  /**
   * Move to different section.
   */
  moveToSection(section: 'id' | 'condition' | 'handshake'): void {
    this.currentSection = section;
  }
}
```

### 2.3 CheckInGuestComponent (Template)

**File**: `rentoza-frontend/src/app/features/bookings/check-in/guest-check-in.component.html`

```html
<div class="guest-check-in-container">
  
  <!-- ========== PROGRESS INDICATOR ========== -->
  <div class="progress-indicator">
    <div [class.completed]="completedSections.idVerification" class="step">
      <span class="step-number">1</span>
      <span class="step-label">Identifikacija</span>
    </div>
    <div class="step-connector" [class.completed]="completedSections.idVerification"></div>
    
    <div [class.completed]="completedSections.conditionAcknowledgment" class="step">
      <span class="step-number">2</span>
      <span class="step-label">Stanje vozila</span>
    </div>
    <div class="step-connector" [class.completed]="completedSections.conditionAcknowledgment"></div>
    
    <div [class.completed]="completedSections.handshake" class="step">
      <span class="step-number">3</span>
      <span class="step-label">Preuzimanje</span>
    </div>
  </div>

  <!-- ========== ID VERIFICATION SECTION ========== -->
  <div *ngIf="currentSection === 'id' || !completedSections.idVerification" class="section id-verification">
    <h2>Verifikacija identifikacije</h2>
    <p class="section-description">
      Molimo učitajte fotografije vašeg dokumenta za identifikaciju i selfie za liveness proveru.
    </p>

    <form [formGroup]="idVerificationForm">
      
      <!-- Document Type Selection -->
      <div class="form-group">
        <label for="documentType">Tip dokumenta</label>
        <select id="documentType" formControlName="documentType" class="form-control">
          <option value="NATIONAL_ID">Ličnu karticu</option>
          <option value="PASSPORT">Pasos</option>
          <option value="DRIVER_LICENSE">Vozačku dozvolu</option>
        </select>
      </div>

      <!-- Issue Country -->
      <div class="form-group">
        <label for="issueCountry">Zemlja izdavanja</label>
        <input type="text" id="issueCountry" formControlName="issueCountry" 
               class="form-control" placeholder="RS" maxlength="2">
      </div>

      <!-- ID Front Photo -->
      <div class="photo-upload-group">
        <label>Prednja strana dokumenta</label>
        <div class="photo-upload-box">
          <input type="file" 
                 accept="image/*"
                 (change)="onIdFrontSelected($event)"
                 class="photo-input"
                 #idFrontInput>
          
          <div *ngIf="idFrontPreview" class="photo-preview">
            <img [src]="idFrontPreview" alt="ID Front Preview">
          </div>
          <div *ngIf="!idFrontPreview" class="upload-placeholder">
            <mat-icon>image</mat-icon>
            <p>Kliknite za učitavanje fotografije</p>
            <button type="button" (click)="idFrontInput.click()" class="btn btn-secondary">
              Odaberite fotografiju
            </button>
          </div>
        </div>
      </div>

      <!-- ID Back Photo -->
      <div class="photo-upload-group">
        <label>Stražnja strana dokumenta</label>
        <div class="photo-upload-box">
          <input type="file" 
                 accept="image/*"
                 (change)="onIdBackSelected($event)"
                 class="photo-input"
                 #idBackInput>
          
          <div *ngIf="idBackPreview" class="photo-preview">
            <img [src]="idBackPreview" alt="ID Back Preview">
          </div>
          <div *ngIf="!idBackPreview" class="upload-placeholder">
            <mat-icon>image</mat-icon>
            <p>Kliknite za učitavanje fotografije</p>
            <button type="button" (click)="idBackInput.click()" class="btn btn-secondary">
              Odaberite fotografiju
            </button>
          </div>
        </div>
      </div>

      <!-- Selfie Photo -->
      <div class="photo-upload-group">
        <label>Selfie (za proveru živnosti)</label>
        <p class="help-text">Fotografija lica sa jasnom vidljiveness (prirodna osvetljenost, bez kašnjenja)</p>
        <div class="photo-upload-box">
          <input type="file" 
                 accept="image/*"
                 (change)="onSelfieSelected($event)"
                 class="photo-input"
                 #selfieInput>
          
          <div *ngIf="selfiePreview" class="photo-preview">
            <img [src]="selfiePreview" alt="Selfie Preview">
          </div>
          <div *ngIf="!selfiePreview" class="upload-placeholder">
            <mat-icon>camera_alt</mat-icon>
            <p>Kliknite za učitavanje selfija</p>
            <button type="button" (click)="selfieInput.click()" class="btn btn-secondary">
              Fotografiši se
            </button>
          </div>
        </div>
      </div>

      <!-- Verification Status -->
      <div *ngIf="verificationStatus === 'submitting'" class="status-message submitting">
        <mat-spinner diameter="30"></mat-spinner>
        <p>Proveravamo vašu identifikaciju...</p>
      </div>

      <div *ngIf="verificationStatus === 'verified'" class="status-message success">
        <mat-icon>check_circle</mat-icon>
        <p>Vaša identifikacija je potvrđena!</p>
      </div>

      <div *ngIf="verificationStatus === 'failed'" class="status-message error">
        <mat-icon>error</mat-icon>
        <p>{{ verificationError }}</p>
        <p *ngIf="verificationAttempts < maxVerificationAttempts" class="remaining-attempts">
          Preostalo pokušaja: {{ maxVerificationAttempts - verificationAttempts }}
        </p>
      </div>

      <!-- Action Buttons -->
      <div class="form-actions">
        <button type="button" 
                (click)="resetIdVerification()"
                class="btn btn-secondary">
          Očisti
        </button>
        <button type="button" 
                (click)="submitIdVerification()"
                [disabled]="idVerificationForm.invalid || verificationStatus === 'submitting' || verificationStatus === 'verified'"
                class="btn btn-primary">
          <mat-spinner *ngIf="verificationStatus === 'submitting'" diameter="20"></mat-spinner>
          {{ verificationStatus === 'verified' ? 'Potvrđeno' : 'Pošalji za verifikaciju' }}
        </button>
      </div>
    </form>
  </div>

  <!-- ========== VEHICLE CONDITION SECTION ========== -->
  <div *ngIf="completedSections.idVerification && (currentSection === 'condition' || !completedSections.conditionAcknowledgment)" 
       class="section vehicle-condition">
    <h2>Pregled stanja vozila</h2>
    <p class="section-description">
      Pregledajte fotografije vozila koje je domaćin snimio. Obeležite postojeće štete.
    </p>

    <!-- Vehicle Photos Carousel -->
    <div class="vehicle-photos-carousel">
      <div *ngFor="let photo of vehiclePhotos" class="photo-item">
        <img [src]="photo.url" [alt]="photo.type">
        <p class="photo-type">{{ photo.type }}</p>
      </div>
    </div>

    <!-- Condition Acknowledgment Form -->
    <form [formGroup]="conditionForm">
      <div class="form-group checkbox">
        <label>
          <input type="checkbox" formControlName="conditionAccepted">
          Potvrđujem da sam pregledao/pregledala vozilo i da se slaže sa prikazanim fotografijama
        </label>
      </div>

      <div class="form-group">
        <label for="conditionComment">Napomene (opciono)</label>
        <textarea id="conditionComment" 
                  formControlName="conditionComment"
                  class="form-control"
                  placeholder="Opišite bilo koju primenu ili posebnu napomenu..."
                  rows="3"></textarea>
      </div>

      <div class="form-actions">
        <button type="button" 
                (click)="acknowledgeCondition()"
                [disabled]="conditionForm.invalid"
                class="btn btn-primary">
          Potvrdi stanje vozila
        </button>
      </div>
    </form>
  </div>

  <!-- ========== HANDSHAKE SECTION ========== -->
  <div *ngIf="completedSections.conditionAcknowledgment && (currentSection === 'handshake' || !completedSections.handshake)"
       class="section handshake">
    <h2>Preuzimanje vozila</h2>
    <p class="section-description">
      Posljednji korak - potvrdite preuzimanje vozila. Vaša lokacija mora biti u dozvoljenim granicama.
    </p>

    <!-- Geofence Status -->
    <div class="geofence-section">
      <h3>Lokacija</h3>
      
      <div *ngIf="geofenceStatus === 'checking'" class="geofence-status checking">
        <mat-spinner diameter="40"></mat-spinner>
        <p>Proveravamo vašu lokaciju...</p>
      </div>

      <div *ngIf="geofenceStatus === 'valid'" class="geofence-status valid">
        <mat-icon>location_on</mat-icon>
        <p>Nalazite se na dogovorenom mestu!</p>
        <p class="distance-info">Rastojanje od vozila: {{ geofenceDistance }}m</p>
      </div>

      <div *ngIf="geofenceStatus === 'invalid'" class="geofence-status invalid">
        <mat-icon>error_outline</mat-icon>
        <p *ngIf="guestLocationError">{{ guestLocationError }}</p>
        <p *ngIf="!guestLocationError && geofenceDistance > 0">
          Preudaleko od vozila ({{ geofenceDistance }}m, dozvoljeno: 100m)
        </p>
      </div>

      <button type="button" 
              (click)="getGuestLocation()"
              class="btn btn-secondary">
        Osveži lokaciju
      </button>
    </div>

    <!-- Handshake Confirmation -->
    <form [formGroup]="handshakeForm">
      <div class="form-group checkbox">
        <label>
          <input type="checkbox" formControlName="confirmed">
          Potvrđujem da se nalazim kod vozila i spremam ga za putovanje
        </label>
      </div>

      <div class="form-actions">
        <button type="button" 
                (click)="confirmHandshake()"
                [disabled]="!handshakeForm.valid || (!geofenceValid && !handshakeForm.get('confirmed')?.value)"
                class="btn btn-primary btn-large">
          Počni putovanje
        </button>
      </div>
    </form>
  </div>
</div>
```

### 2.4 CheckInService Updates

**File**: `rentoza-frontend/src/app/shared/services/check-in.service.ts`

```typescript
import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';

@Injectable({
  providedIn: 'root'
})
export class CheckInService {

  private apiUrl = '/api/check-in';

  constructor(private http: HttpClient) {}

  /**
   * Get check-in status for a booking.
   */
  getCheckInStatus(bookingId: number): Observable<any> {
    return this.http.get<any>(`${this.apiUrl}/${bookingId}/status`);
  }

  /**
   * Get vehicle photos uploaded by host.
   */
  getVehiclePhotos(bookingId: number): Observable<any[]> {
    return this.http.get<any[]>(`${this.apiUrl}/${bookingId}/photos`);
  }

  /**
   * Submit ID verification with photos.
   * 
   * @param request Contains base64-encoded photos and document info
   */
  submitIdVerification(request: any): Observable<any> {
    return this.http.post<any>(`${this.apiUrl}/id-verification`, request);
  }

  /**
   * Acknowledge vehicle condition.
   */
  acknowledgeCondition(request: any): Observable<any> {
    return this.http.post<any>(`${this.apiUrl}/acknowledge-condition`, request);
  }

  /**
   * Validate geofence (guest proximity to car).
   */
  validateGeofence(bookingId: number, lat: number, lng: number): Observable<any> {
    const params = {
      latitude: lat.toString(),
      longitude: lng.toString()
    };
    return this.http.post<any>(
      `${this.apiUrl}/${bookingId}/validate-geofence`,
      {},
      { params }
    );
  }

  /**
   * Confirm handshake and start trip.
   */
  confirmHandshake(request: any): Observable<any> {
    return this.http.post<any>(`${this.apiUrl}/confirm-handshake`, request);
  }
}
```

---

## Part 3: Geospatial Search Integration

### 3.1 Architecture Overview

```
HomeComponent (Search)
├── Location Input
│   ├── Current Location Button
│   └── Address Autocomplete
├── Map Display
│   ├── Search Center Marker
│   ├── Car Markers (fuzzy or exact)
│   └── Distance Radius Circle
└── Results Display
    ├── Fuzzy Location Indicators
    ├── Price & Ratings
    └── Availability Dates
```

### 3.2 HomeComponent (TypeScript)

**File**: `rentoza-frontend/src/app/features/home/pages/home/home.component.ts`

```typescript
import { Component, OnInit, ViewChild, NgZone } from '@angular/core';
import { FormBuilder, FormGroup, Validators } from '@angular/forms';
import { GoogleMap } from '@angular/google-maps';
import { CarService } from '../../../../shared/services/car.service';
import { GeocodingService } from '../../../../shared/services/geocoding.service';
import { LocationService } from '../../../../shared/services/location.service';
import { NotificationService } from '../../../../shared/services/notification.service';
import { Router } from '@angular/router';
import { debounceTime, distinctUntilChanged } from 'rxjs/operators';

/**
 * Home page with geospatial car search.
 * 
 * Features:
 * - Map-based car discovery
 * - Location obfuscation for privacy
 * - Real-time availability checking
 * - Fuzzy coordinates for non-booked cars
 */
@Component({
  selector: 'app-home',
  templateUrl: './home.component.html',
  styleUrls: ['./home.component.scss']
})
export class HomeComponent implements OnInit {

  @ViewChild('mapElement') mapElement!: GoogleMap;

  // ========== FORM STATE ==========
  searchForm!: FormGroup;
  
  // ========== LOCATION STATE ==========
  searchCenter: { lat: number; lng: number } = { lat: 44.8176, lng: 20.4633 }; // Belgrade
  searchRadius = 10; // km
  
  // ========== MAP STATE ==========
  mapZoom = 12;
  mapOptions: google.maps.MapOptions = {
    zoom: 12,
    center: this.searchCenter,
    mapTypeControl: true,
    fullscreenControl: true
  };
  
  // Circle showing search radius
  circleOptions: google.maps.CircleOptions = {
    fillColor: '#3f51b5',
    fillOpacity: 0.1,
    strokeColor: '#3f51b5',
    strokeOpacity: 0.5,
    strokeWeight: 2,
    editable: false
  };

  // ========== SEARCH RESULTS ==========
  searchResults: any[] = [];
  searching = false;
  noResults = false;
  
  // Map markers for cars
  carMarkers: Array<{ car: any; position: google.maps.LatLngLiteral }> = [];

  // ========== UI STATE ==========
  viewMode: 'list' | 'map' = 'map';

  constructor(
    private formBuilder: FormBuilder,
    private carService: CarService,
    private geocodingService: GeocodingService,
    private locationService: LocationService,
    private notificationService: NotificationService,
    private router: Router,
    private ngZone: NgZone
  ) {}

  ngOnInit(): void {
    this.initializeForm();
    this.loadUserLocation();
  }

  /**
   * Initialize search form.
   */
  private initializeForm(): void {
    this.searchForm = this.formBuilder.group({
      location: [''],
      startDate: [null, Validators.required],
      endDate: [null, Validators.required],
      radiusKm: [10, [Validators.required, Validators.min(1), Validators.max(100)]]
    });

    // Debounce location input for address suggestions
    this.searchForm.get('location')?.valueChanges
      .pipe(
        debounceTime(300),
        distinctUntilChanged()
      )
      .subscribe((query: string) => {
        if (query && query.length > 2) {
          this.searchAddresses(query);
        }
      });
  }

  /**
   * Load user's current location.
   */
  private loadUserLocation(): void {
    this.locationService.getCurrentLocation()
      .subscribe(
        (coords: GeolocationCoordinates) => {
          this.searchCenter = {
            lat: coords.latitude,
            lng: coords.longitude
          };
          
          this.searchForm.patchValue({
            radiusKm: this.searchRadius
          });
          
          // Auto-perform initial search
          this.searchNearby();
        },
        (error) => {
          console.warn('Could not get user location:', error);
          // Use default Belgrade location
          this.notificationService.info('Koristiš podrazumevanu lokaciju (Beograd). Možeš je promeniti na mapi.');
        }
      );
  }

  /**
   * Search for addresses matching input.
   */
  private searchAddresses(query: string): void {
    this.geocodingService.searchAddress(query, {
      viewbox: '18.8,42.2,23.0,46.2',
      bounded: true,
      countrycodes: 'rs'
    })
      .subscribe(
        (suggestions: any[]) => {
          // Display autocomplete suggestions (implementation depends on UI library)
          console.log('Address suggestions:', suggestions);
        }
      );
  }

  /**
   * Handle address selection from autocomplete.
   */
  onAddressSelected(suggestion: any): void {
    const lat = parseFloat(suggestion.lat);
    const lng = parseFloat(suggestion.lon);
    
    this.searchCenter = { lat, lng };
    this.searchForm.patchValue({
      location: suggestion.display_name
    });
    
    this.searchNearby();
  }

  /**
   * Use current location for search.
   */
  useCurrentLocation(): void {
    this.loadUserLocation();
  }

  /**
   * Handle map click for location selection.
   */
  onMapClick(event: google.maps.MapMouseEvent): void {
    if (!event.latLng) return;

    this.searchCenter = {
      lat: event.latLng.lat(),
      lng: event.latLng.lng()
    };

    this.searchNearby();
  }

  /**
   * Search for cars near the selected location.
   */
  searchNearby(): void {
    if (this.searchForm.invalid) {
      this.notificationService.error('Molimo popunite sve obavezne poljeKolone');
      return;
    }

    this.searching = true;
    this.noResults = false;
    this.searchResults = [];

    const startDate = this.searchForm.get('startDate')?.value;
    const endDate = this.searchForm.get('endDate')?.value;
    const radius = this.searchForm.get('radiusKm')?.value;

    this.carService.searchNearby(
      this.searchCenter.lat,
      this.searchCenter.lng,
      radius,
      startDate,
      endDate
    )
      .subscribe(
        (results: any[]) => {
          this.searchResults = results;
          this.createMapMarkers(results);
          this.searching = false;
          
          if (results.length === 0) {
            this.noResults = true;
            this.notificationService.info('Nije pronađen nijedan automobil na ovoj lokaciji');
          }
        },
        (error) => {
          console.error('Search failed:', error);
          this.notificationService.error('Greška pri pretrazi automobila');
          this.searching = false;
        }
      );
  }

  /**
   * Create map markers for search results.
   */
  private createMapMarkers(cars: any[]): void {
    this.carMarkers = cars.map(car => ({
      car,
      position: {
        lat: car.latitude,
        lng: car.longitude
      }
    }));
  }

  /**
   * Handle car selection from list or map.
   */
  selectCar(car: any): void {
    this.router.navigate(['/cars', car.id], {
      queryParams: {
        startDate: this.searchForm.get('startDate')?.value,
        endDate: this.searchForm.get('endDate')?.value
      }
    });
  }

  /**
   * Toggle between map and list view.
   */
  toggleViewMode(): void {
    this.viewMode = this.viewMode === 'map' ? 'list' : 'map';
  }

  /**
   * Update search radius from slider.
   */
  onRadiusChange(radius: number): void {
    this.searchRadius = radius;
    this.searchForm.patchValue({ radiusKm: radius });
    this.searchNearby();
  }
}
```

### 3.3 HomeComponent (Template)

**File**: `rentoza-frontend/src/app/features/home/pages/home/home.component.html`

```html
<div class="home-container">
  
  <!-- ========== SEARCH HEADER ========== -->
  <div class="search-header">
    <h1>Pronađi vozilo za putovanje</h1>
    
    <form [formGroup]="searchForm" class="search-form">
      
      <!-- Location Input -->
      <div class="form-group location-input">
        <label for="location">
          <mat-icon>location_on</mat-icon>
          Lokacija preuzimanja
        </label>
        <input type="text" 
               id="location"
               formControlName="location"
               placeholder="Unesite adresu ili kliknite na mapu..."
               class="form-control">
        
        <button type="button" 
                (click)="useCurrentLocation()"
                class="btn-icon"
                title="Koristi trenutnu lokaciju">
          <mat-icon>my_location</mat-icon>
        </button>
      </div>

      <!-- Date Range -->
      <div class="form-row">
        <div class="form-group">
          <label for="startDate">Od</label>
          <input type="date" 
                 id="startDate"
                 formControlName="startDate"
                 class="form-control"
                 required>
        </div>
        <div class="form-group">
          <label for="endDate">Do</label>
          <input type="date" 
                 id="endDate"
                 formControlName="endDate"
                 class="form-control"
                 required>
        </div>
      </div>

      <!-- Search Radius -->
      <div class="form-group radius-slider">
        <label for="radiusKm">Radius: {{ searchRadius }} km</label>
        <input type="range" 
               id="radiusKm"
               formControlName="radiusKm"
               min="1" max="100" step="1"
               (change)="onRadiusChange($event.target.value)"
               class="form-control">
      </div>

      <!-- View Toggle -->
      <div class="view-toggle">
        <button [class.active]="viewMode === 'map'" 
                (click)="viewMode = 'map'"
                type="button"
                class="btn-icon">
          <mat-icon>map</mat-icon>
          Mapa
        </button>
        <button [class.active]="viewMode === 'list'" 
                (click)="viewMode = 'list'"
                type="button"
                class="btn-icon">
          <mat-icon>list</mat-icon>
          Lista
        </button>
      </div>
    </form>
  </div>

  <!-- ========== MAP VIEW ========== -->
  <div *ngIf="viewMode === 'map'" class="map-view">
    <google-map #mapElement
                [center]="searchCenter"
                [zoom]="mapZoom"
                [options]="mapOptions"
                (mapClick)="onMapClick($event)">
      
      <!-- Search Center Marker -->
      <map-marker [position]="searchCenter"
                  [title]="'Vašablokirana lokacija'"
                  [icon]="{ url: 'assets/icons/location-marker.png', scaledSize: { width: 32, height: 32 } }">
      </map-marker>

      <!-- Search Radius Circle -->
      <map-circle [center]="searchCenter"
                  [radius]="searchRadius * 1000"
                  [options]="circleOptions">
      </map-circle>

      <!-- Car Markers -->
      <map-marker *ngFor="let marker of carMarkers"
                  [position]="marker.position"
                  [title]="marker.car.brand + ' ' + marker.car.model"
                  (markerClick)="selectCar(marker.car)">
        
        <!-- Info Window -->
        <map-info-window>
          <div class="car-info-window">
            <h4>{{ marker.car.brand }} {{ marker.car.model }}</h4>
            <p>{{ marker.car.pricePerDay | currency:'RSD':'symbol':'1.0-0' }}/dan</p>
            <p *ngIf="marker.car.averageRating">
              ⭐ {{ marker.car.averageRating | number:'1.1-1' }}
            </p>
            <button (click)="selectCar(marker.car)" class="btn btn-small">
              Detaljnije
            </button>
          </div>
        </map-info-window>
      </map-marker>
    </google-map>
  </div>

  <!-- ========== LIST VIEW ========== -->
  <div *ngIf="viewMode === 'list'" class="list-view">
    
    <!-- Loading State -->
    <div *ngIf="searching" class="loading-state">
      <mat-spinner diameter="50"></mat-spinner>
      <p>Pretraživam automobile...</p>
    </div>

    <!-- No Results State -->
    <div *ngIf="noResults && !searching" class="no-results">
      <mat-icon>directions_car</mat-icon>
      <h3>Nema dostupnih automobila</h3>
      <p>Pokušaj sa drugom lokacijom ili datumom</p>
    </div>

    <!-- Car Results -->
    <div *ngIf="searchResults.length > 0" class="cars-grid">
      <div *ngFor="let car of searchResults" class="car-card">
        
        <!-- Car Image -->
        <div class="car-image">
          <img [src]="car.primaryImageUrl" [alt]="car.brand + ' ' + car.model">
        </div>

        <!-- Car Info -->
        <div class="car-info">
          <h3>{{ car.brand }} {{ car.model }}</h3>
          <p class="year">{{ car.year }}</p>

          <!-- Location with Obfuscation Indicator -->
          <div class="location-display">
            <mat-icon>location_on</mat-icon>
            <span class="location-text"
                  [title]="car.isBooked ? 'Tačna lokacija' : 'Približna lokacija (priv .... razloga)'">
              {{ car.location }}
              <span *ngIf="!car.isBooked" class="obfuscation-indicator" title="Približna lokacija">~</span>
            </span>
          </div>

          <!-- Rating -->
          <div class="rating" *ngIf="car.averageRating">
            <span class="stars">⭐ {{ car.averageRating | number:'1.1-1' }}</span>
          </div>

          <!-- Price -->
          <div class="price">
            {{ car.pricePerDay | currency:'RSD':'symbol':'1.0-0' }}<span>/dan</span>
          </div>

          <!-- Booking Status -->
          <div *ngIf="car.isBooked" class="booking-badge">
            ✓ Kreirane rezervacije
          </div>
        </div>

        <!-- Action Button -->
        <button (click)="selectCar(car)" class="btn btn-primary btn-block">
          Detaljnije
        </button>
      </div>
    </div>
  </div>
</div>
```

### 3.4 CarService Updates

**File**: `rentoza-frontend/src/app/shared/services/car.service.ts`

```typescript
import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';

@Injectable({
  providedIn: 'root'
})
export class CarService {

  private apiUrl = '/api/cars';

  constructor(private http: HttpClient) {}

  /**
   * Search for cars near a location (geospatial).
   * 
   * @param latitude Search center latitude
   * @param longitude Search center longitude
   * @param radiusKm Search radius in kilometers
   * @param startDate Booking start date
   * @param endDate Booking end date
   */
  searchNearby(
    latitude: number,
    longitude: number,
    radiusKm: number,
    startDate: string,
    endDate: string
  ): Observable<any[]> {
    const params = {
      latitude: latitude.toString(),
      longitude: longitude.toString(),
      radiusKm: radiusKm.toString(),
      startDate,
      endDate
    };

    return this.http.get<any[]>(`${this.apiUrl}/search-nearby`, { params });
  }

  /**
   * Get detailed car information.
   */
  getCar(id: number): Observable<any> {
    return this.http.get<any>(`${this.apiUrl}/${id}`);
  }

  /**
   * Get available dates for a car.
   */
  getAvailableDates(id: number): Observable<any> {
    return this.http.get<any>(`${this.apiUrl}/${id}/available-dates`);
  }
}
```

### 3.5 LocationService (New)

**File**: `rentoza-frontend/src/app/shared/services/location.service.ts`

```typescript
import { Injectable } from '@angular/core';
import { Observable, from } from 'rxjs';

/**
 * Service for accessing browser geolocation API.
 */
@Injectable({
  providedIn: 'root'
})
export class LocationService {

  /**
   * Get user's current GPS location.
   */
  getCurrentLocation(): Observable<GeolocationCoordinates> {
    return from(
      new Promise<GeolocationCoordinates>((resolve, reject) => {
        if (!navigator.geolocation) {
          reject(new Error('Geolocation nije dostupna u vašem pretapraču'));
          return;
        }

        navigator.geolocation.getCurrentPosition(
          (position) => {
            resolve(position.coords);
          },
          (error) => {
            reject(error);
          },
          {
            enableHighAccuracy: true,
            timeout: 10000,
            maximumAge: 0
          }
        );
      })
    );
  }

  /**
   * Watch user's location in real-time (for continuous tracking).
   */
  watchLocation(): Observable<GeolocationCoordinates> {
    return new Observable((observer) => {
      if (!navigator.geolocation) {
        observer.error(new Error('Geolocation nije dostupna'));
        return;
      }

      const watchId = navigator.geolocation.watchPosition(
        (position) => {
          observer.next(position.coords);
        },
        (error) => {
          observer.error(error);
        },
        {
          enableHighAccuracy: true,
          timeout: 10000,
          maximumAge: 0
        }
      );

      return () => {
        navigator.geolocation.clearWatch(watchId);
      };
    });
  }
}
```

---

## Module Imports & Configuration

### 3.6 AppModule Configuration

**File**: `rentoza-frontend/src/app/app.module.ts`

```typescript
import { NgModule } from '@angular/core';
import { BrowserModule } from '@angular/platform-browser';
import { HttpClientModule } from '@angular/common/http';
import { GoogleMapsModule } from '@angular/google-maps';
import { ReactiveFormsModule, FormsModule } from '@angular/forms';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatProgressBarModule } from '@angular/material/progress-bar';

import { AppComponent } from './app.component';
import { BookingFormComponent } from './features/booking/booking-form/booking-form.component';
import { CheckInGuestComponent } from './features/bookings/check-in/guest-check-in.component';
import { HomeComponent } from './features/home/pages/home/home.component';

@NgModule({
  declarations: [
    AppComponent,
    BookingFormComponent,
    CheckInGuestComponent,
    HomeComponent
  ],
  imports: [
    BrowserModule,
    HttpClientModule,
    GoogleMapsModule,
    ReactiveFormsModule,
    FormsModule,
    MatIconModule,
    MatProgressSpinnerModule,
    MatProgressBarModule
  ],
  providers: [],
  bootstrap: [AppComponent]
})
export class AppModule { }
```

### 3.7 Environment Configuration

**File**: `rentoza-frontend/src/environments/environment.ts`

```typescript
export const environment = {
  production: false,
  
  // API Configuration
  apiUrl: 'http://localhost:8080/api',
  
  // Google Maps API Key
  googleMapsApiKey: 'YOUR_API_KEY_HERE',
  
  // Geolocation Settings
  geolocation: {
    enableHighAccuracy: true,
    timeout: 10000,
    maximumAge: 0
  },
  
  // Search Settings
  search: {
    defaultRadius: 10, // km
    maxRadius: 100, // km
    debounceTime: 300 // ms
  },
  
  // Serbia Bounds
  serbiaBounds: {
    north: 46.2,
    south: 42.2,
    east: 23.0,
    west: 18.8
  }
};
```

---

## Implementation Checklist

### Booking Flow (Part 1)
- [ ] Create `BookingFormComponent.ts` with location picker
- [ ] Create `BookingFormComponent.html` with map UI
- [ ] Create `GeocodingService` for address search/reverse geocoding
- [ ] Create `DeliveryService` for fee estimation
- [ ] Add Google Maps integration
- [ ] Test location validation (Serbia bounds)
- [ ] Test delivery fee estimation
- [ ] Test booking submission with location data

### Check-in Flow (Part 2)
- [ ] Update `CheckInGuestComponent.ts` with ID verification
- [ ] Update `CheckInGuestComponent.html` with photo upload UI
- [ ] Create photo validation (size, format)
- [ ] Implement base64 encoding for photo submission
- [ ] Add geofence validation UI
- [ ] Test ID photo upload
- [ ] Test geofence distance calculation
- [ ] Test handshake confirmation

### Geospatial Search (Part 3)
- [ ] Update `HomeComponent.ts` with geospatial search
- [ ] Update `HomeComponent.html` with map integration
- [ ] Create `LocationService` for geolocation
- [ ] Update `CarService` with `searchNearby()`
- [ ] Implement location obfuscation indicators (~)
- [ ] Add radius slider UI
- [ ] Test spatial search queries
- [ ] Test map marker clustering (if 100+ results)

### Configuration & Services
- [ ] Update `AppModule` with GoogleMapsModule
- [ ] Configure environment variables
- [ ] Add Material icons/spinners
- [ ] Setup Google Maps API key
- [ ] Configure Nominatim geocoding (free alternative)

---

## Testing Strategy

### Unit Tests
- Location validation (Serbia bounds)
- Delivery fee calculation
- Photo validation (size, format)
- Geofence distance calculation

### Integration Tests
- Booking creation with location data
- ID verification submission
- Geofence check at handshake
- Car search with geospatial filtering

### E2E Tests
- Complete booking flow with custom location
- Complete check-in with ID verification
- Complete search and car selection

---

## Performance Considerations

### Optimization Techniques
1. **Lazy Load Google Maps**: Load API only on pages that need it
2. **Debounce Address Search**: 300ms debounce to reduce API calls
3. **Compress Photos**: Reduce base64 size before transmission
4. **Cache Search Results**: Store results for 5 minutes
5. **Virtual Scrolling**: Use CDK virtual scroll for large result lists (100+ cars)

### Example: Lazy Load Google Maps

```typescript
// In app.module.ts
import { GoogleMapsModule } from '@angular/google-maps';

const googleMapsModuleFactory = () => import('@angular/google-maps').then(m => m.GoogleMapsModule);

@NgModule({
  imports: [
    // ... other imports ...
  ],
  declarations: [...],
  providers: [...]
})
export class AppModule { }
```

---

## Security Considerations

### Frontend Security
1. **HTTPS Only**: All API calls must use HTTPS
2. **CORS Configuration**: Backend must allow frontend origin
3. **Photo Encryption**: Encrypt base64 photos before transmission
4. **XSS Prevention**: Angular sanitizes user input by default
5. **CSRF Protection**: Include CSRF token in POST requests

### Privacy
1. **Location Obfuscation**: Non-booked cars show fuzzy coordinates (±500m)
2. **Photo Deletion**: Delete ID verification photos after verification
3. **No Geolocation Tracking**: Don't store historical locations
4. **User Consent**: Request geolocation permission explicitly

---

## Deployment Checklist

- [ ] Deploy backend (BookingService, CheckInService updates)
- [ ] Generate production API key
- [ ] Configure CORS for production domain
- [ ] Test all geospatial features in staging
- [ ] Canary deploy to 10% of users
- [ ] Monitor error logs and user feedback
- [ ] Gradual rollout to 100%

---

## References & Documentation

- **Angular Google Maps**: https://angular.io/guide/google-maps-intro
- **Nominatim API**: https://nominatim.org/release-docs/latest/api/Overview/
- **Geolocation API**: https://developer.mozilla.org/en-US/docs/Web/API/Geolocation_API
- **WGS84 Coordinates**: https://en.wikipedia.org/wiki/World_Geodetic_System
- **Haversine Formula**: https://en.wikipedia.org/wiki/Haversine_formula

---

## Document History

| Version | Date | Changes |
|---------|------|---------|
| 1.0 | 2025-12-05 | Complete frontend integration guide for Phases 1-3 |

