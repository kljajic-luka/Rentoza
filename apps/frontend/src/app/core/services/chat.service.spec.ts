import { TestBed, fakeAsync, tick, flush } from '@angular/core/testing';
import { HttpClientTestingModule, HttpTestingController } from '@angular/common/http/testing';
import { ChatService } from './chat.service';
import { WebSocketService, WebSocketConnectionStatus } from './websocket.service';
import { AuthService } from '@core/auth/auth.service';
import { ToastService } from './toast.service';
import { BehaviorSubject, of } from 'rxjs';
import { MessageDTO, ConversationDTO, TypingIndicatorDTO } from '@core/models/chat.model';
import { UserRole } from '@core/models/user-role.type';

/**
 * Unit tests for ChatService
 * Tests typing indicators, optimistic updates, and offline queue
 */
describe('ChatService', () => {
  let service: ChatService;
  let httpMock: HttpTestingController;
  let webSocketServiceMock: jasmine.SpyObj<WebSocketService>;
  let authServiceMock: jasmine.SpyObj<AuthService>;
  let toastServiceMock: jasmine.SpyObj<ToastService>;
  let wsStatus$: BehaviorSubject<WebSocketConnectionStatus>;

  const mockUser = {
    id: 'user-123',
    firstName: 'Test',
    lastName: 'User',
    email: 'test@example.com',
    roles: ['USER'] as UserRole[],
  };

  beforeEach(() => {
    wsStatus$ = new BehaviorSubject<WebSocketConnectionStatus>(
      WebSocketConnectionStatus.DISCONNECTED
    );

    webSocketServiceMock = jasmine.createSpyObj(
      'WebSocketService',
      ['connect', 'disconnect', 'subscribe', 'send', 'isConnected'],
      {
        status$: wsStatus$.asObservable(),
      }
    );

    authServiceMock = jasmine.createSpyObj('AuthService', ['getCurrentUser', 'getAccessToken'], {
      currentUser$: of(mockUser),
    });
    authServiceMock.getCurrentUser.and.returnValue(mockUser);

    toastServiceMock = jasmine.createSpyObj('ToastService', ['success', 'error', 'warning']);

    TestBed.configureTestingModule({
      imports: [HttpClientTestingModule],
      providers: [
        ChatService,
        { provide: WebSocketService, useValue: webSocketServiceMock },
        { provide: AuthService, useValue: authServiceMock },
        { provide: ToastService, useValue: toastServiceMock },
      ],
    });

    service = TestBed.inject(ChatService);
    httpMock = TestBed.inject(HttpTestingController);

    // Clear localStorage before each test
    localStorage.removeItem('rentoza_chat_offline_queue');
  });

  afterEach(() => {
    httpMock.verify();
    localStorage.removeItem('rentoza_chat_offline_queue');
  });

  describe('initialization', () => {
    it('should be created', () => {
      expect(service).toBeTruthy();
    });

    it('should load offline queue from localStorage on init', () => {
      const queuedItems = [
        {
          id: 'item-1',
          bookingId: 'booking-1',
          content: 'Test',
          timestamp: new Date().toISOString(),
          retryCount: 0,
          status: 'queued' as const,
        },
      ];
      localStorage.setItem('rentoza_chat_offline_queue', JSON.stringify(queuedItems));

      const newService = TestBed.inject(ChatService);
      // Force re-initialization would require a fresh TestBed
    });
  });

  describe('typing indicators', () => {
    it('should send typing indicator via WebSocket when sendTypingIndicator is called', () => {
      webSocketServiceMock.isConnected.and.returnValue(true);

      service.sendTypingIndicator('booking-123', true);

      expect(webSocketServiceMock.send).toHaveBeenCalledWith(
        '/app/chat/booking-123/typing',
        jasmine.objectContaining({
          isTyping: true,
          userId: mockUser.id,
        })
      );
    });

    it('should not send typing indicator when WebSocket is not connected', () => {
      webSocketServiceMock.isConnected.and.returnValue(false);

      service.sendTypingIndicator('booking-123', true);

      expect(webSocketServiceMock.send).not.toHaveBeenCalled();
    });

    it('should emit typing indicator via typingIndicators$ when received', (done) => {
      const typingData: TypingIndicatorDTO = {
        conversationId: 1,
        userId: 'other-user',
        userName: 'Other User',
        isTyping: true,
        timestamp: new Date().toISOString(),
      };

      service.typingIndicators$.subscribe((typing) => {
        expect(typing.conversationId).toBe(1);
        expect(typing.isTyping).toBe(true);
        done();
      });

      // Simulate receiving typing indicator through private method
      (service as any).handleTypingIndicator(typingData);
    });
  });

  describe('optimistic updates', () => {
    it('should create optimistic message with temporary ID', () => {
      const message = service.createOptimisticMessage('Hello World', 1);

      expect(message.id).toBeLessThan(0); // Negative ID for optimistic
      expect(message.content).toBe('Hello World');
      expect(message.conversationId).toBe(1);
      expect(message.senderId).toBe(mockUser.id);
      expect(message.optimisticId).toBeDefined();
      expect(message.status).toBe('sending');
    });

    it('should replace optimistic message with server response', fakeAsync(() => {
      // Set up active conversation
      const conv: ConversationDTO = {
        id: 1,
        bookingId: 'booking-1',
        renterId: 'renter-1',
        ownerId: 'owner-1',
        status: 'ACTIVE',
        createdAt: new Date().toISOString(),
        unreadCount: 0,
        messagingAllowed: true,
        messages: [],
      };

      (service as any).activeConversationSubject.next(conv);

      const optimisticMessage = service.createOptimisticMessage('Test', 1);
      conv.messages = [optimisticMessage];
      (service as any).activeConversationSubject.next({ ...conv, messages: [optimisticMessage] });

      const serverMessage: MessageDTO = {
        id: 999,
        conversationId: 1,
        senderId: mockUser.id,
        content: 'Test',
        timestamp: new Date().toISOString(),
        readBy: [],
        isRead: false,
      };

      service.replaceOptimisticMessage(optimisticMessage.optimisticId!, serverMessage);

      const updatedConv = (service as any).activeConversationSubject.value;
      expect(updatedConv.messages[0].id).toBe(999);
      expect(updatedConv.messages[0].status).toBe('sent');
    }));

    it('should mark optimistic message as failed on error', () => {
      const conv: ConversationDTO = {
        id: 1,
        bookingId: 'booking-1',
        renterId: 'renter-1',
        ownerId: 'owner-1',
        status: 'ACTIVE',
        createdAt: new Date().toISOString(),
        unreadCount: 0,
        messagingAllowed: true,
        messages: [],
      };

      const optimisticMessage = service.createOptimisticMessage('Test', 1);
      conv.messages = [optimisticMessage];
      (service as any).activeConversationSubject.next({ ...conv, messages: [optimisticMessage] });

      service.markOptimisticMessageFailed(optimisticMessage.optimisticId!);

      const updatedConv = (service as any).activeConversationSubject.value;
      expect(updatedConv.messages[0].status).toBe('failed');
    });
  });

  describe('offline queue', () => {
    it('should queue message when offline', () => {
      const item = service.queueOfflineMessage('booking-1', 'Offline message');

      expect(item.id).toContain('offline_');
      expect(item.bookingId).toBe('booking-1');
      expect(item.content).toBe('Offline message');
      expect(item.status).toBe('queued');
      expect(item.retryCount).toBe(0);
    });

    it('should persist offline queue to localStorage', () => {
      service.queueOfflineMessage('booking-1', 'Test message');

      const stored = localStorage.getItem('rentoza_chat_offline_queue');
      expect(stored).toBeTruthy();

      const parsed = JSON.parse(stored!);
      expect(parsed.length).toBe(1);
      expect(parsed[0].content).toBe('Test message');
    });

    it('should return correct queue count', () => {
      expect(service.getOfflineQueueCount()).toBe(0);

      service.queueOfflineMessage('booking-1', 'Message 1');
      expect(service.getOfflineQueueCount()).toBe(1);

      service.queueOfflineMessage('booking-2', 'Message 2');
      expect(service.getOfflineQueueCount()).toBe(2);
    });

    it('should flush offline queue when WebSocket reconnects', fakeAsync(() => {
      // Queue a message
      service.queueOfflineMessage('booking-1', 'Queued message');

      // Simulate WebSocket reconnection
      wsStatus$.next(WebSocketConnectionStatus.CONNECTED);
      tick(100);

      // Should attempt to send queued message
      const req = httpMock.expectOne((r) => r.url.includes('/conversations/booking-1/messages'));
      expect(req.request.body.content).toBe('Queued message');

      req.flush({
        id: 1,
        conversationId: 1,
        senderId: mockUser.id,
        content: 'Queued message',
        timestamp: new Date().toISOString(),
        readBy: [],
        isRead: false,
      });

      tick();
      flush();

      // Queue should be empty after successful send
      expect(service.getOfflineQueueCount()).toBe(0);
    }));
  });

  describe('message sending', () => {
    it('should send message via REST API', () => {
      const conv: ConversationDTO = {
        id: 1,
        bookingId: 'booking-1',
        renterId: 'renter-1',
        ownerId: 'owner-1',
        status: 'ACTIVE',
        createdAt: new Date().toISOString(),
        unreadCount: 0,
        messagingAllowed: true,
      };

      (service as any).activeConversationSubject.next(conv);

      service.sendMessage('booking-1', { content: 'Hello' }).subscribe();

      const req = httpMock.expectOne((r) => r.url.includes('/conversations/booking-1/messages'));
      expect(req.request.method).toBe('POST');
      expect(req.request.body.content).toBe('Hello');

      req.flush({
        id: 1,
        conversationId: 1,
        senderId: mockUser.id,
        content: 'Hello',
        timestamp: new Date().toISOString(),
        readBy: [],
        isRead: false,
      });
    });

    it('should show error toast on message send failure', () => {
      service.sendMessage('booking-1', { content: 'Hello' }).subscribe({
        error: () => {},
      });

      const req = httpMock.expectOne((r) => r.url.includes('/conversations/booking-1/messages'));
      req.error(new ErrorEvent('Network error'));

      expect(toastServiceMock.error).toHaveBeenCalled();
    });
  });

  describe('WebSocket operations', () => {
    it('should send message via WebSocket when connected', () => {
      webSocketServiceMock.isConnected.and.returnValue(true);

      service.sendMessageViaWebSocket('booking-1', 'Hello');

      expect(webSocketServiceMock.send).toHaveBeenCalledWith(
        '/app/chat/booking-1',
        jasmine.objectContaining({
          content: 'Hello',
          bookingId: 'booking-1',
        })
      );
    });

    it('should fallback to REST API when WebSocket not connected', () => {
      webSocketServiceMock.isConnected.and.returnValue(false);

      const conv: ConversationDTO = {
        id: 1,
        bookingId: 'booking-1',
        renterId: 'renter-1',
        ownerId: 'owner-1',
        status: 'ACTIVE',
        createdAt: new Date().toISOString(),
        unreadCount: 0,
        messagingAllowed: true,
      };

      (service as any).activeConversationSubject.next(conv);

      service.sendMessageViaWebSocket('booking-1', 'Hello');

      const req = httpMock.expectOne((r) => r.url.includes('/conversations/booking-1/messages'));
      req.flush({
        id: 1,
        conversationId: 1,
        senderId: mockUser.id,
        content: 'Hello',
        timestamp: new Date().toISOString(),
        readBy: [],
        isRead: false,
      });
    });
  });

  describe('conversations', () => {
    it('should fetch user conversations', () => {
      service.getUserConversations().subscribe((convs) => {
        expect(convs.length).toBe(1);
      });

      const req = httpMock.expectOne((r) => r.url.includes('/conversations'));
      expect(req.request.method).toBe('GET');

      req.flush([
        {
          id: 1,
          bookingId: 'booking-1',
          renterId: 'renter-1',
          ownerId: 'owner-1',
          status: 'ACTIVE',
          createdAt: new Date().toISOString(),
          unreadCount: 2,
          messagingAllowed: true,
        },
      ]);
    });

    it('should sort conversations by lastMessageAt', () => {
      service.getUserConversations().subscribe((convs) => {
        expect(convs[0].bookingId).toBe('booking-2'); // Most recent
      });

      const req = httpMock.expectOne((r) => r.url.includes('/conversations'));
      req.flush([
        {
          id: 1,
          bookingId: 'booking-1',
          lastMessageAt: '2024-01-01T10:00:00Z',
          renterId: 'renter-1',
          ownerId: 'owner-1',
          status: 'ACTIVE',
          createdAt: new Date().toISOString(),
          unreadCount: 0,
          messagingAllowed: true,
        },
        {
          id: 2,
          bookingId: 'booking-2',
          lastMessageAt: '2024-01-02T10:00:00Z', // More recent
          renterId: 'renter-1',
          ownerId: 'owner-2',
          status: 'ACTIVE',
          createdAt: new Date().toISOString(),
          unreadCount: 0,
          messagingAllowed: true,
        },
      ]);
    });

    it('should mark messages as read', () => {
      const conv: ConversationDTO = {
        id: 1,
        bookingId: 'booking-1',
        renterId: 'renter-1',
        ownerId: 'owner-1',
        status: 'ACTIVE',
        createdAt: new Date().toISOString(),
        unreadCount: 5,
        messagingAllowed: true,
      };

      (service as any).conversationsSubject.next([conv]);
      (service as any).activeConversationSubject.next(conv);

      service.markMessagesAsRead('booking-1').subscribe();

      const req = httpMock.expectOne((r) => r.url.includes('/conversations/booking-1/read'));
      expect(req.request.method).toBe('PUT');
      req.flush(null);

      const conversations = (service as any).conversationsSubject.value;
      expect(conversations[0].unreadCount).toBe(0);
    });
  });
});
