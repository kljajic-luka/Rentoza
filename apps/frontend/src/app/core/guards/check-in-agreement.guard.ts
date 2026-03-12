import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';
import { map, of } from 'rxjs';

import { BookingService } from '@core/services/booking.service';

export const checkInAgreementGuard: CanActivateFn = (route) => {
  const bookingService = inject(BookingService);
  const router = inject(Router);
  const bookingId = route.paramMap.get('id');

  if (!bookingId) {
    return of(router.createUrlTree(['/bookings']));
  }

  return bookingService.resolveCheckInAgreementGate(bookingId).pipe(
    map((gate) => {
      if (gate.state === 'allowed') {
        return true;
      }

      if (gate.state === 'retry') {
        return router.createUrlTree(['/bookings', bookingId], {
          queryParams: { agreementStatusUnavailable: '1' },
        });
      }

      return router.createUrlTree(['/bookings', bookingId], {
        queryParams: { agreementRequired: '1' },
      });
    }),
  );
};