// Polyfill for Node.js globals in browser environment
// Required for sockjs-client and stompjs which expect Node.js globals
(window as any).global = window;
(window as any).process = {
  env: { DEBUG: undefined },
  version: '',
  nextTick: (fn: Function) => setTimeout(fn, 0)
};
(window as any).Buffer = (window as any).Buffer || {
  isBuffer: () => false
};

import { APP_INITIALIZER, importProvidersFrom, provideZoneChangeDetection } from '@angular/core';
import { bootstrapApplication } from '@angular/platform-browser';
import { BrowserAnimationsModule } from '@angular/platform-browser/animations';
import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { provideRouter } from '@angular/router';
import { provideNativeDateAdapter } from '@angular/material/core';
import { JwtModule } from '@auth0/angular-jwt';
import { provideToastr } from 'ngx-toastr';

import { App } from './app/app';
import { routes } from './app/app.routes';
import { authTokenInterceptor } from '@core/auth/token.interceptor';
import { errorResponseInterceptor } from '@core/interceptors/error.interceptor';
import { AuthService } from '@core/auth/auth.service';

function initializeAuth(authService: AuthService): () => Promise<void> {
  return () => authService.initializeSession().catch(() => void 0);
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
    importProvidersFrom(
      BrowserAnimationsModule,
      JwtModule.forRoot({
        config: {
          tokenGetter: () => null,
          allowedDomains: ['localhost:8080', 'localhost:8081'], // Allow both main API and chat service
        },
      })
    ),
    provideHttpClient(withInterceptors([authTokenInterceptor, errorResponseInterceptor])),
    provideNativeDateAdapter(),
    provideToastr({
      positionClass: 'toast-bottom-right',
      preventDuplicates: true,
    }),
  ],
}).catch((err) => console.error(err));
