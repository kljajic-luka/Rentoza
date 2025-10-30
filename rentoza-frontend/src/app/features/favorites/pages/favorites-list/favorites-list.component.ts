import { Component, inject, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { Router, RouterModule } from '@angular/router';
import { MatCardModule } from '@angular/material/card';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { FavoriteService } from '@core/services/favorite.service';
import { Favorite } from '@core/models/favorite.model';
import { finalize } from 'rxjs';

@Component({
  selector: 'app-favorites-list',
  standalone: true,
  imports: [
    CommonModule,
    RouterModule,
    MatCardModule,
    MatButtonModule,
    MatIconModule,
    MatProgressSpinnerModule
  ],
  template: `
    <section class="favorites">
      <header class="favorites__header">
        <h1>❤️ Omiljeni automobili</h1>
        <p class="subtitle">Ovde možete videti sve automobile koje ste dodali u favorite</p>
      </header>

      @if (isLoading()) {
        <div class="loading">
          <mat-progress-spinner mode="indeterminate"></mat-progress-spinner>
        </div>
      } @else if (favorites().length === 0) {
        <div class="empty-state">
          <mat-icon>favorite_border</mat-icon>
          <h2>Još nemate omiljenih automobila</h2>
          <p>Pregledajte dostupne automobile i dodajte ih u favorite</p>
          <a mat-raised-button color="primary" routerLink="/cars">
            Pregledaj automobile
          </a>
        </div>
      } @else {
        <div class="favorites__grid">
          @for (favorite of favorites(); track favorite.id) {
            <mat-card class="favorite-card">
              <div class="favorite-card__media">
                @if (favorite.carImageUrl) {
                  <img [src]="favorite.carImageUrl" [alt]="favorite.carBrand + ' ' + favorite.carModel" />
                } @else {
                  <div class="placeholder">
                    <mat-icon>directions_car</mat-icon>
                  </div>
                }
                <button
                  mat-icon-button
                  class="remove-btn"
                  (click)="removeFavorite(favorite)"
                  [attr.aria-label]="'Ukloni ' + favorite.carBrand + ' ' + favorite.carModel + ' iz favorita'"
                >
                  <mat-icon>close</mat-icon>
                </button>
              </div>

              <mat-card-content class="favorite-card__content">
                <h3>{{ favorite.carBrand }} {{ favorite.carModel }}</h3>
                <div class="favorite-card__details">
                  <span class="year">{{ favorite.carYear }}</span>
                  <span class="price">{{ favorite.carPricePerDay | number: '1.0-0' }} RSD/dnevno</span>
                </div>
                <div class="favorite-card__location">
                  <mat-icon>place</mat-icon>
                  <span>{{ favorite.carLocation }}</span>
                </div>
                @if (!favorite.carAvailable) {
                  <div class="unavailable-badge">
                    <mat-icon>event_busy</mat-icon>
                    <span>Trenutno nedostupno</span>
                  </div>
                }
              </mat-card-content>

              <mat-card-actions>
                <a
                  mat-raised-button
                  color="primary"
                  [routerLink]="['/cars', favorite.carId]"
                >
                  Pogledaj detalje
                </a>
              </mat-card-actions>
            </mat-card>
          }
        </div>
      }
    </section>
  `,
  styles: [`
    .favorites {
      padding: 2rem;
      max-width: 1400px;
      margin: 0 auto;

      &__header {
        margin-bottom: 2rem;

        h1 {
          font-size: 2.5rem;
          font-weight: 700;
          color: #1e293b;
          margin: 0 0 0.5rem 0;
        }

        .subtitle {
          color: #64748b;
          font-size: 1.1rem;
        }
      }

      &__grid {
        display: grid;
        grid-template-columns: repeat(auto-fill, minmax(320px, 1fr));
        gap: 2rem;
      }
    }

    .loading {
      display: flex;
      justify-content: center;
      align-items: center;
      min-height: 400px;
    }

    .empty-state {
      text-align: center;
      padding: 4rem 2rem;

      mat-icon {
        font-size: 80px;
        width: 80px;
        height: 80px;
        color: #cbd5e1;
        margin-bottom: 1rem;
      }

      h2 {
        font-size: 1.75rem;
        color: #475569;
        margin-bottom: 0.5rem;
      }

      p {
        color: #64748b;
        margin-bottom: 2rem;
      }
    }

    .favorite-card {
      display: flex;
      flex-direction: column;
      height: 100%;
      border-radius: 16px;
      overflow: hidden;
      transition: transform 0.2s, box-shadow 0.2s;

      &:hover {
        transform: translateY(-4px);
        box-shadow: 0 12px 24px rgba(0, 0, 0, 0.15);
      }

      &__media {
        position: relative;
        padding-top: 60%;
        overflow: hidden;
        background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);

        img {
          position: absolute;
          top: 0;
          left: 0;
          width: 100%;
          height: 100%;
          object-fit: cover;
        }

        .placeholder {
          position: absolute;
          top: 0;
          left: 0;
          width: 100%;
          height: 100%;
          display: flex;
          align-items: center;
          justify-content: center;

          mat-icon {
            font-size: 64px;
            width: 64px;
            height: 64px;
            color: rgba(255, 255, 255, 0.7);
          }
        }

        .remove-btn {
          position: absolute;
          top: 8px;
          right: 8px;
          background: rgba(255, 255, 255, 0.9);
          backdrop-filter: blur(10px);

          &:hover {
            background: white;
            mat-icon {
              color: #ef4444;
            }
          }

          mat-icon {
            color: #64748b;
          }
        }
      }

      &__content {
        flex: 1;
        padding: 1.5rem;

        h3 {
          font-size: 1.25rem;
          font-weight: 600;
          color: #1e293b;
          margin: 0 0 0.75rem 0;
        }
      }

      &__details {
        display: flex;
        justify-content: space-between;
        align-items: center;
        margin-bottom: 0.75rem;

        .year {
          font-size: 0.875rem;
          color: #64748b;
          background: #f1f5f9;
          padding: 0.25rem 0.75rem;
          border-radius: 12px;
        }

        .price {
          font-size: 1.125rem;
          font-weight: 600;
          color: #6366f1;
        }
      }

      &__location {
        display: flex;
        align-items: center;
        gap: 0.5rem;
        color: #64748b;
        margin-bottom: 1rem;

        mat-icon {
          font-size: 18px;
          width: 18px;
          height: 18px;
        }
      }
    }

    .unavailable-badge {
      display: flex;
      align-items: center;
      gap: 0.5rem;
      padding: 0.5rem 0.75rem;
      background: #fee2e2;
      color: #dc2626;
      border-radius: 8px;
      font-size: 0.875rem;

      mat-icon {
        font-size: 18px;
        width: 18px;
        height: 18px;
      }
    }

    mat-card-actions {
      padding: 0 1.5rem 1.5rem;
      margin: 0;

      button {
        width: 100%;
      }
    }

    @media (max-width: 768px) {
      .favorites {
        padding: 1rem;

        &__header h1 {
          font-size: 2rem;
        }

        &__grid {
          grid-template-columns: 1fr;
        }
      }
    }
  `]
})
export class FavoritesListComponent {
  private readonly favoriteService = inject(FavoriteService);
  private readonly router = inject(Router);

  favorites = signal<Favorite[]>([]);
  isLoading = signal(true);

  ngOnInit(): void {
    this.loadFavorites();
  }

  private loadFavorites(): void {
    this.isLoading.set(true);
    this.favoriteService
      .getFavorites()
      .pipe(finalize(() => this.isLoading.set(false)))
      .subscribe({
        next: (favorites) => {
          this.favorites.set(favorites);
        },
        error: (error) => {
          console.error('Error loading favorites:', error);
          this.favorites.set([]);
        }
      });
  }

  removeFavorite(favorite: Favorite): void {
    this.favoriteService.removeFavorite(favorite.carId).subscribe({
      next: () => {
        this.favorites.update(favs => favs.filter(f => f.id !== favorite.id));
      },
      error: (error) => {
        console.error('Error removing favorite:', error);
      }
    });
  }
}
