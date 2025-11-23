import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable, map } from 'rxjs';
import { environment } from '../../../environments/environment';

export interface OwnerCarPreview {
  id: number;
  brand: string;
  model: string;
  year: number;
  imageUrl: string;
  pricePerDay: number;
  rating: number;
  tripCount: number;
}

export interface ReviewPreview {
  rating: number;
  comment: string;
}

export interface OwnerPublicProfile {
  id: number;
  firstName: string;
  lastName: string;
  avatarUrl: string;
  joinDate: string;
  about: string;
  averageRating: number;
  totalTrips: number;
  responseTime: string;
  responseRate: string;
  isSuperHost: boolean;
  recentReviews: ReviewPreview[];
  cars: OwnerCarPreview[];
}

@Injectable({
  providedIn: 'root',
})
export class OwnerPublicService {
  private http = inject(HttpClient);
  private apiUrl = `${environment.baseApiUrl}/owners`;

  getOwnerPublicProfile(id: number, start?: string, end?: string): Observable<OwnerPublicProfile> {
    let params: any = {};
    if (start && end) {
      params = { start, end };
    }
    return this.http.get<OwnerPublicProfile>(`${this.apiUrl}/${id}/public-profile`, { params }).pipe(
      map((profile) => ({
        ...profile,
        avatarUrl: this.resolveAvatarUrl(profile.avatarUrl),
      }))
    );
  }

  /**
   * Resolve avatar URL to absolute path.
   * In development, prepends the backend base URL for relative paths.
   */
  private resolveAvatarUrl(url: string | undefined | null): string {
    if (!url) {
      return '';
    }
    if (url.startsWith('/uploads/')) {
      return `${environment.baseUrl}${url}`;
    }
    return url;
  }
}
