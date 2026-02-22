/**
 * Vehicle Wireframe Component
 *
 * Interactive SVG wireframe of a car for marking damage hotspots.
 * Used by guests to indicate pre-existing or new damage locations.
 */
import { Component, Input, Output, EventEmitter, ChangeDetectionStrategy } from '@angular/core';
import { CommonModule } from '@angular/common';
import { HotspotLocation, HotspotMarkingDTO } from '../../../core/models/check-in.model';

interface HotspotZone {
  location: HotspotLocation;
  label: string;
  path: string; // SVG path
  x: number; // Center X for label positioning
  y: number; // Center Y for label positioning
}

// SVG hotspot zones for a top-down car view
const HOTSPOT_ZONES: HotspotZone[] = [
  // Front
  {
    location: 'FRONT_BUMPER',
    label: 'Prednji branik',
    path: 'M80,20 L220,20 L210,40 L90,40 Z',
    x: 150,
    y: 30,
  },
  { location: 'HOOD', label: 'Hauba', path: 'M90,40 L210,40 L200,100 L100,100 Z', x: 150, y: 70 },
  {
    location: 'LEFT_HEADLIGHT',
    label: 'L far',
    path: 'M60,25 L80,25 L90,45 L70,45 Z',
    x: 75,
    y: 35,
  },
  {
    location: 'RIGHT_HEADLIGHT',
    label: 'D far',
    path: 'M220,25 L240,25 L230,45 L210,45 Z',
    x: 225,
    y: 35,
  },
  {
    location: 'LEFT_MIRROR',
    label: 'L retrovizor',
    path: 'M60,100 L80,100 L80,120 L60,120 Z',
    x: 70,
    y: 110,
  },
  {
    location: 'RIGHT_MIRROR',
    label: 'D retrovizor',
    path: 'M220,100 L240,100 L240,120 L220,120 Z',
    x: 230,
    y: 110,
  },
  {
    location: 'WINDSHIELD',
    label: 'Vetrobran',
    path: 'M100,100 L200,100 L195,140 L105,140 Z',
    x: 150,
    y: 120,
  },

  // Sides - Left
  {
    location: 'FRONT_LEFT_FENDER',
    label: 'L prednji blatobran',
    path: 'M60,45 L90,40 L100,100 L80,100 Z',
    x: 80,
    y: 70,
  },
  {
    location: 'LEFT_DOOR_FRONT',
    label: 'L prednja vrata',
    path: 'M60,120 L80,100 L105,140 L105,200 L60,200 Z',
    x: 82,
    y: 160,
  },
  {
    location: 'LEFT_DOOR_REAR',
    label: 'L zadnja vrata',
    path: 'M60,200 L105,200 L105,260 L80,300 L60,280 Z',
    x: 82,
    y: 240,
  },
  {
    location: 'REAR_LEFT_FENDER',
    label: 'L zadnji blatobran',
    path: 'M60,280 L80,300 L100,360 L90,360 L60,320 Z',
    x: 80,
    y: 320,
  },

  // Sides - Right
  {
    location: 'FRONT_RIGHT_FENDER',
    label: 'D prednji blatobran',
    path: 'M240,45 L210,40 L200,100 L220,100 Z',
    x: 220,
    y: 70,
  },
  {
    location: 'RIGHT_DOOR_FRONT',
    label: 'D prednja vrata',
    path: 'M240,120 L220,100 L195,140 L195,200 L240,200 Z',
    x: 218,
    y: 160,
  },
  {
    location: 'RIGHT_DOOR_REAR',
    label: 'D zadnja vrata',
    path: 'M240,200 L195,200 L195,260 L220,300 L240,280 Z',
    x: 218,
    y: 240,
  },
  {
    location: 'REAR_RIGHT_FENDER',
    label: 'D zadnji blatobran',
    path: 'M240,280 L220,300 L200,360 L210,360 L240,320 Z',
    x: 220,
    y: 320,
  },

  // Roof and interior
  {
    location: 'ROOF',
    label: 'Krov',
    path: 'M105,140 L195,140 L195,260 L105,260 Z',
    x: 150,
    y: 200,
  },

  // Rear
  {
    location: 'TRUNK',
    label: 'Gepek',
    path: 'M100,300 L200,300 L210,360 L90,360 Z',
    x: 150,
    y: 330,
  },
  {
    location: 'REAR_BUMPER',
    label: 'Zadnji branik',
    path: 'M90,360 L210,360 L220,380 L80,380 Z',
    x: 150,
    y: 370,
  },
  {
    location: 'REAR_WINDOW',
    label: 'Zadnje staklo',
    path: 'M105,260 L195,260 L200,300 L100,300 Z',
    x: 150,
    y: 280,
  },
  {
    location: 'LEFT_TAILLIGHT',
    label: 'L stop',
    path: 'M60,360 L90,360 L80,380 L60,380 Z',
    x: 75,
    y: 370,
  },
  {
    location: 'RIGHT_TAILLIGHT',
    label: 'D stop',
    path: 'M210,360 L240,360 L240,380 L220,380 Z',
    x: 225,
    y: 370,
  },

  // Wheels
  {
    location: 'WHEEL_FRONT_LEFT',
    label: 'PL točak',
    path: 'M50,60 A15,15 0 1,1 50,90 A15,15 0 1,1 50,60',
    x: 50,
    y: 75,
  },
  {
    location: 'WHEEL_FRONT_RIGHT',
    label: 'PD točak',
    path: 'M250,60 A15,15 0 1,1 250,90 A15,15 0 1,1 250,60',
    x: 250,
    y: 75,
  },
  {
    location: 'WHEEL_REAR_LEFT',
    label: 'ZL točak',
    path: 'M50,310 A15,15 0 1,1 50,340 A15,15 0 1,1 50,310',
    x: 50,
    y: 325,
  },
  {
    location: 'WHEEL_REAR_RIGHT',
    label: 'ZD točak',
    path: 'M250,310 A15,15 0 1,1 250,340 A15,15 0 1,1 250,310',
    x: 250,
    y: 325,
  },
];

