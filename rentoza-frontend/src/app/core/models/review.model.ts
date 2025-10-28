export interface Review {
  id: string;
  rating: number;
  comment: string;
  createdAt: string;
  reviewerFirstName?: string;
  reviewerLastName?: string;
}
