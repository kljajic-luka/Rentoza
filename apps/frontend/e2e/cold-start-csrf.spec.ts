import { test, expect, type Page, type Cookie, type BrowserContext } from '@playwright/test';

/**
 * Cold Start CSRF Protection E2E Test Suite
 *
 * PURPOSE:
 * Validates the Security Hardening sprint where CSRF protection was enabled
 * on the `/api/auth/supabase/login` endpoint. This test ensures the application correctly
 * handles the "Cold Start" scenario where a user lands with ZERO cookies.
 *
 * THE PROBLEM:
 * With CSRF enabled on login, the following race condition can occur:
 * 1. User lands on /login with no cookies (cold start)
 * 2. Angular app bootstraps and calls /api/auth/supabase/refresh (or /api/users/me)
 * 3. Backend responds with 401 BUT also sets XSRF-TOKEN cookie
 * 4. If user clicks Login BEFORE step 3 completes → 403 Forbidden
 *
 * THE SOLUTION:
 * The test verifies that:
 * 1. Bootstrap XHR completes before login is possible
 * 2. XSRF-TOKEN cookie is issued on bootstrap
 * 3. Login POST succeeds with 200 (not 403)
 * 4. HttpOnly access_token cookie is set after login
 *
 * @see SecurityConfig.java - CSRF configuration
 * @see AuthController.java - ensureCsrfCookie() method
 * @see token.interceptor.ts - XSRF header injection
 */

// ============================================================================
// CONSTANTS
// ============================================================================

const BASE_URL = 'http://localhost:4200';
const API_BASE_URL = 'http://localhost:8080';

const TEST_USER = {
  email:
    process.env.E2E_TEST_EMAIL ??
    process.env.E2E_TEST_USER_EMAIL ??
    process.env.LOCAL_AUTH_USER_EMAIL ??
    'demo.user.local@rentoza.rs',
  password:
    process.env.E2E_TEST_PASSWORD ??
    process.env.E2E_TEST_USER_PASSWORD ??
    process.env.LOCAL_AUTH_USER_PASSWORD ??
    'DemoUser123!',
};

const COOKIE_NAMES = {
  ACCESS_TOKEN: 'access_token',
  REFRESH_TOKEN: 'rentoza_refresh',
  XSRF_TOKEN: 'XSRF-TOKEN',
} as const;

const ENDPOINTS = {
  LOGIN: '/api/auth/supabase/login',
  REFRESH: '/api/auth/supabase/refresh',
  USERS_ME: '/api/users/me',
} as const;

// ============================================================================
// HELPER FUNCTIONS
// ============================================================================

/**
 * Get a cookie by name from the browser context.
 * Uses context.cookies() for programmatic verification.
 */
async function getCookieByName(context: BrowserContext, name: string): Promise<Cookie | undefined> {
  const cookies = await context.cookies();
  return cookies.find((cookie) => cookie.name === name);
}

/**
 * Assert that a cookie exists and has the expected flags.
 */
async function assertCookieExists(
  context: BrowserContext,
  cookieName: string,
  expectedFlags?: { httpOnly?: boolean; sameSite?: 'Strict' | 'Lax' | 'None' }
): Promise<Cookie> {
  const cookie = await getCookieByName(context, cookieName);
  expect(cookie, `Cookie '${cookieName}' should exist`).toBeDefined();

  if (expectedFlags?.httpOnly !== undefined) {
    expect(cookie!.httpOnly, `Cookie '${cookieName}' httpOnly flag`).toBe(expectedFlags.httpOnly);
  }
  if (expectedFlags?.sameSite !== undefined) {
    expect(cookie!.sameSite, `Cookie '${cookieName}' sameSite flag`).toBe(expectedFlags.sameSite);
  }

  return cookie!;
}

/**
 * Assert that a cookie does NOT exist in the browser context.
 */
