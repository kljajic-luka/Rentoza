import { Injectable, inject, signal } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable, tap, catchError, of, BehaviorSubject } from 'rxjs';
import { environment } from '@environments/environment';
import { Favorite, FavoriteToggleResponse } from '@core/models/favorite.model';

/**
 * Service for managing user favorites with optimistic UI updates
 */
@Injectable({
  providedIn: 'root',
})
export class FavoriteService {
  private readonly http = inject(HttpClient);
  private readonly baseUrl = `${environment.baseApiUrl}/favorites`;

  // Reactive state for favorited car IDs
  private favoritedCarIdsSubject = new BehaviorSubject<Set<number>>(new Set());
  public favoritedCarIds$ = this.favoritedCarIdsSubject.asObservable();

  // Signal for favorite count
  public favoriteCount = signal<number>(0);

  /**
   * Load favorited car IDs for the authenticated user
   * Call this after login
   */
  loadFavoritedCarIds(): Observable<number[]> {
    return this.http.get<number[]>(`${this.baseUrl}/car-ids`).pipe(
      tap((carIds) => {
        this.favoritedCarIdsSubject.next(new Set(carIds));
        this.favoriteCount.set(carIds.length);
      }),
      catchError(() => {
        this.favoritedCarIdsSubject.next(new Set());
        this.favoriteCount.set(0);
        return of([]);
      })
    );
  }

  /**
   * Get all favorites for the user
   */
  getFavorites(): Observable<Favorite[]> {
    return this.http.get<Favorite[]>(this.baseUrl);
  }

  /**
   * Add a car to favorites
   */
  addFavorite(carId: number): Observable<Favorite> {
    // Optimistic update
    const currentIds = this.favoritedCarIdsSubject.value;
    const newIds = new Set(currentIds);
    newIds.add(carId);
    this.favoritedCarIdsSubject.next(newIds);
    this.favoriteCount.set(newIds.size);

    return this.http.post<Favorite>(`${this.baseUrl}/${carId}`, {}).pipe(
      catchError((error) => {
        // Revert on error
        this.favoritedCarIdsSubject.next(currentIds);
        this.favoriteCount.set(currentIds.size);
        throw error;
      })
    );
  }

  /**
   * Remove a car from favorites
   */
  removeFavorite(carId: number): Observable<void> {
    // Optimistic update
    const currentIds = this.favoritedCarIdsSubject.value;
    const newIds = new Set(currentIds);
    newIds.delete(carId);
    this.favoritedCarIdsSubject.next(newIds);
    this.favoriteCount.set(newIds.size);

    return this.http.delete<void>(`${this.baseUrl}/${carId}`).pipe(
      catchError((error) => {
        // Revert on error
        this.favoritedCarIdsSubject.next(currentIds);
        this.favoriteCount.set(currentIds.size);
        throw error;
      })
    );
  }

  /**
   * Toggle favorite status (add if not favorited, remove if favorited)
   */
  toggleFavorite(carId: number): Observable<FavoriteToggleResponse> {
    const currentIds = this.favoritedCarIdsSubject.value;
    const wasFavorited = currentIds.has(carId);

    // Optimistic update
    const newIds = new Set(currentIds);
    if (wasFavorited) {
      newIds.delete(carId);
    } else {
      newIds.add(carId);
    }
    this.favoritedCarIdsSubject.next(newIds);
    this.favoriteCount.set(newIds.size);

    return this.http.put<FavoriteToggleResponse>(`${this.baseUrl}/${carId}/toggle`, {}).pipe(
      catchError((error) => {
        // Revert on error
        this.favoritedCarIdsSubject.next(currentIds);
        this.favoriteCount.set(currentIds.size);
        throw error;
      })
    );
  }

  /**
   * Check if a car is favorited (from local state, no HTTP call)
   */
  isFavorited(carId: number): boolean {
    return this.favoritedCarIdsSubject.value.has(carId);
  }

  /**
   * Check if a car is favorited (from server)
   */
  checkFavorite(carId: number): Observable<{ isFavorited: boolean }> {
    return this.http.get<{ isFavorited: boolean }>(`${this.baseUrl}/${carId}/check`);
  }

  /**
   * Get favorite count for a specific car
   */
  getCarFavoriteCount(carId: number): Observable<{ count: number }> {
    return this.http.get<{ count: number }>(`${this.baseUrl}/${carId}/count`);
  }

  /**
   * Clear local state (use on logout)
   */
  clearFavorites(): void {
    this.favoritedCarIdsSubject.next(new Set());
    this.favoriteCount.set(0);
  }
}
