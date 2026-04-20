import { expect, test, type Page } from '@playwright/test';

const TEST_USER_EMAIL = process.env.E2E_TEST_EMAIL ?? 'kljajic.l007@gmail.com';
const TEST_USER_PASSWORD = process.env.E2E_TEST_PASSWORD ?? 'Kljaja01!';
const REAL_SCA_SMOKE_ENABLED = process.env.E2E_REAL_MOCK_SCA === 'true';

async function loginIfNeeded(page: Page) {
  await page.goto('/auth/login');

  const emailInput = page.locator('input[type="email"]');
  const passwordInput = page.locator('input[type="password"]');

  if (await emailInput.isVisible({ timeout: 3000 }).catch(() => false)) {
    await emailInput.fill(TEST_USER_EMAIL);
    await passwordInput.fill(TEST_USER_PASSWORD);
    await passwordInput.blur();

    const submitButton = page.getByRole('button', { name: /^Prijavi se$/ });
    await Promise.all([
      page.waitForURL(/\/(pocetna|cars|vozila|bookings|home)/, { timeout: 15000 }),
      submitButton.click(),
    ]);
  }
}

test.describe('Payment return callback flow', () => {
  test('polls booking status and redirects to booking detail after success callback', async ({
    page,
  }) => {
    const mockUser = {
      id: '123',
      firstName: 'E2E',
      lastName: 'User',
      email: TEST_USER_EMAIL,
      roles: ['USER'],
      registrationStatus: 'ACTIVE',
    };

    await page.route('**/api/auth/login', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ authenticated: true, user: mockUser }),
      });
    });

    await page.route('**/api/auth/supabase/refresh', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ authenticated: true, user: mockUser }),
      });
    });

    await page.route('**/api/users/me', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(mockUser),
      });
    });

    await loginIfNeeded(page);

    // Simulate callback poll response becoming terminal-success.
    await page.route('**/api/bookings/123', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          id: 123,
          status: 'ACTIVE',
        }),
      });
    });

    await page.goto('/bookings/payment-return?bookingId=123&status=success');

    await expect(page).toHaveURL(/\/bookings\/123$/, { timeout: 10000 });
  });

  test('pm_card_async-like behavior: delayed confirmation keeps user on return page until terminal status', async ({
    page,
  }) => {
    const mockUser = {
      id: '456',
      firstName: 'E2E',
      lastName: 'Async',
      email: TEST_USER_EMAIL,
      roles: ['USER'],
      registrationStatus: 'ACTIVE',
    };

    await page.route('**/api/auth/login', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ authenticated: true, user: mockUser }),
      });
    });

    await page.route('**/api/auth/supabase/refresh', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ authenticated: true, user: mockUser }),
      });
    });

    await page.route('**/api/users/me', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(mockUser),
      });
    });

    await loginIfNeeded(page);

    let pollCount = 0;
    await page.route('**/api/bookings/456', async (route) => {
      pollCount += 1;
      const status = pollCount < 3 ? 'CHECKOUT_DAMAGE_DISPUTE' : 'ACTIVE';
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          id: 456,
          status,
        }),
      });
    });

    await page.goto('/bookings/payment-return?bookingId=456&status=success');

    // During delayed provider confirmation, page should remain on callback route.
    await page.waitForTimeout(2500);
    await expect(page).toHaveURL(/\/bookings\/payment-return\?bookingId=456/);

    // Once polling reaches terminal-success booking status, UI redirects to booking detail.
    await expect(page).toHaveURL(/\/bookings\/456$/, { timeout: 10000 });
  });

  test('staging smoke: real mock ACS approval flow without callback mocking', async ({ page }) => {
    test.skip(!REAL_SCA_SMOKE_ENABLED, 'Set E2E_REAL_MOCK_SCA=true to run staging smoke flow.');

    const carId = process.env.E2E_REAL_CAR_ID;
    const startTime = process.env.E2E_REAL_START_TIME;
    const endTime = process.env.E2E_REAL_END_TIME;

    test.skip(
      !carId || !startTime || !endTime,
      'Set E2E_REAL_CAR_ID, E2E_REAL_START_TIME, and E2E_REAL_END_TIME.',
    );

    test.slow();
    await loginIfNeeded(page);

    const createResult = await page.evaluate(
      async ({ inCarId, inStartTime, inEndTime }) => {
        const payload = {
          carId: inCarId,
          startTime: inStartTime,
          endTime: inEndTime,
          insuranceType: 'BASIC',
          prepaidRefuel: false,
          driverName: 'E2E',
          driverSurname: 'Smoke',
          driverPhone: '+381600000000',
          paymentMethodId: 'pm_card_sca_required',
          idempotencyKey: `e2e-real-sca-${Date.now()}`,
        };

        const response = await fetch('/api/bookings', {
          method: 'POST',
          headers: {
            'Content-Type': 'application/json',
          },
          credentials: 'include',
          body: JSON.stringify(payload),
        });

        let body: unknown;
        try {
          body = await response.json();
        } catch {
          body = null;
        }

        return {
          status: response.status,
          body,
        };
      },
      {
        inCarId: carId,
        inStartTime: startTime,
        inEndTime: endTime,
      },
    );

    expect(createResult.status).toBeLessThan(500);

    const responseBody = createResult.body as {
      redirectRequired?: boolean;
      redirectUrl?: string;
      booking?: { id?: string | number };
    } | null;

    expect(responseBody?.redirectRequired).toBeTruthy();
    expect(responseBody?.redirectUrl).toContain('/mock/acs/challenge?token=');

    const redirectUrl = responseBody?.redirectUrl as string;
    await page.goto(redirectUrl);
    await page.getByRole('button', { name: /odobri/i }).click();

    await expect(page).toHaveURL(/\/bookings\/payment-return\?bookingId=/, { timeout: 15000 });

    const bookingId = responseBody?.booking?.id;
    if (bookingId !== undefined && bookingId !== null) {
      await expect(page).toHaveURL(new RegExp(`/bookings/${bookingId}$`), { timeout: 20000 });
    }
  });
});
