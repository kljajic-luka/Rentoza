import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable, map } from 'rxjs';

import { environment } from '@environments/environment';
import { UserProfile, UserProfileDetails } from '@core/models/user.model';

@Injectable({ providedIn: 'root' })
export class UserService {
  private readonly baseUrl = `${environment.baseApiUrl}/users`;

  constructor(private readonly http: HttpClient) {}

  getProfile(): Observable<UserProfile> {
    return this.http.get<UserProfile>(`${this.baseUrl}/profile`).pipe(
      map((profile) => ({
        ...profile,
        id: String(profile.id),
        roles: profile.roles ?? [],
      }))
    );
  }

  getProfileDetails(): Observable<UserProfileDetails> {
    return this.http.get<UserProfileDetails>(`${this.baseUrl}/profile/details`).pipe(
      map((profile) => ({
        ...profile,
        id: String(profile.id),
        roles: profile.roles?.length ? profile.roles : [profile.role],
        reviews: profile.reviews?.map((review) => ({
          ...review,
          id: String(review.id),
        })) ?? [],
      }))
    );
  }
}
