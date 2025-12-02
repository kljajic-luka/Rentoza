import { Routes } from '@angular/router';

import { RoleGuard } from '@core/guards/role.guard';
import { RoleRedirectGuard } from '@core/guards/role-redirect.guard';
import { guestGuard } from '@core/guards/guest.guard';

export const routes: Routes = [
  {
    path: '',
    canActivate: [RoleRedirectGuard],
    loadComponent: () =>
      import('@features/home/pages/home/home.component').then((m) => m.HomeComponent),
  },
  {
    path: 'auth',
    children: [
      {
        path: '',
        pathMatch: 'full',
        redirectTo: 'login',
      },
      {
        path: 'login',
        canActivate: [guestGuard],
        loadComponent: () =>
          import('@features/auth/pages/login/login.component').then((m) => m.LoginComponent),
      },
      {
        path: 'register',
        canActivate: [guestGuard],
        loadComponent: () =>
          import('@features/auth/pages/register/register.component').then(
            (m) => m.RegisterComponent
          ),
      },
      {
        path: 'callback',
        loadComponent: () =>
          import('@features/auth/pages/auth-callback/auth-callback.component').then(
            (m) => m.AuthCallbackComponent
          ),
      },
    ],
  },
  // OAuth2 cookie-only success route (no token in URL)
  {
    path: 'oauth2/success',
    loadComponent: () =>
      import('@features/auth/pages/auth-callback/auth-callback.component').then(
        (m) => m.AuthCallbackComponent
      ),
  },
  {
    path: 'pocetna',
    canActivate: [RoleRedirectGuard],
    loadComponent: () =>
      import('@features/home/pages/home/home.component').then((m) => m.HomeComponent),
  },
  {
    path: 'vozila',
    canActivate: [RoleRedirectGuard],
    loadComponent: () =>
      import('@features/cars/pages/car-list/car-list.component').then((m) => m.CarListComponent),
  },
  {
    path: 'cars',
    children: [
      {
        path: '',
        canActivate: [RoleRedirectGuard],
        loadComponent: () =>
          import('@features/cars/pages/car-list/car-list.component').then(
            (m) => m.CarListComponent
          ),
      },
      {
        path: ':id',
        loadComponent: () =>
          import('@features/cars/pages/car-detail/car-detail.component').then(
            (m) => m.CarDetailComponent
          ),
      },
    ],
  },
  {
    path: 'bookings',
    children: [
      {
        path: '',
        canActivate: [RoleGuard, RoleRedirectGuard],
        data: { roles: ['USER', 'ADMIN'] },
        loadComponent: () =>
          import('@features/bookings/pages/booking-history/booking-history.component').then(
            (m) => m.BookingHistoryComponent
          ),
      },
      {
        path: ':id',
        canActivate: [RoleGuard, RoleRedirectGuard],
        data: { roles: ['USER', 'OWNER', 'ADMIN'] },
        loadComponent: () =>
          import('@features/bookings/pages/booking-detail/booking-detail.component').then(
            (m) => m.BookingDetailComponent
          ),
      },
      {
        path: ':id/check-in',
        canActivate: [RoleGuard, RoleRedirectGuard],
        data: { roles: ['USER', 'OWNER', 'ADMIN'] },
        loadComponent: () =>
          import('@features/bookings/check-in/check-in-wizard.component').then(
            (m) => m.CheckInWizardComponent
          ),
      },
      {
        path: ':id/checkout',
        canActivate: [RoleGuard, RoleRedirectGuard],
        data: { roles: ['USER', 'OWNER', 'ADMIN'] },
        loadComponent: () =>
          import('@features/bookings/check-out/checkout-wizard.component').then(
            (m) => m.CheckoutWizardComponent
          ),
      },
      {
        path: ':id/review',
        canActivate: [RoleGuard, RoleRedirectGuard],
        data: { roles: ['USER', 'ADMIN'] },
        loadComponent: () =>
          import('@features/bookings/pages/add-review/add-review.component').then(
            (m) => m.AddReviewComponent
          ),
      },
    ],
  },
  {
    path: 'favorites',
    canActivate: [RoleGuard, RoleRedirectGuard],
    data: { roles: ['USER', 'ADMIN'] },
    loadComponent: () =>
      import('@features/favorites/pages/favorites-list/favorites-list.component').then(
        (m) => m.FavoritesListComponent
      ),
  },
  {
    path: 'reviews',
    loadComponent: () =>
      import('@features/reviews/pages/review-list/review-list.component').then(
        (m) => m.ReviewListComponent
      ),
  },
  {
    path: 'owner',
    canActivate: [RoleGuard, RoleRedirectGuard],
    data: { roles: ['OWNER', 'ADMIN'] },
    children: [
      {
        path: '',
        pathMatch: 'full',
        redirectTo: 'dashboard',
      },
      {
        path: 'dashboard',
        loadComponent: () =>
          import('@features/owner/pages/dashboard/owner-dashboard.component').then(
            (m) => m.OwnerDashboardComponent
          ),
      },
      {
        path: 'cars',
        loadComponent: () =>
          import('@features/owner/pages/my-cars/my-cars.component').then((m) => m.MyCarsComponent),
      },
      {
        path: 'cars/new',
        loadComponent: () =>
          import('@features/owner/pages/add-car-wizard/add-car-wizard.component').then(
            (m) => m.AddCarWizardComponent
          ),
      },
      {
        path: 'bookings',
        loadComponent: () =>
          import('@features/owner/pages/bookings/owner-bookings.component').then(
            (m) => m.OwnerBookingsComponent
          ),
      },
      {
        path: 'earnings',
        loadComponent: () =>
          import('@features/owner/pages/earnings/earnings.component').then(
            (m) => m.EarningsComponent
          ),
      },
      {
        path: 'reviews',
        loadComponent: () =>
          import('@features/owner/pages/reviews/owner-reviews.component').then(
            (m) => m.OwnerReviewsComponent
          ),
      },
      {
        path: 'verification',
        loadComponent: () =>
          import('@features/owner/pages/verification/verification.component').then(
            (m) => m.VerificationComponent
          ),
      },
    ],
  },
  {
    path: 'users',
    children: [
      {
        path: '',
        pathMatch: 'full',
        redirectTo: 'profile',
      },
      {
        path: 'profile',
        canActivate: [RoleGuard],
        data: { roles: ['USER', 'OWNER', 'ADMIN'] },
        loadComponent: () =>
          import('@features/users/pages/profile/profile.component').then((m) => m.ProfileComponent),
      },
    ],
  },
  {
    path: 'messages',
    canActivate: [RoleGuard, RoleRedirectGuard],
    data: { roles: ['USER', 'OWNER', 'ADMIN'] },
    loadComponent: () =>
      import('@features/messages/pages/messages/messages.component').then(
        (m) => m.MessagesComponent
      ),
  },
  {
    path: 'owners/:id',
    loadComponent: () =>
      import('@features/owner/pages/owner-profile-page/owner-profile-page.component').then(
        (m) => m.OwnerProfilePageComponent
      ),
  },
  {
    path: '**',
    loadComponent: () =>
      import('@shared/components/not-found/not-found.component').then((m) => m.NotFoundComponent),
  },
];
