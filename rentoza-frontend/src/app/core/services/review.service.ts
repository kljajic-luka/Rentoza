import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';

import { environment } from '@environments/environment';
import { Review } from '@core/models/review.model';

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
}
