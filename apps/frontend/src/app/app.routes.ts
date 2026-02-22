import { Routes } from '@angular/router';

import { RoleGuard } from '@core/guards/role.guard';
import { RoleRedirectGuard } from '@core/guards/role-redirect.guard';
import { guestGuard } from '@core/guards/guest.guard';
import { ProfileCompletionGuard } from '@core/guards/profile-completion.guard';

export const routes: Routes = [
  {
    path: '',
    canActivate: [RoleRedirectGuard],
    title: 'Iznajmi auto u Srbiji | Rentoza',
    loadComponent: () =>
      import('@features/home/pages/home/home.component').then((m) => m.HomeComponent),
  },
  {
    path: 'admin',
    canActivate: [RoleGuard, RoleRedirectGuard],
    data: { roles: ['ADMIN'] },
    loadChildren: () => import('@features/admin/admin.routes').then((m) => m.ADMIN_ROUTES),
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
        title: 'Prijava | Rentoza',
        loadComponent: () =>
          import('@features/auth/pages/login/login.component').then((m) => m.LoginComponent),
      },
      {
        path: 'register',
        canActivate: [guestGuard],
        title: 'Registracija | Rentoza',
        loadComponent: () =>
          import('@features/auth/pages/register/register.component').then(
            (m) => m.RegisterComponent,
          ),
      },
      // ═══════════════════════════════════════════════════════════════════════════
      // PHASE 3: PASSWORD RECOVERY (Turo Standard P0)
      // ═══════════════════════════════════════════════════════════════════════════
      {
        path: 'forgot-password',
        canActivate: [guestGuard],
        loadComponent: () =>
          import('@features/auth/pages/forgot-password/forgot-password.component').then(
            (m) => m.ForgotPasswordComponent,
          ),
      },
      {
        path: 'reset-password',
        canActivate: [guestGuard],
        loadComponent: () =>
          import('@features/auth/pages/reset-password/reset-password.component').then(
            (m) => m.ResetPasswordComponent,
          ),
      },
      {
        path: 'callback',
        loadComponent: () =>
          import('@features/auth/pages/auth-callback/auth-callback.component').then(
            (m) => m.AuthCallbackComponent,
          ),
      },
      // ═══════════════════════════════════════════════════════════════════════════
      // SUPABASE EMAIL CONFIRMATION CALLBACK
      // ═══════════════════════════════════════════════════════════════════════════
      // Handles Supabase email verification redirect with access_token in URL hash
      // Flow: Supabase verifies email → redirects here → we exchange token → login
      {
        path: 'confirm',
        loadComponent: () =>
          import('@features/auth/pages/email-confirm/email-confirm.component').then(
            (m) => m.EmailConfirmComponent,
          ),
      },
      // ═══════════════════════════════════════════════════════════════════════════
      // PHASE 2: OAuth Profile Completion Route
      // ═══════════════════════════════════════════════════════════════════════════
      // For Google OAuth users with registrationStatus=INCOMPLETE
      // User must be authenticated but profile is incomplete
      {
        path: 'complete-profile',
        canActivate: [RoleGuard],
        data: { roles: ['USER', 'OWNER'] },
        loadComponent: () =>
          import('@features/auth/pages/oauth-complete/oauth-complete.component').then(
            (m) => m.OAuthCompleteComponent,
          ),
      },
      // ═══════════════════════════════════════════════════════════════════════════
      // SUPABASE GOOGLE OAUTH CALLBACK
      // ═══════════════════════════════════════════════════════════════════════════
      // Handles Supabase Google OAuth redirect with code and state parameters.
      // Flow: Google → Supabase → Backend redirect → Frontend callback
      // The backend returns HttpOnly cookies; this component handles navigation.
      {
        path: 'supabase/google/callback',
        loadComponent: () =>
          import('@features/auth/pages/supabase-google-callback/supabase-google-callback.component').then(
            (m) => m.SupabaseGoogleCallbackComponent,
          ),
      },
    ],
  },
  // OAuth2 cookie-only success route (no token in URL)
  {
    path: 'oauth2/success',
    loadComponent: () =>
      import('@features/auth/pages/auth-callback/auth-callback.component').then(
        (m) => m.AuthCallbackComponent,
      ),
  },
  {
    path: 'pocetna',
    canActivate: [RoleRedirectGuard],
    title: 'Iznajmi auto u Srbiji | Rentoza',
    loadComponent: () =>
      import('@features/home/pages/home/home.component').then((m) => m.HomeComponent),
  },
  {
    path: 'vozila',
    canActivate: [RoleRedirectGuard],
    title: 'Pretraži automobile | Rentoza',
    loadComponent: () =>
      import('@features/cars/pages/car-list/car-list.component').then((m) => m.CarListComponent),
  },
  {
    path: 'cars',
    children: [
      {
        path: '',
        canActivate: [RoleRedirectGuard],
        title: 'Pretraži automobile | Rentoza',
        loadComponent: () =>
          import('@features/cars/pages/car-list/car-list.component').then(
            (m) => m.CarListComponent,
          ),
      },
      {
        path: ':id',
        loadComponent: () =>
          import('@features/cars/pages/car-detail/car-detail.component').then(
            (m) => m.CarDetailComponent,
          ),
      },
    ],
  },
  {
    path: 'bookings',
    canActivate: [ProfileCompletionGuard], // Require complete profile for booking actions
    children: [
      {
        path: '',
        canActivate: [RoleGuard, RoleRedirectGuard],
        title: 'Moje rezervacije | Rentoza',
        data: { roles: ['USER', 'ADMIN'] },
        loadComponent: () =>
          import('@features/bookings/pages/booking-history/booking-history.component').then(
            (m) => m.BookingHistoryComponent,
          ),
      },
      {
        path: ':id',
        canActivate: [RoleGuard, RoleRedirectGuard],
        data: { roles: ['USER', 'OWNER', 'ADMIN'] },
        loadComponent: () =>
          import('@features/bookings/pages/booking-detail/booking-detail.component').then(
            (m) => m.BookingDetailComponent,
          ),
      },
      {
        path: ':id/confirmation',
        canActivate: [RoleGuard, RoleRedirectGuard],
        title: 'Rezervacija potvrđena | Rentoza',
        data: { roles: ['USER', 'OWNER', 'ADMIN'] },
        loadComponent: () =>
          import('@features/bookings/pages/booking-confirmation/booking-confirmation.component').then(
            (m) => m.BookingConfirmationComponent,
          ),
      },
      {
        path: ':id/check-in',
        canActivate: [RoleGuard, RoleRedirectGuard],
        data: { roles: ['USER', 'OWNER', 'ADMIN'] },
        loadComponent: () =>
          import('@features/bookings/check-in/check-in-wizard.component').then(
            (m) => m.CheckInWizardComponent,
          ),
      },
      {
        path: ':id/checkout',
        canActivate: [RoleGuard, RoleRedirectGuard],
        data: { roles: ['USER', 'OWNER', 'ADMIN'] },
        loadComponent: () =>
          import('@features/bookings/check-out/checkout-wizard.component').then(
            (m) => m.CheckoutWizardComponent,
          ),
      },
      {
        path: ':id/review',
        canActivate: [RoleGuard, RoleRedirectGuard],
        data: { roles: ['USER', 'ADMIN'] },
        loadComponent: () =>
          import('@features/bookings/pages/add-review/add-review.component').then(
            (m) => m.AddReviewComponent,
          ),
      },
    ],
  },
  {
    path: 'favorites',
    canActivate: [RoleGuard, RoleRedirectGuard],
    title: 'Omiljeni automobili | Rentoza',
    data: { roles: ['USER', 'ADMIN'] },
    loadComponent: () =>
      import('@features/favorites/pages/favorites-list/favorites-list.component').then(
        (m) => m.FavoritesListComponent,
      ),
  },
  {
    path: 'verify-license',
    canActivate: [RoleGuard],
    title: 'Verifikacija vozačke dozvole | Rentoza',
    data: { roles: ['USER', 'ADMIN'] },
    loadComponent: () =>
      import('@features/renter-verification/pages/renter-verification-page/renter-verification-page.component').then(
        (m) => m.RenterVerificationPageComponent,
      ),
  },
  {
    path: 'reviews',
    title: 'Recenzije | Rentoza',
    loadComponent: () =>
      import('@features/reviews/pages/review-list/review-list.component').then(
        (m) => m.ReviewListComponent,
      ),
  },
  {
    path: 'owner',
    canActivate: [RoleGuard, RoleRedirectGuard, ProfileCompletionGuard], // Require complete profile for owner actions
    data: { roles: ['OWNER', 'ADMIN'] },
    children: [
      {
        path: '',
        pathMatch: 'full',
        redirectTo: 'dashboard',
      },
      {
        path: 'dashboard',
        title: 'Dashboard vlasnika | Rentoza',
        loadComponent: () =>
          import('@features/owner/pages/dashboard/owner-dashboard.component').then(
            (m) => m.OwnerDashboardComponent,
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
            (m) => m.AddCarWizardComponent,
          ),
      },
      {
        path: 'bookings',
        loadComponent: () =>
          import('@features/owner/pages/bookings/owner-bookings.component').then(
            (m) => m.OwnerBookingsComponent,
          ),
      },
      {
        path: 'booking/:id/review',
        loadComponent: () =>
          import('@features/owner/pages/add-review/owner-add-review.component').then(
            (m) => m.OwnerAddReviewComponent,
          ),
      },
      {
        path: 'earnings',
        loadComponent: () =>
          import('@features/owner/pages/earnings/earnings.component').then(
            (m) => m.EarningsComponent,
          ),
      },
      {
        path: 'reviews',
        loadComponent: () =>
          import('@features/owner/pages/reviews/owner-reviews.component').then(
            (m) => m.OwnerReviewsComponent,
          ),
      },
      {
        path: 'verification',
        loadComponent: () =>
          import('@features/owner/pages/owner-verification-page/owner-verification-page.component').then(
            (m) => m.OwnerVerificationPageComponent,
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
        title: 'Moj profil | Rentoza',
        data: { roles: ['USER', 'OWNER', 'ADMIN'] },
        loadComponent: () =>
          import('@features/users/pages/profile/profile.component').then((m) => m.ProfileComponent),
      },
    ],
  },
  {
    path: 'messages',
    canActivate: [RoleGuard, RoleRedirectGuard],
    title: 'Poruke | Rentoza',
    data: { roles: ['USER', 'OWNER', 'ADMIN'] },
    loadComponent: () =>
      import('@features/messages/pages/messages/messages.component').then(
        (m) => m.MessagesComponent,
      ),
  },
  {
    path: 'owners/:id',
    loadComponent: () =>
      import('@features/owner/pages/owner-profile-page/owner-profile-page.component').then(
        (m) => m.OwnerProfilePageComponent,
      ),
  },
  // ═══════════════════════════════════════════════════════════════════════════
  // LEGAL PAGES - Privacy Policy & Beta Terms
  // ═══════════════════════════════════════════════════════════════════════════
  // Publicly accessible, no authentication required
  {
    path: 'privacy',
    loadComponent: () =>
      import('@features/legal/pages/privacy-policy/privacy-policy.component').then(
        (m) => m.PrivacyPolicyComponent,
      ),
    title: 'Privacy Policy - Rentoza',
  },
  {
    path: 'beta-terms',
    loadComponent: () =>
      import('@features/legal/pages/beta-terms/beta-terms.component').then(
        (m) => m.BetaTermsComponent,
      ),
    title: 'Beta Terms - Rentoza',
  },
  {
    path: '**',
    loadComponent: () =>
      import('@shared/components/not-found/not-found.component').then((m) => m.NotFoundComponent),
  },
];
