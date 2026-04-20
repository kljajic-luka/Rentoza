import { ReviewDirection } from './review.model';
import { UserRole } from './user-role.type';
import { RegistrationStatus, OwnerType } from './auth.model';

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
  /**
   * Registration status for profile completion flow.
   * INCOMPLETE = Google OAuth user needs to complete profile.
   * ACTIVE = Fully registered user.
   */
  registrationStatus?: RegistrationStatus;
  /** Owner type (INDIVIDUAL or LEGAL_ENTITY) - only for OWNER role */
  ownerType?: OwnerType;
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

  // ========== Age/DOB Fields (Enterprise-Grade) ==========
  /** User's date of birth (ISO format: YYYY-MM-DD) */
  dateOfBirth?: string | null;
  /** Calculated age from DOB (null if DOB not set) */
  age?: number | null;
  /** Whether DOB was verified via official document (license OCR) */
  dobVerified?: boolean;
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
  /** Date of birth (ISO format: YYYY-MM-DD). Can only be set if not already verified via license OCR. */
  dateOfBirth?: string | null;
}