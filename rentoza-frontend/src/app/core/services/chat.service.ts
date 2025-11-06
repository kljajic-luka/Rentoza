import { Injectable, inject, OnDestroy } from '@angular/core';
import { HttpClient, HttpParams, HttpErrorResponse } from '@angular/common/http';
import { Observable, Subject, BehaviorSubject, firstValueFrom, throwError, EMPTY } from 'rxjs';
import { map, tap, catchError, takeUntil, retry, shareReplay } from 'rxjs/operators';
import { environment } from '@environments/environment';
import {
  ConversationDTO,
  MessageDTO,
  SendMessageRequest,
  CreateConversationRequest,
} from '@core/models/chat.model';
import { WebSocketService, WebSocketConnectionStatus } from './websocket.service';
import { AuthService } from '@core/auth/auth.service';
import { ToastrService } from 'ngx-toastr';

@Injectable({
  providedIn: 'root',
})
export class ChatService implements OnDestroy {
  private readonly http = inject(HttpClient);
  private readonly webSocketService = inject(WebSocketService);
  private readonly authService = inject(AuthService);
  private readonly toastr = inject(ToastrService);
  private readonly chatApiUrl = environment.chatApiUrl || 'http://localhost:8081/api';

  private messageSubject = new Subject<MessageDTO>();
  private conversationsSubject = new BehaviorSubject<ConversationDTO[]>([]);
  private activeConversationSubject = new BehaviorSubject<ConversationDTO | null>(null);
  private destroy$ = new Subject<void>();

  private isWebSocketInitialized = false;
  private webSocketInitPromise: Promise<void> | null = null;

  public messages$ = this.messageSubject.asObservable();
  public conversations$ = this.conversationsSubject.asObservable();
  public activeConversation$ = this.activeConversationSubject.asObservable();

  constructor() {
    // Subscribe to WebSocket status changes
    this.webSocketService.status$.pipe(takeUntil(this.destroy$)).subscribe((status) => {
      console.log('[ChatService] WebSocket status:', status);
      if (
        status === WebSocketConnectionStatus.DISCONNECTED ||
        status === WebSocketConnectionStatus.ERROR
      ) {
        // Handle disconnection - could reload conversations when reconnected
      }
    });
  }

  ngOnDestroy(): void {
    this.destroy$.next();
    this.destroy$.complete();
    this.disconnectWebSocket();
  }

  /**
   * Initialize WebSocket connection and subscriptions (idempotent)
   */
  async initializeWebSocket(): Promise<void> {
    // If already initialized, return immediately
    if (this.isWebSocketInitialized && this.webSocketService.isConnected()) {
      console.log('[ChatService] WebSocket already initialized and connected');
      return;
    }

    // If initialization is in progress, return the existing promise
    if (this.webSocketInitPromise) {
      console.log('[ChatService] WebSocket initialization already in progress');
      return this.webSocketInitPromise;
    }

    // Start new initialization
    this.webSocketInitPromise = this._initializeWebSocket();

    try {
      await this.webSocketInitPromise;
    } finally {
      this.webSocketInitPromise = null;
    }
  }

  private async _initializeWebSocket(): Promise<void> {
    try {
      // Check if user is authenticated
      const user = this.authService.getCurrentUser();
      if (!user) {
        throw new Error('User not authenticated');
      }

      // Connect to WebSocket
      await this.webSocketService.connect();

      // Subscribe to user's personal queue for messages
      this.webSocketService.subscribe('/user/queue/messages', (message) => {
        try {
          const msg: MessageDTO = JSON.parse(message.body);
          this.handleIncomingMessage(msg);
        } catch (error) {
          console.error('[ChatService] Error processing WebSocket message:', error);
        }
      });

      // Subscribe to broadcast topic for public messages
      this.webSocketService.subscribe('/topic/messages', (message) => {
        try {
          const msg: MessageDTO = JSON.parse(message.body);
          this.handleIncomingMessage(msg);
        } catch (error) {
          console.error('[ChatService] Error processing WebSocket message:', error);
        }
      });

      this.isWebSocketInitialized = true;
      console.log('[ChatService] WebSocket initialized successfully');
    } catch (error) {
      console.error('[ChatService] Failed to initialize WebSocket:', error);
      this.isWebSocketInitialized = false;
      throw error;
    }
  }

