import { Component, Input } from '@angular/core';
import { CommonModule } from '@angular/common';
import { MatCardModule } from '@angular/material/card';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressBarModule } from '@angular/material/progress-bar';

export interface RiskFactor {
  name: string;
  points: number;
  category: 'Compliance' | 'Identity' | 'Behavioral' | 'Account';
  isNegative: boolean; // true if reduces risk (good)
}

@Component({
  selector: 'app-risk-score-card',
  standalone: true,
  imports: [
    CommonModule,
    MatCardModule,
    MatIconModule,
    MatProgressBarModule,
  ],
  template: `
    <mat-card class="risk-card" [ngClass]="'risk-' + riskLevel.toLowerCase()">
      <!-- Header -->
      <div class="card-header">
        <div class="header-left">
          <mat-icon class="risk-icon">{{ getRiskIcon() }}</mat-icon>
          <div>
            <h3 class="card-title">Risk Assessment</h3>
            <p class="card-subtitle" *ngIf="calculatedAt">
              Last calculated: {{ calculatedAt | date:'short' }}
            </p>
          </div>
        </div>
      </div>

      <!-- Score Display -->
      <div class="score-section">
        <div class="score-display">
          <span class="score-number">{{ totalScore }}</span>
          <span class="score-max">/100</span>
        </div>
        <div class="score-level" [ngClass]="'level-' + riskLevel.toLowerCase()">
          {{ getRiskLevelDisplay() }}
        </div>
      </div>

      <!-- Progress Bar -->
      <div class="progress-section">
        <mat-progress-bar 
          mode="determinate" 
          [value]="totalScore"
          [ngClass]="'bar-' + riskLevel.toLowerCase()"
        ></mat-progress-bar>
      </div>

      <!-- Risk Factors Breakdown -->
      <div class="factors-section">
        <h4 class="factors-title">Contributing Factors</h4>
        <div class="factors-list">
          <!-- Positive factors (risk reducers) -->
          <div class="factors-group" *ngIf="positiveFactors.length > 0">
            <p class="group-label positive-label">✅ Positive Indicators</p>
            <div *ngFor="let factor of positiveFactors" class="factor-item positive">
              <div class="factor-content">
                <span class="factor-name">{{ factor.name }}</span>
                <span class="factor-category">{{ factor.category }}</span>
              </div>
              <div class="factor-points negative-points">-{{ Math.abs(factor.points) }} pts</div>
            </div>
          </div>

          <!-- Negative factors (risk increasers) -->
          <div class="factors-group" *ngIf="negativeFactors.length > 0">
            <p class="group-label risk-label">⚠️ Risk Factors</p>
            <div *ngFor="let factor of negativeFactors.slice(0, 6)" class="factor-item negative">
              <div class="factor-content">
                <span class="factor-name">{{ factor.name }}</span>
                <span class="factor-category">{{ factor.category }}</span>
              </div>
              <div class="factor-points positive-points">+{{ factor.points }} pts</div>
            </div>
            <div *ngIf="negativeFactors.length > 6" class="more-factors">
              +{{ negativeFactors.length - 6 }} more risk {{ negativeFactors.length - 6 === 1 ? 'factor' : 'factors' }}
            </div>
          </div>

          <!-- No factors -->
          <div *ngIf="factors.length === 0" class="no-factors">
            <mat-icon>check_circle</mat-icon>
            <p>No risk factors identified. Account in good standing.</p>
          </div>
        </div>
      </div>

      <!-- Action Recommendations -->
      <div class="action-section" [ngClass]="'action-' + riskLevel.toLowerCase()">
        <mat-icon class="action-icon">{{ getActionIcon() }}</mat-icon>
        <p class="action-label">{{ getActionRecommendation() }}</p>
      </div>
    </mat-card>
  `,
  styles: [`
    .risk-card {
      border-left: 4px solid;
      padding: 24px;
      position: relative;

      &.risk-low {
        border-left-color: #10b981;
        background: rgba(16, 185, 129, 0.03);
      }

      &.risk-medium {
        border-left-color: #f59e0b;
        background: rgba(245, 158, 11, 0.03);
      }

      &.risk-high {
        border-left-color: #ef4444;
        background: rgba(239, 68, 68, 0.03);
      }

      &.risk-critical {
        border-left-color: #8b5cf6;
        background: rgba(139, 92, 246, 0.05);
      }
    }

    .card-header {
      display: flex;
      justify-content: space-between;
      align-items: flex-start;
      margin-bottom: 24px;
    }

    .header-left {
      display: flex;
      gap: 16px;
      align-items: flex-start;
    }

    .risk-icon {
      font-size: 36px;
      width: 36px;
      height: 36px;
      opacity: 0.8;
    }

    .card-title {
      margin: 0 0 4px 0;
      font-size: 20px;
      font-weight: 700;
      color: var(--color-text-primary);
    }

    .card-subtitle {
      margin: 0;
      font-size: 13px;
      color: var(--color-text-muted);
    }

    .score-section {
      display: flex;
      align-items: center;
      gap: 24px;
      margin-bottom: 20px;
    }

    .score-display {
      display: flex;
      align-items: baseline;
      gap: 4px;
    }

    .score-number {
      font-size: 48px;
      font-weight: 800;
      line-height: 1;
      color: var(--color-text-primary);
    }

    .score-max {
      font-size: 24px;
      font-weight: 600;
      color: var(--color-text-muted);
    }

    .score-level {
      padding: 8px 16px;
      border-radius: 20px;
      font-size: 14px;
      font-weight: 700;
      text-transform: uppercase;
      letter-spacing: 0.5px;

      &.level-low {
        background: #10b981;
        color: white;
      }

      &.level-medium {
        background: #f59e0b;
        color: white;
      }

      &.level-high {
        background: #ef4444;
        color: white;
      }

      &.level-critical {
        background: #8b5cf6;
        color: white;
      }
    }

    .progress-section {
      margin-bottom: 28px;

      ::ng-deep mat-progress-bar {
        height: 10px;
        border-radius: 5px;

        &.bar-low .mdc-linear-progress__bar-inner {
          border-color: #10b981 !important;
        }

        &.bar-medium .mdc-linear-progress__bar-inner {
          border-color: #f59e0b !important;
        }

        &.bar-high .mdc-linear-progress__bar-inner {
          border-color: #ef4444 !important;
        }

        &.bar-critical .mdc-linear-progress__bar-inner {
          border-color: #8b5cf6 !important;
        }
      }
    }

    .factors-section {
      margin-bottom: 24px;
    }

    .factors-title {
      margin: 0 0 16px 0;
      font-size: 15px;
      font-weight: 700;
      color: var(--color-text-primary);
      text-transform: uppercase;
      letter-spacing: 0.5px;
    }

    .factors-list {
      display: flex;
      flex-direction: column;
      gap: 20px;
    }

    .factors-group {
      display: flex;
      flex-direction: column;
      gap: 8px;
    }

    .group-label {
      margin: 0 0 8px 0;
      font-size: 13px;
      font-weight: 700;
      text-transform: uppercase;
      letter-spacing: 0.5px;

      &.positive-label {
        color: #10b981;
      }

      &.risk-label {
        color: #ef4444;
      }
    }

    .factor-item {
      display: flex;
      justify-content: space-between;
      align-items: center;
      padding: 12px 16px;
      border-radius: 10px;
      transition: all 150ms ease;

      &.positive {
        background: rgba(16, 185, 129, 0.08);
        border-left: 3px solid #10b981;

        &:hover {
          background: rgba(16, 185, 129, 0.12);
        }
      }

      &.negative {
        background: rgba(239, 68, 68, 0.08);
        border-left: 3px solid #ef4444;

        &:hover {
          background: rgba(239, 68, 68, 0.12);
        }
      }
    }

    .factor-content {
      display: flex;
      flex-direction: column;
      gap: 4px;
    }

    .factor-name {
      font-size: 14px;
      font-weight: 600;
      color: var(--color-text-primary);
    }

    .factor-category {
      font-size: 11px;
      text-transform: uppercase;
      letter-spacing: 0.5px;
      color: var(--color-text-muted);
      font-weight: 600;
    }

    .factor-points {
      font-size: 16px;
      font-weight: 800;
      white-space: nowrap;

      &.positive-points {
        color: #ef4444;
      }

      &.negative-points {
        color: #10b981;
      }
    }

    .more-factors {
      padding: 8px 16px;
      text-align: center;
      font-size: 13px;
      color: var(--color-text-muted);
      font-style: italic;
    }

    .no-factors {
      display: flex;
      flex-direction: column;
      align-items: center;
      gap: 12px;
      padding: 32px;
      text-align: center;

      mat-icon {
        font-size: 48px;
        width: 48px;
        height: 48px;
        color: #10b981;
      }

      p {
        margin: 0;
        color: var(--color-text-muted);
      }
    }

    .action-section {
      display: flex;
      align-items: center;
      gap: 12px;
      padding: 16px;
      border-radius: 12px;
      border: 2px solid;

      &.action-low {
        border-color: #10b981;
        background: rgba(16, 185, 129, 0.08);

        .action-icon {
          color: #10b981;
        }
      }

      &.action-medium {
        border-color: #f59e0b;
        background: rgba(245, 158, 11, 0.08);

        .action-icon {
          color: #f59e0b;
        }
      }

      &.action-high {
        border-color: #ef4444;
        background: rgba(239, 68, 68, 0.08);

        .action-icon {
          color: #ef4444;
        }
      }

      &.action-critical {
        border-color: #8b5cf6;
        background: rgba(139, 92, 246, 0.08);

        .action-icon {
          color: #8b5cf6;
        }
      }
    }

    .action-icon {
      font-size: 24px;
      width: 24px;
      height: 24px;
    }

    .action-label {
      margin: 0;
      font-size: 14px;
      font-weight: 600;
      color: var(--color-text-primary);
      line-height: 1.4;
    }
  `]
})
export class RiskScoreCardComponent {
  @Input() totalScore: number = 0;
  @Input() riskLevel: string = 'LOW';
  @Input() factors: RiskFactor[] = [];
  @Input() calculatedAt?: Date | string;

