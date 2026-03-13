import { HttpClientTestingModule } from '@angular/common/http/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { TestBed } from '@angular/core/testing';
import { RouterTestingModule } from '@angular/router/testing';

import { CookieConsentComponent } from './cookie-consent.component';
import { AuthService } from '../../../core/auth/auth.service';

describe('CookieConsentComponent', () => {
  const authServiceStub = {
    isAuthenticated: () => false,
  } as Pick<AuthService, 'isAuthenticated'>;

  beforeEach(async () => {
    localStorage.clear();

    await TestBed.configureTestingModule({
      imports: [CookieConsentComponent, RouterTestingModule, HttpClientTestingModule],
      providers: [provideNoopAnimations(), { provide: AuthService, useValue: authServiceStub }],
    }).compileComponents();
  });

  afterEach(() => {
    localStorage.clear();
    document.documentElement.style.removeProperty('--cookie-consent-offset');
  });

  it('renders in fixed mode by default for fresh sessions', () => {
    const fixture = TestBed.createComponent(CookieConsentComponent);
    fixture.detectChanges();

    const compiled = fixture.nativeElement as HTMLElement;
    expect(compiled.querySelector('.cookie-banner--fixed')).not.toBeNull();
  });

  it('renders inline when requested', () => {
    const fixture = TestBed.createComponent(CookieConsentComponent);

    fixture.componentRef.setInput('renderMode', 'inline');
    fixture.detectChanges();

    const compiled = fixture.nativeElement as HTMLElement;
    expect(compiled.querySelector('.cookie-banner--inline')).not.toBeNull();
    expect(compiled.querySelector('.cookie-banner--fixed')).toBeNull();
  });

  it('hides when suspended even if consent is missing', () => {
    const fixture = TestBed.createComponent(CookieConsentComponent);

    fixture.componentRef.setInput('suspended', true);
    fixture.detectChanges();

    const compiled = fixture.nativeElement as HTMLElement;
    expect(compiled.querySelector('.cookie-banner')).toBeNull();
  });

  it('persists consent choice and dismisses the banner', () => {
    const fixture = TestBed.createComponent(CookieConsentComponent);
    fixture.detectChanges();

    const host = fixture.nativeElement as HTMLElement;

    const acceptButton = Array.from(host.querySelectorAll('button')).find(
      (button) => button.textContent?.includes('Prihvati sve'),
    ) as HTMLButtonElement;

    acceptButton.click();
    fixture.detectChanges();

    const storedConsent = localStorage.getItem('rentoza_cookie_consent');
    expect(storedConsent).not.toBeNull();
    expect(JSON.parse(storedConsent!)).toEqual(
      jasmine.objectContaining({
        essential: true,
        analytics: true,
        marketing: true,
      }),
    );
    expect(fixture.componentInstance.showBanner()).toBe(false);
  });
});