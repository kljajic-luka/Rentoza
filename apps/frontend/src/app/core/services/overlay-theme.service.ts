import { DOCUMENT } from '@angular/common';
import { Inject, Injectable, effect } from '@angular/core';
import { OverlayContainer } from '@angular/cdk/overlay';
import { ThemeService } from './theme.service';

/**
 * OverlayThemeService
 *
 * Synchronizes the current theme class to Angular Material's CDK overlay container.
 * This ensures dialogs, menus, tooltips, and other overlay components inherit
 * the correct theme (light/dark) from the app-level theme toggle.
 *
 * Why this is needed:
 * - Material overlays render in a separate container appended to <body>
 * - The .theme-dark class is applied to <html>, not the overlay container
 * - Without this service, overlay components don't inherit theme styles properly
 *
 * Usage:
 * - Provide this service in app config or root module
 * - It will automatically sync theme changes via Angular signals effect
 */
@Injectable({ providedIn: 'root' })
export class OverlayThemeService {
  constructor(
    @Inject(DOCUMENT) private readonly document: Document,
    private readonly themeService: ThemeService,
    private readonly overlayContainer: OverlayContainer
  ) {
    // Use Angular signals effect to reactively sync theme class
    effect(() => {
      const currentTheme = this.themeService.theme();
      const containerElement = this.overlayContainer.getContainerElement();

      // Apply or remove theme-dark class based on current theme
      containerElement.classList.toggle('theme-dark', currentTheme === 'dark');
    });
  }
}