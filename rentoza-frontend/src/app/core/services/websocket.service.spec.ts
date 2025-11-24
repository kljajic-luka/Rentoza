import { TestBed } from '@angular/core/testing';
import { BehaviorSubject, Subject } from 'rxjs';
import { ToastrService } from 'ngx-toastr';

import { WebSocketService } from './websocket.service';
import { AuthService } from '@core/auth/auth.service';

class AuthServiceMock {
  private readonly user$ = new BehaviorSubject<any>(null);
  private readonly token$ = new BehaviorSubject<string | null>('token');
  private readonly sessionExpiredStream = new Subject<void>();

  currentUser$ = this.user$.asObservable();
  accessToken$ = this.token$.asObservable();
  sessionExpired$ = this.sessionExpiredStream.asObservable();

  getCurrentUser() {
    return this.user$.value;
  }

  getAccessToken() {
    return this.token$.value;
  }
}

describe('WebSocketService', () => {
  let service: WebSocketService;

  const toastrStub = {
    warning: jasmine.createSpy('warning'),
    error: jasmine.createSpy('error'),
  } as Pick<ToastrService, 'warning' | 'error'>;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [
        WebSocketService,
        { provide: AuthService, useClass: AuthServiceMock },
        { provide: ToastrService, useValue: toastrStub },
      ],
    });

    service = TestBed.inject(WebSocketService);
  });

  it('replays stored subscriptions after reconnect', () => {
    const subscribeSpy = jasmine
      .createSpy('subscribe')
      .and.callFake((_destination: string, handler: any) => {
        return {
          unsubscribe: jasmine.createSpy('unsubscribe'),
          handler,
        } as any;
      });

    (service as any).stompClient = { connected: true, subscribe: subscribeSpy };

    const callback = jasmine.createSpy('callback');
    service.subscribe('/topic/notifications', callback);
    expect(subscribeSpy).toHaveBeenCalledTimes(1);

    subscribeSpy.calls.reset();
    (service as any).stompClient = { connected: true, subscribe: subscribeSpy };

    (service as any).resubscribeAll();

    expect(subscribeSpy).toHaveBeenCalledTimes(1);
    const handler = subscribeSpy.calls.mostRecent().args[1];
    handler({ body: JSON.stringify({ message: 'ping' }) });
    expect(callback).toHaveBeenCalled();
  });
});