@Component({
  selector: 'app-vehicle-wireframe',
  standalone: true,
  imports: [CommonModule],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="wireframe-container">
      <svg viewBox="0 0 300 400" class="wireframe-svg">
        <!-- Background -->
        <rect x="0" y="0" width="300" height="400" rx="8" />

        <!-- Car outline -->
        <path
          d="M80,20 L220,20 L240,45 L240,355 L220,380 L80,380 L60,355 L60,45 Z"
          fill="none"
          stroke-width="2"
        />

        <!-- Hotspot zones -->
        @for (zone of zones; track zone.location) {
        <g
          class="hotspot-zone"
          [class.active]="isHotspotActive(zone.location)"
          (click)="onZoneClick(zone.location)"
        >
          <path [attr.d]="zone.path" class="zone-path" />

          <!-- Active marker -->
          @if (isHotspotActive(zone.location)) {
          <circle [attr.cx]="zone.x" [attr.cy]="zone.y" r="12" class="active-marker" />
          <text [attr.x]="zone.x" [attr.y]="zone.y + 4" class="active-text">✓</text>
          }
        </g>
        }

        <!-- Labels for active zones (outside SVG click areas) -->
      </svg>

      <!-- Legend -->
      @if (hotspots.length > 0) {
      <div class="legend">
        <h4>Označena oštećenja:</h4>
        <ul>
          @for (hotspot of hotspots; track hotspot.location) {
          <li>
            <span class="dot"></span>
            {{ getZoneLabel(hotspot.location) }}
          </li>
          }
        </ul>
      </div>
      } @else {
      <p class="hint">Dodirnite deo vozila da označite oštećenje</p>
      }
    </div>
  `,
  styles: [
    `
      .wireframe-container {
        display: flex;
        flex-direction: column;
        align-items: center;
        gap: 12px;
      }

      .wireframe-svg {
        width: 100%;
        max-width: 250px;
        height: auto;
        border-radius: 12px;
        box-shadow: 0 2px 8px rgba(0, 0, 0, 0.1);
      }

      .wireframe-svg rect {
        fill: var(--color-surface-muted, #f5f5f5);
      }

      .wireframe-svg > path {
        stroke: var(--color-text-primary, #333);
      }

      .hotspot-zone {
        cursor: pointer;
        transition: all 0.2s ease;
      }

      .zone-path {
        fill: transparent;
        stroke: var(--color-border-subtle, #bdbdbd);
        stroke-width: 1;
        transition: all 0.2s ease;
      }

      .hotspot-zone:hover .zone-path {
        fill: rgba(25, 118, 210, 0.1);
        stroke: var(--brand-primary);
        stroke-width: 2;
      }

      .hotspot-zone.active .zone-path {
        fill: rgba(244, 67, 54, 0.2);
        stroke: var(--warn-color, #f44336);
        stroke-width: 2;
      }

      .active-marker {
        fill: var(--warn-color, #f44336);
        stroke: white;
        stroke-width: 2;
      }

      .active-text {
        fill: white;
        font-size: 14px;
        font-weight: bold;
        text-anchor: middle;
      }

      /* Legend */
      .legend {
        width: 100%;
        padding: 12px;
        background: var(--color-surface, #ffffff);
        border-radius: 8px;
        border: 1px solid var(--color-border-subtle, #e0e0e0);
      }

      .legend h4 {
        margin: 0 0 8px;
        font-size: 14px;
        color: var(--warn-color, #f44336);
      }

      .legend ul {
        margin: 0;
        padding: 0;
        list-style: none;
      }

      .legend li {
        display: flex;
        align-items: center;
        gap: 8px;
        padding: 4px 0;
        font-size: 13px;
        color: var(--color-text-primary, #212121);
      }

      .legend .dot {
        width: 8px;
        height: 8px;
        border-radius: 50%;
        background: var(--warn-color, #f44336);
      }

      .hint {
        margin: 0;
        font-size: 13px;
        color: var(--color-text-muted, #757575);
        text-align: center;
      }
    `,
  ],
})
export class VehicleWireframeComponent {
  @Input() hotspots: HotspotMarkingDTO[] = [];
  @Output() hotspotClicked = new EventEmitter<HotspotLocation>();

  zones = HOTSPOT_ZONES;

  isHotspotActive(location: HotspotLocation): boolean {
    return this.hotspots.some((h) => h.location === location);
  }

  getZoneLabel(location: HotspotLocation): string {
    const zone = HOTSPOT_ZONES.find((z) => z.location === location);
    return zone?.label ?? location;
  }

  onZoneClick(location: HotspotLocation): void {
    this.hotspotClicked.emit(location);
  }
}