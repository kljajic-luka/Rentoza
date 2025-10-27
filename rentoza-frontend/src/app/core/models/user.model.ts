import { UserRole } from './user-role.type';

export interface User {
  id: string;
  firstName: string;
  lastName: string;
  email: string;
  avatarUrl?: string;
  roles: UserRole[];
}

export interface UserProfile extends User {
  phoneNumber?: string;
  bio?: string;
  createdAt?: string;
}
