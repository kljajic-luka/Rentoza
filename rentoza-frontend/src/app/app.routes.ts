import { Routes } from '@angular/router';

import { RoleGuard } from '@core/guards/role.guard';

export const routes: Routes = [
  {
    path: '',
    loadComponent: () =>
      import('@features/home/pages/home/home.component').then((m) => m.HomeComponent)
  },
  {
    path: 'auth',
    children: [
      {
        path: '',
        pathMatch: 'full',
        redirectTo: 'login'
      },
      {
        path: 'login',
        loadComponent: () =>
          import('@features/auth/pages/login/login.component').then((m) => m.LoginComponent)
      },
      {
        path: 'register',
        loadComponent: () =>
          import('@features/auth/pages/register/register.component').then((m) => m.RegisterComponent)
      }
    ]
  },
  {
    path: 'cars',
    children: [
      {
        path: '',
        loadComponent: () =>
          import('@features/cars/pages/car-list/car-list.component').then((m) => m.CarListComponent)
      },
      {
        path: ':id',
        loadComponent: () =>
          import('@features/cars/pages/car-detail/car-detail.component').then(
            (m) => m.CarDetailComponent
          )
      }
    ]
  },
  {
    path: 'bookings',
    canActivate: [RoleGuard],
    data: { roles: ['USER', 'OWNER', 'ADMIN'] },
    loadComponent: () =>
      import('@features/bookings/pages/booking-history/booking-history.component').then(
        (m) => m.BookingHistoryComponent
      )
  },
  {
    path: 'favorites',
    canActivate: [RoleGuard],
    data: { roles: ['USER', 'OWNER', 'ADMIN'] },
    loadComponent: () =>
      import('@features/favorites/pages/favorites-list/favorites-list.component').then(
        (m) => m.FavoritesListComponent
      )
  },
  {
    path: 'reviews',
    loadComponent: () =>
      import('@features/reviews/pages/review-list/review-list.component').then(
        (m) => m.ReviewListComponent
      )
  },
  {
    path: 'users',
    children: [
      {
        path: '',
        pathMatch: 'full',
        redirectTo: 'profile'
      },
      {
        path: 'profile',
        canActivate: [RoleGuard],
        data: { roles: ['USER', 'OWNER', 'ADMIN'] },
        loadComponent: () =>
          import('@features/users/pages/profile/profile.component').then((m) => m.ProfileComponent)
      }
    ]
  },
  {
    path: '**',
    loadComponent: () =>
      import('@shared/components/not-found/not-found.component').then((m) => m.NotFoundComponent)
  }
];
