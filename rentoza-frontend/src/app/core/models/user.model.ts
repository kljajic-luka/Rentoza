import { ReviewDirection } from './review.model';
import { UserRole } from './user-role.type';

export interface User {
  id: string;
  firstName: string;
  lastName: string;
  email: string;
  age?: number;
  avatarUrl?: string;
  roles: UserRole[];
}

export interface UserProfile extends User {
  phone?: string;
  bio?: string;
  createdAt?: string;
}

export interface ProfileStats {
  completedTrips: number;
  hostedTrips: number;
  totalReviews: number;
}

export interface ProfileReview {
  id: string;
  rating: number;
  comment: string;
  createdAt: string;
  direction: ReviewDirection;
  reviewerFirstName?: string;
  reviewerLastName?: string;
  reviewerAvatarUrl?: string;
}

export interface UserProfileDetails {
  id: string;
  firstName: string;
  lastName: string;
  email: string;
  phone?: string;
  role: UserRole;
  roles: UserRole[];
  avatarUrl?: string;
  bio?: string;
  createdAt?: string;
  averageRating: number;
  stats: ProfileStats;
  reviews: ProfileReview[];
}

/**
 * Request DTO for secure partial profile updates.
 * Only contains fields users are allowed to update directly.
 * Sensitive identity fields (name, email, role) are intentionally excluded.
 */
export interface UpdateProfileRequest {
  phone?: string;
  avatarUrl?: string;
  bio?: string;
  lastName?: string;
}
