import { Component, Input } from '@angular/core';
import { CommonModule } from '@angular/common';
import { MatCardModule } from '@angular/material/card';
import { MatIconModule } from '@angular/material/icon';

@Component({
  selector: 'app-kpi-card',
  standalone: true,
  imports: [CommonModule, MatCardModule, MatIconModule],
  template: `
    <mat-card class="surface-card surface-roomy kpi-card">
      <mat-card-content class="metric-card">
        <div class="metric-icon" [ngClass]="iconTintClass">
          <mat-icon>{{ icon }}</mat-icon>
        </div>
        <div class="stack" style="gap:4px;">
          <span class="metric-label">{{ title }}</span>
          <span class="metric-value">{{ value }}</span>
          <span *ngIf="trend" class="metric-trend" [ngClass]="trendClass">
            <mat-icon fontIcon="{{ trendIcon }}" class="trend-icon"></mat-icon>
            {{ trend }}
          </span>
        </div>
      </mat-card-content>
    </mat-card>
  `,
  styleUrls: ['../../../admin-shared.styles.scss', './kpi-card.component.scss'],
})
export class KpiCardComponent {
  @Input() title: string = '';
  @Input() value: string | number = 0;
  @Input() icon: string = 'analytics';
  @Input() iconTint: 'info' | 'success' | 'warn' | 'accent' | 'neutral' = 'info';
  @Input() trend?: string;
  @Input() trendDirection: 'up' | 'down' | 'neutral' = 'neutral';

  get trendClass(): string {
    return this.trendDirection === 'up'
      ? 'trend-up'
      : this.trendDirection === 'down'
      ? 'trend-down'
      : 'trend-neutral';
  }

  get trendIcon(): string {
    return this.trendDirection === 'up'
      ? 'arrow_upward'
      : this.trendDirection === 'down'
      ? 'arrow_downward'
      : 'remove';
  }

  get iconTintClass(): string {
    switch (this.iconTint) {
      case 'success':
        return 'icon-success';
      case 'warn':
        return 'icon-warn';
      case 'accent':
        return 'icon-accent';
      case 'neutral':
        return 'icon-neutral';
      default:
        return 'icon-info';
    }
  }
}