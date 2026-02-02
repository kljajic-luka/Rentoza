import { Routes } from '@angular/router';

/**
 * Legal Routes
 *
 * Routes for legal pages (Privacy Policy, Beta Terms)
 * These are publicly accessible, no authentication required.
 */
export const LEGAL_ROUTES: Routes = [
  {
    path: 'privacy',
    loadComponent: () =>
      import('./pages/privacy-policy/privacy-policy.component').then(
        (m) => m.PrivacyPolicyComponent,
      ),
    title: 'Privacy Policy - Rentoza',
  },
  {
    path: 'beta-terms',
    loadComponent: () =>
      import('./pages/beta-terms/beta-terms.component').then((m) => m.BetaTermsComponent),
    title: 'Beta Terms - Rentoza',
  },
];
