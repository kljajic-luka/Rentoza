import { HttpClientTestingModule } from '@angular/common/http/testing';
import { OverlayContainer } from '@angular/cdk/overlay';
import { BreakpointObserver, BreakpointState } from '@angular/cdk/layout';
import { Component } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { MatDialog } from '@angular/material/dialog';
import { RouterTestingModule } from '@angular/router/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { JwtHelperService } from '@auth0/angular-jwt';
import { ToastrService } from 'ngx-toastr';
import { BehaviorSubject, Observable, Subject } from 'rxjs';
import { Router } from '@angular/router';

import { App } from './app';

@Component({
  standalone: true,
  template: '<div>stub route</div>',
})
class StubRouteComponent {}

class JwtHelperServiceStub {
  decodeToken(): Record<string, unknown> {
    return {};
  }

  isTokenExpired(): boolean {
    return true;
  }
}

class BreakpointObserverStub {
  private readonly state$ = new BehaviorSubject<BreakpointState>({
    matches: false,
    breakpoints: {},
  });

  observe(): Observable<BreakpointState> {
    return this.state$.asObservable();
  }

  setMatches(matches: boolean): void {
    this.state$.next({
      matches,
      breakpoints: {},
    });
  }
}

class OverlayContainerStub {
  readonly element = document.createElement('div');

  getContainerElement(): HTMLElement {
    return this.element;
  }
}

class MatDialogStub {
  readonly afterOpened = new Subject<void>();
  readonly afterAllClosed = new Subject<void>();
  openDialogs: unknown[] = [];
}

describe('App', () => {
  let router: Router;
  let breakpointObserver: BreakpointObserverStub;
  let overlayContainer: OverlayContainerStub;
  let dialog: MatDialogStub;

  beforeEach(async () => {
    breakpointObserver = new BreakpointObserverStub();
    overlayContainer = new OverlayContainerStub();
    dialog = new MatDialogStub();

    localStorage.clear();

    await TestBed.configureTestingModule({
      imports: [
        App,
        RouterTestingModule.withRoutes([
          { path: 'auth/login', component: StubRouteComponent },
          { path: '', component: StubRouteComponent },
        ]),
        HttpClientTestingModule,
      ],
      providers: [
        provideNoopAnimations(),
        { provide: JwtHelperService, useClass: JwtHelperServiceStub },
        { provide: BreakpointObserver, useValue: breakpointObserver },
        { provide: OverlayContainer, useValue: overlayContainer },
        { provide: MatDialog, useValue: dialog },
        {
          provide: ToastrService,
          useValue: {
            success: () => undefined,
            error: () => undefined,
            info: () => undefined,
            warning: () => undefined,
          },
        },
      ],
    }).compileComponents();

    router = TestBed.inject(Router);
  });

  afterEach(() => {
    localStorage.clear();
    overlayContainer.element.replaceChildren();
  });

  it('should create the app', () => {
    const fixture = TestBed.createComponent(App);
    const app = fixture.componentInstance;
    expect(app).toBeTruthy();
  });

  it('should render layout component', () => {
    const fixture = TestBed.createComponent(App);
    fixture.detectChanges();
    const compiled = fixture.nativeElement as HTMLElement;
    expect(compiled.querySelector('app-layout')).not.toBeNull();
  });

  it('renders consent inline on mobile auth routes in fresh sessions', async () => {
    breakpointObserver.setMatches(true);
    await router.navigateByUrl('/auth/login');

    const fixture = TestBed.createComponent(App);
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();

    const compiled = fixture.nativeElement as HTMLElement;
    expect(compiled.querySelector('.cookie-banner--inline')).not.toBeNull();
    expect(compiled.querySelector('.cookie-banner--fixed')).toBeNull();
  });

  it('suppresses consent when a dialog overlay is present', async () => {
    overlayContainer.element.innerHTML =
      '<div class="cdk-overlay-pane"><div class="mat-mdc-dialog-container"></div></div>';

    const fixture = TestBed.createComponent(App);
    dialog.afterOpened.next();
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();

    const compiled = fixture.nativeElement as HTMLElement;
    expect(compiled.querySelector('.cookie-banner')).toBeNull();
  });
});
