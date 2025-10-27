import { HttpErrorResponse, HttpInterceptorFn } from '@angular/common/http';
import { inject } from '@angular/core';
import { ToastrService } from 'ngx-toastr';
import { catchError, throwError } from 'rxjs';

import { AuthService } from '@core/auth/auth.service';

export const errorResponseInterceptor: HttpInterceptorFn = (req, next) => {
  const toastr = inject(ToastrService);
  const authService = inject(AuthService);

  return next(req).pipe(
    catchError((error: HttpErrorResponse) => {
      const message = extractErrorMessage(error);
      toastr.error(message, 'Request failed');

      if (error.status === 401) {
        authService.logout();
      }

      return throwError(() => error);
    })
  );
};

function extractErrorMessage(error: HttpErrorResponse): string {
  if (typeof error.error === 'string' && error.error.trim().length > 0) {
    return error.error;
  }

  if (error.error?.message) {
    return error.error.message as string;
  }

  switch (error.status) {
    case 0:
      return 'Unable to reach the server. Please check your connection.';
    case 401:
      return 'You need to log in to continue.';
    case 403:
      return 'You are not authorised to perform this action.';
    case 404:
      return 'The requested resource was not found.';
    default:
      return 'Something went wrong. Please try again later.';
  }
}