  /**
   * Disconnect WebSocket
   */
  disconnectWebSocket(): void {
    this.webSocketService.disconnect(true);
    this.isWebSocketInitialized = false;
    this.webSocketInitPromise = null;
  }

  /**
   * Create a new conversation
   */
  createConversation(request: CreateConversationRequest): Observable<ConversationDTO> {
    return this.http.post<ConversationDTO>(`${this.chatApiUrl}/conversations`, request).pipe(
      tap((conversation) => {
        // Add to conversations list
        const conversations = this.conversationsSubject.value;
        this.conversationsSubject.next([conversation, ...conversations]);
        console.log('[ChatService] Conversation created:', conversation.id);
      }),
      catchError(this.handleError.bind(this))
    );
  }

  /**
   * Get a specific conversation by booking ID
   */
  getConversation(bookingId: string, page = 0, size = 50): Observable<ConversationDTO> {
    const params = new HttpParams().set('page', page.toString()).set('size', size.toString());
    return this.http
      .get<ConversationDTO>(`${this.chatApiUrl}/conversations/${bookingId}`, {
        params,
      })
      .pipe(
        tap((conversation) => {
          this.activeConversationSubject.next(conversation);
          console.log('[ChatService] Conversation loaded:', conversation.id);
        }),
        shareReplay(1),
        catchError(this.handleError.bind(this))
      );
  }

  /**
   * Send a message via REST API
   */
  sendMessage(bookingId: string, request: SendMessageRequest): Observable<MessageDTO> {
    return this.http
      .post<MessageDTO>(`${this.chatApiUrl}/conversations/${bookingId}/messages`, request)
      .pipe(
        tap((message) => {
          // Optimistically add to active conversation
          this.addMessageToActiveConversation(message);
          console.log('[ChatService] Message sent:', message.id);
        }),
        catchError((error) => {
          this.toastr.error('Failed to send message', 'Error');
          return this.handleError(error);
        })
      );
  }

  /**
   * Send a message via WebSocket (faster)
   */
  sendMessageViaWebSocket(bookingId: string, content: string): void {
    if (!this.webSocketService.isConnected()) {
      console.warn('[ChatService] WebSocket not connected, falling back to HTTP');
      this.sendMessage(bookingId, { content }).subscribe();
      return;
    }

    this.webSocketService.send(`/app/chat/${bookingId}`, {
      content,
      bookingId,
    });
  }

  /**
   * Mark messages as read
   */
  markMessagesAsRead(bookingId: string): Observable<void> {
    return this.http.put<void>(`${this.chatApiUrl}/conversations/${bookingId}/read`, {}).pipe(
      tap(() => {
        // Update unread count in conversations list
        const conversations = this.conversationsSubject.value;
        const updated = conversations.map((conv) =>
          conv.bookingId === bookingId ? { ...conv, unreadCount: 0 } : conv
        );
        this.conversationsSubject.next(updated);

        // Update active conversation
        const activeConv = this.activeConversationSubject.value;
        if (activeConv && activeConv.bookingId === bookingId) {
          this.activeConversationSubject.next({ ...activeConv, unreadCount: 0 });
        }
      }),
      catchError(this.handleError.bind(this))
    );
  }

  /**
   * Get all conversations for the authenticated user
   */
  getUserConversations(): Observable<ConversationDTO[]> {
    console.log('[ChatService] Fetching conversations from:', `${this.chatApiUrl}/conversations`);
    return this.http.get<ConversationDTO[]>(`${this.chatApiUrl}/conversations`).pipe(
      map((conversations) => {
        // Sort by lastMessageAt (most recent first)
        const sorted = conversations.sort((a, b) => {
          const dateA = a.lastMessageAt ? new Date(a.lastMessageAt).getTime() : 0;
          const dateB = b.lastMessageAt ? new Date(b.lastMessageAt).getTime() : 0;
          return dateB - dateA;
        });
        return sorted;
      }),
      tap((conversations) => {
        console.log(
          '[ChatService] Loaded and sorted',
          conversations.length,
          'conversations:',
          conversations
        );
        this.conversationsSubject.next(conversations);
      }),
      retry({
        count: 2,
        delay: 1000,
      }),
      catchError((error) => {
        console.error('[ChatService] Error loading conversations:', error);
        this.toastr.error('Failed to load conversations', 'Error');
        return this.handleError(error);
      })
    );
  }

