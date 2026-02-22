import { HttpClientTestingModule } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { RouterTestingModule } from '@angular/router/testing';
import { JwtHelperService } from '@auth0/angular-jwt';
import { ToastrService } from 'ngx-toastr';

import { App } from './app';

class JwtHelperServiceStub {
  decodeToken(): Record<string, unknown> {
    return {};
  }

  isTokenExpired(): boolean {
    return true;
  }
}

describe('App', () => {
  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [App, RouterTestingModule, HttpClientTestingModule],
      providers: [
        { provide: JwtHelperService, useClass: JwtHelperServiceStub },
        {
          provide: ToastrService,
          useValue: {
            success: () => undefined,
            error: () => undefined,
            info: () => undefined,
            warning: () => undefined
          }
        }
      ]
    }).compileComponents();
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
});