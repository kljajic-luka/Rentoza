import { User, UserProfile } from './user.model';

export interface LoginRequest {
  email: string;
  password: string;
}

export interface RegisterRequest {
  firstName: string;
  lastName: string;
  email: string;
  password: string;
  phone?: string;
  role?: string;
}

export interface AuthResponse {
  accessToken: string;
  refreshToken?: string;
  user?: User | UserProfile;
}
