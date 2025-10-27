import { importProvidersFrom, provideZoneChangeDetection } from '@angular/core';
import { bootstrapApplication } from '@angular/platform-browser';
import { BrowserAnimationsModule } from '@angular/platform-browser/animations';
import { HttpClientModule, provideHttpClient, withInterceptors } from '@angular/common/http';
import { provideRouter } from '@angular/router';
import { provideNativeDateAdapter } from '@angular/material/core';
import { JwtModule } from '@auth0/angular-jwt';
import { provideToastr } from 'ngx-toastr';

import { App } from './app/app';
import { routes } from './app/app.routes';
import { authTokenInterceptor } from '@core/auth/token.interceptor';
import { errorResponseInterceptor } from '@core/interceptors/error.interceptor';

function tokenGetter(): string | null {
  return localStorage.getItem('rentoza.accessToken');
}

bootstrapApplication(App, {
  providers: [
    provideZoneChangeDetection({ eventCoalescing: true }),
    provideRouter(routes),
    importProvidersFrom(
      BrowserAnimationsModule,
      HttpClientModule,
      JwtModule.forRoot({
        config: {
          tokenGetter,
          allowedDomains: ['localhost:8080']
        }
      })
    ),
    provideHttpClient(withInterceptors([authTokenInterceptor, errorResponseInterceptor])),
    provideNativeDateAdapter(),
    provideToastr({
      positionClass: 'toast-bottom-right',
      preventDuplicates: true
    })
  ]
}).catch((err) => console.error(err));
