import { HttpContextToken } from '@angular/common/http';

export const SKIP_AUTH = new HttpContextToken<boolean>(() => false);
export const RETRIED_REQUEST = new HttpContextToken<boolean>(() => false);