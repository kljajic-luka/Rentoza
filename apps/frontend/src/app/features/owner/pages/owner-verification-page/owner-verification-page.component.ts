import { Component } from '@angular/core';
import { CommonModule } from '@angular/common';
import { RouterLink } from '@angular/router';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { OwnerVerificationComponent } from '../../components/owner-verification/owner-verification.component';

/**
 * Dedicated page for owner identity verification.
 */
@Component({
  selector: 'app-owner-verification-page',
  standalone: true,
  imports: [
    CommonModule,
    RouterLink,
    MatButtonModule,
    MatIconModule,
    OwnerVerificationComponent,
  ],
  template: `
    <div class="verification-page">
      <header class="page-header">
        <button mat-icon-button routerLink="/users/profile">
          <mat-icon>arrow_back</mat-icon>
        </button>
        <h1>Verifikacija vlasnika</h1>
      </header>
      
      <app-owner-verification></app-owner-verification>
    </div>
  `,
  styles: [`
    .verification-page {
      max-width: 600px;
      margin: 0 auto;
      padding: 2rem 1rem;
    }
    
    .page-header {
      display: flex;
      align-items: center;
      gap: 0.5rem;
      margin-bottom: 2rem;
      
      h1 {
        margin: 0;
        font-size: 1.5rem;
        font-weight: 600;
      }
    }
  `]
})
export class OwnerVerificationPageComponent {}