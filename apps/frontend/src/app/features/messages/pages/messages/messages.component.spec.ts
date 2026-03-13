import { ComponentFixture, TestBed } from '@angular/core/testing';
import { NO_ERRORS_SCHEMA } from '@angular/core';
import { RouterTestingModule } from '@angular/router/testing';
import { Subject, of, EMPTY } from 'rxjs';

import { MessagesComponent } from './messages.component';
import { ChatService } from '@core/services/chat.service';
import { AuthService } from '@core/auth/auth.service';
import { ThemeService } from '@core/services/theme.service';
import { ToastService } from '@core/services/toast.service';
import { TypingIndicatorDTO } from '@core/models/chat.model';
import { UserRole } from '@core/models/user-role.type';

describe('MessagesComponent typing indicators', () => {
  let component: MessagesComponent;
  let fixture: ComponentFixture<MessagesComponent>;
  let typingSubject: Subject<TypingIndicatorDTO>;

  const mockUser = {
    id: 'user-123',
    firstName: 'Test',
    lastName: 'User',
    email: 'test@example.com',
    roles: ['USER'] as UserRole[],
  };

  beforeEach(async () => {
    typingSubject = new Subject<TypingIndicatorDTO>();

    const chatServiceMock = {
      typingIndicators$: typingSubject.asObservable(),
      messages$: EMPTY,
      messageStatusUpdates$: EMPTY,
      conversations$: EMPTY,
      activeConversation$: EMPTY,
      initializeWebSocket: jasmine.createSpy('initializeWebSocket').and.returnValue(Promise.resolve()),
      sendTypingIndicator: jasmine.createSpy('sendTypingIndicator'),
      getUserConversations: jasmine.createSpy('getUserConversations').and.returnValue(of([])),
      getAdminConversations: jasmine.createSpy('getAdminConversations').and.returnValue(of([])),
    };

    const authServiceMock = {
      currentUser$: of(mockUser),
      hasAnyRole: jasmine.createSpy('hasAnyRole').and.returnValue(false),
      getCurrentUser: jasmine.createSpy('getCurrentUser').and.returnValue(mockUser),
    };

    const themeServiceMock = {
      theme: () => 'light',
    };

    const toastServiceMock = {
      success: jasmine.createSpy('success'),
      error: jasmine.createSpy('error'),
    };

    await TestBed.configureTestingModule({
      imports: [MessagesComponent, RouterTestingModule],
      providers: [
        { provide: ChatService, useValue: chatServiceMock },
        { provide: AuthService, useValue: authServiceMock },
        { provide: ThemeService, useValue: themeServiceMock },
        { provide: ToastService, useValue: toastServiceMock },
      ],
      schemas: [NO_ERRORS_SCHEMA],
    }).compileComponents();

    fixture = TestBed.createComponent(MessagesComponent);
    component = fixture.componentInstance;

    // Set current user and selected conversation before triggering ngOnInit
    component.currentUserId.set(mockUser.id);
    component.selectedConversationId.set(1);

    fixture.detectChanges();
    // Allow async ngOnInit to settle
    await fixture.whenStable();
  });

  it('should show typing from other user in selected conversation', () => {
    typingSubject.next({
      conversationId: 1,
      userId: 'other-user',
      userName: 'Other User',
      isTyping: true,
      timestamp: new Date().toISOString(),
    });

    expect(component.isTyping()).toBe(true);
    expect(component.typingUserName()).toBe('Other User');
  });

  it('should not show typing from the current user (self-event defense-in-depth)', () => {
    typingSubject.next({
      conversationId: 1,
      userId: mockUser.id,
      userName: 'Test User',
      isTyping: true,
      timestamp: new Date().toISOString(),
    });

    expect(component.isTyping()).toBe(false);
    expect(component.typingUserName()).toBe('');
  });

  it('should not show typing from a different conversation', () => {
    typingSubject.next({
      conversationId: 999,
      userId: 'other-user',
      userName: 'Other User',
      isTyping: true,
      timestamp: new Date().toISOString(),
    });

    expect(component.isTyping()).toBe(false);
    expect(component.typingUserName()).toBe('');
  });

  it('should clear typing when isTyping is false', () => {
    // Start typing
    typingSubject.next({
      conversationId: 1,
      userId: 'other-user',
      userName: 'Other User',
      isTyping: true,
      timestamp: new Date().toISOString(),
    });
    expect(component.isTyping()).toBe(true);

    // Stop typing
    typingSubject.next({
      conversationId: 1,
      userId: 'other-user',
      userName: 'Other User',
      isTyping: false,
      timestamp: new Date().toISOString(),
    });
    expect(component.isTyping()).toBe(false);
  });
});
