import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable, map } from 'rxjs';

import { environment } from '@environments/environment';
import { UserProfile, UserProfileDetails, UpdateProfileRequest } from '@core/models/user.model';

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

  /**
   * Update user profile with secure partial update.
   * Only allows updating safe fields: phone, avatarUrl, bio, and (conditionally) lastName.
   * Sensitive fields (name, email, role) are rejected by backend unless backend explicitly allows.
   */
  updateMyProfile(request: UpdateProfileRequest): Observable<UserProfileDetails> {
    return this.http.patch<UserProfileDetails>(`${this.baseUrl}/me`, request).pipe(
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
