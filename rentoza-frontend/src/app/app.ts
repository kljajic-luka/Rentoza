import { Component } from '@angular/core';
import { LayoutComponent } from '@shared/components/layout/layout.component';
import { CookieConsentComponent } from '@shared/components/cookie-consent/cookie-consent.component';

@Component({
  selector: 'app-root',
  standalone: true,
  imports: [LayoutComponent, CookieConsentComponent],
  templateUrl: './app.html',
  styleUrl: './app.scss',
})
export class App {}