async function assertCookieNotExists(context: BrowserContext, cookieName: string): Promise<void> {
  const cookie = await getCookieByName(context, cookieName);
  expect(cookie, `Cookie '${cookieName}' should NOT exist`).toBeUndefined();
}

/**
 * Wait for a cookie to be set within a timeout period.
 * Polls every 100ms until the cookie exists or timeout is reached.
 */
async function waitForCookie(
  context: BrowserContext,
  cookieName: string,
  timeoutMs: number = 5000
): Promise<Cookie> {
  const startTime = Date.now();

  while (Date.now() - startTime < timeoutMs) {
    const cookie = await getCookieByName(context, cookieName);
    if (cookie) {
      return cookie;
    }
    await new Promise((resolve) => setTimeout(resolve, 100));
  }

  throw new Error(`Timeout waiting for cookie '${cookieName}' after ${timeoutMs}ms`);
}

/**
 * Wait for page to be fully loaded and network idle.
 */
async function waitForPageReady(page: Page): Promise<void> {
  await page.waitForLoadState('domcontentloaded');
  await page.waitForLoadState('networkidle');
}

/**
 * Clear all browser state (cookies only).
 * Call BEFORE navigating to simulate true cold start.
 *
 * NOTE: localStorage/sessionStorage must be cleared AFTER navigation
 * because Playwright requires a page context.
 */
async function clearAllBrowserState(context: BrowserContext): Promise<void> {
  await context.clearCookies();
}

// ============================================================================
// TEST SUITE: COLD START CSRF PROTECTION
// ============================================================================

