import { Component, signal, output } from '@angular/core';
import { MatButtonToggleChange, MatButtonToggleModule } from '@angular/material/button-toggle';

export interface TimeRange {
  start: Date;
  end: Date;
  label: string;
}

@Component({
  selector: 'app-time-range-selector',
  standalone: true,
  imports: [MatButtonToggleModule],
  template: `
    <mat-button-toggle-group
      [value]="selectedRange()"
      (change)="onRangeChange($event)"
      class="time-range-group"
    >
      <mat-button-toggle value="7d">7D</mat-button-toggle>
      <mat-button-toggle value="30d">30D</mat-button-toggle>
      <mat-button-toggle value="90d">90D</mat-button-toggle>
      <mat-button-toggle value="1y">1Y</mat-button-toggle>
    </mat-button-toggle-group>
  `,
  styles: [
    `
      .time-range-group {
        border-radius: 8px;
        overflow: hidden;
      }

      :host ::ng-deep .mat-button-toggle-group {
        border: 1px solid rgba(255, 255, 255, 0.1);
        border-radius: 8px;
      }

      :host ::ng-deep .mat-button-toggle {
        font-size: 12px;
        font-weight: 600;
        letter-spacing: 0.02em;
      }

      :host ::ng-deep .mat-button-toggle-checked {
        background: rgba(89, 60, 251, 0.12);
        color: var(--color-primary, #593cfb);
      }
    `,
  ],
})
export class TimeRangeSelectorComponent {
  selectedRange = signal('30d');
  rangeChange = output<TimeRange>();

  onRangeChange(event: MatButtonToggleChange): void {
    this.selectedRange.set(event.value);
    const end = new Date();
    const start = new Date();
    const daysMap: Record<string, number> = { '7d': 7, '30d': 30, '90d': 90, '1y': 365 };
    const days = daysMap[event.value] ?? 30;
    start.setDate(start.getDate() - days);
    this.rangeChange.emit({ start, end, label: event.value });
  }
}
