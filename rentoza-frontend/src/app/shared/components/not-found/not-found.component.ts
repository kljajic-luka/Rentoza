import { CommonModule } from '@angular/common';
import { ChangeDetectionStrategy, Component } from '@angular/core';
import { RouterModule } from '@angular/router';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';

@Component({
  selector: 'app-not-found',
  standalone: true,
  imports: [CommonModule, RouterModule, MatButtonModule, MatIconModule],
  template: `
    <div class="not-found">
      <mat-icon fontIcon="travel_explore" aria-hidden="true"></mat-icon>
      <h2>Page not found</h2>
      <p>The page you are looking for does not exist or has been moved.</p>
      <a mat-stroked-button routerLink="/">
        <mat-icon>arrow_back</mat-icon>
        Back to home
      </a>
    </div>
  `,
  styles: [
    `
      .not-found {
        margin: 6rem auto;
        max-width: 420px;
        text-align: center;
        display: flex;
        flex-direction: column;
        align-items: center;
        gap: 1rem;
      }

      mat-icon {
        font-size: 64px;
        width: 64px;
        height: 64px;
      }
    `
  ],
  changeDetection: ChangeDetectionStrategy.OnPush
})
export class NotFoundComponent {}
