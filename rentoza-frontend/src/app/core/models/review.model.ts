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
}
