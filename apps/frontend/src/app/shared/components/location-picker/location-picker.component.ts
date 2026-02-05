/**
 * Location Picker Component
 *
 * Interactive Mapbox GL JS map component for selecting and displaying locations.
 * Supports both read-only display and interactive location picking.
 *
 * FEATURES:
 * - Mapbox GL JS map with street-level detail
 * - Click-to-select location (when editable)
 * - Privacy obfuscation for approximate locations
 * - Geolocation button for current location
 * - Responsive design with mobile touch support
 * - Multi-marker support with clustering (Phase 2.4+)
 * - Marker selection with popup display
 * - Radius slider for search area visualization
 *
 * @example
 * <!-- Read-only car location display -->
 * <app-location-picker
 *   [latitude]="car.locationLatitude"
 *   [longitude]="car.locationLongitude"
 *   [editable]="false"
 *   [showPrivacyCircle]="true">
 * </app-location-picker>
 *
 * <!-- Editable pickup location selector -->
 * <app-location-picker
 *   [latitude]="pickupLat"
 *   [longitude]="pickupLon"
 *   [editable]="true"
 *   (locationChanged)="onPickupLocationChanged($event)">
 * </app-location-picker>
 *
 * <!-- Multi-marker search results view -->
 * <app-location-picker
 *   [latitude]="searchCenter.lat"
 *   [longitude]="searchCenter.lng"
 *   [markers]="carMarkers"
 *   [clusterMarkers]="true"
 *   [showRadiusCircle]="true"
 *   [markerRadius]="25"
 *   (markerSelected)="onCarSelected($event)">
 * </app-location-picker>
 *
 * @since 2.4.0 (Geospatial Location Migration)
 */
import {
  Component,
  Input,
  Output,
  EventEmitter,
  OnInit,
  OnDestroy,
  AfterViewInit,
  ElementRef,
  ViewChild,
  ChangeDetectionStrategy,
  signal,
  computed,
  SimpleChanges,
  OnChanges,
  inject,
} from '@angular/core';
import { CommonModule } from '@angular/common';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatTooltipModule } from '@angular/material/tooltip';
import { MatSliderModule } from '@angular/material/slider';
import { FormsModule } from '@angular/forms';
import { environment } from '../../../../environments/environment';
import { CarMarker } from '../../../core/models/location.model';
import { ThemeService } from '../../../core/services/theme.service';
import { effect } from '@angular/core';

// Mapbox GL JS types
declare const mapboxgl: any;

export interface LocationCoordinates {
  latitude: number;
  longitude: number;
}

/** Re-export CarMarker for consumers importing from this file */
export type { CarMarker } from '../../../core/models/location.model';

