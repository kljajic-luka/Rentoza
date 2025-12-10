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
                (m) => m.UserDetailComponent
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
            path: ':id',
            loadComponent: () =>
              import('./cars/car-detail/car-detail.component').then((m) => m.CarDetailComponent),
          },
        ],
      },
      {
        path: 'disputes',
        children: [
          {
            path: '',
            loadComponent: () =>
              import('./disputes/dispute-list/dispute-list.component').then(
                (m) => m.DisputeListComponent
              ),
          },
          {
            path: ':id',
            loadComponent: () =>
              import('./disputes/dispute-detail/dispute-detail.component').then(
                (m) => m.DisputeDetailComponent
              ),
          },
        ],
      },
      {
        path: 'financial',
        loadComponent: () =>
          import('./financial/financial-dashboard/financial-dashboard.component').then(
            (m) => m.FinancialDashboardComponent
          ),
      },
    ],
  },
];
