import { Component, OnInit, inject } from '@angular/core';
import { Router, NavigationEnd } from '@angular/router';
import { LayoutComponent } from '@shared/components/layout/layout.component';
import { CookieConsentComponent } from '@shared/components/cookie-consent/cookie-consent.component';
import { ToastComponent } from '@shared/components/toast/toast.component';
import { LoadingBarComponent } from '@shared/components/loading-bar/loading-bar.component';
import { filter } from 'rxjs';

@Component({
  selector: 'app-root',
  standalone: true,
  imports: [LayoutComponent, CookieConsentComponent, ToastComponent, LoadingBarComponent],
  templateUrl: './app.html',
  styleUrl: './app.scss',
})
export class App implements OnInit {
  private readonly router = inject(Router);

  /** When true, the router outlet wrapper gets a 'leaving' class to fade out */
  routeLeaving = false;

  ngOnInit(): void {
    // Scroll to top on every successful navigation (instant, not smooth –
    // smooth would fight the page transition animation)
    this.router.events
      .pipe(filter((e) => e instanceof NavigationEnd))
      .subscribe(() => {
        window.scrollTo({ top: 0, behavior: 'instant' as ScrollBehavior });
      });
  }
}
