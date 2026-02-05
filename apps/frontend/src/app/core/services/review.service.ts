import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';

import { environment } from '@environments/environment';
import { Review, RenterReviewRequest, OwnerReviewRequest } from '@core/models/review.model';

@Injectable({ providedIn: 'root' })
export class ReviewService {
  private readonly baseUrl = `${environment.baseApiUrl}`;

  constructor(private readonly http: HttpClient) {}

  getReviewsForCar(carId: string): Observable<Review[]> {
    return this.http.get<Review[]>(`${this.baseUrl}/reviews/car/${carId}`);
  }

  getRecentReviews(): Observable<Review[]> {
    return this.http.get<Review[]>(`${this.baseUrl}/reviews/recent`);
  }

  /**
   * Submit a renter review for a completed booking
   * POST /api/reviews/from-renter
   */
  submitRenterReview(request: RenterReviewRequest): Observable<{ id: number; rating: number; message: string }> {
    return this.http.post<{ id: number; rating: number; message: string }>(
      `${this.baseUrl}/reviews/from-renter`,
      request,
      { withCredentials: true }
    );
  }

  /**
   * Submit an owner review for a renter after a completed booking
   * POST /api/reviews/from-owner
   */
  submitOwnerReview(request: OwnerReviewRequest): Observable<{ id: number; rating: number; message: string }> {
    return this.http.post<{ id: number; rating: number; message: string }>(
      `${this.baseUrl}/reviews/from-owner`,
      request,
      { withCredentials: true }
    );
  }

  /**
   * Get reviews received by an owner (from renters)
   * GET /api/reviews/received/{email}
   */
  getReceivedReviews(email: string): Observable<Review[]> {
    return this.http.get<Review[]>(`${this.baseUrl}/reviews/received/${email}`, {
      withCredentials: true
    });
  }

  /**
   * Get reviews given by an owner (to renters)
   * GET /api/reviews/from-owner/{email}
   */
  getReviewsFromOwner(email: string): Observable<Review[]> {
    return this.http.get<Review[]>(`${this.baseUrl}/reviews/from-owner/${email}`, {
      withCredentials: true
    });
  }
}