  /**
   * Get or create conversation for a booking
   */
  async getOrCreateConversation(
    bookingId: string,
    renterId: string,
    ownerId: string,
    initialMessage?: string
  ): Promise<ConversationDTO> {
    try {
      // Try to fetch existing conversation
      return await firstValueFrom(this.getConversation(bookingId));
    } catch (error: any) {
      // If 404, create new conversation
      if (error.status === 404) {
        const request: CreateConversationRequest = {
          bookingId,
          renterId,
          ownerId,
          initialMessage,
        };
        return await firstValueFrom(this.createConversation(request));
      }
      throw error;
    }
  }

  /**
   * Set active conversation
   */
  setActiveConversation(conversation: ConversationDTO | null): void {
    this.activeConversationSubject.next(conversation);
  }

  /**
   * Get current user ID
   */
  getCurrentUserId(): string {
    return this.authService.getCurrentUser()?.id || '';
  }

  /**
   * Check if WebSocket is connected
   */
  isWebSocketConnected(): boolean {
    return this.webSocketService.isConnected();
  }

  /**
   * Handle incoming message from WebSocket
   */
  private handleIncomingMessage(message: MessageDTO): void {
    // Emit to message stream
    this.messageSubject.next(message);

    // Update conversation with new message
    this.updateConversationWithNewMessage(message);

    // Add to active conversation if it matches
    this.addMessageToActiveConversation(message);
  }

  /**
   * Update conversation with new message from WebSocket
   */
  private updateConversationWithNewMessage(message: MessageDTO): void {
    const conversations = this.conversationsSubject.value;
    let conversationFound = false;

    const updated = conversations.map((conv) => {
      if (conv.id === message.conversationId) {
        conversationFound = true;
        return {
          ...conv,
          lastMessageAt: message.timestamp,
          unreadCount: conv.unreadCount + (message.isOwnMessage ? 0 : 1),
        };
      }
      return conv;
    });

    // Sort by lastMessageAt
    updated.sort((a, b) => {
      const dateA = a.lastMessageAt ? new Date(a.lastMessageAt).getTime() : 0;
      const dateB = b.lastMessageAt ? new Date(b.lastMessageAt).getTime() : 0;
      return dateB - dateA;
    });

    this.conversationsSubject.next(updated);

    // If conversation not found, it might be new - reload conversations
    if (!conversationFound) {
      console.log('[ChatService] New conversation detected, reloading...');
      this.getUserConversations().subscribe();
    }
  }

  /**
   * Add message to active conversation
   */
  private addMessageToActiveConversation(message: MessageDTO): void {
    const activeConv = this.activeConversationSubject.value;
    if (activeConv && activeConv.id === message.conversationId) {
      const messages = activeConv.messages || [];
      // Avoid duplicates
      if (!messages.find((m) => m.id === message.id)) {
        this.activeConversationSubject.next({
          ...activeConv,
          messages: [...messages, message],
          lastMessageAt: message.timestamp,
        });
      }
    }
  }

  /**
   * Centralized error handling
   */
  private handleError(error: HttpErrorResponse): Observable<never> {
    let errorMessage = 'An error occurred';

    if (error.error instanceof ErrorEvent) {
      // Client-side error
      errorMessage = `Error: ${error.error.message}`;
    } else {
      // Server-side error
      errorMessage = `Error ${error.status}: ${error.message}`;
      if (error.error?.message) {
        errorMessage = error.error.message;
      }
    }

    console.error('[ChatService] HTTP Error:', errorMessage, error);
    return throwError(() => error);
  }

  // Keep legacy polling methods for backward compatibility
  startPolling(bookingId: string, intervalMs = 3000): void {
    console.warn('[ChatService] Polling is deprecated. Use WebSocket instead.');
  }

  stopPolling(): void {
    // No-op - polling is deprecated
  }
}
