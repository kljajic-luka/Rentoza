import { User } from './user.model';

export interface Review {
  id: string;
  rating: number;
  comment: string;
  createdAt: string;
  reviewer: Pick<User, 'id' | 'firstName' | 'lastName' | 'avatarUrl'>;
}
