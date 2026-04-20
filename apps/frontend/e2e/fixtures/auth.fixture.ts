import {
  test as base,
  expect,
  type Page,
  type Cookie,
  type BrowserContext,
} from '@playwright/test';

/**
 * Authentication Fixture for Playwright Tests
 *
 * Provides reusable authentication helpers and session management
 * for cookie-based authentication testing.
 *
 * Usage:
 * ```typescript
 * import { test, expect } from './fixtures/auth.fixture';
 *
 * test('my authenticated test', async ({ authenticatedPage }) => {
 *   // Page is already logged in with cookies set
 *   await authenticatedPage.goto('/profile');
 * });
 * ```
 */

const DEFAULT_USER_EMAIL =
  process.env.E2E_TEST_EMAIL ??
  process.env.E2E_TEST_USER_EMAIL ??
  process.env.LOCAL_AUTH_USER_EMAIL ??
  'demo.user.local@rentoza.rs';

const DEFAULT_USER_PASSWORD =
  process.env.E2E_TEST_PASSWORD ??
  process.env.E2E_TEST_USER_PASSWORD ??
  process.env.LOCAL_AUTH_USER_PASSWORD ??
  'DemoUser123!';

export const TEST_USER = {
  email: DEFAULT_USER_EMAIL,
  password: DEFAULT_USER_PASSWORD,
};

export const TEST_OWNER = {
  email: process.env.E2E_OWNER_EMAIL ?? process.env.LOCAL_AUTH_OWNER_EMAIL ?? 'demo.owner.local@rentoza.rs',
  password:
    process.env.E2E_OWNER_PASSWORD ?? process.env.LOCAL_AUTH_OWNER_PASSWORD ?? 'DemoOwner123!',
};

export const TEST_ADMIN = {
  email: process.env.E2E_ADMIN_EMAIL ?? process.env.LOCAL_AUTH_ADMIN_EMAIL ?? 'demo.admin.local@rentoza.rs',
  password:
    process.env.E2E_ADMIN_PASSWORD ?? process.env.LOCAL_AUTH_ADMIN_PASSWORD ?? 'DemoAdmin123!',
};

export const COOKIE_NAMES = {
  ACCESS_TOKEN: 'access_token',
  REFRESH_TOKEN: 'rentoza_refresh',
  XSRF_TOKEN: 'XSRF-TOKEN',
};

export const ENDPOINTS = {
  LOGIN: '/api/auth/supabase/login',
  REFRESH: '/api/auth/supabase/refresh',
  USERS_ME: '/api/users/me',
  LOGOUT: '/api/auth/supabase/logout',
};

// Helper to get cookie by name
export async function getCookieByName(page: Page, name: string): Promise<Cookie | undefined> {
  const cookies = await page.context().cookies();
  return cookies.find((cookie) => cookie.name === name);
}

// Helper to assert cookie flags
export function assertCookieFlags(
  cookie: Cookie,
  expectedFlags: {
    httpOnly?: boolean;
    secure?: boolean;
    sameSite?: 'Strict' | 'Lax' | 'None';
  }
) {
  if (expectedFlags.httpOnly !== undefined) {
    expect(cookie.httpOnly, `Cookie ${cookie.name} httpOnly flag`).toBe(expectedFlags.httpOnly);
  }
  if (expectedFlags.secure !== undefined) {
    expect(cookie.secure, `Cookie ${cookie.name} secure flag`).toBe(expectedFlags.secure);
  }
  if (expectedFlags.sameSite !== undefined) {
    expect(cookie.sameSite, `Cookie ${cookie.name} sameSite flag`).toBe(expectedFlags.sameSite);
  }
}

// Helper to wait for page ready
export async function waitForPageReady(page: Page) {
  await page.waitForLoadState('networkidle');
  await page.waitForLoadState('domcontentloaded');
}

/**
 * Clear all browser state for cold start testing.
 * Ensures zero cookies, zero localStorage, zero sessionStorage.
 */
export async function clearAllBrowserState(page: Page, context: BrowserContext): Promise<void> {
  // Clear cookies
  await context.clearCookies();

  // Navigate to app first to ensure storage is accessible
  await page.goto('http://localhost:4200', { waitUntil: 'domcontentloaded' });

  // Clear localStorage and sessionStorage
  await page.evaluate(() => {
    localStorage.clear();
    sessionStorage.clear();
  });
}

/**
 * Wait for the bootstrap XHR to complete.
 * This is the initial request that triggers XSRF-TOKEN cookie issuance.
 */
