import { Injectable, inject, signal } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable, tap, catchError, of, BehaviorSubject, throwError } from 'rxjs';
import { environment } from '@environments/environment';
import { Favorite, FavoriteToggleResponse } from '@core/models/favorite.model';
import { AuthService } from '@core/auth/auth.service';

/**
 * Service for managing user favorites with optimistic UI updates
 */
@Injectable({
  providedIn: 'root',
})
export class FavoriteService {
  private readonly http = inject(HttpClient);
  private readonly authService = inject(AuthService);
  private readonly baseUrl = `${environment.baseApiUrl}/favorites`;

  // Reactive state for favorited car IDs
  private favoritedCarIdsSubject = new BehaviorSubject<Set<number>>(new Set());
  public favoritedCarIds$ = this.favoritedCarIdsSubject.asObservable();

  // Signal for favorited car IDs (for reactive component updates)
  public favoritedCarIdsSignal = signal<Set<number>>(new Set());

  // Signal for favorite count
  public favoriteCount = signal<number>(0);



  /**
   * Load favorited car IDs for the authenticated user
   * Call this after login
   */
  loadFavoritedCarIds(): Observable<number[]> {
    return this.http
      .get<number[]>(`${this.baseUrl}/car-ids`, {
        withCredentials: true,
      })
      .pipe(
        tap((carIds) => {
          this.updateFavoritedIds(new Set(carIds));
        }),
        catchError(() => {
          this.updateFavoritedIds(new Set());
          return of([]);
        })
      );
  }

  /**
   * Get all favorites for the user
   */
  getFavorites(): Observable<Favorite[]> {
    return this.http.get<Favorite[]>(this.baseUrl, {
      withCredentials: true,
    });
  }

  /**
   * Add a car to favorites
   */
  addFavorite(carId: number): Observable<Favorite> {
    // Optimistic update
    const currentIds = this.favoritedCarIdsSubject.value;
    const previousIds = new Set(currentIds);
    const optimisticIds = new Set(previousIds);
    optimisticIds.add(carId);
    this.updateFavoritedIds(optimisticIds);

    return this.http
      .post<Favorite>(
        `${this.baseUrl}/${carId}`,
        {},
        {
          withCredentials: true,
        }
      )
      .pipe(
        catchError((error) => {
          this.updateFavoritedIds(previousIds);
          return throwError(() => error);
        })
      );
  }

  /**
   * Remove a car from favorites
   */
  removeFavorite(carId: number): Observable<void> {
    // Optimistic update
    const currentIds = this.favoritedCarIdsSubject.value;
    const previousIds = new Set(currentIds);
    const optimisticIds = new Set(previousIds);
    optimisticIds.delete(carId);
    this.updateFavoritedIds(optimisticIds);

    return this.http
      .delete<void>(`${this.baseUrl}/${carId}`, {
        withCredentials: true,
      })
      .pipe(
        catchError((error) => {
          this.updateFavoritedIds(previousIds);
          return throwError(() => error);
        })
      );
  }

  /**
   * Toggle favorite status (add if not favorited, remove if favorited)
   */
  toggleFavorite(carId: number): Observable<FavoriteToggleResponse> {
    const currentIds = new Set(this.favoritedCarIdsSubject.value);
    const wasFavorited = currentIds.has(carId);

    // Optimistic update
    const optimisticIds = new Set(currentIds);
    if (wasFavorited) {
      optimisticIds.delete(carId);
    } else {
      optimisticIds.add(carId);
    }
    this.updateFavoritedIds(optimisticIds);

    return this.http
      .put<FavoriteToggleResponse>(
        `${this.baseUrl}/${carId}/toggle`,
        {},
        {
          withCredentials: true,
        }
      )
      .pipe(
        tap((response) => {
          const finalIds = new Set(this.favoritedCarIdsSubject.value);
          if (response.isFavorited) {
            finalIds.add(carId);
          } else {
            finalIds.delete(carId);
          }
          this.updateFavoritedIds(finalIds);
        }),
        catchError((error) => {
          // Revert on error
          this.updateFavoritedIds(currentIds);
          return throwError(() => error);
        })
      );
  }

  /**
   * Check if a car is favorited (from local state, no HTTP call)
   */
  isFavorited(carId: number): boolean {
    return this.favoritedCarIdsSignal().has(carId);
  }

  /**
   * Check if a car is favorited (from server)
   */
  checkFavorite(carId: number): Observable<{ isFavorited: boolean }> {
    return this.http.get<{ isFavorited: boolean }>(`${this.baseUrl}/${carId}/check`, {
      withCredentials: true,
    });
  }

  /**
   * Get favorite count for a specific car
   */
  getCarFavoriteCount(carId: number): Observable<{ count: number }> {
    return this.http.get<{ count: number }>(`${this.baseUrl}/${carId}/count`, {
      withCredentials: true,
    });
  }

  /**
   * Clear local state (use on logout)
   */
  clearFavorites(): void {
    this.updateFavoritedIds(new Set());
  }

  private updateFavoritedIds(ids: Set<number>): void {
    const snapshot = new Set(ids);
    this.favoritedCarIdsSubject.next(snapshot);
    this.favoritedCarIdsSignal.set(snapshot);
    this.favoriteCount.set(snapshot.size);
  }
}