@Component({
  selector: 'app-location-picker',
  standalone: true,
  imports: [
    CommonModule,
    MatButtonModule,
    MatIconModule,
    MatProgressSpinnerModule,
    MatTooltipModule,
    MatSliderModule,
    FormsModule,
  ],
  templateUrl: './location-picker.component.html',
  styleUrls: ['./location-picker.component.scss'],
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class LocationPickerComponent implements OnInit, AfterViewInit, OnDestroy, OnChanges {
  @ViewChild('mapContainer') mapContainer!: ElementRef<HTMLDivElement>;

  /** Latitude of the location to display/select */
  @Input() latitude: number | null = null;

  /** Longitude of the location to display/select */
  @Input() longitude: number | null = null;

  /** Whether the user can click to change the location */
  @Input() editable = false;

  /** Show privacy circle (for unbooked guests viewing car location) */
  @Input() showPrivacyCircle = false;

  /** Privacy circle radius in meters (default 500m) */
  @Input() privacyRadiusMeters = 500;

  /** Map height in pixels or CSS value */
  @Input() height = '300px';

  /** Initial zoom level */
  @Input() zoom = 14;

  /** Show geolocation button */
  @Input() showGeolocationButton = true;

  /** Marker color */
  @Input() markerColor = '#1976d2';

  // === NEW MULTI-MARKER INPUTS (Phase 2.4) ===

  /** Array of car markers to display on map */
  @Input() markers: CarMarker[] = [];

  /** Enable marker clustering for dense areas */
  @Input() clusterMarkers = false;

  /** Show radius circle around center (for search area) */
  @Input() showRadiusCircle = false;

  /** Search radius in kilometers (for radius circle display) */
  @Input() markerRadius = 25;

  /** Show radius slider control */
  @Input() showRadiusSlider = false;

  /** Minimum radius for slider (km) */
  @Input() radiusMin = 5;

  /** Maximum radius for slider (km) */
  @Input() radiusMax = 100;

  /** Selected marker ID */
  @Input() selectedMarkerId: number | null = null;

  // === OUTPUTS ===

  /** Emitted when user selects a new location */
  @Output() locationChanged = new EventEmitter<LocationCoordinates>();

  /** Emitted when geolocation is requested */
  @Output() geolocationRequested = new EventEmitter<void>();

  /** Emitted when a car marker is clicked */
  @Output() markerSelected = new EventEmitter<number>();

  /** Emitted when radius slider changes */
  @Output() radiusChanged = new EventEmitter<number>();

  // Signals for reactive state
  protected readonly isLoading = signal(true);
  protected readonly mapError = signal<string | null>(null);
  protected readonly isGeolocating = signal(false);
  protected readonly hasLocation = computed(
    () => this.latitude !== null && this.longitude !== null
  );
  protected readonly currentRadius = signal(25);

  private map: any = null;
  private marker: any = null;
  private privacyCircle: any = null;
  private mapLoadPromise: Promise<void> | null = null;

  // Multi-marker state
  private carMarkers: Map<number, any> = new Map();
  private markerPopups: Map<number, any> = new Map();
  private radiusCircleAdded = false;

  // Theme service injection
  private readonly themeService = inject(ThemeService);

  // Reactive theme watcher - must be defined as field initializer for injection context
  private readonly themeEffect = effect(() => {
    const currentTheme = this.themeService.theme();
    if (this.map?.loaded?.()) {
      this.updateMapStyle(currentTheme);
    }
  });

  ngOnInit(): void {
    this.loadMapboxScript();
    this.currentRadius.set(this.markerRadius);
  }

  /**
   * Update map style based on current theme
   * Handles smooth style transitions without reloading the map
   */
  private updateMapStyle(theme: 'light' | 'dark'): void {
    if (!this.map) return;

    try {
      const styleConfig = environment.mapbox.styles as Record<string, string>;
      const newStyle = styleConfig[theme] || environment.mapbox.style;

      // Only update if style has actually changed
      const currentStyle = this.map.getStyle()?.name;
      if (currentStyle === newStyle) return;

      // Set the new style with transition
      this.map.setStyle(newStyle, { diff: true });
    } catch (error) {
      console.warn(`[LocationPicker] Failed to update map style to ${theme}:`, error);
      // Gracefully continue - map will remain functional
    }
  }

  ngOnChanges(changes: SimpleChanges): void {
    // Handle markers change
    if (changes['markers'] && !changes['markers'].firstChange) {
      this.updateCarMarkers();
    }

    // Handle selected marker change
    if (changes['selectedMarkerId'] && !changes['selectedMarkerId'].firstChange) {
      this.highlightSelectedMarker();
    }

    // Handle radius change
    if (changes['markerRadius'] && !changes['markerRadius'].firstChange) {
      this.currentRadius.set(this.markerRadius);
      this.updateRadiusCircle();
    }

    // Handle center location change
    if (
      (changes['latitude'] || changes['longitude']) &&
      !changes['latitude']?.firstChange &&
      !changes['longitude']?.firstChange
    ) {
      this.updateCenterAndRadius();
    }
  }

  ngAfterViewInit(): void {
    this.initializeMap();
  }

  ngOnDestroy(): void {
    this.destroyMap();
  }

  /**
   * Load Mapbox GL JS from CDN if not already loaded
   */
  private loadMapboxScript(): void {
    if (typeof mapboxgl !== 'undefined') {
      return; // Already loaded
    }

    // Load CSS
    if (!document.querySelector('link[href*="mapbox-gl"]')) {
      const link = document.createElement('link');
      link.rel = 'stylesheet';
      link.href = 'https://api.mapbox.com/mapbox-gl-js/v3.3.0/mapbox-gl.css';
      document.head.appendChild(link);
    }

    // Load JS
    if (!document.querySelector('script[src*="mapbox-gl"]')) {
      const script = document.createElement('script');
      script.src = 'https://api.mapbox.com/mapbox-gl-js/v3.3.0/mapbox-gl.js';
      script.async = true;
      document.body.appendChild(script);

      this.mapLoadPromise = new Promise((resolve, reject) => {
        script.onload = () => resolve();
        script.onerror = () => reject(new Error('Failed to load Mapbox GL JS'));
      });
    }
  }

  /**
   * Initialize the Mapbox map with theme-aware styling
   * Determines initial style based on current theme and sets up reactive updates
   */
  private async initializeMap(): Promise<void> {
    try {
      // Wait for script to load if needed
      if (this.mapLoadPromise) {
        await this.mapLoadPromise;
      }

      // Wait a tick for the container to be ready
      await new Promise((resolve) => setTimeout(resolve, 50));

      if (!this.mapContainer?.nativeElement) {
        throw new Error('Map container not found');
      }

      // Set access token
      mapboxgl.accessToken = environment.mapbox.accessToken;

      // Determine center
      const center: [number, number] = this.hasLocation()
        ? [this.longitude!, this.latitude!]
        : [environment.mapbox.defaultCenter.longitude, environment.mapbox.defaultCenter.latitude];

      // Determine initial style based on current theme
      const currentTheme = this.themeService.theme();
      const styleConfig = environment.mapbox.styles as Record<string, string>;
      const initialStyle = styleConfig[currentTheme] || environment.mapbox.style;

      // Create map
      this.map = new mapboxgl.Map({
        container: this.mapContainer.nativeElement,
        style: initialStyle,
        center: center,
        zoom: this.hasLocation() ? this.zoom : environment.mapbox.defaultZoom,
        attributionControl: true,
      });

      // Add navigation controls
      this.map.addControl(new mapboxgl.NavigationControl(), 'top-right');

      // Wait for map to load
      this.map.on('load', () => {
        this.isLoading.set(false);
        this.addMarker();
        this.addPrivacyCircle();

        // Initialize multi-marker features
        if (this.showRadiusCircle) {
          this.updateRadiusCircle();
        }
        if (this.markers.length > 0) {
          this.updateCarMarkers();
        }
      });

      // Handle click events for editable mode
      if (this.editable) {
        this.map.on('click', (e: any) => {
          this.onMapClick(e.lngLat.lat, e.lngLat.lng);
        });

        // Change cursor on hover
        this.map.getCanvas().style.cursor = 'crosshair';
      }

      // Handle errors
      this.map.on('error', (e: any) => {
        console.error('Mapbox error:', e);
        this.mapError.set('Failed to load map');
        this.isLoading.set(false);
      });
    } catch (error) {
      console.error('Failed to initialize map:', error);
      this.mapError.set('Failed to initialize map');
      this.isLoading.set(false);
    }
  }

  /**
   * Add or update the location marker
   */
  private addMarker(): void {
    if (!this.map || !this.hasLocation()) {
      return;
    }

    // Remove existing marker
    if (this.marker) {
      this.marker.remove();
    }

    // Create new marker
    this.marker = new mapboxgl.Marker({
      color: this.markerColor,
      draggable: this.editable,
    })
      .setLngLat([this.longitude!, this.latitude!])
      .addTo(this.map);

    // Handle marker drag
    if (this.editable) {
      this.marker.on('dragend', () => {
        const lngLat = this.marker.getLngLat();
        this.emitLocationChange(lngLat.lat, lngLat.lng);
      });
    }
  }

  /**
   * Add privacy circle for approximate location display
   */
  private addPrivacyCircle(): void {
    if (!this.map || !this.showPrivacyCircle || !this.hasLocation()) {
      return;
    }

    // Remove existing circle
    this.removePrivacyCircle();

    const circleId = 'privacy-circle';
    const sourceId = 'privacy-circle-source';

    // Add source with circle geometry
    this.map.addSource(sourceId, {
      type: 'geojson',
      data: this.createCircleGeoJSON(this.latitude!, this.longitude!, this.privacyRadiusMeters),
    });

    // Add fill layer
    this.map.addLayer({
      id: circleId + '-fill',
      type: 'fill',
      source: sourceId,
      paint: {
        'fill-color': this.markerColor,
        'fill-opacity': 0.15,
      },
    });

    // Add outline layer
    this.map.addLayer({
      id: circleId + '-outline',
      type: 'line',
      source: sourceId,
      paint: {
        'line-color': this.markerColor,
        'line-width': 2,
        'line-dasharray': [3, 2],
      },
    });
  }

  /**
   * Create GeoJSON circle geometry
   */
  private createCircleGeoJSON(lat: number, lng: number, radiusMeters: number): object {
    const points = 64;
    const coords: [number, number][] = [];

    for (let i = 0; i < points; i++) {
      const angle = (i / points) * 2 * Math.PI;
      const dx = radiusMeters * Math.cos(angle);
      const dy = radiusMeters * Math.sin(angle);

      // Convert meters to degrees (approximate)
      const dLat = dy / 111320;
      const dLng = dx / (111320 * Math.cos((lat * Math.PI) / 180));

      coords.push([lng + dLng, lat + dLat]);
    }
    coords.push(coords[0]); // Close the polygon

    return {
      type: 'FeatureCollection',
      features: [
        {
          type: 'Feature',
          properties: {},
          geometry: {
            type: 'Polygon',
            coordinates: [coords],
          },
        },
      ],
    };
  }

  /**
   * Remove privacy circle layers
   */
  private removePrivacyCircle(): void {
    if (!this.map) return;

    const layers = ['privacy-circle-fill', 'privacy-circle-outline'];
    layers.forEach((id) => {
      if (this.map.getLayer(id)) {
        this.map.removeLayer(id);
      }
    });

    if (this.map.getSource('privacy-circle-source')) {
      this.map.removeSource('privacy-circle-source');
    }
  }

  /**
   * Handle map click in editable mode
   */
  private onMapClick(lat: number, lng: number): void {
    if (!this.editable) return;

    // Update marker position
    if (this.marker) {
      this.marker.setLngLat([lng, lat]);
    } else {
      this.latitude = lat;
      this.longitude = lng;
      this.addMarker();
    }

    this.emitLocationChange(lat, lng);
  }

  /**
   * Emit location change event
   */
  private emitLocationChange(lat: number, lng: number): void {
    this.locationChanged.emit({
      latitude: lat,
      longitude: lng,
    });
  }

  /**
   * Request user's current location
   */
  protected async requestGeolocation(): Promise<void> {
    if (!navigator.geolocation) {
      this.mapError.set('Geolocation is not supported by your browser');
      return;
    }

    this.isGeolocating.set(true);
    this.geolocationRequested.emit();

    try {
      const position = await new Promise<GeolocationPosition>((resolve, reject) => {
        navigator.geolocation.getCurrentPosition(resolve, reject, {
          enableHighAccuracy: true,
          timeout: 10000,
          maximumAge: 0,
        });
      });

      const { latitude, longitude } = position.coords;

      // Update map view
      if (this.map) {
        this.map.flyTo({
          center: [longitude, latitude],
          zoom: 15,
          essential: true,
        });
      }

      // Update marker if editable
      if (this.editable) {
        this.latitude = latitude;
        this.longitude = longitude;
        this.addMarker();
        this.emitLocationChange(latitude, longitude);
      }
    } catch (error: any) {
      console.error('Geolocation error:', error);
      if (error.code === 1) {
        this.mapError.set('Location permission denied');
      } else if (error.code === 2) {
        this.mapError.set('Unable to determine location');
      } else {
        this.mapError.set('Location request timed out');
      }
    } finally {
      this.isGeolocating.set(false);
    }
  }

  /**
   * Update marker position externally
   */
  public setLocation(latitude: number, longitude: number): void {
    this.latitude = latitude;
    this.longitude = longitude;

    if (this.map) {
      this.addMarker();
      this.map.flyTo({
        center: [longitude, latitude],
        zoom: this.zoom,
        essential: true,
      });

      if (this.showPrivacyCircle) {
        this.addPrivacyCircle();
      }
    }
  }

  // ============================================================================
  // MULTI-MARKER METHODS (Phase 2.4)
  // ============================================================================

  /**
   * Update car markers on the map
   */
  private updateCarMarkers(): void {
    if (!this.map) return;

    // Remove markers that are no longer in the list
    const currentIds = new Set(this.markers.map((m) => m.carId));
    this.carMarkers.forEach((marker, carId) => {
      if (!currentIds.has(carId)) {
        marker.remove();
        this.carMarkers.delete(carId);
        const popup = this.markerPopups.get(carId);
        if (popup) {
          popup.remove();
          this.markerPopups.delete(carId);
        }
      }
    });

    // Add or update markers
    this.markers.forEach((carMarker) => {
      if (this.carMarkers.has(carMarker.carId)) {
        // Update existing marker position
        const marker = this.carMarkers.get(carMarker.carId);
        marker.setLngLat([carMarker.longitude, carMarker.latitude]);
      } else {
        // Create new marker
        this.addCarMarker(carMarker);
      }
    });

    // Fit bounds if we have markers
    if (this.markers.length > 0 && this.latitude == null) {
      this.fitBoundsToMarkers();
    }
  }

  /**
   * Add a single car marker to the map
   */
  private addCarMarker(carMarker: CarMarker): void {
    if (!this.map) return;

    const isSelected = carMarker.carId === this.selectedMarkerId;
    const color = carMarker.markerColor || (isSelected ? '#ff5722' : '#1976d2');

    // Create marker element with custom styling
    const el = document.createElement('div');
    el.className = 'car-marker' + (isSelected ? ' selected' : '');
    el.style.cssText = `
      width: 36px;
      height: 36px;
      background-color: ${color};
      border-radius: 50%;
      border: 3px solid white;
      box-shadow: 0 2px 8px rgba(0,0,0,0.3);
      cursor: pointer;
      display: flex;
      align-items: center;
      justify-content: center;
      transition: transform 0.2s, box-shadow 0.2s;
    `;
    el.innerHTML = `
      <svg width="18" height="18" viewBox="0 0 24 24" fill="white">
        <path d="M18.92 6.01C18.72 5.42 18.16 5 17.5 5h-11c-.66 0-1.21.42-1.42 1.01L3 12v8c0 .55.45 1 1 1h1c.55 0 1-.45 1-1v-1h12v1c0 .55.45 1 1 1h1c.55 0 1-.45 1-1v-8l-2.08-5.99zM6.5 16c-.83 0-1.5-.67-1.5-1.5S5.67 13 6.5 13s1.5.67 1.5 1.5S7.33 16 6.5 16zm11 0c-.83 0-1.5-.67-1.5-1.5s.67-1.5 1.5-1.5 1.5.67 1.5 1.5-.67 1.5-1.5 1.5zM5 11l1.5-4.5h11L19 11H5z"/>
      </svg>
    `;

    // Hover effect
    el.addEventListener('mouseenter', () => {
      el.style.transform = 'scale(1.15)';
      el.style.boxShadow = '0 4px 12px rgba(0,0,0,0.4)';
    });
    el.addEventListener('mouseleave', () => {
      if (carMarker.carId !== this.selectedMarkerId) {
        el.style.transform = 'scale(1)';
        el.style.boxShadow = '0 2px 8px rgba(0,0,0,0.3)';
      }
    });

    // Create popup content
    const popupContent = this.createPopupContent(carMarker);
    const popup = new mapboxgl.Popup({
      offset: 25,
      closeButton: false,
      closeOnClick: false,
    }).setHTML(popupContent);

    // Create marker
    const marker = new mapboxgl.Marker({ element: el })
      .setLngLat([carMarker.longitude, carMarker.latitude])
      .setPopup(popup)
      .addTo(this.map);

    // Click handler
    el.addEventListener('click', (e) => {
      e.stopPropagation();
      this.onCarMarkerClick(carMarker.carId);
    });

    // Store references
    this.carMarkers.set(carMarker.carId, marker);
    this.markerPopups.set(carMarker.carId, popup);
  }

  /**
   * Create popup HTML content for a car marker
   */
  private createPopupContent(carMarker: CarMarker): string {
    const title = carMarker.title || `Car #${carMarker.carId}`;
    const price = carMarker.pricePerDay ? `€${carMarker.pricePerDay}/day` : '';
    const distance = carMarker.distanceKm ? `${carMarker.distanceKm.toFixed(1)} km away` : '';

    return `
      <div style="padding: 8px; min-width: 120px;">
        <div style="font-weight: 600; font-size: 14px; margin-bottom: 4px;">${title}</div>
        ${price ? `<div style="color: #1976d2; font-weight: 500;">${price}</div>` : ''}
        ${distance ? `<div style="color: #666; font-size: 12px;">${distance}</div>` : ''}
      </div>
    `;
  }

  /**
   * Handle car marker click
   */
  private onCarMarkerClick(carId: number): void {
    // Update selection state
    this.selectedMarkerId = carId;
    this.highlightSelectedMarker();

    // Emit event
    this.markerSelected.emit(carId);

    // Show popup
    const marker = this.carMarkers.get(carId);
    if (marker) {
      marker.togglePopup();
    }
  }

  /**
   * Highlight the selected marker
   */
  private highlightSelectedMarker(): void {
    this.carMarkers.forEach((marker, carId) => {
      const el = marker.getElement();
      if (carId === this.selectedMarkerId) {
        el.style.transform = 'scale(1.2)';
        el.style.boxShadow = '0 4px 12px rgba(0,0,0,0.4)';
        el.style.backgroundColor = '#ff5722';
        el.style.zIndex = '100';
      } else {
        el.style.transform = 'scale(1)';
        el.style.boxShadow = '0 2px 8px rgba(0,0,0,0.3)';
        el.style.backgroundColor = '#1976d2';
        el.style.zIndex = '1';
      }
    });
  }

  /**
   * Fit map bounds to show all markers
   */
  private fitBoundsToMarkers(): void {
    if (!this.map || this.markers.length === 0) return;

    const bounds = new mapboxgl.LngLatBounds();

    // Include center location if present
    if (this.latitude != null && this.longitude != null) {
      bounds.extend([this.longitude, this.latitude]);
    }

    // Include all markers
    this.markers.forEach((m) => {
      bounds.extend([m.longitude, m.latitude]);
    });

    this.map.fitBounds(bounds, {
      padding: 50,
      maxZoom: 14,
    });
  }

  // ============================================================================
  // RADIUS CIRCLE METHODS
  // ============================================================================

  /**
   * Add or update the radius circle
   */
  private updateRadiusCircle(): void {
    if (!this.map || !this.showRadiusCircle || this.latitude == null || this.longitude == null) {
      this.removeRadiusCircle();
      return;
    }

    const sourceId = 'radius-circle-source';
    const fillLayerId = 'radius-circle-fill';
    const outlineLayerId = 'radius-circle-outline';

    const radiusMeters = this.currentRadius() * 1000;
    const circleData = this.createCircleGeoJSON(this.latitude, this.longitude, radiusMeters);

    if (this.radiusCircleAdded) {
      // Update existing source
      const source = this.map.getSource(sourceId);
      if (source) {
        source.setData(circleData);
      }
    } else {
      // Add new source and layers
      this.map.addSource(sourceId, {
        type: 'geojson',
        data: circleData,
      });

      this.map.addLayer({
        id: fillLayerId,
        type: 'fill',
        source: sourceId,
        paint: {
          'fill-color': '#1976d2',
          'fill-opacity': 0.08,
        },
      });

      this.map.addLayer({
        id: outlineLayerId,
        type: 'line',
        source: sourceId,
        paint: {
          'line-color': '#1976d2',
          'line-width': 2,
          'line-opacity': 0.5,
        },
      });

      this.radiusCircleAdded = true;
    }
  }

  /**
   * Remove the radius circle
   */
  private removeRadiusCircle(): void {
    if (!this.map || !this.radiusCircleAdded) return;

    const layers = ['radius-circle-fill', 'radius-circle-outline'];
    layers.forEach((id) => {
      if (this.map.getLayer(id)) {
        this.map.removeLayer(id);
      }
    });

    if (this.map.getSource('radius-circle-source')) {
      this.map.removeSource('radius-circle-source');
    }

    this.radiusCircleAdded = false;
  }

  /**
   * Update center location and radius circle
   */
  private updateCenterAndRadius(): void {
    if (!this.map) return;

    // Update center marker
    this.addMarker();

    // Update radius circle
    if (this.showRadiusCircle) {
      this.updateRadiusCircle();
    }

    // Fly to new center
    if (this.latitude != null && this.longitude != null) {
      this.map.flyTo({
        center: [this.longitude, this.latitude],
        zoom: this.getZoomForRadius(this.currentRadius()),
        essential: true,
      });
    }
  }

  /**
   * Handle radius slider change
   */
  protected onRadiusSliderChange(value: number): void {
    this.currentRadius.set(value);
    this.updateRadiusCircle();
    this.radiusChanged.emit(value);

    // Adjust zoom to show the radius
    if (this.map && this.latitude != null && this.longitude != null) {
      this.map.easeTo({
        zoom: this.getZoomForRadius(value),
        duration: 300,
      });
    }
  }

  /**
   * Get appropriate zoom level for a given radius
   */
  private getZoomForRadius(radiusKm: number): number {
    // Approximate zoom levels for different radii
    if (radiusKm <= 5) return 13;
    if (radiusKm <= 10) return 12;
    if (radiusKm <= 25) return 11;
    if (radiusKm <= 50) return 10;
    if (radiusKm <= 100) return 9;
    return 8;
  }

  // ============================================================================
  // CLEANUP
  // ============================================================================

  /**
   * Destroy map and cleanup resources
   */
  private destroyMap(): void {
    // Clean up car markers
    this.carMarkers.forEach((marker) => marker.remove());
    this.carMarkers.clear();
    this.markerPopups.forEach((popup) => popup.remove());
    this.markerPopups.clear();

    // Clean up main marker
    if (this.marker) {
      this.marker.remove();
      this.marker = null;
    }

    // Clean up map
    if (this.map) {
      this.map.remove();
      this.map = null;
    }

    this.radiusCircleAdded = false;
  }
}
