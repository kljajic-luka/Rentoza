import { Component, Input } from '@angular/core';
import { CommonModule } from '@angular/common';
import { MatIconModule } from '@angular/material/icon';
import { MatButtonModule } from '@angular/material/button';
import { RouterModule } from '@angular/router';

@Component({
  selector: 'app-empty-state',
  standalone: true,
  imports: [CommonModule, MatIconModule, MatButtonModule, RouterModule],
  template: `
    <div class="empty-state">
      <div class="empty-icon-wrapper">
        <mat-icon class="empty-icon">{{ icon }}</mat-icon>
      </div>
      <h3 class="empty-title">{{ title }}</h3>
      <p class="empty-message">{{ message }}</p>
      <button 
        *ngIf="actionLabel" 
        mat-raised-button 
        color="primary"
        [routerLink]="actionRoute"
        class="empty-action"
      >
        <mat-icon *ngIf="actionIcon">{{ actionIcon }}</mat-icon>
        {{ actionLabel }}
      </button>
    </div>
  `,
  styles: [`
    .empty-state {
      display: flex;
      flex-direction: column;
      align-items: center;
      justify-content: center;
      padding: 80px 20px;
      text-align: center;
      max-width: 500px;
      margin: 0 auto;

      @media (max-width: 768px) {
        padding: 60px 20px;
      }
    }

    .empty-icon-wrapper {
      width: 120px;
      height: 120px;
      border-radius: 50%;
      background: linear-gradient(
        135deg,
        rgba(59, 130, 246, 0.1),
        rgba(99, 102, 241, 0.08)
      );
      display: flex;
      align-items: center;
      justify-content: center;
      margin-bottom: 24px;

      @media (max-width: 768px) {
        width: 100px;
        height: 100px;
      }
    }

    .empty-icon {
      font-size: 64px;
      width: 64px;
      height: 64px;
      color: var(--icon-color);
      opacity: 0.6;

      @media (max-width: 768px) {
        font-size: 56px;
        width: 56px;
        height: 56px;
      }
    }

    .empty-title {
      font-size: 24px;
      font-weight: 700;
      margin: 0 0 12px 0;
      color: var(--color-text-primary);

      @media (max-width: 768px) {
        font-size: 20px;
      }
    }

    .empty-message {
      font-size: 16px;
      line-height: 1.6;
      color: var(--color-text-muted);
      margin: 0 0 32px 0;

      @media (max-width: 768px) {
        font-size: 14px;
        margin-bottom: 24px;
      }
    }

    .empty-action {
      mat-icon {
        margin-right: 8px;
      }
    }
  `]
})
export class EmptyStateComponent {
  @Input() icon = 'inbox';
  @Input() title = 'No data available';
  @Input() message = 'There are no items to display at this time.';
  @Input() actionLabel?: string;
  @Input() actionIcon?: string;
  @Input() actionRoute?: string | string[];
}