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
