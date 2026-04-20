# Frontend Geospatial Implementation Roadmap

## Post-Backend Integration: Add Car Wizard, Search, Filters & Map

**Status:** Planning Phase  
**Scope:** Frontend integration for geospatial car discovery and booking workflow  
**Timeline Estimate:** 4-5 weeks  
**Risk Level:** MEDIUM - Requires API contract alignment and map vendor integration

---

## Table of Contents

1. [Executive Summary](#executive-summary)
2. [Architecture Overview](#architecture-overview)
3. [Phase 1: Location Picker Component Enhancement](#phase-1-location-picker-component-enhancement)
4. [Phase 2: Add Car Wizard Refactor](#phase-2-add-car-wizard-refactor)
5. [Phase 3: Car Discovery with Geospatial Search](#phase-3-car-discovery-with-geospatial-search)
6. [Phase 4: Advanced Filters & Map Integration](#phase-4-advanced-filters--map-integration)
7. [Phase 5: Booking Flow Integration](#phase-5-booking-flow-integration)
8. [API Contract Requirements](#api-contract-requirements)
9. [Performance & UX Considerations](#performance--ux-considerations)
10. [Testing Strategy](#testing-strategy)

---

## Executive Summary

### Current Backend State (Phase 2.4 Complete)

✅ **Implemented:**

- `GeoPoint` embeddable value object with Haversine distance and obfuscation
- `Car.locationGeoPoint` geospatial field with SPATIAL INDEX
- `Booking.pickupLocation` immutable snapshot at booking time
- `DeliveryPoi` entity for special fee rules (airports, stations)
- `OsrmRoutingService` with Haversine fallback for distance calculation
- `DeliveryFeeCalculator` for distance-based pricing
- `LocationMigrationService` for Nominatim geocoding (rate-limited)
- Admin endpoints for migration status and POI management
- Database V23/V24 migrations with SPATIAL INDEX

### Frontend Gaps (To Be Implemented)

❌ **Not Yet Started:**

- **Add Car Wizard:** No UI for host to enter car with geospatial location
- **Car Search Page:** No frontend search using `findNearby()` backend query
- **Geospatial Filters:** No distance/delivery availability filters on search results
- **Map Integration:** No Mapbox GL JS integration for car discovery
- **Delivery Fee Display:** No UI showing calculated delivery costs
- **Pickup Location Selector:** No interactive map for guests to confirm pickup point

### Risk Assessment

| Issue                                         | Impact   | Mitigation                                                       |
| --------------------------------------------- | -------- | ---------------------------------------------------------------- |
| Mapbox token exposure in frontend             | High     | Environment-based token rotation, client-side rate limiting      |
| Map rendering performance (100+ markers)      | High     | Marker clustering, viewport-based query limits                   |
| Coordinate order bugs (lat/lon vs lon/lat)    | Critical | Type-safe GeoPoint DTO, unit tests for coordinate transforms     |
| Privacy leakage (exact coords before booking) | High     | Server-side obfuscation in API response, frontend privacy circle |
| Offline location search                       | Medium   | Cache nearby cars, progressive load on reconnect                 |

---

## Architecture Overview

### Frontend Data Flow

```
┌─────────────────────────────────────────────────────────────────────┐
│                        FRONTEND ARCHITECTURE                        │
├─────────────────────────────────────────────────────────────────────┤
│                                                                     │
│  ┌──────────────┐         ┌──────────────┐      ┌──────────────┐  │
│  │   Add Car    │         │   Search     │      │   Booking    │  │
│  │   Wizard     │         │   Page       │      │   Page       │  │
│  └──────┬───────┘         └──────┬───────┘      └──────┬───────┘  │
│         │                        │                      │          │
│         └────────────┬───────────┘──────────────────────┘          │
│                      ▼                                              │
│         ┌──────────────────────────┐                               │
│         │  LocationPickerComponent │                               │
│         │  (Mapbox GL JS)          │                               │
│         └──────────────┬───────────┘                               │
│                        │                                            │
│  ┌─────────────────────┴──────────────────┐                        │
│  ▼                                        ▼                        │
│ ┌──────────────────┐         ┌──────────────────┐                 │
│ │ LocationService  │         │ DeliveryService  │                 │
│ │ - nearbySearch() │         │ - calculateFee() │                 │
│ │ - obfuscateMap() │         │ - checkAvailability()│              │
│ └────────┬─────────┘         └────────┬─────────┘                 │
│          │                            │                            │
│          └────────────┬───────────────┘                            │
│                       ▼                                            │
│          ┌──────────────────────────┐                             │
│          │    Backend REST API      │                             │
│          │  /api/cars/search        │                             │
│          │  /api/delivery/fee       │                             │
│          └──────────────────────────┘                             │
│                                                                    │
└────────────────────────────────────────────────────────────────────┘
```

### Key Components

#### 1. LocationPickerComponent (Existing - Needs Enhancement)

**Current State:** Basic Mapbox display of single location  
**Required Enhancements:**

- Multi-marker support for search results
- Drag-to-reposition functionality
- Privacy circle overlay (±500m for unbooked guests)
- Search-on-map functionality
- Cluster support for dense areas

**Props:**

```typescript
interface LocationPickerInput {
	latitude: number;
	longitude: number;
	editable: boolean;
	showPrivacyCircle: boolean; // NEW
	markers?: GeoLocation[]; // NEW: Array of nearby cars
	onLocationChanged(point: GeoPoint): void;
	onMarkerSelected?(carId: string): void; // NEW
	height?: string;
	clusterMarkers?: boolean; // NEW
	markerRadius?: number; // NEW: 5-50km search radius
}
```

#### 2. LocationService (Existing - Needs Enhancement)

**Current State:** Basic location utility functions  
**Required Enhancements:**

```typescript
export class LocationService {
	// EXISTING
	distanceToUser(): number {}

	// NEW: Search for cars near coordinates
	searchNearby(
		latitude: number,
		longitude: number,
		radiusKm: number = 25,
		filters?: SearchFilters
	): Observable<CarSearchResult[]> {}

	// NEW: Get obfuscated location for privacy
	getObfuscatedLocation(
		carId: string,
		hasBookingHistory: boolean
	): Observable<ObfuscatedGeoPoint> {}

	// NEW: Validate location within Serbia bounds
	validateCoordinates(lat: number, lon: number): boolean {}

	// NEW: Geocode address string to coordinates
	// USES BACKEND PROXY (/api/locations/geocode) which calls Mapbox API
	// Mapbox is preferred over Nominatim for:
	// - Partial query support (type "Teraz" → get "Terazije" suggestions)
	// - Higher rate limits on backend (don't expose Nominatim to frontend)
	// - Better UX for Serbian addresses
	geocodeAddress(address: string): Observable<GeocodeSuggestion[]> {}

	// NEW: Reverse geocode coordinates to address
	// Uses backend /api/locations/reverse-geocode endpoint
	reverseGeocodeCoordinates(
		latitude: number,
		longitude: number
	): Observable<ReverseGeocodeResult> {}
}
```

**Implementation Note on Geocoding Provider:**

- **Frontend → Backend:** All geocoding requests must go through backend REST API
- **Backend → Mapbox:** Backend uses Mapbox Geocoding API (token: `<MAPBOX_PUBLIC_TOKEN>`)
- **Rate Limiting:** Backend caches results and rate-limits to Mapbox limits
- **Nominatim:** Used only server-side for migration (LocationMigrationService), NOT for runtime queries
- **Why:** Prevents frontend IP from hitting Nominatim 1 req/sec limit; Mapbox has better UX

#### 3. DeliveryService (New)

**Important:** DeliveryService calls backend endpoints that handle all OSRM routing & fee calculation server-side.

```typescript
export class DeliveryService {
	// Calculate delivery fee for pickup location
	// BACKEND HANDLES: OSRM distance calculation, POI lookup, fee computation
	// Frontend only displays the result (no OSRM calls from client)
	calculateDeliveryFee(
		carId: string,
		pickupLat: number,
		pickupLon: number
	): Observable<DeliveryFeeResult> {}

	// Check if car offers delivery to location
	checkDeliveryAvailability(
		carId: string,
		pickupLat: number,
		pickupLon: number
	): Observable<DeliveryAvailability> {}

	// Get nearby POIs (airports, stations with special fees)
	// Used to show user "You're near airport → special fee applies"
	getNearbyPois(
		latitude: number,
		longitude: number,
		radiusKm: number = 2
	): Observable<DeliveryPoi[]> {}
}
```

**Why Backend-Side Processing:**

1. **Security:** OSRM API key never exposed to frontend
2. **Cost:** Backend caches routes, prevents duplicate OSRM requests
3. **Consistency:** All clients get same fee calculation logic
4. **Privacy:** Server obfuscates car locations before returning to unbooked guests

---

## Phase 1: Location Picker Component Enhancement

### 1.1 Extend LocationPickerComponent for Multi-Marker Display

**Files to Modify:**

- `shared/components/location-picker/location-picker.component.ts`
- `shared/components/location-picker/location-picker.component.html`
- `shared/components/location-picker/location-picker.component.scss`

**Changes:**

```typescript
// location-picker.component.ts
@Component({
	selector: "app-location-picker",
	templateUrl: "./location-picker.component.html",
	styleUrls: ["./location-picker.component.scss"],
})
export class LocationPickerComponent implements OnInit, OnDestroy {
	@Input() latitude: number;
	@Input() longitude: number;
	@Input() editable: boolean = false;
	@Input() showPrivacyCircle: boolean = false; // NEW
	@Input() markers: GeoLocation[] = []; // NEW
	@Input() markerRadius: number = 25; // NEW: 5-50km
	@Input() clusterMarkers: boolean = false; // NEW
	@Input() height: string = "400px";

	@Output() locationChanged = new EventEmitter<GeoPoint>();
	@Output() markerSelected = new EventEmitter<string>(); // NEW: carId
	@Output() radiusChanged = new EventEmitter<number>(); // NEW

	map: mapboxgl.Map;
	mainMarker: mapboxgl.Marker;
	privacyCircle: mapboxgl.MapboxDraw; // NEW
	markerCluster: Supercluster<GeoLocation>; // NEW
	searchRadius: GeoJSON.Circle; // NEW

	private destroy$ = new Subject<void>();

	constructor(private elementRef: ElementRef) {}

	ngOnInit() {
		this.initializeMap();
		this.addMainMarker();

		// NEW: Add privacy obfuscation circle
		if (this.showPrivacyCircle) {
			this.addPrivacyCircle();
		}

		// NEW: Add nearby car markers
		if (this.markers.length > 0) {
			this.addNearbyMarkers();
		}
	}

	// NEW: Add privacy circle overlay (±500m radius)
	private addPrivacyCircle() {
		const circleRadius = 500; // meters
		const circleColor = "rgba(100, 200, 255, 0.2)"; // Light blue

		this.map.addSource("privacy-circle", {
			type: "geojson",
			data: {
				type: "Feature",
				geometry: {
					type: "Point",
					coordinates: [this.longitude, this.latitude],
				},
				properties: {},
			},
		});

		this.map.addLayer({
			id: "privacy-circle-layer",
			type: "circle",
			source: "privacy-circle",
			paint: {
				"circle-radius": this.metersToPixels(circleRadius),
				"circle-color": circleColor,
				"circle-stroke-width": 2,
				"circle-stroke-color": "#64c8ff",
			},
		});
	}

	// NEW: Add markers for nearby search results
	private addNearbyMarkers() {
		if (this.clusterMarkers) {
			this.addClusteredMarkers();
		} else {
			this.addSimpleMarkers();
		}
	}

	// NEW: Add individual markers
	private addSimpleMarkers() {
		this.markers.forEach((location) => {
			const el = document.createElement("div");
			el.className = "car-marker";
			el.style.backgroundImage = `url('/assets/car-pin.svg')`;
			el.style.width = "32px";
			el.style.height = "40px";
			el.style.cursor = "pointer";

			const marker = new mapboxgl.Marker(el)
				.setLngLat([location.longitude, location.latitude])
				.addTo(this.map);

			el.addEventListener("click", () => {
				this.markerSelected.emit(location.carId);
			});
		});
	}

	// NEW: Add clustered markers for performance
	private addClusteredMarkers() {
		// Use mapbox-gl-cluster plugin
		const points = this.markers.map((m, idx) => ({
			type: "Feature",
			geometry: { type: "Point", coordinates: [m.longitude, m.latitude] },
			properties: { id: m.carId, index: idx },
		}));

		this.map.addSource("cars", {
			type: "geojson",
			data: { type: "FeatureCollection", features: points },
			cluster: true,
			clusterMaxZoom: 14,
			clusterRadius: 50,
		});

		// Cluster layer
		this.map.addLayer({
			id: "clusters",
			type: "circle",
			source: "cars",
			filter: ["has", "point_count"],
			paint: {
				"circle-color": "#51bbd6",
				"circle-radius": ["step", ["get", "point_count"], 20, 100, 30, 750, 40],
			},
		});

		// Unclustered point layer
		this.map.addLayer({
			id: "unclustered-point",
			type: "circle",
			source: "cars",
			filter: ["!", ["has", "point_count"]],
			paint: {
				"circle-color": "#11b4da",
				"circle-radius": 4,
				"circle-stroke-width": 1,
				"circle-stroke-color": "#fff",
			},
		});
	}

	// NEW: Handle drag to update radius
	onRadiusChange(newRadius: number) {
		this.markerRadius = newRadius;
		this.radiusChanged.emit(newRadius);

		// Update search area visualization
		this.updateSearchRadiusCircle();
	}

	// NEW: Utility to convert meters to map pixels
	private metersToPixels(meters: number): number {
		const latRadius = meters / 111000;
		const pixelCoord = this.map.project([
			this.longitude,
			this.latitude + latRadius,
		]);
		const centerPixel = this.map.project([this.longitude, this.latitude]);
		return Math.abs(pixelCoord.y - centerPixel.y);
	}

	ngOnDestroy() {
		this.destroy$.next();
		this.destroy$.complete();
		if (this.map) {
			this.map.remove();
		}
	}
}
```

**Template Enhancement:**

```html
<!-- location-picker.component.html -->
<div class="location-picker-container" [style.height]="height">
	<div id="map" class="map-container"></div>

	<!-- NEW: Search radius slider -->
	<div class="search-controls" *ngIf="markers.length > 0">
		<label>Search Radius: {{ markerRadius }}km</label>
		<input
			type="range"
			min="5"
			max="50"
			step="5"
			[value]="markerRadius"
			(change)="onRadiusChange($event.target.value)"
		/>
	</div>

	<!-- NEW: Privacy notice -->
	<div class="privacy-notice" *ngIf="showPrivacyCircle">
		<i class="icon-info"></i>
		<span>Exact location shown after booking</span>
	</div>

	<!-- Loading state -->
	<div class="loading-overlay" *ngIf="isLoading">
		<mat-spinner diameter="40"></mat-spinner>
	</div>

	<!-- Error state -->
	<div class="error-message" *ngIf="error">{{ error }}</div>
</div>
```

**Styles Enhancement:**

```scss
// location-picker.component.scss
.location-picker-container {
	position: relative;
	border-radius: 4px;
	overflow: hidden;
	box-shadow: 0 2px 8px rgba(0, 0, 0, 0.1);

	.map-container {
		width: 100%;
		height: 100%;
	}

	// NEW: Search controls
	.search-controls {
		position: absolute;
		top: 12px;
		right: 12px;
		background: white;
		padding: 12px;
		border-radius: 4px;
		box-shadow: 0 2px 4px rgba(0, 0, 0, 0.1);
		font-size: 14px;
		z-index: 10;

		label {
			display: block;
			margin-bottom: 8px;
			font-weight: 500;
		}

		input[type="range"] {
			width: 150px;
		}
	}

	// NEW: Privacy notice
	.privacy-notice {
		position: absolute;
		bottom: 12px;
		left: 12px;
		background: rgba(100, 150, 200, 0.9);
		color: white;
		padding: 8px 12px;
		border-radius: 4px;
		font-size: 13px;
		z-index: 10;
		display: flex;
		align-items: center;
		gap: 6px;

		i {
			font-size: 16px;
		}
	}

	// Car marker styles
	.car-marker {
		background-repeat: no-repeat;
		background-position: center;
		background-size: contain;
		transition: transform 0.2s ease;

		&:hover {
			transform: scale(1.2);
		}
	}
}
```

### 1.2 Create LocationDTO for API Consistency

**File:** `shared/models/location.model.ts`

```typescript
// location.model.ts
export interface GeoPointDTO {
	latitude: number;
	longitude: number;
	address?: string;
	city?: string;
	zipCode?: string;
	accuracyMeters?: number;
}

export interface ObfuscatedGeoPointDTO {
	latitude: number;
	longitude: number;
	city: string; // City visible, exact address hidden
	obfuscationRadiusMeters: number;
	obfuscationApplied: boolean;
}

export interface GeoLocation {
	carId: string;
	latitude: number;
	longitude: number;
	address?: string;
	city?: string;
	distanceKm?: number; // Distance from search center
	brand?: string;
	model?: string;
	imageUrl?: string;
}

export interface SearchFilters {
	pricePerDayMin?: number;
	pricePerDayMax?: number;
	distanceMaxKm?: number;
	deliveryAvailable?: boolean;
	carType?: string[];
	transmission?: string;
	fuelType?: string;
}
```

---

## Phase 2: Add Car Wizard Refactor

### 2.1 Enhance Add Car Wizard with Geospatial Location

**Files to Create/Modify:**

- `features/add-car/add-car-wizard/add-car-wizard.component.ts`
- `features/add-car/add-car-wizard/steps/location-step.component.ts` (NEW)
- `features/add-car/add-car-wizard/steps/location-step.component.html` (NEW)

**Step 1: Location Entry (New/Modify)**

```typescript
// location-step.component.ts
import { Component, OnInit } from "@angular/core";
import { FormBuilder, FormGroup, Validators } from "@angular/forms";
import { LocationService } from "@core/services/location.service";
import { GeoPointDTO } from "@shared/models/location.model";

@Component({
	selector: "app-location-step",
	templateUrl: "./location-step.component.html",
	styleUrls: ["./location-step.component.scss"],
})
export class LocationStepComponent implements OnInit {
	locationForm: FormGroup;
	selectedLocation: GeoPointDTO;
	geocodingSuggestions: any[] = [];
	isGeocoding: boolean = false;
	locationValidation: {
		isWithinSerbia: boolean;
		accuracy: "PRECISE" | "APPROXIMATE" | "UNKNOWN";
	};

	constructor(
		private fb: FormBuilder,
		private locationService: LocationService
	) {
		this.locationForm = this.fb.group({
			address: ["", [Validators.required, Validators.minLength(5)]],
			city: ["", [Validators.required]],
			zipCode: [""],
			latitude: [{ value: "", disabled: true }], // Auto-filled
			longitude: [{ value: "", disabled: true }], // Auto-filled
			useCurrentLocation: [false],
		});
	}

	ngOnInit() {
		this.setupAddressAutocomplete();
	}

	// FEATURE: Real-time address geocoding
	// IMPORTANT: Uses Mapbox Geocoding API (via backend proxy) for autocomplete
	// NOT Nominatim directly, because:
	// 1. Mapbox handles partial queries much better (e.g., "Teraz" → "Terazije")
	// 2. Avoids hitting Nominatim's strict 1 req/sec per-IP limit on frontend
	// 3. Backend can rate-limit and cache geocoding requests
	setupAddressAutocomplete() {
		this.locationForm
			.get("address")
			?.valueChanges.pipe(
				debounceTime(300),
				distinctUntilChanged(),
				// Only start geocoding after 3+ characters (reduce API calls)
				filter((address) => address && address.length >= 3),
				switchMap((address) => this.locationService.geocodeAddress(address)),
				takeUntil(this.destroy$)
			)
			.subscribe(
				(suggestions) => {
					this.geocodingSuggestions = suggestions;
				},
				(error) => {
					console.error("Geocoding failed:", error);
					this.locationValidation = {
						isWithinSerbia: false,
						accuracy: "UNKNOWN",
					};
				}
			);
	}

	// FEATURE: Select from geocoding suggestions
	selectLocation(suggestion: any) {
		this.selectedLocation = {
			latitude: suggestion.latitude,
			longitude: suggestion.longitude,
			address: suggestion.address,
			city: suggestion.city,
			zipCode: suggestion.zipCode,
			accuracyMeters: suggestion.accuracyMeters,
		};

		// Validate Serbia bounds
		this.locationValidation = {
			isWithinSerbia: this.locationService.validateCoordinates(
				suggestion.latitude,
				suggestion.longitude
			),
			accuracy: this.getAccuracyLevel(suggestion.accuracyMeters),
		};

		// Populate form
		this.locationForm.patchValue({
			latitude: suggestion.latitude,
			longitude: suggestion.longitude,
			address: suggestion.address,
			city: suggestion.city,
			zipCode: suggestion.zipCode,
		});

		this.geocodingSuggestions = [];
	}

	// FEATURE: Use device GPS coordinates
	useCurrentLocation() {
		if (navigator.geolocation) {
			navigator.geolocation.getCurrentPosition(
				(position) => {
					const coords = position.coords;

					// Reverse geocode to get address
					this.locationService
						.reverseGeocodeCoordinates(coords.latitude, coords.longitude)
						.subscribe((address) => {
							this.selectLocation({
								latitude: coords.latitude,
								longitude: coords.longitude,
								address: address.formatted_address,
								city: address.city,
								zipCode: address.zipCode,
								accuracyMeters: coords.accuracy,
							});
						});
				},
				(error) => {
					console.error("Geolocation error:", error);
					alert("Unable to access your location. Please enable GPS.");
				}
			);
		}
	}

	// FEATURE: Interactive map location selection
	openMapSelector() {
		const dialogRef = this.dialog.open(MapSelectorDialog, {
			width: "600px",
			height: "500px",
			data: {
				initialLat: this.selectedLocation?.latitude || 44.8176,
				initialLon: this.selectedLocation?.longitude || 20.4569,
			},
		});

		dialogRef.afterClosed().subscribe((result) => {
			if (result) {
				this.selectLocation(result);
			}
		});
	}

	getAccuracyLevel(
		metersAccuracy?: number
	): "PRECISE" | "APPROXIMATE" | "UNKNOWN" {
		if (!metersAccuracy) return "UNKNOWN";
		if (metersAccuracy < 50) return "PRECISE";
		if (metersAccuracy < 500) return "APPROXIMATE";
		return "UNKNOWN";
	}

	isLocationValid(): boolean {
		return (
			this.selectedLocation !== undefined &&
			this.locationValidation?.isWithinSerbia
		);
	}

	// Return location data for wizard
	getLocationData(): GeoPointDTO {
		return this.selectedLocation;
	}
}
```

**Template:**

```html
<!-- location-step.component.html -->
<div class="location-step">
	<h3>Where is your car located?</h3>
	<p class="step-description">
		Provide an exact location so guests can find your car easily.
	</p>

	<form [formGroup]="locationForm">
		<!-- Use Current Location Button -->
		<div class="quick-actions">
			<button
				type="button"
				mat-stroked-button
				(click)="useCurrentLocation()"
				class="action-button"
			>
				<mat-icon>location_on</mat-icon>
				Use Current Location
			</button>

			<button
				type="button"
				mat-stroked-button
				(click)="openMapSelector()"
				class="action-button"
			>
				<mat-icon>map</mat-icon>
				Select on Map
			</button>
		</div>

		<!-- Address Input with Autocomplete -->
		<mat-form-field appearance="outline" class="full-width">
			<mat-label>Street Address</mat-label>
			<input
				matInput
				formControlName="address"
				placeholder="e.g., Terazije 26"
			/>
			<mat-error *ngIf="locationForm.get('address')?.hasError('required')">
				Address is required
			</mat-error>

			<!-- Autocomplete Suggestions -->
			<mat-autocomplete #auto="matAutocomplete">
				<mat-option
					*ngFor="let suggestion of geocodingSuggestions"
					[value]="suggestion.address"
					(click)="selectLocation(suggestion)"
				>
					<strong>{{ suggestion.address }}</strong>
					<br />
					<small>{{ suggestion.city }}</small>
				</mat-option>
			</mat-autocomplete>
		</mat-form-field>

		<!-- City & Zip -->
		<div class="row">
			<mat-form-field appearance="outline" class="flex-1">
				<mat-label>City</mat-label>
				<input matInput formControlName="city" readonly />
			</mat-form-field>

			<mat-form-field appearance="outline" class="flex-1">
				<mat-label>ZIP Code</mat-label>
				<input matInput formControlName="zipCode" />
			</mat-form-field>
		</div>

		<!-- Coordinates Display (Read-only) -->
		<div class="row" *ngIf="selectedLocation">
			<mat-form-field appearance="outline" class="flex-1">
				<mat-label>Latitude</mat-label>
				<input
					matInput
					[value]="selectedLocation.latitude | number:'1.6-6'"
					readonly
				/>
			</mat-form-field>

			<mat-form-field appearance="outline" class="flex-1">
				<mat-label>Longitude</mat-label>
				<input
					matInput
					[value]="selectedLocation.longitude | number:'1.6-6'"
					readonly
				/>
			</mat-form-field>
		</div>

		<!-- Location Preview Map -->
		<div class="location-preview" *ngIf="selectedLocation">
			<app-location-picker
				[latitude]="selectedLocation.latitude"
				[longitude]="selectedLocation.longitude"
				[editable]="true"
				(locationChanged)="selectLocation($event)"
				height="300px"
			>
			</app-location-picker>

			<!-- Accuracy & Validation Indicators -->
			<div class="location-info">
				<div class="accuracy-badge" [ngClass]="locationValidation.accuracy">
					<mat-icon>
						{{ locationValidation.accuracy === 'PRECISE' ? 'check_circle' :
						'info' }}
					</mat-icon>
					{{ locationValidation.accuracy }} location
				</div>

				<div
					class="serbia-bounds-warning"
					*ngIf="!locationValidation.isWithinSerbia"
					role="alert"
				>
					<mat-icon>warning</mat-icon>
					Location is outside Serbia. Guests may not find your car.
				</div>
			</div>
		</div>
	</form>

	<!-- Wizard Navigation -->
	<div class="wizard-actions">
		<button mat-button (click)="goToPreviousStep()">Back</button>
		<button
			mat-raised-button
			color="primary"
			[disabled]="!isLocationValid()"
			(click)="goToNextStep()"
		>
			Next: Pricing
		</button>
	</div>
</div>
```

### 2.2 Map Selector Dialog (NEW)

**File:** `shared/components/map-selector-dialog/map-selector-dialog.component.ts`

```typescript
@Component({
	selector: "app-map-selector-dialog",
	template: `
		<h2 mat-dialog-title>Select Car Location</h2>
		<mat-dialog-content>
			<app-location-picker
				[latitude]="dialogData.initialLat"
				[longitude]="dialogData.initialLon"
				[editable]="true"
				(locationChanged)="onLocationChanged($event)"
				height="400px"
			>
			</app-location-picker>
		</mat-dialog-content>
		<mat-dialog-actions>
			<button mat-button (click)="dialogRef.close()">Cancel</button>
			<button
				mat-raised-button
				color="primary"
				[disabled]="!selectedLocation"
				(click)="dialogRef.close(selectedLocation)"
			>
				Confirm
			</button>
		</mat-dialog-actions>
	`,
})
export class MapSelectorDialogComponent {
	selectedLocation: any;

	constructor(
		public dialogRef: MatDialogRef<MapSelectorDialogComponent>,
		@Inject(MAT_DIALOG_DATA) public dialogData: any,
		private locationService: LocationService
	) {}

	onLocationChanged(point: GeoPointDTO) {
		// Reverse geocode to get address
		this.locationService
			.reverseGeocodeCoordinates(point.latitude, point.longitude)
			.subscribe((address) => {
				this.selectedLocation = {
					latitude: point.latitude,
					longitude: point.longitude,
					address: address.formatted_address,
					city: address.city,
					zipCode: address.zipCode,
				};
			});
	}
}
```

---

## Phase 3: Car Discovery with Geospatial Search

### 3.1 Create Car Search Page

**Files:**

- `features/car-search/car-search.component.ts` (NEW)
- `features/car-search/car-search.component.html` (NEW)
- `features/car-search/car-search.component.scss` (NEW)

```typescript
// car-search.component.ts
@Component({
	selector: "app-car-search",
	templateUrl: "./car-search.component.html",
	styleUrls: ["./car-search.component.scss"],
})
export class CarSearchComponent implements OnInit, OnDestroy {
	searchForm: FormGroup;
	searchResults: CarSearchResult[] = [];
	isSearching: boolean = false;
	userLocation: GeoPointDTO;
	selectedRadius: number = 25; // km
	totalResultsCount: number = 0;

	filters: SearchFilters = {
		distanceMaxKm: 25,
		pricePerDayMin: 0,
		pricePerDayMax: 10000,
	};

	// Pagination
	currentPage: number = 1;
	pageSize: number = 20;

	private destroy$ = new Subject<void>();

	constructor(
		private fb: FormBuilder,
		private locationService: LocationService,
		private carService: CarService,
		private deliveryService: DeliveryService
	) {
		this.searchForm = this.fb.group({
			pickupDate: [new Date(), Validators.required],
			dropoffDate: [
				new Date(Date.now() + 24 * 60 * 60 * 1000),
				Validators.required,
			],
			latitude: [{ value: 44.8176, disabled: false }, Validators.required],
			longitude: [{ value: 20.4569, disabled: false }, Validators.required],
		});
	}

	ngOnInit() {
		// Get user location from device
		this.getUserLocation();

		// Setup reactive search (search as user changes parameters)
		this.setupReactiveSearch();
	}

	getUserLocation() {
		if (navigator.geolocation) {
			navigator.geolocation.getCurrentPosition(
				(position) => {
					this.userLocation = {
						latitude: position.coords.latitude,
						longitude: position.coords.longitude,
					};
					this.searchForm.patchValue({
						latitude: position.coords.latitude,
						longitude: position.coords.longitude,
					});
					this.performSearch();
				},
				(error) => {
					console.warn("Cannot access user location, using default Belgrade");
					// Use default Belgrade location
					this.performSearch();
				}
			);
		}
	}

	setupReactiveSearch() {
		this.searchForm.valueChanges
			.pipe(
				debounceTime(500),
				distinctUntilChanged(),
				switchMap((formValue) => {
					this.isSearching = true;
					return this.carService.searchNearby(
						formValue.latitude,
						formValue.longitude,
						this.selectedRadius,
						this.filters
					);
				}),
				takeUntil(this.destroy$)
			)
			.subscribe(
				(results) => {
					this.searchResults = results;
					this.totalResultsCount = results.length;
					this.isSearching = false;

					// Enrich results with delivery info
					this.enrichResultsWithDelivery();
				},
				(error) => {
					console.error("Search failed:", error);
					this.isSearching = false;
				}
			);
	}

	// FEATURE: Enrich search results with delivery fee info
	enrichResultsWithDelivery() {
		const pickupLat = this.searchForm.get("latitude")?.value;
		const pickupLon = this.searchForm.get("longitude")?.value;

		this.searchResults.forEach((car) => {
			this.deliveryService
				.calculateDeliveryFee(car.id, pickupLat, pickupLon)
				.subscribe((feeResult) => {
					car.deliveryFee = feeResult.fee;
					car.deliveryDistance = feeResult.distanceKm;
					car.deliveryAvailable = feeResult.available;
				});
		});
	}

	// FEATURE: Apply advanced filters
	applyFilters(updatedFilters: SearchFilters) {
		this.filters = { ...this.filters, ...updatedFilters };
		this.performSearch();
	}

	performSearch() {
		const formValue = this.searchForm.getRawValue();
		this.isSearching = true;

		this.carService
			.searchNearby(
				formValue.latitude,
				formValue.longitude,
				this.selectedRadius,
				this.filters
			)
			.subscribe((results) => {
				this.searchResults = results;
				this.totalResultsCount = results.length;
				this.isSearching = false;
				this.enrichResultsWithDelivery();
			});
	}

	// FEATURE: Sort results
	sortResults(sortBy: "distance" | "price" | "rating") {
		switch (sortBy) {
			case "distance":
				this.searchResults.sort(
					(a, b) => (a.distanceKm || 0) - (b.distanceKm || 0)
				);
				break;
			case "price":
				this.searchResults.sort((a, b) => a.pricePerDay - b.pricePerDay);
				break;
			case "rating":
				this.searchResults.sort((a, b) => (b.rating || 0) - (a.rating || 0));
				break;
		}
	}

	// FEATURE: Handle radius change from map
	onRadiusChange(newRadius: number) {
		this.selectedRadius = newRadius;
		this.performSearch();
	}

	// FEATURE: Go to booking page
	selectCar(car: CarSearchResult) {
		this.router.navigate(["/booking/create", car.id], {
			queryParams: {
				pickupDate: this.searchForm.get("pickupDate")?.value,
				dropoffDate: this.searchForm.get("dropoffDate")?.value,
				pickupLat: this.searchForm.get("latitude")?.value,
				pickupLon: this.searchForm.get("longitude")?.value,
			},
		});
	}

	ngOnDestroy() {
		this.destroy$.next();
		this.destroy$.complete();
	}
}
```

**Template:**

```html
<!-- car-search.component.html -->
<div class="car-search-container">
	<!-- Search Header -->
	<mat-card class="search-card">
		<mat-card-header>
			<h2>Find Cars Near You</h2>
		</mat-card-header>

		<mat-card-content>
			<form [formGroup]="searchForm">
				<div class="search-row">
					<!-- Date Range -->
					<mat-form-field appearance="outline">
						<mat-label>Pickup Date</mat-label>
						<input
							matInput
							[matDatepicker]="startPicker"
							formControlName="pickupDate"
						/>
						<mat-datepicker-toggle
							matSuffix
							[for]="startPicker"
						></mat-datepicker-toggle>
						<mat-datepicker #startPicker></mat-datepicker>
					</mat-form-field>

					<mat-form-field appearance="outline">
						<mat-label>Dropoff Date</mat-label>
						<input
							matInput
							[matDatepicker]="endPicker"
							formControlName="dropoffDate"
						/>
						<mat-datepicker-toggle
							matSuffix
							[for]="endPicker"
						></mat-datepicker-toggle>
						<mat-datepicker #endPicker></mat-datepicker>
					</mat-form-field>

					<button mat-raised-button color="primary" (click)="performSearch()">
						Search
					</button>
				</div>
			</form>
		</mat-card-content>
	</mat-card>

	<!-- Main Layout: Map + Results -->
	<div class="search-layout">
		<!-- Left: Map View -->
		<div class="map-panel">
			<app-location-picker
				[latitude]="searchForm.get('latitude')?.value"
				[longitude]="searchForm.get('longitude')?.value"
				[markers]="searchResults | mapMarkers"
				[markerRadius]="selectedRadius"
				[clusterMarkers]="searchResults.length > 20"
				(markerSelected)="selectCar($event)"
				(radiusChanged)="onRadiusChange($event)"
				height="100%"
			>
			</app-location-picker>
		</div>

		<!-- Right: Results List -->
		<div class="results-panel">
			<!-- Filters Sidebar -->
			<mat-sidenav-container>
				<mat-sidenav mode="side" opened>
					<app-search-filters
						[filters]="filters"
						(filtersChanged)="applyFilters($event)"
					>
					</app-search-filters>
				</mat-sidenav>

				<mat-sidenav-content>
					<!-- Results Header -->
					<div class="results-header">
						<h3>{{ totalResultsCount }} Cars Available</h3>

						<!-- Sort Options -->
						<mat-button-toggle-group>
							<mat-button-toggle
								value="distance"
								(click)="sortResults('distance')"
							>
								By Distance
							</mat-button-toggle>
							<mat-button-toggle value="price" (click)="sortResults('price')">
								By Price
							</mat-button-toggle>
							<mat-button-toggle value="rating" (click)="sortResults('rating')">
								By Rating
							</mat-button-toggle>
						</mat-button-toggle-group>
					</div>

					<!-- Loading State -->
					<div class="loading-state" *ngIf="isSearching">
						<mat-spinner diameter="40"></mat-spinner>
						<p>Searching nearby cars...</p>
					</div>

					<!-- Empty State -->
					<div
						class="empty-state"
						*ngIf="searchResults.length === 0 && !isSearching"
					>
						<mat-icon>directions_car</mat-icon>
						<h4>No cars found</h4>
						<p>Try expanding your search radius or adjusting filters</p>
					</div>

					<!-- Car Results List -->
					<div class="car-results">
						<app-car-search-result
							*ngFor="let car of searchResults | paginate: {itemsPerPage: pageSize, currentPage: currentPage}"
							[car]="car"
							(selected)="selectCar($event)"
						>
						</app-car-search-result>
					</div>

					<!-- Pagination -->
					<pagination-controls
						*ngIf="totalResultsCount > pageSize"
						(pageChange)="currentPage = $event"
					>
					</pagination-controls>
				</mat-sidenav-content>
			</mat-sidenav-container>
		</div>
	</div>
</div>
```

**Styles:**

```scss
// car-search.component.scss
.car-search-container {
	display: flex;
	flex-direction: column;
	height: 100vh;
	background: #f5f5f5;

	.search-card {
		padding: 20px;
		margin: 16px;
		box-shadow: 0 2px 4px rgba(0, 0, 0, 0.1);
	}

	.search-layout {
		display: grid;
		grid-template-columns: 1fr 1fr;
		gap: 16px;
		padding: 16px;
		flex: 1;
		overflow: hidden;

		@media (max-width: 768px) {
			grid-template-columns: 1fr;

			.map-panel {
				display: none;
			}
		}
	}

	.map-panel {
		border-radius: 4px;
		overflow: hidden;
		box-shadow: 0 2px 8px rgba(0, 0, 0, 0.1);
	}

	.results-panel {
		display: flex;
		flex-direction: column;
		background: white;
		border-radius: 4px;
		box-shadow: 0 2px 8px rgba(0, 0, 0, 0.1);
		overflow: hidden;
	}

	.results-header {
		padding: 16px;
		border-bottom: 1px solid #e0e0e0;
		display: flex;
		justify-content: space-between;
		align-items: center;

		h3 {
			margin: 0;
			font-size: 18px;
		}
	}

	.car-results {
		overflow-y: auto;
		flex: 1;
	}

	.loading-state,
	.empty-state {
		display: flex;
		flex-direction: column;
		align-items: center;
		justify-content: center;
		padding: 40px;
		text-align: center;

		mat-icon {
			font-size: 48px;
			width: 48px;
			height: 48px;
			color: #ccc;
			margin-bottom: 16px;
		}
	}
}
```

---

## Phase 4: Advanced Filters & Map Integration

### 4.1 Search Filters Component (NEW)

**File:** `features/car-search/search-filters/search-filters.component.ts`

```typescript
@Component({
	selector: "app-search-filters",
	templateUrl: "./search-filters.component.html",
})
export class SearchFiltersComponent implements OnInit {
	@Input() filters: SearchFilters;
	@Output() filtersChanged = new EventEmitter<SearchFilters>();

	filterForm: FormGroup;
	carTypes = ["Sedan", "SUV", "Hatchback", "Van", "Coupe"];
	transmissions = ["Manual", "Automatic"];
	fuelTypes = ["Petrol", "Diesel", "Electric", "Hybrid"];

	constructor(private fb: FormBuilder) {
		this.filterForm = this.fb.group({
			pricePerDayMin: [0],
			pricePerDayMax: [10000],
			distanceMaxKm: [25],
			deliveryAvailable: [false],
			carType: [[]],
			transmission: [""],
			fuelType: [""],
			minRating: [0],
		});
	}

	ngOnInit() {
		this.filterForm.valueChanges.pipe(debounceTime(300)).subscribe((values) => {
			this.filtersChanged.emit(values);
		});
	}

	// FEATURE: Price range slider
	onPriceChange(min: number, max: number) {
		this.filterForm.patchValue({
			pricePerDayMin: min,
			pricePerDayMax: max,
		});
	}

	// FEATURE: Multi-select car types
	toggleCarType(type: string) {
		const carTypes = this.filterForm.get("carType")?.value || [];
		const index = carTypes.indexOf(type);
		if (index > -1) {
			carTypes.splice(index, 1);
		} else {
			carTypes.push(type);
		}
		this.filterForm.patchValue({ carType: carTypes });
	}

	resetFilters() {
		this.filterForm.reset({
			pricePerDayMin: 0,
			pricePerDayMax: 10000,
			distanceMaxKm: 25,
			deliveryAvailable: false,
			carType: [],
			minRating: 0,
		});
	}
}
```

### 4.2 Car Search Result Card (NEW)

**File:** `features/car-search/car-search-result/car-search-result.component.ts`

```typescript
@Component({
	selector: "app-car-search-result",
	template: `
		<mat-card class="car-result-card">
			<div class="car-image">
				<img [src]="car.imageUrl" [alt]="car.brand + ' ' + car.model" />
				<span class="distance-badge" *ngIf="car.distanceKm">
					{{ car.distanceKm | number : "1.1-1" }}km away
				</span>
			</div>

			<mat-card-header>
				<mat-card-title>
					{{ car.brand }} {{ car.model }} ({{ car.year }})
				</mat-card-title>
				<mat-card-subtitle>
					<mat-icon>location_on</mat-icon>
					{{ car.city }}
				</mat-card-subtitle>
			</mat-card-header>

			<mat-card-content>
				<!-- Price -->
				<div class="price-section">
					<span class="price">{{ car.pricePerDay | currency }}</span>
					<span class="unit">/day</span>
				</div>

				<!-- Delivery Info -->
				<div class="delivery-info" *ngIf="car.deliveryAvailable">
					<mat-icon class="delivery-icon">local_shipping</mat-icon>
					<span class="delivery-fee">
						Delivery: {{ car.deliveryFee | currency }}
					</span>
				</div>

				<!-- Rating -->
				<div class="rating" *ngIf="car.rating">
					<mat-icon class="star">star</mat-icon>
					<span>{{ car.rating | number : "1.1-1" }}</span>
					<span class="reviews">({{ car.reviewCount }} reviews)</span>
				</div>

				<!-- Features -->
				<div class="features">
					<span class="feature" *ngFor="let feature of car.features">
						{{ feature }}
					</span>
				</div>
			</mat-card-content>

			<mat-card-actions>
				<button mat-raised-button color="primary" (click)="selected.emit(car)">
					View Details
				</button>
			</mat-card-actions>
		</mat-card>
	`,
	styles: [
		`
			.car-result-card {
				margin-bottom: 16px;
				cursor: pointer;
				transition: transform 0.2s, box-shadow 0.2s;

				&:hover {
					transform: translateY(-4px);
					box-shadow: 0 8px 16px rgba(0, 0, 0, 0.2);
				}
			}

			.car-image {
				position: relative;
				height: 150px;
				overflow: hidden;
				border-radius: 4px 4px 0 0;

				img {
					width: 100%;
					height: 100%;
					object-fit: cover;
				}

				.distance-badge {
					position: absolute;
					top: 8px;
					right: 8px;
					background: rgba(0, 0, 0, 0.7);
					color: white;
					padding: 4px 8px;
					border-radius: 4px;
					font-size: 12px;
				}
			}

			.price-section {
				display: flex;
				align-items: baseline;
				gap: 4px;
				margin: 12px 0;

				.price {
					font-size: 20px;
					font-weight: 600;
					color: #1976d2;
				}

				.unit {
					font-size: 12px;
					color: #666;
				}
			}

			.delivery-info {
				display: flex;
				align-items: center;
				gap: 8px;
				padding: 8px;
				background: #f0f0f0;
				border-radius: 4px;
				margin: 8px 0;
				font-size: 12px;

				.delivery-icon {
					font-size: 16px;
					color: #ff9800;
				}
			}

			.features {
				display: flex;
				flex-wrap: wrap;
				gap: 4px;
				margin: 8px 0;

				.feature {
					background: #e3f2fd;
					color: #1976d2;
					padding: 2px 6px;
					border-radius: 12px;
					font-size: 11px;
				}
			}
		`,
	],
})
export class CarSearchResultComponent {
	@Input() car: CarSearchResult;
	@Output() selected = new EventEmitter<CarSearchResult>();
}
```

---

## Phase 5: Booking Flow Integration

### 5.1 Enhance Booking Page with Pickup Location Selection

**File:** `features/booking/booking-form/booking-form.component.ts`

```typescript
@Component({
	selector: "app-booking-form",
	templateUrl: "./booking-form.component.html",
})
export class BookingFormComponent implements OnInit {
	car: Car;
	bookingForm: FormGroup;
	pickupLocation: GeoPointDTO;
	deliveryFee: BigDecimal;
	totalCost: BigDecimal;

	constructor(
		private fb: FormBuilder,
		private route: ActivatedRoute,
		private carService: CarService,
		private deliveryService: DeliveryService,
		private bookingService: BookingService,
		private locationService: LocationService
	) {
		this.bookingForm = this.fb.group({
			pickupDate: ["", Validators.required],
			dropoffDate: ["", Validators.required],
			pickupLatitude: ["", Validators.required],
			pickupLongitude: ["", Validators.required],
			guestCheckInLatitude: [""],
			guestCheckInLongitude: [""],
			deliveryDistance: [{ value: "", disabled: true }],
			deliveryFee: [{ value: "", disabled: true }],
			totalCost: [{ value: "", disabled: true }],
		});
	}

	ngOnInit() {
		// Get car details
		const carId = this.route.snapshot.paramMap.get("carId");
		this.carService.getById(carId).subscribe((car) => {
			this.car = car;

			// Pre-fill pickup location from query params
			const pickupLat = this.route.snapshot.queryParams["pickupLat"];
			const pickupLon = this.route.snapshot.queryParams["pickupLon"];

			if (pickupLat && pickupLon) {
				this.setPickupLocation({
					latitude: parseFloat(pickupLat),
					longitude: parseFloat(pickupLon),
				});
			}
		});

		// Calculate delivery fee when pickup location changes
		this.bookingForm
			.get("pickupLatitude")
			?.valueChanges.pipe(
				debounceTime(500),
				switchMap(() => this.calculateDeliveryFee())
			)
			.subscribe();
	}

	// FEATURE: Set pickup location from map
	setPickupLocation(location: GeoPointDTO) {
		this.pickupLocation = location;
		this.bookingForm.patchValue({
			pickupLatitude: location.latitude,
			pickupLongitude: location.longitude,
		});

		this.calculateDeliveryFee();
	}

	// FEATURE: Calculate delivery fee
	private calculateDeliveryFee(): Observable<DeliveryFeeResult> {
		const pickupLat = this.bookingForm.get("pickupLatitude")?.value;
		const pickupLon = this.bookingForm.get("pickupLongitude")?.value;

		return this.deliveryService
			.calculateDeliveryFee(this.car.id, pickupLat, pickupLon)
			.pipe(
				tap((result) => {
					this.deliveryFee = result.fee;
					this.bookingForm.patchValue({
						deliveryDistance: result.distanceKm,
						deliveryFee: result.fee,
					});
					this.calculateTotalCost();
				})
			);
	}

	// FEATURE: Calculate total cost
	private calculateTotalCost() {
		const pickupDate = this.bookingForm.get("pickupDate")?.value;
		const dropoffDate = this.bookingForm.get("dropoffDate")?.value;

		if (pickupDate && dropoffDate) {
			const days = Math.ceil(
				(dropoffDate.getTime() - pickupDate.getTime()) / (1000 * 60 * 60 * 24)
			);
			const baseCost = this.car.pricePerDay * days;
			this.totalCost = baseCost + (this.deliveryFee || 0);

			this.bookingForm.patchValue({
				totalCost: this.totalCost,
			});
		}
	}

	// FEATURE: Open map selector for pickup location
	selectPickupLocationOnMap() {
		const dialogRef = this.dialog.open(MapSelectorDialog, {
			width: "600px",
			height: "500px",
			data: {
				initialLat:
					this.pickupLocation?.latitude || this.car.locationGeoPoint.latitude,
				initialLon:
					this.pickupLocation?.longitude || this.car.locationGeoPoint.longitude,
			},
		});

		dialogRef.afterClosed().subscribe((result) => {
			if (result) {
				this.setPickupLocation(result);
			}
		});
	}

	// FEATURE: Request guest check-in location (after booking confirmed)
	requestGuestCheckInLocation() {
		// Send PIN to guest SMS/email, guest confirms location in separate flow
		const pinCode = Math.random().toString().substring(2, 8);
		// TODO: Send PIN to guest
	}

	// FEATURE: Submit booking
	submitBooking() {
		const bookingData = {
			...this.bookingForm.getRawValue(),
			carId: this.car.id,
			deliveryFee: this.deliveryFee,
		};

		this.bookingService.createBooking(bookingData).subscribe((booking) => {
			this.router.navigate(["/booking/confirmation", booking.id]);
		});
	}
}
```

**Template:**

```html
<!-- booking-form.component.html -->
<mat-card class="booking-form-card">
	<mat-card-header>
		<h2>Confirm Your Booking</h2>
		<p>
			{{ car.brand }} {{ car.model }} - {{ car.pricePerDay | currency }}/day
		</p>
	</mat-card-header>

	<mat-card-content>
		<form [formGroup]="bookingForm">
			<!-- Dates -->
			<div class="form-section">
				<h3>Trip Dates</h3>
				<div class="date-row">
					<mat-form-field appearance="outline">
						<mat-label>Pickup Date</mat-label>
						<input
							matInput
							[matDatepicker]="startPicker"
							formControlName="pickupDate"
						/>
						<mat-datepicker-toggle
							matSuffix
							[for]="startPicker"
						></mat-datepicker-toggle>
						<mat-datepicker #startPicker></mat-datepicker>
					</mat-form-field>

					<mat-form-field appearance="outline">
						<mat-label>Dropoff Date</mat-label>
						<input
							matInput
							[matDatepicker]="endPicker"
							formControlName="dropoffDate"
						/>
						<mat-datepicker-toggle
							matSuffix
							[for]="endPicker"
						></mat-datepicker-toggle>
						<mat-datepicker #endPicker></mat-datepicker>
					</mat-form-field>
				</div>
			</div>

			<!-- Pickup Location -->
			<div class="form-section">
				<h3>Pickup Location</h3>
				<p class="section-description">
					Confirm where you want to pick up the car. You can select a different
					location within the host's delivery area.
				</p>

				<!-- Location Preview Map -->
				<div class="location-map">
					<app-location-picker
						[latitude]="pickupLocation?.latitude || car.locationGeoPoint.latitude"
						[longitude]="pickupLocation?.longitude || car.locationGeoPoint.longitude"
						[editable]="true"
						[showPrivacyCircle]="false"
						(locationChanged)="setPickupLocation($event)"
						height="350px"
					>
					</app-location-picker>
				</div>

				<!-- Or Use Map Selector -->
				<button
					type="button"
					mat-stroked-button
					(click)="selectPickupLocationOnMap()"
					class="map-selector-button"
				>
					<mat-icon>map</mat-icon>
					Select Different Location
				</button>

				<!-- Selected Coordinates Display -->
				<div class="location-info" *ngIf="pickupLocation">
					<mat-list>
						<mat-list-item>
							<mat-icon matListItemIcon>location_on</mat-icon>
							<div matListItemTitle>Pickup Location</div>
							<div matListItemLine>
								{{ pickupLocation.latitude | number: '1.6-6' }}, {{
								pickupLocation.longitude | number: '1.6-6' }}
							</div>
						</mat-list-item>

						<mat-list-item *ngIf="pickupLocation.address">
							<mat-icon matListItemIcon>home</mat-icon>
							<div matListItemTitle>Address</div>
							<div matListItemLine>{{ pickupLocation.address }}</div>
						</mat-list-item>
					</mat-list>
				</div>
			</div>

			<!-- Delivery Information -->
			<div class="form-section delivery-section" *ngIf="deliveryFee !== null">
				<h3>Delivery Information</h3>
				<mat-list>
					<mat-list-item>
						<mat-icon matListItemIcon>distance</mat-icon>
						<div matListItemTitle>Delivery Distance</div>
						<div matListItemLine>
							{{ bookingForm.get('deliveryDistance')?.value | number: '1.1-1' }}
							km
						</div>
					</mat-list-item>

					<mat-list-item>
						<mat-icon matListItemIcon>attach_money</mat-icon>
						<div matListItemTitle>Delivery Fee</div>
						<div matListItemLine>
							{{ bookingForm.get('deliveryFee')?.value | currency }}
						</div>
					</mat-list-item>
				</mat-list>
			</div>

			<!-- Cost Summary -->
			<div class="cost-summary">
				<mat-list>
					<mat-list-item>
						<div>Car Rental</div>
						<div class="cost-value">
							{{ car.pricePerDay * dayCount | currency }}
						</div>
					</mat-list-item>

					<mat-list-item *ngIf="deliveryFee">
						<div>Delivery Fee</div>
						<div class="cost-value">{{ deliveryFee | currency }}</div>
					</mat-list-item>

					<mat-list-item class="total-row">
						<div><strong>Total Cost</strong></div>
						<div class="cost-value total">
							<strong>{{ totalCost | currency }}</strong>
						</div>
					</mat-list-item>
				</mat-list>
			</div>

			<!-- Terms & Conditions -->
			<div class="terms-section">
				<mat-checkbox formControlName="agreeTerms">
					I agree to the Terms of Service and understand the cancellation policy
				</mat-checkbox>
				<a href="/terms">Read our Terms</a>
			</div>
		</form>
	</mat-card-content>

	<mat-card-actions>
		<button mat-button (click)="goBack()">Cancel</button>
		<button
			mat-raised-button
			color="primary"
			[disabled]="!bookingForm.valid"
			(click)="submitBooking()"
		>
			Confirm Booking
		</button>
	</mat-card-actions>
</mat-card>
```

---

## Implementation Prerequisites & Dependencies

### 1. Backend API Versioning Strategy

⚠️ **DECISION MADE:** Using `/api/` endpoints (no version suffix). Confirm all backend controllers implement this path.

**Current State (from codebase):**
- Backend likely uses `/api/` or `/api/v2/` for existing endpoints
- New geospatial endpoints should follow same versioning strategy

**Options:**

| Option | Pros | Cons | Recommendation |
|--------|------|------|---|
| **Use `/api/` (no version)** | Backwards compatible, simpler | Mix of versions in same path | ✅ **SELECTED** |
| **Use `/api/geospatial/`** | Semantic grouping | Non-standard versioning | Use for feature-specific namespace |

**Backend Implementation (Required):**
- [x] API versioning strategy: **`/api/` (no version suffix)**
- [ ] Implement backend controller routes: `/api/cars/search`, `/api/delivery/fee/{carId}`, `/api/locations/geocode`, etc.
- [ ] Follow existing patterns in codebase for request/response DTOs

**Frontend Service Setup:**
```typescript
// location.service.ts - Service endpoints use /api/
private apiUrl = environment.apiUrl + '/api';

// Examples:
GET  ${apiUrl}/cars/search                    // Search nearby cars
GET  ${apiUrl}/delivery/fee/{carId}           // Calculate delivery fee
GET  ${apiUrl}/locations/geocode              // Geocode address
GET  ${apiUrl}/locations/reverse-geocode      // Reverse geocode coordinates
GET  ${apiUrl}/locations/pois                 // Get nearby POIs
GET  ${apiUrl}/locations/mapbox-token         // Get Mapbox token (Phase 1)
```

---

### 2. Marker Clustering Library Selection

⚠️ **DECISION NEEDED:** Roadmap mentions Supercluster, but Mapbox GL JS has built-in clustering.

**Comparison:**

| Library | Performance | Setup | Features | Recommendation |
|---------|-------------|-------|----------|---|
| **Mapbox Built-in (`cluster: true` on GeoJSON source)** | Excellent (native) | 5 lines code | Basic clustering, visual only | ✅ **USE THIS** for <500 markers |
| **@mapbox/supercluster** | Excellent | 20+ lines code | Advanced: custom aggregation, ID-based filtering | Use if custom cluster behavior needed |
| **mapbox-gl-cluster plugin** | Good | Plugin setup | Extra cluster controls | Avoid (unmaintained) |

**Recommendation: Use Mapbox Built-in Clustering (No Additional NPM Packages)**

**Implementation (Update LocationPickerComponent):**

```typescript
// REMOVE this line:
markerCluster: Supercluster<GeoLocation>;  // ❌ DELETE

// REPLACE addClusteredMarkers() with simpler approach:
private addClusteredMarkers() {
	// Mapbox built-in clustering requires GeoJSON source
	const points = this.markers.map((m) => ({
		type: "Feature",
		geometry: { type: "Point", coordinates: [m.longitude, m.latitude] },
		properties: { id: m.carId, brand: m.brand, model: m.model },
	}));

	this.map.addSource("cars", {
		type: "geojson",
		data: { type: "FeatureCollection", features: points },
		cluster: true,          // ✅ Built-in clustering
		clusterMaxZoom: 14,     // Stop clustering at zoom 14
		clusterRadius: 50,      // Cluster radius in pixels
	});

	// Layer for clusters (visual indicator)
	this.map.addLayer({
		id: "clusters",
		type: "circle",
		source: "cars",
		filter: ["has", "point_count"],
		paint: {
			"circle-color": [
				"step",
				["get", "point_count"],
				"#51bbd6", // < 100 points → light blue
				100,
				"#f1f075", // 100-750 points → yellow
				750,
				"#f28cb1", // > 750 points → pink
			],
			"circle-radius": [
				"step",
				["get", "point_count"],
				20, // Small clusters
				100,
				30, // Medium clusters
				750,
				40, // Large clusters
			],
		},
	});

	// Layer for cluster count text
	this.map.addLayer({
		id: "cluster-count",
		type: "symbol",
		source: "cars",
		filter: ["has", "point_count"],
		layout: {
			"text-field": ["get", "point_count"],
			"text-font": ["DIN Offc Pro Medium"],
			"text-size": 12,
		},
		paint: {
			"text-color": "#fff",
		},
	});

	// Layer for unclustered individual markers
	this.map.addLayer({
		id: "unclustered-point",
		type: "circle",
		source: "cars",
		filter: ["!", ["has", "point_count"]],
		paint: {
			"circle-color": "#11b4da",
			"circle-radius": 5,
			"circle-stroke-width": 2,
			"circle-stroke-color": "#fff",
		},
	});

	// Click handler: Individual marker
	this.map.on("click", "unclustered-point", (e) => {
		const carId = e.features?.[0]?.properties?.id;
		if (carId) {
			this.markerSelected.emit(carId);
		}
	});

	// Click handler: Cluster expansion (zoom in)
	this.map.on("click", "clusters", (e) => {
		const clusterId = e.features?.[0]?.properties?.cluster_id;
		this.map.getSource("cars")?.getClusterExpansionZoom(
			clusterId,
			(err, zoom) => {
				if (!err && zoom) {
					this.map.easeTo({
						center: e.lngLat,
						zoom: zoom,
					});
				}
			}
		);
	});

	// Change cursor on cluster/marker hover
	this.map.on("mouseenter", "clusters", () => {
		this.map.getCanvas().style.cursor = "pointer";
	});
	this.map.on("mouseleave", "clusters", () => {
		this.map.getCanvas().style.cursor = "";
	});
}
```

**NPM Dependencies:**
```bash
# ✅ NO ADDITIONAL PACKAGE NEEDED
# Mapbox GL JS v1.0+ includes clustering natively
# Existing dependency: @mapbox/mapbox-gl: "^2.x"
```

**If Advanced Cluster Aggregation Needed (Optional):**
```bash
# Only if you need: custom cluster properties, filtered aggregation, etc.
npm install @mapbox/supercluster
```
But for standard use case (showing car count), **built-in clustering is sufficient and faster**.

---

### 3. Mapbox Token Security

⚠️ **CRITICAL SECURITY ISSUE:** Token in environment files is exposed to client-side code.

**Current Risk:**
```typescript
// environment.ts (EXPOSED)
export const environment = {
	mapboxToken: '<MAPBOX_PUBLIC_TOKEN>'
};

// In component: Token visible to user
mapboxgl.accessToken = environment.mapboxToken; // ❌ Token visible in DevTools
```

**Attack Vectors:**
1. User opens DevTools → Network tab → sees API calls with token
2. Token extracted and used on attacker's domain
3. Attacker exhausts token quota (abuse)
4. Only fix: revoke token + redeploy (downtime)

---

**Solution 1: URL Restriction on Mapbox Dashboard (QUICK)**

⏱️ **Setup Time:** 5 minutes  
💰 **Cost:** FREE  
🛡️ **Protection:** Immediate  

**Steps:**

1. Go to https://account.mapbox.com/tokens/
2. Find and edit token `<MAPBOX_PUBLIC_TOKEN>`
3. Scroll to **"URL restrictions"** section
4. Add these URLs:
   - `https://rentoza.rs`
   - `https://www.rentoza.rs`
   - `https://*.rentoza.rs` (all subdomains)
   - `localhost` (local development)
   - `127.0.0.1` (local testing)
5. Enable **"Public scopes only"** (prevent admin API access)
6. Click **Save**

**Result:**
- Token still accessible from browser (required by Mapbox GL JS)
- But only works on your whitelisted domains
- Attacker's stolen token won't work on `evil.com`
- Zero code changes needed

---

**Solution 2: Backend Token Proxy (RECOMMENDED FOR HIGH SECURITY)**

⏱️ **Setup Time:** 1-2 hours  
💰 **Cost:** Added HTTP request  
🛡️ **Protection:** Zero client-side token exposure  

**Backend Endpoint:**

```java
// LocationController.java
@RestController
@RequestMapping("/api/locations")
public class LocationController {
    
    @Value("${rentoza.mapbox.access-token}")
    private String mapboxToken;
    
    @Value("${rentoza.mapbox.style}")
    private String mapboxStyle;
    
    // GET /api/locations/mapbox-token
    @GetMapping("/mapbox-token")
    public ResponseEntity<MapboxTokenResponse> getMapboxToken(
        @AuthenticationPrincipal UserDetails user
    ) {
        // Only return token to authenticated users
        if (user == null) {
            return ResponseEntity.status(401).build();
        }
        
        return ResponseEntity.ok(new MapboxTokenResponse(
            mapboxToken,
            mapboxStyle,
            44.8176,  // Default lat (Belgrade)
            20.4569   // Default lon
        ));
    }
}

// DTO
@Data
@NoArgsConstructor
@AllArgsConstructor
public class MapboxTokenResponse {
    private String token;
    private String style;
    private Double defaultLatitude;
    private Double defaultLongitude;
}
```

**Frontend Service:**

```typescript
// location.service.ts
export class LocationService {
    private mapboxToken: string;
    private tokenLoaded = new BehaviorSubject<boolean>(false);

    constructor(private http: HttpClient) {
        this.loadMapboxToken();
    }

    private loadMapboxToken() {
        this.http
            .get<any>("/api/locations/mapbox-token")
            .pipe(
                catchError((err) => {
                    console.error("Failed to load Mapbox token:", err);
                    // Fallback to environment token if backend fails
                    this.mapboxToken = environment.mapboxToken;
                    this.tokenLoaded.next(true);
                    return of(null);
                })
            )
            .subscribe((response) => {
                if (response) {
                    this.mapboxToken = response.token;
                }
                mapboxgl.accessToken = this.mapboxToken;
                this.tokenLoaded.next(true);
            });
    }

    // Ensure token is loaded before using maps
    isTokenReady(): Observable<boolean> {
        return this.tokenLoaded.asObservable();
    }
}
```

**LocationPickerComponent:**

```typescript
export class LocationPickerComponent implements OnInit {
    constructor(private locationService: LocationService) {}

    ngOnInit() {
        // Wait for token before initializing map
        this.locationService.isTokenReady().pipe(
            filter(ready => ready),
            take(1)
        ).subscribe(() => {
            this.initializeMap();
        });
    }
}
```

**Advantages:**
- Token never exposed to client (hidden in HTTP response body)
- Can rotate token without code redeploy (just update config)
- Can rate-limit per user (track token usage)
- Can audit who requests tokens (security logs)
- Token request requires authentication

**Disadvantages:**
- Extra HTTP request on app init (~100ms)
- Slightly more backend complexity

---

**RECOMMENDATION: Use Both (Defense-in-Depth)**

| Step | Action | Timeline | Effort |
|------|--------|----------|--------|
| 1 | **URL Restriction (Mapbox Dashboard)** | Immediate | 5 min |
| 2 | **Backend Token Proxy** | Phase 1 (Week 1) | 2 hours |
| 3 | **Environment Variable Rotation** | Production | On-demand |

**Immediate Action (Do This Today):**
1. Open https://account.mapbox.com/tokens/
2. Edit token → Add URL restrictions
3. Save (instant protection)

**Phase 1 Implementation:**
1. Create `/api/locations/mapbox-token` endpoint
2. Update LocationService to load token from backend
3. Test with URL restrictions enabled
4. Remove token from environment files
5. Deploy

---

## API Contract Requirements

### Security & Privacy Considerations

#### Geocoding Service Choice

| Provider      | Frontend | Backend                 | Rationale                                          |
| ------------- | -------- | ----------------------- | -------------------------------------------------- |
| **Mapbox**    | ✗ No     | ✅ Yes                  | Better partial query support, higher rate limits   |
| **Nominatim** | ✗ No     | ✅ Yes (migration only) | Free, but strict 1 req/sec limit, server-side only |
| **OSRM**      | ✗ No     | ✅ Yes                  | Distance/routing calculations, backend-proxied     |

#### GeoPointDTO Sync (Frontend ↔ Backend)

**Java (Backend) - GeoPoint Embeddable:**

```java
@Embeddable
public class GeoPoint {
    private BigDecimal latitude;        // -90 to +90
    private BigDecimal longitude;       // -180 to +180
    private String address;             // "Terazije 26, Beograd"
    private String city;                // "Belgrade"
    private String zipCode;             // "11000"
    private Integer accuracyMeters;     // GPS accuracy (null = unknown)
}
```

**TypeScript (Frontend) - GeoPointDTO:**

```typescript
export interface GeoPointDTO {
	latitude: number;
	longitude: number;
	address?: string;
	city?: string;
	zipCode?: string;
	accuracyMeters?: number;
}
```

✅ **Status:** Fields match exactly  
⚠️ **Note:** BigDecimal → number (JSON serialization handles conversion)  
✅ **Nullable fields:** Both use optional properties (JS `?`, Java `@Nullable`)

### Backend Endpoints Needed (From Implementation Summary)

#### Car Search Endpoint

```
GET /api/cars/search
Query Parameters:
  latitude: number (required)
  longitude: number (required)
  radiusKm: number (default: 25, max: 100)
  priceMin?: number
  priceMax?: number
  carType?: string[] (comma-separated)
  transmission?: string
  fuelType?: string
  deliveryAvailable?: boolean
  page?: number (default: 1)
  pageSize?: number (default: 20)

Response:
{
  data: [
    {
      id: string
      brand: string
      model: string
      year: number
      pricePerDay: decimal
      locationGeoPoint: {
        latitude: number
        longitude: number
        address?: string
        city: string
        zipCode?: string
        obfuscated: boolean  // true if user not booking history
      }
      distanceKm: number  // Distance from search center
      imageUrl: string
      rating: number
      reviewCount: number
      features: string[]
    }
  ],
  pagination: {
    total: number
    page: number
    pageSize: number
  }
}
```

#### Delivery Fee Endpoint

⚠️ **CRITICAL:** Backend MUST calculate delivery fees using OSRM routing (not Haversine).

```
GET /api/delivery/fee/{carId}
Query Parameters:
  pickupLatitude: number (required)
  pickupLongitude: number (required)

Response:
{
  carId: string
  carLocation: {
    latitude: number
    longitude: number
    city: string
  }
  pickupLocation: {
    latitude: number
    longitude: number
    city: string
  }
  distanceKm: double          // OSRM road distance, not Haversine
  deliveryFee: decimal        // Calculated based on distance + POI rules
  available: boolean          // Whether delivery is offered to this location
  reason?: string             // e.g., "Outside delivery radius", "Location in restricted area"
  appliedPoiCode?: string     // e.g., "NS-AIRPORT" if special POI fee applied
  poiName?: string            // e.g., "Nikola Tesla Airport"
  breakdown?: {
    baseFee: decimal          // Flat fee (if applicable)
    distanceFee: decimal      // Fee per km beyond delivery radius
    poiSurcharge: decimal     // Additional charge for POI location
  }
}
```

**Implementation (Backend):**

```java
// DeliveryFeeController.java
@GetMapping("/fee/{carId}")
public ResponseEntity<DeliveryFeeResult> calculateDeliveryFee(
    @PathVariable String carId,
    @RequestParam BigDecimal pickupLatitude,
    @RequestParam BigDecimal pickupLongitude
) {
    Car car = carRepository.findById(carId).orElseThrow();
    GeoPoint pickup = new GeoPoint(pickupLatitude, pickupLongitude);

    // 1. Check if delivery available at this location
    if (!isWithinDeliveryArea(car, pickup)) {
        return ResponseEntity.ok(DeliveryFeeResult.unavailable("Outside delivery radius"));
    }

    // 2. Calculate road distance using OSRM (not Haversine)
    double distanceKm = osrmRoutingService.calculateRouteWithFallback(
        car.getLocationGeoPoint(),
        pickup
    ).getDistanceKm();

    // 3. Check for POI surcharges
    DeliveryPoi nearbyPoi = deliveryPoiRepository.findNearby(pickup, 2.0)
        .stream()
        .max(Comparator.comparingInt(DeliveryPoi::getPriority))
        .orElse(null);

    // 4. Calculate fee using calculator
    BigDecimal fee = deliveryFeeCalculator.calculateFee(
        car, distanceKm, nearbyPoi
    );

    return ResponseEntity.ok(DeliveryFeeResult.success(
        car.getId(), car.getLocationGeoPoint(), pickup,
        distanceKm, fee, nearbyPoi
    ));
}
```

#### Geocoding Endpoint (Frontend to Backend)

⚠️ **CRITICAL:** Backend MUST use Mapbox Geocoding API, NOT Nominatim, for autocomplete queries.

```
GET /api/locations/geocode
Query Parameters:
  address: string (required)
  bias_lat?: number (optional, for relevance)
  bias_lon?: number (optional, for relevance)

Response:
{
  suggestions: [
    {
      id: string
      latitude: number
      longitude: number
      formatted_address: string    // e.g., "Terazije 26, Belgrade, Serbia"
      address: string              // e.g., "Terazije 26"
      city: string                 // e.g., "Belgrade"
      zipCode: string              // e.g., "11000"
      accuracyMeters: number       // Confidence in result
      placeType: string            // "address", "place", "region"
    }
  ]
}
```

**Implementation (Backend):**

```java
// LocationController.java
@GetMapping("/geocode")
public ResponseEntity<GeocodingResponse> geocode(
    @RequestParam String address,
    @RequestParam(required = false) BigDecimal biasLat,
    @RequestParam(required = false) BigDecimal biasLon
) {
    // Call Mapbox Geocoding API (not Nominatim)
    // Mapbox token: rentoza.mapbox.access-token property
    return mapboxGeocodingService.search(address, biasLat, biasLon);
}
```

#### Reverse Geocoding Endpoint

```
GET /api/locations/reverse-geocode
Query Parameters:
  latitude: number (required)
  longitude: number (required)

Response:
{
  formatted_address: string    // e.g., "Terazije 26, Belgrade, Serbia"
  address: string              // e.g., "Terazije 26"
  city: string                 // e.g., "Belgrade"
  zipCode: string              // e.g., "11000"
  country: string              // "Serbia"
  placeType: string            // "address", "place", "region"
}
```

**Implementation (Backend):**

```java
// LocationController.java
@GetMapping("/reverse-geocode")
public ResponseEntity<ReverseGeocodeResponse> reverseGeocode(
    @RequestParam BigDecimal latitude,
    @RequestParam BigDecimal longitude
) {
    // Call Mapbox Reverse Geocoding API
    return mapboxGeocodingService.reverseSearch(latitude, longitude);
}
```

#### POI Lookup Endpoint

```
GET /api/locations/pois
Query Parameters:
  latitude: number (required)
  longitude: number (required)
  radiusKm: number (default: 2)

Response:
{
  pois: [
    {
      id: string
      name: string
      code: string
      latitude: number
      longitude: number
      type: string
      fixedFee?: decimal
      surcharge?: decimal
    }
  ]
}
```

---

## Performance & UX Considerations

### 1. Map Performance

- **Marker Clustering:** Use clustering for >50 markers (mapbox-gl-cluster)
- **Viewport-based Loading:** Only load markers in current map view
- **Progressive Enhancement:** Load car details asynchronously on card interaction

### 2. Search Performance

- **Debounced Queries:** 500ms debounce on search parameter changes
- **Result Caching:** Cache search results for 5 minutes by coordinates
- **Pagination:** Load 20 results per page, lazy-load on scroll

### 3. Mobile Optimization

- **Responsive Layout:** Stack map below results on mobile (< 768px)
- **Touch-friendly Markers:** 40px+ marker size for touch targets
- **Location Access:** Prominent "Use Current Location" button for geo-enabled searches

### 4. Privacy & Security

- **Obfuscation Server-side:** Server decides whether to return exact or fuzzy coordinates
- **Token Rotation:** Mapbox token should not expire; implement token refresh mechanism
- **Client-side Rate Limiting:** Implement frontend throttling to prevent abuse

### 5. Offline Support

- **Search Cache:** Cache last 10 searches locally (Service Worker)
- **Pending Bookings:** Queue booking creation if offline, sync on reconnect
- **Map Tiles:** Cache map tiles for offline viewing (Mapbox GL can support this)

---

## Testing Strategy

### Unit Tests

```typescript
// location.service.spec.ts
describe("LocationService", () => {
	it("should calculate distance between two coordinates", () => {
		const belgrade = { latitude: 44.8125, longitude: 20.4612 };
		const vozdovac = { latitude: 44.7518, longitude: 20.5271 };

		const distance = service.distanceTo(belgrade, vozdovac);
		expect(distance).toBeCloseTo(8000, -2); // ~8km
	});

	it("should obfuscate coordinates within radius", () => {
		const original = { latitude: 44.8125, longitude: 20.4612 };
		const obfuscated = service.obfuscate(original, 500);

		const distance = service.distanceTo(original, obfuscated);
		expect(distance).toBeLessThan(500);
	});

	it("should validate coordinates within Serbia bounds", () => {
		expect(service.validateCoordinates(44.8, 20.4)).toBe(true);
		expect(service.validateCoordinates(50.0, 20.4)).toBe(false); // Outside Serbia
	});
});
```

### Integration Tests

```typescript
// car-search.integration.spec.ts
describe("Car Search E2E", () => {
	it("should search for nearby cars and enrich with delivery fees", fakeAsync(() => {
		const searchRequest = {
			latitude: 44.8125,
			longitude: 20.4612,
			radiusKm: 25,
		};

		component.searchNearby(searchRequest);
		tick(500); // Debounce

		httpMock.expectOne((req) => req.url.includes("/api/cars/search"));

		expect(component.searchResults.length).toBeGreaterThan(0);
		expect(component.searchResults[0].deliveryFee).toBeDefined();
	}));
});
```

### E2E Tests (Cypress/Protractor)

```typescript
// car-search.e2e.ts
describe("Car Search Page", () => {
	it("should search cars and display on map", () => {
		cy.visit("/search");
		cy.get("app-location-picker").should("be.visible");
		cy.get('[data-test="search-button"]').click();
		cy.get(".car-result-card").should("have.length.greaterThan", 0);
	});

	it("should select car and navigate to booking", () => {
		cy.get(".car-result-card").first().click();
		cy.url().should("include", "/booking/create");
	});
});
```

---

## Implementation Timeline

| Phase            | Duration  | Key Deliverables                                                |
| ---------------- | --------- | --------------------------------------------------------------- |
| **Phase 1**      | 1 week    | LocationPickerComponent enhancements, LocationService expansion |
| **Phase 2**      | 1 week    | Add Car Wizard location step, Map selector dialog               |
| **Phase 3**      | 1.5 weeks | Car Search page, Location service integration                   |
| **Phase 4**      | 1 week    | Search filters, Car result cards, Map clustering                |
| **Phase 5**      | 0.5 weeks | Booking flow integration, Delivery fee display                  |
| **Testing & QA** | 1 week    | Unit tests, integration tests, E2E tests, performance testing   |

**Total: 4-5 weeks**

---

## Success Criteria

- ✅ Hosts can add cars with precise geospatial locations
- ✅ Guests can search cars within configurable radius (5-50km)
- ✅ Map shows clustered markers for >50 results without performance issues (p99 < 500ms)
- ✅ Delivery fees calculated and displayed in search results and booking
- ✅ Privacy obfuscation working: unbooked guests see fuzzy circle, booked guests see exact location
- ✅ Mobile-friendly: responsive layout on tablets and phones
- ✅ Offline support: can view cached search results
- ✅ All E2E tests passing on Chrome, Safari, Firefox

---

## Backend Implementation Checklist (Before Frontend Starts)

**⚠️ CRITICAL:** These endpoints MUST be fully implemented and tested before frontend development begins.

### Geocoding Endpoints

- [ ] **GET /api/locations/geocode**

  - [ ] Uses **Mapbox Geocoding API** (not Nominatim)
  - [ ] Supports partial queries (e.g., "Teraz" → "Terazije")
  - [ ] Includes bias coordinates for location relevance
  - [ ] Caches results to reduce API calls
  - [ ] Returns placeType field (address/place/region)
  - [ ] Rate-limited to Mapbox plan limits

- [ ] **GET /api/locations/reverse-geocode**
  - [ ] Uses Mapbox Reverse Geocoding API
  - [ ] Returns formatted_address, city, zipCode, country
  - [ ] Handles coordinates outside Serbia gracefully
  - [ ] Caches results

### Delivery Fee Endpoints

- [ ] **GET /api/delivery/fee/{carId}**

  - [ ] Calculates distance using **OSRM routing** (not Haversine)
  - [ ] Includes OSRM fallback (1.3x Haversine multiplier)
  - [ ] Looks up nearby POIs and applies surcharges
  - [ ] Returns fee breakdown (baseFee, distanceFee, poiSurcharge)
  - [ ] Validates pickup location within Serbia bounds
  - [ ] Returns `available: false` if delivery not offered

- [ ] **GET /api/locations/pois**
  - [ ] Returns POIs near coordinates within radius
  - [ ] Ordered by priority (higher priority first)
  - [ ] Includes POI name, code, type, fees

### Car Search Endpoints

- [ ] **GET /api/cars/search**
  - [ ] Uses SPATIAL INDEX on cars table
  - [ ] Supports radiusKm parameter (5-100km)
  - [ ] Supports price, type, transmission, fuel filters
  - [ ] Returns results ordered by distance
  - [ ] **PRIVACY:** Obfuscates coordinates for unbooked guests (±500m)
  - [ ] **PRIVACY:** Shows exact coordinates only for returning customers
  - [ ] Pagination support (page, pageSize)
  - [ ] p99 latency < 200ms for 50k cars

### Data Integrity

- [ ] All cars have valid locationGeoPoint (within Serbia bounds)
- [ ] All bookings have pickupLocation snapshot (immutable)
- [ ] Geofence validation in check-in flow working
- [ ] Car.location (legacy string) still populated for backwards compatibility
- [ ] Database V23/V24 migrations completed successfully

### Testing

- [ ] Unit tests for OSRM distance vs Haversine fallback
- [ ] Integration tests for SPATIAL queries (verify coordinate order: lon, lat)
- [ ] Tests for Mapbox geocoding with partial queries
- [ ] Tests for POI lookup and fee surcharges
- [ ] Tests for privacy obfuscation (unbooked guest sees ±500m fuzzy circle)
- [ ] Load tests: 100 concurrent /cars/search requests < 200ms p99

---

**Next Steps:**

1. **Backend Team:** Complete the above checklist
2. **Frontend Team:** Begin Phase 1 (LocationPickerComponent) once endpoints are ready
3. **Joint:** Conduct API contract review (particularly coordinate order: lon,lat vs lat,lon)
4. **Frontend:** Set up Mapbox token management (environment-based rotation)
5. **QA:** Create E2E test suite for geospatial workflows
