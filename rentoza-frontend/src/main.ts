// Polyfill for Node.js globals in browser environment
// Required for sockjs-client and stompjs which expect Node.js globals
(window as any).global = window;
(window as any).process = {
  env: { DEBUG: undefined },
  version: '',
  nextTick: (fn: Function) => setTimeout(fn, 0),
};
(window as any).Buffer = (window as any).Buffer || {
  isBuffer: () => false,
};

import {
  APP_INITIALIZER,
  importProvidersFrom,
  provideZoneChangeDetection,
  isDevMode,
} from '@angular/core';
import { bootstrapApplication } from '@angular/platform-browser';
import { BrowserAnimationsModule } from '@angular/platform-browser/animations';
import { provideHttpClient, withInterceptors, withXsrfConfiguration } from '@angular/common/http';
import { provideRouter } from '@angular/router';
import { provideNativeDateAdapter } from '@angular/material/core';
import { provideServiceWorker } from '@angular/service-worker';
import { JwtModule } from '@auth0/angular-jwt';
import { provideToastr } from 'ngx-toastr';
import { provideCharts, withDefaultRegisterables } from 'ng2-charts';

import { App } from './app/app';
import { routes } from './app/app.routes';
import { authTokenInterceptor } from '@core/auth/token.interceptor';
import { errorResponseInterceptor } from '@core/interceptors/error.interceptor';
import { httpCacheInterceptor } from '@core/interceptors/http-cache.interceptor';
import { idempotencyInterceptor } from '@core/interceptors/idempotency.interceptor';
import { AuthService } from '@core/auth/auth.service';
import { PerformanceMonitoringService } from '@core/services/performance-monitoring.service';
import { OverlayThemeService } from '@core/services/overlay-theme.service';
import { environment } from '@environments/environment';

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
    importProvidersFrom(
      BrowserAnimationsModule,
      JwtModule.forRoot({
        config: {
          tokenGetter: () => null,
          allowedDomains: ['localhost:8080', 'localhost:8081'], // Allow both main API and chat service
        },
      })
    ),
    provideHttpClient(
      withXsrfConfiguration({
        cookieName: 'XSRF-TOKEN',
        headerName: 'X-XSRF-TOKEN',
      }),
      // Interceptor order matters: auth → idempotency → cache → error
      // Idempotency must run after auth (needs user context) but before error handling
      withInterceptors([
        authTokenInterceptor,
        idempotencyInterceptor,
        httpCacheInterceptor,
        errorResponseInterceptor,
      ])
    ),
    provideNativeDateAdapter(),
    provideToastr({
      positionClass: 'toast-bottom-right',
      preventDuplicates: true,
    }),
    provideServiceWorker('ngsw-worker.js', {
      enabled: !isDevMode(),
      registrationStrategy: 'registerWhenStable:30000',
    }),
    provideCharts(withDefaultRegisterables()),
  ],
}).catch((err) => console.error(err));