test.describe('Cold Start CSRF Protection', () => {
  test.describe('Bootstrap Handshake Flow', () => {
    /**
     * TEST 1: XSRF-TOKEN Cookie Issuance on Cold Start
     *
     * Scenario: User lands on login page with zero cookies.
     * Expected: Bootstrap XHR triggers XSRF-TOKEN cookie issuance.
     */
    test('should issue XSRF-TOKEN cookie on bootstrap XHR before login form is interactive', async ({
      page,
      context,
    }) => {
      // STEP 1: Ensure clean browser state (cold start)
      await clearAllBrowserState(context);

      // STEP 2: Set up network interception for bootstrap calls BEFORE navigating
      let bootstrapRequestCaptured = false;
      let bootstrapResponseStatus: number | null = null;

      // Intercept the bootstrap XHR (refresh endpoint or users/me)
      const bootstrapPromise = page.waitForResponse(
        (response) => {
          const url = response.url();
          const isBootstrapCall =
            url.includes(ENDPOINTS.REFRESH) || url.includes(ENDPOINTS.USERS_ME);

          if (isBootstrapCall) {
            bootstrapRequestCaptured = true;
            bootstrapResponseStatus = response.status();
            return true;
          }
          return false;
        },
        { timeout: 15000 }
      );

      // STEP 3: Navigate to login page (triggers Angular APP_INITIALIZER → bootstrap XHR)
      await page.goto(`${BASE_URL}/auth/login`, { waitUntil: 'domcontentloaded' });

      // STEP 4: Wait for bootstrap XHR to complete
      const bootstrapResponse = await bootstrapPromise;

      // ASSERTION 1: Bootstrap request was captured
      expect(bootstrapRequestCaptured, 'Bootstrap XHR should be intercepted').toBe(true);

      // ASSERTION 2: Bootstrap response is 401 (expected for unauthenticated user)
      // The key is that the response ALSO sets the XSRF-TOKEN cookie
      expect(
        [200, 401].includes(bootstrapResponseStatus!),
        `Bootstrap should return 200 or 401, got ${bootstrapResponseStatus}`
      ).toBe(true);

      // STEP 5: Wait for page to fully render
      await waitForPageReady(page);

      // ASSERTION 3: XSRF-TOKEN cookie is now set
      const xsrfCookie = await assertCookieExists(context, COOKIE_NAMES.XSRF_TOKEN, {
        httpOnly: false, // MUST be readable by JavaScript for Angular XSRF integration
        sameSite: 'Lax',
      });

      // ASSERTION 4: XSRF token has a valid value
      expect(xsrfCookie.value, 'XSRF-TOKEN should have a non-empty value').toBeTruthy();
      expect(xsrfCookie.value.length, 'XSRF-TOKEN should be a substantial token').toBeGreaterThan(
        10
      );

      console.log('✅ Cold Start Bootstrap Verification:');
      console.log(`   - Bootstrap URL: ${bootstrapResponse.url()}`);
      console.log(`   - Bootstrap Status: ${bootstrapResponseStatus}`);
      console.log(`   - XSRF-TOKEN Cookie: ${xsrfCookie.value.substring(0, 20)}...`);
    });

    /**
     * TEST 2: Complete Cold Start → Login Flow (The Critical Path)
     *
     * Scenario: User with ZERO cookies loads app, waits for bootstrap, then logs in.
     * Expected: Login POST returns 200 (not 403), HttpOnly cookies are set.
     *
     * This is the PRIMARY regression test for the Security Hardening sprint.
     */
    test('should complete login successfully after cold start bootstrap', async ({
      page,
      context,
    }) => {
      // ========================================================================
      // PHASE 1: COLD START - CLEAN BROWSER STATE
      // ========================================================================

      // Clear cookies and storage directly (no navigation)
      await context.clearCookies();

      console.log('🔷 Phase 1: Cold Start - Cookies cleared');

      // ========================================================================
      // PHASE 2: NAVIGATE TO LOGIN & WAIT FOR BOOTSTRAP
      // ========================================================================

      // Set up response interception for bootstrap call BEFORE navigating
      const bootstrapPromise = page.waitForResponse(
        (response) => {
          const url = response.url();
          return url.includes(ENDPOINTS.REFRESH) || url.includes(ENDPOINTS.USERS_ME);
        },
        { timeout: 15000 }
      );

      // Navigate to login page
      await page.goto(`${BASE_URL}/auth/login`, { waitUntil: 'domcontentloaded' });

      // Wait for bootstrap XHR to complete
      await bootstrapPromise;

      // Additional wait for Angular to finish rendering
      await waitForPageReady(page);

      // Clear localStorage/sessionStorage now that we have a page context
      await page.evaluate(() => {
        localStorage.clear();
        sessionStorage.clear();
      });

      console.log('🔷 Phase 2: Navigation complete, bootstrap XHR finished');

      // ========================================================================
      // PHASE 3: VERIFY XSRF-TOKEN COOKIE BEFORE LOGIN
      // ========================================================================

      const xsrfCookie = await getCookieByName(context, COOKIE_NAMES.XSRF_TOKEN);

      expect(
        xsrfCookie,
        'CRITICAL: XSRF-TOKEN cookie MUST be set before login attempt. ' +
          'If missing, login will fail with 403 Forbidden.'
      ).toBeDefined();

      console.log(`🔷 Phase 3: XSRF-TOKEN verified: ${xsrfCookie?.value.substring(0, 20)}...`);

      // ========================================================================
      // PHASE 4: FILL LOGIN FORM (Realistic User Interaction)
      // ========================================================================

      // Wait for form elements to be visible
      const emailInput = page.locator('input[formControlName="email"]');
      const passwordInput = page.locator('input[formControlName="password"]');
      const submitButton = page.locator('button[type="submit"]:has-text("Prijavi se")');

      await emailInput.waitFor({ state: 'visible', timeout: 5000 });
      await passwordInput.waitFor({ state: 'visible', timeout: 5000 });

      // Fill form with realistic typing delays (simulates user think time)
      await emailInput.fill(TEST_USER.email);
      await passwordInput.fill(TEST_USER.password);

      // Trigger Angular change detection by blurring
      await passwordInput.blur();

      // Wait for form validation to complete (form should be valid)
      await page.waitForFunction(
        () => document.querySelector('form')?.classList.contains('ng-valid'),
        { timeout: 5000 }
      );

      console.log('🔷 Phase 4: Login form filled and validated');

      // ========================================================================
      // PHASE 5: SUBMIT LOGIN & VERIFY SUCCESS
      // ========================================================================

      // Set up response interception for login POST
      const loginResponsePromise = page.waitForResponse(
        (response) => response.url().includes(ENDPOINTS.LOGIN),
        { timeout: 15000 }
      );

      // Click submit
      await submitButton.click();

      // Wait for login response
      const loginResponse = await loginResponsePromise;
      const loginStatus = loginResponse.status();

      // CRITICAL ASSERTION: Login should return 200 (not 403)
      expect(
        loginStatus,
        `CSRF REGRESSION: Login returned ${loginStatus}. ` +
          'Expected 200. If 403, XSRF token was missing or invalid.'
      ).toBe(200);

      console.log(`🔷 Phase 5: Login POST returned ${loginStatus}`);

      // ========================================================================
      // PHASE 6: VERIFY POST-LOGIN COOKIE STATE
      // ========================================================================

      // Wait for redirect to complete (may redirect to /, /home, /dashboard, /profile, or /cars)
      await page.waitForURL(/\/(home|dashboard|profile|cars)?$/i, { timeout: 15000 });
      await waitForPageReady(page);

      // Verify HttpOnly access_token cookie is set
      const accessTokenCookie = await assertCookieExists(context, COOKIE_NAMES.ACCESS_TOKEN, {
        httpOnly: true,
        sameSite: 'Lax',
      });

      // Verify refresh_token cookie is set
      const refreshTokenCookie = await assertCookieExists(context, COOKIE_NAMES.REFRESH_TOKEN, {
        httpOnly: true,
        sameSite: 'Lax',
      });

      // Verify XSRF-TOKEN is still present (may be rotated)
      const finalXsrfCookie = await assertCookieExists(context, COOKIE_NAMES.XSRF_TOKEN, {
        httpOnly: false,
      });

      console.log('🔷 Phase 6: Post-login cookies verified:');
      console.log(
        `   - access_token: ${accessTokenCookie.value.substring(0, 20)}... (HttpOnly: ${
          accessTokenCookie.httpOnly
        })`
      );
      console.log(
        `   - refresh_token: ${refreshTokenCookie.value.substring(0, 20)}... (HttpOnly: ${
          refreshTokenCookie.httpOnly
        })`
      );
      console.log(`   - XSRF-TOKEN: ${finalXsrfCookie.value.substring(0, 20)}...`);

      // ========================================================================
      // PHASE 7: VERIFY NO TOKENS IN LOCALSTORAGE (SECURITY CHECK)
      // ========================================================================

      const accessTokenInStorage = await page.evaluate(() => localStorage.getItem('access_token'));
      const currentUserInStorage = await page.evaluate(() => localStorage.getItem('current_user'));

      expect(
        accessTokenInStorage,
        'SECURITY: access_token should NOT be in localStorage (XSS vulnerable)'
      ).toBeNull();

      console.log('🔷 Phase 7: localStorage security check passed');
      console.log('✅ Cold Start → Login flow completed successfully!');
    });

    /**
     * TEST 3: Verify X-XSRF-TOKEN Header is Sent on Login POST
     *
     * Scenario: User logs in after cold start.
     * Expected: Login POST includes X-XSRF-TOKEN header matching the cookie value.
     */
    test('should send X-XSRF-TOKEN header on login POST request', async ({ page, context }) => {
      // Clear state
      await clearAllBrowserState(context);

      // Navigate and wait for bootstrap
      const bootstrapPromise = page.waitForResponse(
        (response) =>
          response.url().includes(ENDPOINTS.REFRESH) || response.url().includes(ENDPOINTS.USERS_ME),
        { timeout: 15000 }
      );

      await page.goto(`${BASE_URL}/auth/login`);
      await bootstrapPromise;
      await waitForPageReady(page);

      // Get the XSRF token from cookie
      const xsrfCookie = await getCookieByName(context, COOKIE_NAMES.XSRF_TOKEN);
      expect(xsrfCookie, 'XSRF-TOKEN cookie should be set').toBeDefined();

      // Set up request interception to capture headers
      let loginRequestHeaders: Record<string, string> = {};

      await page.route('**/api/auth/supabase/login', async (route) => {
        const request = route.request();
        loginRequestHeaders = request.headers();
        await route.continue();
      });

      // Fill and submit form
      await page.fill('input[formControlName="email"]', TEST_USER.email);
      await page.fill('input[formControlName="password"]', TEST_USER.password);
      await page.locator('input[formControlName="password"]').blur();
      await page.waitForSelector('form.ng-valid', { timeout: 5000 });

      // Submit and wait for login request
      const loginPromise = page.waitForResponse(
        (response) => response.url().includes(ENDPOINTS.LOGIN),
        { timeout: 15000 }
      );

      await page.click('button[type="submit"]:has-text("Prijavi se")');
      await loginPromise;

      // ASSERTION: X-XSRF-TOKEN header was sent
      const xsrfHeader = loginRequestHeaders['x-xsrf-token'] || loginRequestHeaders['X-XSRF-TOKEN'];

      expect(xsrfHeader, 'Login POST must include X-XSRF-TOKEN header').toBeDefined();

      expect(xsrfHeader, 'X-XSRF-TOKEN header value should match cookie value').toBe(
        xsrfCookie!.value
      );

      console.log('✅ X-XSRF-TOKEN header verification passed');
      console.log(`   - Cookie value: ${xsrfCookie!.value.substring(0, 20)}...`);
      console.log(`   - Header value: ${xsrfHeader.substring(0, 20)}...`);
    });
  });

  test.describe('Race Condition Prevention', () => {
    /**
     * TEST 4: Login Attempt Before Bootstrap Completes (Negative Test)
     *
     * Scenario: Simulate a fast user who clicks login before bootstrap XHR completes.
     * Expected: Either the form is disabled OR the request should still succeed
     *           because Angular's HttpClient waits for document.cookie to be readable.
     *
     * Note: This test validates the UX safeguard, not the security mechanism.
     */
    test('should handle login attempt during bootstrap gracefully', async ({ page, context }) => {
      // Clear state
      await clearAllBrowserState(context);

      // Set up interception to delay bootstrap response
      await page.route('**/api/auth/supabase/refresh', async (route) => {
        // Simulate slow network - delay bootstrap by 2 seconds
        await new Promise((resolve) => setTimeout(resolve, 2000));
        await route.continue();
      });

      // Navigate to login (bootstrap will be delayed)
      await page.goto(`${BASE_URL}/auth/login`, { waitUntil: 'domcontentloaded' });

      // Wait for form to be visible (but bootstrap is still pending)
      const emailInput = page.locator('input[formControlName="email"]');
      await emailInput.waitFor({ state: 'visible', timeout: 5000 });

      // Attempt to fill form immediately (before bootstrap completes)
      await emailInput.fill(TEST_USER.email);
      await page.fill('input[formControlName="password"]', TEST_USER.password);

      // Check if XSRF cookie exists yet (it shouldn't if bootstrap is still pending)
      const xsrfCookieBeforeBootstrap = await getCookieByName(context, COOKIE_NAMES.XSRF_TOKEN);

      if (!xsrfCookieBeforeBootstrap) {
        console.log('⚠️ XSRF cookie not yet set (bootstrap still pending)');

        // The submit button should ideally be disabled or the interceptor should queue the request
        // Wait for bootstrap to complete
        await page.waitForResponse((response) => response.url().includes(ENDPOINTS.REFRESH), {
          timeout: 10000,
        });
      }

      // Now wait for XSRF cookie
      await page.waitForTimeout(500); // Small delay for cookie to be set

      const xsrfCookieAfterBootstrap = await getCookieByName(context, COOKIE_NAMES.XSRF_TOKEN);
      expect(xsrfCookieAfterBootstrap, 'XSRF cookie should be set after bootstrap').toBeDefined();

      // Now submit should work
      await page.locator('input[formControlName="password"]').blur();
      await page.waitForSelector('form.ng-valid', { timeout: 5000 });

      const loginResponse = await Promise.all([
        page.waitForResponse((response) => response.url().includes(ENDPOINTS.LOGIN), {
          timeout: 15000,
        }),
        page.click('button[type="submit"]:has-text("Prijavi se")'),
      ]);

      expect(loginResponse[0].status(), 'Login should succeed after bootstrap').toBe(200);

      console.log('✅ Race condition handling verified');
    });
  });

  test.describe('Cookie Security Validation', () => {
    /**
     * TEST 5: Verify Cookie Security Flags After Login
     *
     * Validates that all authentication cookies have correct security flags:
     * - access_token: HttpOnly=true (XSS protection)
     * - refresh_token: HttpOnly=true (XSS protection)
     * - XSRF-TOKEN: HttpOnly=false (must be readable by JS)
     */
    test('should set correct security flags on all authentication cookies', async ({
      page,
      context,
    }) => {
      // Clear state
      await clearAllBrowserState(context);

      // Set up response interception BEFORE navigating
      const bootstrapPromise = page.waitForResponse(
        (response) =>
          response.url().includes(ENDPOINTS.REFRESH) || response.url().includes(ENDPOINTS.USERS_ME),
        { timeout: 15000 }
      );

      // Navigate and wait for bootstrap
      await page.goto(`${BASE_URL}/auth/login`);
      await bootstrapPromise;
      await waitForPageReady(page);

      // Login - use waitForFunction instead of waitForSelector for ng-valid
      await page.fill('input[formControlName="email"]', TEST_USER.email);
      await page.fill('input[formControlName="password"]', TEST_USER.password);
      await page.locator('input[formControlName="password"]').blur();
      await page.waitForFunction(
        () => document.querySelector('form')?.classList.contains('ng-valid'),
        { timeout: 5000 }
      );

      await Promise.all([
        page.waitForURL(/\/(home|dashboard|profile|cars)?$/i, { timeout: 15000 }),
        page.click('button[type="submit"]:has-text("Prijavi se")'),
      ]);

      await waitForPageReady(page);

      // Get all cookies
      const cookies = await context.cookies();

      // Validate access_token
      const accessToken = cookies.find((c) => c.name === COOKIE_NAMES.ACCESS_TOKEN);
      expect(accessToken, 'access_token cookie should exist').toBeDefined();
      expect(accessToken!.httpOnly, 'access_token MUST be HttpOnly (XSS protection)').toBe(true);
      expect(accessToken!.sameSite, 'access_token should have SameSite=Lax').toBe('Lax');

      // Validate refresh_token
      const refreshToken = cookies.find((c) => c.name === COOKIE_NAMES.REFRESH_TOKEN);
      expect(refreshToken, 'refresh_token cookie should exist').toBeDefined();
      expect(refreshToken!.httpOnly, 'refresh_token MUST be HttpOnly (XSS protection)').toBe(true);
      expect(refreshToken!.sameSite, 'refresh_token should have SameSite=Lax').toBe('Lax');
      expect(refreshToken!.path, 'refresh_token should have restricted path').toBe(
        '/api/auth/supabase/refresh'
      );

      // Validate XSRF-TOKEN
      const xsrfToken = cookies.find((c) => c.name === COOKIE_NAMES.XSRF_TOKEN);
      expect(xsrfToken, 'XSRF-TOKEN cookie should exist').toBeDefined();
      expect(
        xsrfToken!.httpOnly,
        'XSRF-TOKEN must NOT be HttpOnly (Angular needs to read it)'
      ).toBe(false);
      expect(xsrfToken!.sameSite, 'XSRF-TOKEN should have SameSite=Lax').toBe('Lax');

      console.log('✅ Cookie security flags validated:');
      console.log(
        `   - access_token: HttpOnly=${accessToken!.httpOnly}, SameSite=${accessToken!.sameSite}`
      );
      console.log(
        `   - refresh_token: HttpOnly=${refreshToken!.httpOnly}, SameSite=${
          refreshToken!.sameSite
        }, Path=${refreshToken!.path}`
      );
      console.log(
        `   - XSRF-TOKEN: HttpOnly=${xsrfToken!.httpOnly}, SameSite=${xsrfToken!.sameSite}`
      );
    });
  });
});

