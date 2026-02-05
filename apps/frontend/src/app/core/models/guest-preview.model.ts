export interface ReviewPreview {
  rating: number;
  comment: string;
}

/**
 * Guest preview data for host approval workflow.
 * Contains enterprise-grade information for hosts to make informed booking decisions.
 */
export interface GuestBookingPreview {
  // Basic Profile
  profilePhotoUrl: string;
  firstName: string;
  lastInitial: string;
  joinDate: string;

  // Verification Status
  emailVerified: boolean;
  phoneVerified: boolean;
  identityVerified: boolean;
  drivingEligibilityStatus: string; // 'APPROVED', 'PENDING_REVIEW', 'REJECTED', 'NOT_STARTED'

  // Guest Demographics
  age?: number;
  ageVerified: boolean;

  // Driving Experience
  licenseCountry?: string;      // e.g., 'SRB', 'HRV', 'DEU'
  licenseCategories?: string;   // e.g., 'B', 'B,C'
  licenseTenureMonths?: number; // How long they've held license
  licenseExpiryDate?: string;   // When license expires

  // Reliability Stats
  starRating: number;
  tripCount: number;
  cancelledTripsCount: number;
  cancellationRate?: number;    // Percentage (0-100)

  // Achievement Badges (enterprise achievements, NOT verification badges)
  badges: string[];

  // Host Reviews
  hostReviews: ReviewPreview[];

  // Trip Details
  requestedStartDateTime: string;
  requestedEndDateTime: string;
  message?: string;
  protectionPlan: string;
}

