export type ReviewDirection = 'FROM_USER' | 'FROM_OWNER';

export interface Review {
  id: string;
  rating: number;
  comment: string;
  createdAt: string;
  direction?: ReviewDirection;
  reviewerFirstName?: string;
  reviewerLastName?: string;
  reviewerAvatarUrl?: string;
  revieweeFirstName?: string;
  revieweeLastName?: string;
  revieweeAvatarUrl?: string;
  carId?: string;
  carBrand?: string;
  carModel?: string;
  carYear?: number;
  carLocation?: string;
}

/**
 * Request payload for renter-to-owner review with category ratings
 */
export interface RenterReviewRequest {
  bookingId: number;
  cleanlinessRating: number;
  maintenanceRating: number;
  communicationRating: number;
  convenienceRating: number;
  accuracyRating: number;
  comment?: string;
}

/**
 * Request payload for owner-to-renter review with category ratings
 */
export interface OwnerReviewRequest {
  bookingId: number;
  communicationRating: number;
  cleanlinessRating: number;
  timelinessRating: number;
  respectForRulesRating: number;
  comment?: string;
}

/**
 * Review categories for UI display
 */
export interface ReviewCategory {
  key: keyof Omit<RenterReviewRequest, 'bookingId' | 'comment'>;
  label: string;
  icon: string;
  rating: number;
}

export const REVIEW_CATEGORIES: Omit<ReviewCategory, 'rating'>[] = [
  { key: 'cleanlinessRating', label: 'Čistoća', icon: 'cleaning_services' },
  { key: 'maintenanceRating', label: 'Održavanje', icon: 'build' },
  { key: 'communicationRating', label: 'Komunikacija', icon: 'chat' },
  { key: 'convenienceRating', label: 'Pogodnost', icon: 'star' },
  { key: 'accuracyRating', label: 'Tačnost opisa', icon: 'verified' },
];

/**
 * Review categories for owner reviews of renters
 */
export interface OwnerReviewCategory {
  key: keyof Omit<OwnerReviewRequest, 'bookingId' | 'comment'>;
  label: string;
  icon: string;
  rating: number;
}

export const OWNER_REVIEW_CATEGORIES: Omit<OwnerReviewCategory, 'rating'>[] = [
  { key: 'communicationRating', label: 'Komunikacija', icon: 'chat' },
  { key: 'cleanlinessRating', label: 'Čistoća vozila', icon: 'cleaning_services' },
  { key: 'timelinessRating', label: 'Blagovremenost', icon: 'schedule' },
  { key: 'respectForRulesRating', label: 'Poštovanje pravila', icon: 'gavel' },
];