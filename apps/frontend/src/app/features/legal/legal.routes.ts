import { Routes } from '@angular/router';

/**
 * Legal Routes
 *
 * Routes for legal pages (Privacy Policy, Terms of Service, Beta Terms)
 * These are publicly accessible, no authentication required.
 */
export const LEGAL_ROUTES: Routes = [
  {
    path: 'privacy-policy',
    loadComponent: () =>
      import('./pages/privacy-policy/privacy-policy.component').then(
        (m) => m.PrivacyPolicyComponent,
      ),
    title: 'Privacy Policy - Rentoza',
  },
  {
    path: 'privacy',
    redirectTo: 'privacy-policy',
    pathMatch: 'full',
  },
  {
    path: 'terms-of-service',
    loadComponent: () =>
      import('./pages/terms-of-service/terms-of-service.component').then(
        (m) => m.TermsOfServiceComponent,
      ),
    title: 'Terms of Service - Rentoza',
  },
  {
    path: 'terms',
    redirectTo: 'terms-of-service',
    pathMatch: 'full',
  },
  {
    path: 'beta-terms',
    loadComponent: () =>
      import('./pages/beta-terms/beta-terms.component').then((m) => m.BetaTermsComponent),
    title: 'Beta Terms - Rentoza',
  },
];
