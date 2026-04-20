# Rentoza Frontend

Angular 18 (v20) single-page application prepared for integration with the Spring Boot Rentoza backend (`http://localhost:8080/api`). The project uses standalone bootstrapping, lazy-loaded feature modules, Angular Material, Flex Layout, and JWT-based authentication helpers.

## Getting started

### Prerequisites

- Node.js v22.12+ (or ≥ v20.19)
- npm v10+

### Installation

```bash
npm install
```

### Development server

```bash
npm start
```

Navigate to `http://localhost:4200/`. The app reloads automatically when you change any source file.

### Additional scripts

| Script | Description |
| ------------- | ------------------------------------- |
| `npm run build` | Production build (outputs to `dist/`). |
| `npm run build:staging` | Staging build + payment-mode guard (`mock` enforced). |
| `npm run serve:staging` | Serve the app with staging environment settings. |
| `npm run deploy:staging` | Build staging + deploy to Firebase preview channel (`staging`, expires in 7 days). |
| `npm test` | Run unit tests with Karma/Jasmine. |
| `npm run lint` | Lint TypeScript & templates via ESLint. |

## Project structure

```
src/app
├── app.routes.ts        # Standalone route map
├── core                 # Cross-cutting concerns
│   ├── auth             # Auth service & token interceptor
│   ├── guards           # Role-based guard
│   ├── interceptors     # Global HTTP interceptors
│   ├── models           # DTO interfaces (Car, Booking, User, Review)
│   └── services         # REST API gateways (cars, bookings, users, reviews, theme)
├── features             # Standalone feature components per domain (home, auth, cars, bookings, reviews, users)
└── shared               # Reusable standalone components, directives, and pipes
```

Dedicated environment files live in `src/environments/` with `baseApiUrl` preconfigured for development and production builds.

## Key features

- **Authentication**: `AuthService` handles login/register/logout, JWT storage, and profile refresh. `RoleGuard` restricts routes to OWNER/USER/ADMIN roles.
- **HTTP interceptors**: Token interceptor attaches JWTs; the error interceptor surfaces API failures via `ngx-toastr` notifications.
- **UI shell**: Responsive Angular Material toolbar + sidenav with theme toggle (`ThemeService`) and adaptive navigation.
- **Feature navigation**: Lazy standalone routes for home, auth, cars, reviews, bookings, and users.
- **Reusable layer**: Standalone shared components/directives/pipes (e.g., layout shell, role directive, display-name pipe) can be imported directly where needed.
- **Styling**: SCSS-based Material theming with light/dark palettes and Toastr integration.

## ESLint & Prettier

- ESLint configured via `.eslintrc.json` (Angular, TypeScript & template rules).
- Prettier rules embedded in `package.json` to keep formatting consistent. Run `npm run lint` before committing.

## Backend integration tips

- REST endpoints assume the Spring Boot API is reachable at `http://localhost:8080/api`.
- Adjust `allowedDomains` in `main.ts` if you expose the backend under a different host/port.
- Use the `SKIP_AUTH` HTTP context token (from `core/auth/token.interceptor`) on requests that must bypass JWT headers.

## Next steps

- Hook services to real backend DTOs once the API contracts are final.
- Extend guards or route data as additional roles/permissions emerge.
- Add e2e coverage (e.g., Cypress or Playwright) when workflows stabilize.

## Legal & Privacy

### Legal Pages

The application includes GDPR-compliant legal pages for the beta testing phase:

| Route | Component | Description |
|-------|-----------|-------------|
| `/privacy` | `PrivacyPolicyComponent` | Privacy policy covering data collection, processing, third-party services, user rights, and GDPR compliance |
| `/beta-terms` | `BetaTermsComponent` | Beta terms and conditions including disclaimer, tester responsibilities, and feedback usage |

**Location:** `src/app/features/legal/pages/`

### Footer Links

Legal links are displayed in the site footer (`src/app/shared/components/layout/`) and include:
- Privacy Policy
- Beta Terms
- Contact email

### Auth Pages

Login and registration pages include:
- **Beta Notice Banner**: Informs users this is a private friends-and-family beta
- **Legal Links**: "By signing in/registering, you agree to our Beta Terms & Privacy Policy"

### Cookie Consent

Currently **not required** because:
- No third-party analytics (Google Analytics, Facebook Pixel, etc.)
- No advertising cookies
- Only essential session cookies for authentication

The Privacy Policy includes a statement: *"We currently do not use analytics or advertising cookies. If this changes, this page will be updated."*

If analytics are added in the future, implement a cookie consent banner that:
1. Blocks analytics scripts until consent is given
2. Provides Accept/Reject options
3. Stores preference in localStorage
4. Does not show again after user makes a choice

### Backend PII Protection

The Spring Boot backend includes:
- `PiiMaskingConverter` in logback configuration
- Masks emails, phone numbers, and credit card numbers in logs
- Tokens logged only as hashes (first 10 chars)
