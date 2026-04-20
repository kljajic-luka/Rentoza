import { expect, test } from '@playwright/test';

test.describe('Mobile cookie consent', () => {
  test('renders inline on mobile login so the Google CTA is not covered', async ({ page }) => {
    await page.route('**/api/auth/supabase/refresh', async (route) => {
      await route.fulfill({
        status: 401,
        contentType: 'application/json',
        body: JSON.stringify({ message: 'No active session' }),
      });
    });

    await page.goto('/auth/login');
    await page.evaluate(() => {
      localStorage.removeItem('rentoza_cookie_consent');
      sessionStorage.clear();
    });
    await page.reload();

    await expect
      .poll(async () => page.locator('.cookie-banner').count(), {
        timeout: 20000,
      })
      .toBeGreaterThan(0);

    const consent = page.getByRole('region', { name: 'Podešavanja kolačića' });
    const googleButton = page.getByRole('button', { name: 'Prijavi se sa Google nalogom' });

    await expect(consent).toBeVisible();
    await expect(googleButton).toBeVisible();

    const layoutState = await page.evaluate(() => {
      const button = document.querySelector('.google-signin-btn') as HTMLElement | null;
      const consentBanner = document.querySelector('.cookie-banner') as HTMLElement | null;

      if (!button || !consentBanner) {
        return null;
      }

      button.scrollIntoView({ block: 'center' });

      const buttonRect = button.getBoundingClientRect();
      const consentRect = consentBanner.getBoundingClientRect();
      const centerX = buttonRect.left + buttonRect.width / 2;
      const centerY = buttonRect.top + buttonRect.height / 2;
      const topElement = document.elementFromPoint(centerX, centerY) as HTMLElement | null;

      return {
        consentPosition: window.getComputedStyle(consentBanner).position,
        covered: !(
          buttonRect.bottom <= consentRect.top ||
          buttonRect.top >= consentRect.bottom ||
          buttonRect.right <= consentRect.left ||
          buttonRect.left >= consentRect.right
        ),
        topElementMatches: Boolean(topElement && button.contains(topElement)),
        bannerBottom: consentRect.bottom,
        buttonTop: buttonRect.top,
      };
    });

    expect(layoutState).not.toBeNull();
    expect(layoutState!.consentPosition).not.toBe('fixed');
    expect(layoutState!.covered).toBe(false);
    expect(layoutState!.bannerBottom).toBeLessThanOrEqual(layoutState!.buttonTop);
    expect(layoutState!.topElementMatches).toBe(true);
  });
});