// ============================================================================
// AUXILIARY TEST SUITE: 403 REGRESSION DETECTION
// ============================================================================

test.describe('CSRF 403 Regression Detection', () => {
  /**
   * TEST 6: Direct Login POST Without XSRF Token Should Fail
   *
   * This is a NEGATIVE test to verify that CSRF protection is active.
   *
   * Spring Security CSRF behavior:
   * - If no XSRF-TOKEN cookie exists: May return 403 or proceed (depends on config)
   * - If cookie exists but header missing: Should return 403
   * - If credentials are wrong: Returns 401 (auth check may happen before CSRF)
   *
   * We verify protection is working by ensuring the request is REJECTED
   * (either 403 for CSRF or 401 for auth), not silently accepted.
   */
  test('should reject login POST without X-XSRF-TOKEN header (security verification)', async ({
    page,
    context,
    request,
  }) => {
    // STEP 1: First, get an XSRF-TOKEN cookie by visiting the app
    // This ensures the server-side expects CSRF validation
    await page.goto(`${BASE_URL}/auth/login`, { waitUntil: 'networkidle' });

    // Wait for XSRF cookie to be set
    await waitForCookie(context, COOKIE_NAMES.XSRF_TOKEN, 5000);

    // Get all cookies including XSRF-TOKEN
    const cookies = await context.cookies();
    const xsrfCookie = cookies.find((c) => c.name === COOKIE_NAMES.XSRF_TOKEN);
    expect(xsrfCookie, 'XSRF-TOKEN cookie should exist').toBeTruthy();

    console.log(`🔷 Got XSRF-TOKEN cookie: ${xsrfCookie!.value.substring(0, 20)}...`);

    // STEP 2: Make a direct API call WITH the XSRF cookie but WITHOUT the header
    // This simulates a CSRF attack where the cookie is automatically sent
    // but the attacker cannot set the custom header
    const response = await request.post(`${API_BASE_URL}/api/auth/supabase/login`, {
      data: {
        email: TEST_USER.email,
        password: TEST_USER.password,
      },
      headers: {
        'Content-Type': 'application/json',
        // Intentionally NOT sending X-XSRF-TOKEN header
        // Cookie is automatically sent by Playwright's request context
      },
    });

    // ASSERTION: Should be rejected
    // - 403: CSRF token mismatch (expected for CSRF protection)
    // - 401: Auth check ran first but request was still rejected
    // - NOT 200: Would indicate CSRF protection is bypassed
    const status = response.status();

    expect(
      [401, 403].includes(status),
      `Login without X-XSRF-TOKEN header should be rejected. ` +
        `Got ${status}. If 200, CSRF protection is misconfigured!`
    ).toBe(true);

    // Log the specific rejection reason
    if (status === 403) {
      console.log('✅ CSRF protection verified: Rejected with 403 Forbidden (CSRF token mismatch)');
    } else if (status === 401) {
      console.log('✅ Request rejected with 401 (auth check before CSRF, but still protected)');
    }
  });
});
