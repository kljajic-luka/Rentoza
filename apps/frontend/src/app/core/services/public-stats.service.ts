import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable, shareReplay } from 'rxjs';

import { environment } from '@environments/environment';

export interface HomeStats {
  guestSatisfactionRating: number;
  verifiedVehiclesCount: number;
  supportAvailability: string;
}

@Injectable({ providedIn: 'root' })
export class PublicStatsService {
  private readonly baseUrl = `${environment.baseApiUrl}/public`;

  constructor(private readonly http: HttpClient) {}

  getHomeStats(): Observable<HomeStats> {
    return this.http
      .get<HomeStats>(`${this.baseUrl}/home-stats`)
      .pipe(shareReplay({ bufferSize: 1, refCount: true }));
  }
}
