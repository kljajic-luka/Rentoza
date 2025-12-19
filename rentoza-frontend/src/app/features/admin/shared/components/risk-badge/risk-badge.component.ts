import { Component, Input } from '@angular/core';
import { CommonModule } from '@angular/common';
import { MatTooltipModule } from '@angular/material/tooltip';
import { MatIconModule } from '@angular/material/icon';

@Component({
  selector: 'app-risk-badge',
  standalone: true,
  imports: [CommonModule, MatTooltipModule, MatIconModule],
  template: `
    <div 
      class="risk-badge" 
      [ngClass]="'badge-' + riskLevel.toLowerCase()"
      [matTooltip]="getTooltipText()"
      matTooltipPosition="above"
    >
      <mat-icon class="badge-icon">{{ getBadgeIcon() }}</mat-icon>
      <span class="badge-score">{{ totalScore }}</span>
    </div>
  `,
  styles: [`
    .risk-badge {
      display: inline-flex;
      align-items: center;
      gap: 6px;
      padding: 6px 12px;
      border-radius: 16px;
      font-size: 13px;
      font-weight: 700;
      cursor: help;
      transition: all 150ms ease;

      &:hover {
        transform: translateY(-1px);
        box-shadow: 0 4px 12px rgba(0, 0, 0, 0.15);
      }

      &.badge-low {
        background: linear-gradient(135deg, #10b981, #059669);
        color: white;
      }

      &.badge-medium {
        background: linear-gradient(135deg, #f59e0b, #d97706);
        color: white;
      }

      &.badge-high {
        background: linear-gradient(135deg, #ef4444, #dc2626);
        color: white;
      }

      &.badge-critical {
        background: linear-gradient(135deg, #8b5cf6, #7c3aed);
        color: white;
        animation: pulse 2s ease-in-out infinite;
      }
    }

    .badge-icon {
      font-size: 16px;
      width: 16px;
      height: 16px;
    }

    .badge-score {
      font-size: 13px;
      font-weight: 700;
    }

    @keyframes pulse {
      0%, 100% {
        box-shadow: 0 0 0 0 rgba(139, 92, 246, 0.7);
      }
      50% {
        box-shadow: 0 0 0 8px rgba(139, 92, 246, 0);
      }
    }
  `]
})
export class RiskBadgeComponent {
  @Input() totalScore: number = 0;
  @Input() riskLevel: string = 'LOW';
  @Input() topFactors: string[] = [];

  getBadgeIcon(): string {
    const level = this.riskLevel.toLowerCase();
    switch (level) {
      case 'low': return 'check_circle';
      case 'medium': return 'warning';
      case 'high': return 'error';
      case 'critical': return 'block';
      default: return 'help';
    }
  }

  getTooltipText(): string {
    const level = this.riskLevel.toUpperCase();
    const factorsText = this.topFactors.length > 0
      ? `\nTop factors: ${this.topFactors.slice(0, 3).join(', ')}`
      : '\nNo specific risk factors';
    
    return `Risk Score: ${this.totalScore}/100 (${level})${factorsText}`;
  }
}
