import { ComponentFixture, TestBed } from '@angular/core/testing';
import { computed, signal } from '@angular/core';
import { NO_ERRORS_SCHEMA } from '@angular/core';
import { Subject } from 'rxjs';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { MatSnackBar } from '@angular/material/snack-bar';

import { GuidedPhotoCaptureComponent } from './guided-photo-capture.component';
import { PhotoGuidanceService } from '../../../../core/services/photo-guidance.service';
import { ThemeService } from '../../../../core/services/theme.service';
import { CheckInPersistenceService } from '../../../../core/services/check-in-persistence.service';

describe('GuidedPhotoCaptureComponent', () => {
  let fixture: ComponentFixture<GuidedPhotoCaptureComponent>;
  let component: GuidedPhotoCaptureComponent;
  let startSequence$: Subject<any[]>;

  beforeEach(async () => {
    startSequence$ = new Subject<any[]>();

    const captureSequence = signal<any[]>([]);
    const currentIndex = signal(0);

    const guidanceServiceStub = {
      currentGuidance: computed(() => captureSequence()[currentIndex()] ?? null),
      currentIndex: currentIndex.asReadonly(),
      captureSequence: captureSequence.asReadonly(),
      startGuestCheckInCapture: jasmine
        .createSpy('startGuestCheckInCapture')
        .and.returnValue(startSequence$.asObservable()),
      startHostCheckInCapture: jasmine
        .createSpy('startHostCheckInCapture')
        .and.returnValue(startSequence$.asObservable()),
      startCheckoutCapture: jasmine
        .createSpy('startCheckoutCapture')
        .and.returnValue(startSequence$.asObservable()),
      goToPhoto: jasmine.createSpy('goToPhoto'),
      recordCapture: jasmine.createSpy('recordCapture'),
      endCapture: jasmine.createSpy('endCapture'),
      reset: jasmine.createSpy('reset'),
    };

    const persistenceStub = {
      releaseLock: jasmine.createSpy('releaseLock'),
      base64ToBlob: jasmine.createSpy('base64ToBlob'),
      blobToBase64: jasmine.createSpy('blobToBase64'),
      saveCaptureState: jasmine.createSpy('saveCaptureState').and.resolveTo(),
    };

    await TestBed.configureTestingModule({
      imports: [GuidedPhotoCaptureComponent],
      providers: [
        provideNoopAnimations(),
        { provide: PhotoGuidanceService, useValue: guidanceServiceStub },
        { provide: ThemeService, useValue: { theme: signal<'light' | 'dark'>('light') } },
        { provide: CheckInPersistenceService, useValue: persistenceStub },
        { provide: MatSnackBar, useValue: jasmine.createSpyObj('MatSnackBar', ['open']) },
      ],
      schemas: [NO_ERRORS_SCHEMA],
    }).compileComponents();

    fixture = TestBed.createComponent(GuidedPhotoCaptureComponent);
    component = fixture.componentInstance;
    fixture.componentRef.setInput('bookingId', 42);
    fixture.componentRef.setInput('mode', 'guest-checkin');
  });

  it('renders a loading shell before guidance arrives', () => {
    expect(() => fixture.detectChanges()).not.toThrow();

    expect(fixture.nativeElement.textContent).toContain('Učitavanje uputstava');
  });
});