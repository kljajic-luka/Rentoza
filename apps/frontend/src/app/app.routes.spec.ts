import { routes } from './app.routes';

describe('app.routes payment callback ordering', () => {
  it('places bookings/payment-return before bookings/:id to avoid shadowing', () => {
    const bookingsRoute = routes.find((route) => route.path === 'bookings');

    expect(bookingsRoute).toBeDefined();
    expect(Array.isArray(bookingsRoute?.children)).toBeTrue();

    const children = bookingsRoute?.children ?? [];
    const paymentReturnIndex = children.findIndex((child) => child.path === 'payment-return');
    const bookingIdIndex = children.findIndex((child) => child.path === ':id');

    expect(paymentReturnIndex).toBeGreaterThanOrEqual(0);
    expect(bookingIdIndex).toBeGreaterThanOrEqual(0);
    expect(paymentReturnIndex).toBeLessThan(bookingIdIndex);
  });
});
