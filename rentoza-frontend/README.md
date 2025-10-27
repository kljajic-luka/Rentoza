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

| Script        | Description                           |
| ------------- | ------------------------------------- |
| `npm run build` | Production build (outputs to `dist/`). |
| `npm test`      | Run unit tests with Karma/Jasmine.    |
| `npm run lint`  | Lint TypeScript & templates via ESLint. |

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
