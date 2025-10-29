import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';

import { environment } from '@environments/environment';
import { Car } from '@core/models/car.model';
import { Review } from '@core/models/review.model';

@Injectable({ providedIn: 'root' })
export class CarService {
  private readonly baseUrl = `${environment.baseApiUrl}/cars`;

  constructor(private readonly http: HttpClient) {}

  getCars(): Observable<Car[]> {
    return this.http.get<Car[]>(this.baseUrl);
  }

  getCarsByLocation(location: string): Observable<Car[]> {
    return this.http.get<Car[]>(`${this.baseUrl}/location/${location}`);
  }

  getCarById(id: string): Observable<Car> {
    return this.http.get<Car>(`${this.baseUrl}/${id}`);
  }

  getCarReviews(id: string): Observable<Review[]> {
    return this.http.get<Review[]>(`${this.baseUrl}/${id}/reviews`);
  }
}