export async function waitForBootstrapXhr(page: Page): Promise<void> {
  await page.waitForResponse(
    (response) => {
      const url = response.url();
      return url.includes(ENDPOINTS.REFRESH) || url.includes(ENDPOINTS.USERS_ME);
    },
    { timeout: 15000 }
  );
}

/**
 * Verify that XSRF-TOKEN cookie is set (required before login POST).
 */
export async function verifyXsrfTokenExists(context: BrowserContext): Promise<Cookie> {
  const cookies = await context.cookies();
  const xsrfCookie = cookies.find((cookie) => cookie.name === COOKIE_NAMES.XSRF_TOKEN);
  expect(xsrfCookie, 'XSRF-TOKEN cookie must be set before login').toBeDefined();
  expect(xsrfCookie!.httpOnly, 'XSRF-TOKEN must NOT be HttpOnly').toBe(false);
  return xsrfCookie!;
}

/**
 * Get cookie by name from browser context (alternative to page-based).
 */
export async function getCookieFromContext(
  context: BrowserContext,
  name: string
): Promise<Cookie | undefined> {
  const cookies = await context.cookies();
  return cookies.find((cookie) => cookie.name === name);
}

// Helper to perform login
export async function performLogin(
  page: Page,
  email: string = TEST_USER.email,
  password: string = TEST_USER.password
) {
  await page.goto('http://localhost:4200/auth/login');
  await waitForPageReady(page);

  await page.fill('input[formControlName="email"]', email);
  await page.fill('input[formControlName="password"]', password);

  // Wait for Angular to process form validation
  await page.waitForTimeout(500);

  // Wait for submit button to be clickable
  const submitButton = page.locator('button[type="submit"]:has-text("Prijavi se")');
  await submitButton.waitFor({ state: 'visible', timeout: 5000 });

  await Promise.all([
    page.waitForURL(/\/(home|dashboard|profile|cars|bookings|owner)?$/, { timeout: 15000 }),
    submitButton.click(),
  ]);

  await waitForPageReady(page);
}

// Helper to verify authentication state
export async function verifyAuthenticationState(page: Page) {
  const accessTokenCookie = await getCookieByName(page, COOKIE_NAMES.ACCESS_TOKEN);
  expect(
    accessTokenCookie,
    'User should be authenticated (access_token cookie present)'
  ).toBeDefined();

  // Verify no tokens in localStorage
  const accessTokenInStorage = await page.evaluate(() => localStorage.getItem('access_token'));
  expect(
    accessTokenInStorage,
    'access_token should NOT be in localStorage (cookie mode)'
  ).toBeNull();
}

// Helper to verify unauthenticated state
export async function verifyUnauthenticatedState(page: Page) {
  const accessTokenCookie = await getCookieByName(page, COOKIE_NAMES.ACCESS_TOKEN);
  expect(
    accessTokenCookie,
    'User should NOT be authenticated (no access_token cookie)'
  ).toBeUndefined();
}

// Extended test with authentication fixtures
type AuthFixtures = {
  authenticatedPage: Page;
  authHelpers: {
    login: (email?: string, password?: string) => Promise<void>;
    logout: () => Promise<void>;
    verifyAuthenticated: () => Promise<void>;
    verifyUnauthenticated: () => Promise<void>;
    getCookie: (name: string) => Promise<Cookie | undefined>;
  };
};

export const test = base.extend<AuthFixtures>({
  // Fixture: Pre-authenticated page
  authenticatedPage: async ({ page }, use) => {
    // Perform login before test
    await performLogin(page);
    await verifyAuthenticationState(page);

    // Provide authenticated page to test
    await use(page);

    // Cleanup: logout after test
    await page.context().clearCookies();
  },

  // Fixture: Auth helper functions
  authHelpers: async ({ page }, use) => {
    const helpers = {
      login: async (email?: string, password?: string) => {
        await performLogin(page, email, password);
      },
      logout: async () => {
        await page
          .click(
            'button:has-text("Logout"), button:has-text("Odjavi se"), [data-testid="logout-button"]'
          )
          .catch(() => {
            return page.click('.user-menu button, .dropdown-menu button:has-text("Logout")');
          });
        await page.waitForURL('**/auth/login', { timeout: 10000 });
        await waitForPageReady(page);
      },
      verifyAuthenticated: async () => {
        await verifyAuthenticationState(page);
      },
      verifyUnauthenticated: async () => {
        await verifyUnauthenticatedState(page);
      },
      getCookie: async (name: string) => {
        return getCookieByName(page, name);
      },
    };

    await use(helpers);
  },
});

export { expect };
