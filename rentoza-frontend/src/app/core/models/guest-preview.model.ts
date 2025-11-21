export interface ReviewPreview {
  rating: number;
  comment: string;
}

export interface GuestBookingPreview {
  profilePhotoUrl: string;
  firstName: string;
  lastInitial: string;
  joinDate: string;

  emailVerified: boolean;
  phoneVerified: boolean;
  identityVerified: boolean;
  drivingEligibilityStatus: string;

  starRating: number;
  tripCount: number;

  hostReviews: ReviewPreview[];

  requestedStartDateTime: string;
  requestedEndDateTime: string;
  message?: string;
  protectionPlan: string;
}
