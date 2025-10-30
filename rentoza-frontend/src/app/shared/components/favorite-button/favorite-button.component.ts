import { Component, Input, inject, signal, computed } from '@angular/core';
import { CommonModule } from '@angular/common';
import { MatIconModule } from '@angular/material/icon';
import { MatButtonModule } from '@angular/material/button';
import { MatTooltipModule } from '@angular/material/tooltip';
import { FavoriteService } from '@core/services/favorite.service';
import { AuthService } from '@core/auth/auth.service';
import { ToastrService } from 'ngx-toastr';
import { Router } from '@angular/router';
import { toSignal } from '@angular/core/rxjs-interop';
import { firstValueFrom } from 'rxjs';

/**
 * Reusable favorite button component with optimistic updates
 * Shows filled heart when favorited, outline when not
 */
@Component({
  selector: 'app-favorite-button',
  standalone: true,
  imports: [
    CommonModule,
    MatIconModule,
    MatButtonModule,
    MatTooltipModule
  ],
  template: `
    <button
      mat-icon-button
      [class.favorited]="isFavorited()"
      [disabled]="isLoading()"
      [matTooltip]="tooltipText()"
      (click)="toggleFavorite($event)"
      attr.aria-label="{{isFavorited() ? 'Ukloni iz favorita' : 'Dodaj u favorite'}}"
    >
      <mat-icon [class.animate]="isAnimating()">
        {{ isFavorited() ? 'favorite' : 'favorite_border' }}
      </mat-icon>
    </button>
  `,
  styles: [`
    button {
      transition: all 200ms ease;
    }

    button.favorited mat-icon {
      color: #ef4444; /* Red for favorited */
      animation: heartBeat 0.3s ease;
    }

    button:not(.favorited) mat-icon {
      color: rgba(100, 116, 139, 0.7);
    }

    button:hover:not(:disabled) mat-icon {
      transform: scale(1.15);
      color: #ef4444;
    }

    button:disabled {
      opacity: 0.5;
      cursor: not-allowed;
    }

    button mat-icon.animate {
      animation: heartBeat 0.3s ease;
    }

    @keyframes heartBeat {
      0%, 100% {
        transform: scale(1);
      }
      25% {
        transform: scale(1.3);
      }
      50% {
        transform: scale(1.1);
      }
    }
  `]
})
export class FavoriteButtonComponent {
  private readonly favoriteService = inject(FavoriteService);
  private readonly authService = inject(AuthService);
  private readonly toastr = inject(ToastrService);
  private readonly router = inject(Router);

  @Input({ required: true }) carId!: number;
  @Input() size: 'small' | 'medium' | 'large' = 'medium';

  isLoading = signal(false);
  isAnimating = signal(false);

  // Convert Observable to Signal
  private readonly currentUser = toSignal(this.authService.currentUser$);

  // Computed signal for favorite status - directly read from signal
  isFavorited = computed(() => this.favoriteService.favoritedCarIdsSignal().has(this.carId));

  // Computed tooltip text
  tooltipText = computed(() =>
    this.isFavorited() ? 'Ukloni iz favorita' : 'Dodaj u favorite'
  );

  async toggleFavorite(event: Event): Promise<void> {
    event.stopPropagation();
    event.preventDefault();

    // Check if user is authenticated
    const user = this.currentUser();
    if (!user) {
      this.toastr.info('Prijavite se da biste dodali automobile u favorite', 'Prijava potrebna');
      void this.router.navigate(['/auth/login'], {
        queryParams: { returnUrl: this.router.url }
      });
      return;
    }

    this.isLoading.set(true);
    this.isAnimating.set(true);

    try {
      const response = await firstValueFrom(this.favoriteService.toggleFavorite(this.carId));
      const message =
        response?.message ??
        (this.isFavorited() ? 'Dodato u favorite' : 'Uklonjeno iz favorita');
      this.toastr.success(message);
    } catch (error) {
      console.error('Error toggling favorite:', error);
      this.toastr.error('Greška prilikom ažuriranja favorita');
    } finally {
      this.isLoading.set(false);
      setTimeout(() => this.isAnimating.set(false), 300);
    }
  }
}
