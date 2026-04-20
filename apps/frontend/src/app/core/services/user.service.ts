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
        avatarUrl: this.resolveAvatarUrl(profile.avatarUrl),
      }))
    );
  }

  getProfileDetails(): Observable<UserProfileDetails> {
    return this.http.get<UserProfileDetails>(`${this.baseUrl}/profile/details`).pipe(
      map((profile) => ({
        ...profile,
        id: String(profile.id),
        roles: profile.roles?.length ? profile.roles : [profile.role],
        avatarUrl: this.resolveAvatarUrl(profile.avatarUrl),
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

  /**
   * Upload profile picture file.
   * Backend processes the image (resize, compress, strip EXIF) and returns optimized URL.
   *
   * @param file The image file (JPEG, PNG, or WebP, max 4MB)
   * @returns Observable with the new profile picture URL including cache-busting timestamp
   */
  uploadProfilePicture(file: File): Observable<ProfilePictureResult> {
    const formData = new FormData();
    formData.append('file', file);

    return this.http.post<ProfilePictureResult>(`${this.baseUrl}/me/profile-picture`, formData).pipe(
      map((result) => ({
        // Prepend base URL for relative paths (e.g., /uploads/...)
        profilePictureUrl: result.profilePictureUrl.startsWith('/')
          ? `${environment.baseUrl}${result.profilePictureUrl}`
          : result.profilePictureUrl,
      }))
    );
  }

  /**
   * Delete the user's profile picture.
   * Removes the file from storage and clears avatarUrl in database.
   */
  deleteProfilePicture(): Observable<{ message: string }> {
    return this.http.delete<{ message: string }>(`${this.baseUrl}/me/profile-picture`);
  }

  /**
   * Resolve avatar URL to absolute path.
   * In development, prepends the backend base URL for relative paths.
   * In production (same domain), relative paths work directly.
   */
  private resolveAvatarUrl(url: string | undefined | null): string | undefined {
    if (!url) {
      return undefined;
    }
    // Prepend base URL for relative paths starting with /uploads/
    if (url.startsWith('/uploads/')) {
      return `${environment.baseUrl}${url}`;
    }
    return url;
  }
}

/**
 * Response from profile picture upload endpoint.
 */
export interface ProfilePictureResult {
  profilePictureUrl: string;
}