  // Expose Math for template
  Math = Math;

  get positiveFactors(): RiskFactor[] {
    return this.factors.filter(f => f.isNegative);
  }

  get negativeFactors(): RiskFactor[] {
    return this.factors.filter(f => !f.isNegative);
  }

  getRiskIcon(): string {
    const level = this.riskLevel.toLowerCase();
    switch (level) {
      case 'low': return 'check_circle';
      case 'medium': return 'warning';
      case 'high': return 'error';
      case 'critical': return 'block';
      default: return 'help';
    }
  }

  getRiskLevelDisplay(): string {
    const level = this.riskLevel.toLowerCase();
    switch (level) {
      case 'low': return '🟢 Low Risk';
      case 'medium': return '🟡 Medium Risk';
      case 'high': return '🔴 High Risk';
      case 'critical': return '⛔ Critical Risk';
      default: return 'Unknown';
    }
  }

  getActionIcon(): string {
    const level = this.riskLevel.toLowerCase();
    switch (level) {
      case 'low': return 'verified_user';
      case 'medium': return 'visibility';
      case 'high': return 'flag';
      case 'critical': return 'emergency';
      default: return 'info';
    }
  }

  getActionRecommendation(): string {
    const level = this.riskLevel.toLowerCase();
    switch (level) {
      case 'low':
        return 'AUTO-APPROVE: Account in good standing. No restrictions needed.';
      case 'medium':
        return 'STANDARD PROCESS: Standard approval required. Monitor activity.';
      case 'high':
        return 'MANUAL REVIEW REQUIRED: High risk score requires admin review before approval.';
      case 'critical':
        return 'ESCALATION REQUIRED: Critical risk level. Suspend activity and escalate to compliance team.';
      default:
        return 'Review account details.';
    }
  }
}