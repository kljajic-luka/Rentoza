import {
  APP_INITIALIZER,
  ErrorHandler,
  importProvidersFrom,
  provideZoneChangeDetection,
  isDevMode,
} from '@angular/core';
import { bootstrapApplication } from '@angular/platform-browser';
import { BrowserAnimationsModule } from '@angular/platform-browser/animations';
import { provideHttpClient, withInterceptors, withXsrfConfiguration } from '@angular/common/http';
import { provideRouter } from '@angular/router';
import { MAT_DATE_LOCALE, provideNativeDateAdapter } from '@angular/material/core';
import { provideServiceWorker } from '@angular/service-worker';
import { provideToastr } from 'ngx-toastr';
import { MatPaginatorIntl } from '@angular/material/paginator';
import { SrMatPaginatorIntl } from '@core/i18n/mat-paginator-i18n';

import { App } from './app/app';
import { routes } from './app/app.routes';
import { authTokenInterceptor } from '@core/auth/token.interceptor';
import { XSRF_TOKEN_COOKIE, XSRF_TOKEN_HEADER } from '@core/auth/cookie.constants';
import { errorResponseInterceptor } from '@core/interceptors/error.interceptor';
import { httpCacheInterceptor } from '@core/interceptors/http-cache.interceptor';
import { idempotencyInterceptor } from '@core/interceptors/idempotency.interceptor';
import { retryInterceptor } from '@core/interceptors/retry.interceptor';
import { AuthService } from '@core/auth/auth.service';
import { PerformanceMonitoringService } from '@core/services/performance-monitoring.service';
import { OverlayThemeService } from '@core/services/overlay-theme.service';
import { environment } from '@environments/environment';
import { GlobalErrorHandler } from '@core/error/global-error-handler';

function initializeAuth(authService: AuthService): () => Promise<void> {
  return async () => {
    await authService.initializeSession().catch(() => void 0);
    // Start periodic token expiration watcher (checks every 60 seconds)
    authService.startTokenWatcher(60000);
  };
}

function initializePerformanceMonitoring(perfService: PerformanceMonitoringService): () => void {
  return () => {
    if (environment.production) {
      perfService.initMonitoring();
    }
  };
}

function initializeOverlayTheme(overlayThemeService: OverlayThemeService): () => void {
  return () => {
    // Service instantiation is sufficient - effect() in constructor will handle theme sync
    void overlayThemeService;
  };
}

bootstrapApplication(App, {
  providers: [
    { provide: ErrorHandler, useClass: GlobalErrorHandler },
    provideZoneChangeDetection({ eventCoalescing: true }),
    provideRouter(routes),
    {
      provide: APP_INITIALIZER,
      useFactory: initializeAuth,
      deps: [AuthService],
      multi: true,
    },
    {
      provide: APP_INITIALIZER,
      useFactory: initializePerformanceMonitoring,
      deps: [PerformanceMonitoringService],
      multi: true,
    },
    {
      provide: APP_INITIALIZER,
      useFactory: initializeOverlayTheme,
      deps: [OverlayThemeService],
      multi: true,
    },
    importProvidersFrom(BrowserAnimationsModule),
    provideHttpClient(
      withXsrfConfiguration({
        cookieName: XSRF_TOKEN_COOKIE,
        headerName: XSRF_TOKEN_HEADER,
      }),
      // Interceptor order matters: auth → idempotency → retry → cache → error
      // Retry must run after auth but before error handling, so transient failures
      // are retried transparently before showing errors to users
      withInterceptors([
        authTokenInterceptor,
        idempotencyInterceptor,
        retryInterceptor,
        httpCacheInterceptor,
        errorResponseInterceptor,
      ]),
    ),
    { provide: MAT_DATE_LOCALE, useValue: 'en-GB' },
    provideNativeDateAdapter(),
    provideToastr({
      positionClass: 'toast-bottom-right',
      preventDuplicates: true,
    }),
    provideServiceWorker('ngsw-worker.js', {
      enabled: !isDevMode(),
      registrationStrategy: 'registerWhenStable:30000',
    }),
    /* Serbian localization for Angular Material Paginator (WCAG 3.1.2 Language of Parts) */
    { provide: MatPaginatorIntl, useClass: SrMatPaginatorIntl },
  ],
}).catch((err) => console.error(err));
