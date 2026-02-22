import { Routes } from '@angular/router';
import { AdminLayoutComponent } from './admin-layout/admin-layout.component';

export const ADMIN_ROUTES: Routes = [
  {
    path: '',
    component: AdminLayoutComponent,
    children: [
      {
        path: '',
        redirectTo: 'dashboard',
        pathMatch: 'full',
      },
      {
        path: 'dashboard',
        loadComponent: () =>
          import('./dashboard/dashboard.component').then((m) => m.AdminDashboardComponent),
      },
      {
        path: 'users',
        children: [
          {
            path: '',
            loadComponent: () =>
              import('./users/user-list/user-list.component').then((m) => m.UserListComponent),
          },
          {
            path: ':id',
            loadComponent: () =>
              import('./users/user-detail/user-detail.component').then(
                (m) => m.UserDetailComponent,
              ),
          },
        ],
      },
      {
        path: 'cars',
        children: [
          {
            path: '',
            loadComponent: () =>
              import('./cars/car-list/car-list.component').then((m) => m.CarListComponent),
          },
          {
            path: ':carId/review',
            loadComponent: () =>
              import('./cars/car-review/car-review.component').then((m) => m.CarReviewComponent),
          },
          {
            path: ':id',
            loadComponent: () =>
              import('./cars/car-detail/car-detail.component').then((m) => m.CarDetailComponent),
          },
        ],
      },
      {
        path: 'bookings',
        children: [
          {
            path: '',
            loadComponent: () =>
              import('./bookings/booking-list/booking-list.component').then(
                (m) => m.BookingListComponent,
              ),
          },
          {
            path: ':id',
            loadComponent: () =>
              import('./bookings/booking-detail/booking-detail.component').then(
                (m) => m.BookingDetailComponent,
              ),
          },
        ],
      },
      {
        path: 'flagged-messages',
        loadComponent: () =>
          import('./flagged-messages/flagged-message-list/flagged-message-list.component').then(
            (m) => m.FlaggedMessageListComponent,
          ),
      },
      {
        path: 'disputes',
        children: [
          {
            path: '',
            loadComponent: () =>
              import('./disputes/dispute-list/dispute-list.component').then(
                (m) => m.DisputeListComponent,
              ),
          },
          {
            path: ':id',
            loadComponent: () =>
              import('./disputes/dispute-detail/dispute-detail.component').then(
                (m) => m.DisputeDetailComponent,
              ),
          },
        ],
      },
      {
        path: 'renter-verifications',
        children: [
          {
            path: '',
            loadComponent: () =>
              import('./renter-verifications/renter-verification-list/renter-verification-list.component').then(
                (m) => m.RenterVerificationListComponent,
              ),
          },
          {
            path: ':userId',
            loadComponent: () =>
              import('./renter-verifications/renter-verification-detail/renter-verification-detail.component').then(
                (m) => m.RenterVerificationDetailComponent,
              ),
          },
        ],
      },
      {
        path: 'financial',
        loadComponent: () =>
          import('./financial/financial-dashboard/financial-dashboard.component').then(
            (m) => m.FinancialDashboardComponent,
          ),
      },
      {
        path: 'settings',
        loadComponent: () =>
          import('./settings/settings.component').then((m) => m.AdminSettingsComponent),
      },
    ],
  },
];