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
} from '@angular/core';
import { CommonModule } from '@angular/common';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatTooltipModule } from '@angular/material/tooltip';
import { environment } from '../../../../environments/environment';

// Mapbox GL JS types
declare const mapboxgl: any;

export interface LocationCoordinates {
  latitude: number;
  longitude: number;
}

@Component({
  selector: 'app-location-picker',
  standalone: true,
  imports: [
    CommonModule,
    MatButtonModule,
    MatIconModule,
    MatProgressSpinnerModule,
    MatTooltipModule,
  ],
  templateUrl: './location-picker.component.html',
  styleUrls: ['./location-picker.component.scss'],
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class LocationPickerComponent implements OnInit, AfterViewInit, OnDestroy {
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

  /** Emitted when user selects a new location */
  @Output() locationChanged = new EventEmitter<LocationCoordinates>();

  /** Emitted when geolocation is requested */
  @Output() geolocationRequested = new EventEmitter<void>();

  // Signals for reactive state
  protected readonly isLoading = signal(true);
  protected readonly mapError = signal<string | null>(null);
  protected readonly isGeolocating = signal(false);
  protected readonly hasLocation = computed(
    () => this.latitude !== null && this.longitude !== null
  );

  private map: any = null;
  private marker: any = null;
  private privacyCircle: any = null;
  private mapLoadPromise: Promise<void> | null = null;

  ngOnInit(): void {
    this.loadMapboxScript();
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
   * Initialize the Mapbox map
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

      // Create map
      this.map = new mapboxgl.Map({
        container: this.mapContainer.nativeElement,
        style: environment.mapbox.style,
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

  /**
   * Destroy map and cleanup resources
   */
  private destroyMap(): void {
    if (this.marker) {
      this.marker.remove();
      this.marker = null;
    }

    if (this.map) {
      this.map.remove();
      this.map = null;
    }
  }
}
