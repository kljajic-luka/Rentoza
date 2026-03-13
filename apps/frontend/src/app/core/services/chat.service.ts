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
  MessageStatusUpdate,
  TypingIndicatorDTO,
  OfflineQueueItem,
} from '@core/models/chat.model';
import { WebSocketService, WebSocketConnectionStatus } from './websocket.service';
import { AuthService } from '@core/auth/auth.service';
import { ToastService } from './toast.service';

/**
 * Custom error for authentication failures.
 * Used to distinguish auth errors from connection errors.
 */
export class AuthenticationError extends Error {
  constructor(message: string) {
    super(message);
    this.name = 'AuthenticationError';
    Object.setPrototypeOf(this, AuthenticationError.prototype);
  }
}

/**
 * Custom error for connection failures.
 * Used to trigger retry logic with exponential backoff.
 */
export class ConnectionError extends Error {
  constructor(message: string) {
    super(message);
    this.name = 'ConnectionError';
    Object.setPrototypeOf(this, ConnectionError.prototype);
  }
}

const OFFLINE_QUEUE_KEY = 'rentoza_chat_offline_queue';
const MAX_RETRY_COUNT = 3;

/**
 * ChatService - Enterprise-grade messaging service
 *
 * Features:
 * - WebSocket real-time messaging
 * - Typing indicators
 * - Optimistic updates
 * - Offline queue with localStorage persistence
 * - Read receipts
 */
@Injectable({
  providedIn: 'root',
})
export class ChatService implements OnDestroy {
  private readonly http = inject(HttpClient);
  private readonly webSocketService = inject(WebSocketService);
  private readonly authService = inject(AuthService);
  private readonly toast = inject(ToastService);
  private readonly chatApiUrl = environment.chatApiUrl;

  // Subjects for reactive streams
  private messageSubject = new Subject<MessageDTO>();
  private messageStatusUpdateSubject = new Subject<MessageStatusUpdate>();
  private typingIndicatorSubject = new Subject<TypingIndicatorDTO>();
  private conversationsSubject = new BehaviorSubject<ConversationDTO[]>([]);
  private activeConversationSubject = new BehaviorSubject<ConversationDTO | null>(null);
  private offlineQueueSubject = new BehaviorSubject<OfflineQueueItem[]>([]);
  private destroy$ = new Subject<void>();

  private isWebSocketInitialized = false;
  private webSocketInitPromise: Promise<void> | null = null;
  private typingTimeouts = new Map<number, ReturnType<typeof setTimeout>>();
  private activeSubscriptionBookingId: string | null = null;

  // Public observables
  public messages$ = this.messageSubject.asObservable();
  public messageStatusUpdates$ = this.messageStatusUpdateSubject.asObservable();
  public typingIndicators$ = this.typingIndicatorSubject.asObservable();
  public conversations$ = this.conversationsSubject.asObservable();
  public activeConversation$ = this.activeConversationSubject.asObservable();
  public offlineQueue$ = this.offlineQueueSubject.asObservable();

  constructor() {
    // Load offline queue from localStorage
    this.loadOfflineQueue();

    // Subscribe to WebSocket status changes
    this.webSocketService.status$.pipe(takeUntil(this.destroy$)).subscribe((status) => {
      if (status === WebSocketConnectionStatus.CONNECTED) {
        // WebSocket reconnected - flush offline queue
        this.flushOfflineQueue();
      }
    });

    // Also flush offline queue when browser comes back online (REST fallback)
    // This handles the case where WS is not connected but HTTP is available
    if (typeof window !== 'undefined') {
      const onlineHandler = () => {
        if (this.offlineQueueSubject.value.length > 0) {
          console.log('[Chat] Network online detected, flushing offline queue via REST');
          this.flushOfflineQueue();
        }
      };
      window.addEventListener('online', onlineHandler);
      this.destroy$.subscribe(() => window.removeEventListener('online', onlineHandler));
    }
  }

  ngOnDestroy(): void {
    this.destroy$.next();
    this.destroy$.complete();
    this.disconnectWebSocket();
    this.typingTimeouts.forEach((timeout) => clearTimeout(timeout));
    this.typingTimeouts.clear();
  }

  // ===========================================================================
  // WebSocket Initialization
  // ===========================================================================

  async initializeWebSocket(): Promise<void> {
    if (this.isWebSocketInitialized && this.webSocketService.isConnected()) {
      return;
    }

    if (this.webSocketInitPromise) {
      return this.webSocketInitPromise;
    }

    this.webSocketInitPromise = this._initializeWebSocket();

    try {
      await this.webSocketInitPromise;
    } finally {
      this.webSocketInitPromise = null;
    }
  }

  private async _initializeWebSocket(): Promise<void> {
    try {
      const user = this.authService.getCurrentUser();
      if (!user) {
        throw new AuthenticationError('Molimo vas da se prijavite.');
      }

      await this.webSocketService.connect();

      // Subscribe to user-specific error queue (server sends errors here)
      this.webSocketService.subscribe('/user/queue/errors', (message) => {
        try {
          const error = JSON.parse(message.body);
          console.warn('[WS] Server error:', error);
          if (error.error) {
            this.toast.error(error.error);
          }
        } catch (e) {
          // Silent error handling
        }
      });

      // Subscribe to message status updates via user queue
      this.webSocketService.subscribe('/user/queue/message-status', (message) => {
        try {
          const statusUpdate: MessageStatusUpdate = JSON.parse(message.body);
          this.handleMessageStatusUpdate(statusUpdate);
        } catch (error) {
          // Silent error handling
        }
      });

      this.isWebSocketInitialized = true;
    } catch (error) {
      this.isWebSocketInitialized = false;
      throw error;
    }
  }

  /**
   * Subscribe to conversation-specific WebSocket topics.
   * Backend broadcasts to /topic/conversation/{bookingId}, /topic/conversation/{bookingId}/typing,
   * and /topic/conversation/{bookingId}/read.
   */
  subscribeToConversation(bookingId: string): void {
    // Unsubscribe from previous conversation
    if (this.activeSubscriptionBookingId && this.activeSubscriptionBookingId !== bookingId) {
      this.unsubscribeFromConversation(this.activeSubscriptionBookingId);
    }

    if (this.activeSubscriptionBookingId === bookingId) {
      return; // Already subscribed
    }

    if (!this.webSocketService.isConnected()) {
      return;
    }

    this.activeSubscriptionBookingId = bookingId;

    // Subscribe to messages for this conversation
    this.webSocketService.subscribe(`/topic/conversation/${bookingId}`, (message) => {
      try {
        const msg: MessageDTO = JSON.parse(message.body);
        this.handleIncomingMessage(msg);
      } catch (error) {
        // Silent error handling
      }
    });

    // Subscribe to typing indicators for this conversation
    this.webSocketService.subscribe(`/topic/conversation/${bookingId}/typing`, (message) => {
      try {
        const rawTyping = JSON.parse(message.body);
        // Map backend DTO (userId/typing/displayName) to frontend DTO (userId/isTyping/userName)
        const typing: TypingIndicatorDTO = {
          conversationId: 0, // Will be set from active conversation context
          userId: rawTyping.userId?.toString() || '',
          userName: rawTyping.displayName || rawTyping.userName || '',
          isTyping: rawTyping.typing ?? rawTyping.isTyping ?? false,
          timestamp: new Date().toISOString(),
        };
        // Set conversationId from the active conversation
        const activeConv = this.activeConversationSubject.value;
        if (activeConv) {
          typing.conversationId = activeConv.id;
        }
        this.handleTypingIndicator(typing);
      } catch (error) {
        // Silent error handling
      }
    });

    // Subscribe to read receipts for this conversation
    this.webSocketService.subscribe(`/topic/conversation/${bookingId}/read`, (message) => {
      try {
        const readReceipt = JSON.parse(message.body);
        // Update messages with read status
        const activeConv = this.activeConversationSubject.value;
        if (activeConv) {
          const statusUpdate: MessageStatusUpdate = {
            messageId: 0, // Read receipt applies to all messages
            conversationId: activeConv.id,
            readAt: new Date(readReceipt.timestamp).toISOString(),
            readBy: [readReceipt.userId?.toString()],
          };
          this.handleMessageStatusUpdate(statusUpdate);
        }
      } catch (error) {
        // Silent error handling
      }
    });
  }

  /**
   * Unsubscribe from conversation-specific WebSocket topics.
   */
  unsubscribeFromConversation(bookingId: string): void {
    this.webSocketService.unsubscribe(`/topic/conversation/${bookingId}`);
    this.webSocketService.unsubscribe(`/topic/conversation/${bookingId}/typing`);
    this.webSocketService.unsubscribe(`/topic/conversation/${bookingId}/read`);
    if (this.activeSubscriptionBookingId === bookingId) {
      this.activeSubscriptionBookingId = null;
    }
  }

  disconnectWebSocket(): void {
    this.webSocketService.disconnect(true);
    this.isWebSocketInitialized = false;
    this.webSocketInitPromise = null;
  }

  // ===========================================================================
  // Typing Indicators
  // ===========================================================================

  /**
   * Send typing indicator via WebSocket
   */
  sendTypingIndicator(bookingId: string, isTyping: boolean): void {
    if (!this.webSocketService.isConnected()) {
      return;
    }

    const user = this.authService.getCurrentUser();
    if (!user) {
      return;
    }

    // Send typing DTO matching backend TypingIndicatorDTO (userId/typing/displayName)
    this.webSocketService.send(`/app/chat/${bookingId}/typing`, {
      typing: isTyping,
      userId: user.id,
      displayName: `${user.firstName || ''} ${user.lastName || ''}`.trim() || 'User',
    });
  }

  private handleTypingIndicator(typing: TypingIndicatorDTO): void {
    // Suppress self-typing events (backend echoes to all subscribers including sender)
    const currentUser = this.authService.getCurrentUser();
    if (currentUser && typing.userId === currentUser.id?.toString()) {
      return;
    }

    // Clear existing timeout for this conversation
    const existingTimeout = this.typingTimeouts.get(typing.conversationId);
    if (existingTimeout) {
      clearTimeout(existingTimeout);
      this.typingTimeouts.delete(typing.conversationId);
    }

    // Emit typing indicator
    this.typingIndicatorSubject.next(typing);

    // Auto-clear typing after 5 seconds if isTyping is true
    if (typing.isTyping) {
      const timeout = setTimeout(() => {
        this.typingIndicatorSubject.next({
          ...typing,
          isTyping: false,
        });
        this.typingTimeouts.delete(typing.conversationId);
      }, 5000);
      this.typingTimeouts.set(typing.conversationId, timeout);
    }
  }

  // ===========================================================================
  // Optimistic Updates
  // ===========================================================================

  /**
   * Create an optimistic message for immediate UI feedback
   */
  createOptimisticMessage(content: string, conversationId: number): MessageDTO {
    const user = this.authService.getCurrentUser();
    const optimisticId = `opt_${Date.now()}_${Math.random().toString(36).substr(2, 9)}`;

    return {
      id: -Date.now(), // Negative ID to distinguish from server IDs
      conversationId,
      senderId: user?.id || '',
      senderName: `${user?.firstName || ''} ${user?.lastName || ''}`.trim() || 'You',
      content,
      timestamp: new Date().toISOString(),
      readBy: [],
      isRead: false,
      optimisticId,
      status: 'sending',
    };
  }

  /**
   * Replace optimistic message with server response
   */
  replaceOptimisticMessage(optimisticId: string, serverMessage: MessageDTO): void {
    const activeConv = this.activeConversationSubject.value;
    if (!activeConv) return;

    const messages = activeConv.messages || [];
    const updatedMessages = messages.map((msg) =>
      msg.optimisticId === optimisticId ? { ...serverMessage, status: 'sent' as const } : msg,
    );

    this.activeConversationSubject.next({
      ...activeConv,
      messages: updatedMessages,
    });
  }

  /**
   * Mark optimistic message as failed
   */
  markOptimisticMessageFailed(optimisticId: string): void {
    const activeConv = this.activeConversationSubject.value;
    if (!activeConv) return;

    const messages = activeConv.messages || [];
    const updatedMessages = messages.map((msg) =>
      msg.optimisticId === optimisticId ? { ...msg, status: 'failed' as const } : msg,
    );

    this.activeConversationSubject.next({
      ...activeConv,
      messages: updatedMessages,
    });
  }

  // ===========================================================================
  // Offline Queue (localStorage MVP)
  // ===========================================================================

  private loadOfflineQueue(): void {
    try {
      const stored = localStorage.getItem(OFFLINE_QUEUE_KEY);
      if (stored) {
        const queue: OfflineQueueItem[] = JSON.parse(stored);
        this.offlineQueueSubject.next(queue);
      }
    } catch (error) {
      console.error('Failed to load offline queue:', error);
      localStorage.removeItem(OFFLINE_QUEUE_KEY);
    }
  }

  private saveOfflineQueue(): void {
    try {
      const queue = this.offlineQueueSubject.value;
      localStorage.setItem(OFFLINE_QUEUE_KEY, JSON.stringify(queue));
    } catch (error) {
      console.error('Failed to save offline queue:', error);
    }
  }

  /**
   * Add message to offline queue
   */
  queueOfflineMessage(bookingId: string, content: string): OfflineQueueItem {
    const item: OfflineQueueItem = {
      id: `offline_${Date.now()}_${Math.random().toString(36).substr(2, 9)}`,
      bookingId,
      content,
      timestamp: new Date().toISOString(),
      retryCount: 0,
      status: 'queued',
    };

    const queue = [...this.offlineQueueSubject.value, item];
    this.offlineQueueSubject.next(queue);
    this.saveOfflineQueue();

    return item;
  }

  /**
   * Flush offline queue - send all queued messages
   */
  async flushOfflineQueue(): Promise<void> {
    const queue = this.offlineQueueSubject.value;
    if (queue.length === 0) return;

    for (const item of queue) {
      if (item.status === 'sending') continue;
      if (item.retryCount >= MAX_RETRY_COUNT) {
        this.removeFromOfflineQueue(item.id);
        continue;
      }

      try {
        // Mark as sending
        this.updateOfflineQueueItem(item.id, { status: 'sending' });

        // Attempt to send
        await firstValueFrom(this.sendMessage(item.bookingId, { content: item.content }));

        // Success - remove from queue
        this.removeFromOfflineQueue(item.id);
      } catch (error) {
        // Failed - increment retry count
        this.updateOfflineQueueItem(item.id, {
          status: 'failed',
          retryCount: item.retryCount + 1,
        });
      }
    }
  }

  private updateOfflineQueueItem(id: string, updates: Partial<OfflineQueueItem>): void {
    const queue = this.offlineQueueSubject.value.map((item) =>
      item.id === id ? { ...item, ...updates } : item,
    );
    this.offlineQueueSubject.next(queue);
    this.saveOfflineQueue();
  }

  private removeFromOfflineQueue(id: string): void {
    const queue = this.offlineQueueSubject.value.filter((item) => item.id !== id);
    this.offlineQueueSubject.next(queue);
    this.saveOfflineQueue();
  }

  /**
   * Get offline queue count
   */
  getOfflineQueueCount(): number {
    return this.offlineQueueSubject.value.length;
  }

  // ===========================================================================
  // Conversation CRUD
  // ===========================================================================

  createConversation(request: CreateConversationRequest): Observable<ConversationDTO> {
    return this.http.post<ConversationDTO>(`${this.chatApiUrl}/conversations`, request).pipe(
      tap((conversation) => {
        const conversations = this.conversationsSubject.value;
        this.conversationsSubject.next([conversation, ...conversations]);
      }),
      catchError(this.handleError.bind(this)),
    );
  }

  /**
   * Get conversation with message history.
   *
   * Ensures all messages have correct isOwnMessage flag by enriching them
   * based on the current logged-in user. The backend returns messages
   * with isOwnMessage calculated for the requester, which is correct for
   * initial load but should be re-verified to be safe.
   *
   * @param bookingId The booking ID for the conversation
   * @param page Page number (0-indexed)
   * @param size Messages per page
   * @returns Observable<ConversationDTO> with enriched messages
   */
  getConversation(bookingId: string, page = 0, size = 50): Observable<ConversationDTO> {
    const params = new HttpParams().set('page', page.toString()).set('size', size.toString());
    return this.http
      .get<ConversationDTO>(`${this.chatApiUrl}/conversations/${bookingId}`, { params })
      .pipe(
        map((conversation) => {
          // Enrich all messages with correct isOwnMessage flag
          const currentUser = this.authService.getCurrentUser();

          if (conversation.messages) {
            conversation.messages = conversation.messages.map((msg) =>
              this.enrichMessageWithOwnershipFlag(msg, currentUser?.id),
            );
          }

          return conversation;
        }),
        tap((conversation) => {
          this.activeConversationSubject.next(conversation);
        }),
        shareReplay(1),
        catchError(this.handleError.bind(this)),
      );
  }

  /**
   * Send a message via REST API with optimistic update
   */
  sendMessage(bookingId: string, request: SendMessageRequest): Observable<MessageDTO> {
    return this.http
      .post<MessageDTO>(`${this.chatApiUrl}/conversations/${bookingId}/messages`, request)
      .pipe(
        tap((message) => {
          this.addMessageToActiveConversation(message);
        }),
        catchError((error) => {
          this.toast.error('Poruka nije poslata. Pokušajte ponovo.');
          return this.handleError(error);
        }),
      );
  }

  /**
   * Upload a file attachment for a conversation.
   * Returns the URL of the uploaded file to be used as mediaUrl in SendMessageRequest.
   *
   * @param bookingId The booking ID for the conversation
   * @param file The file to upload
   * @returns Observable with { url: string, filename: string }
   */
  uploadAttachment(bookingId: string, file: File): Observable<{ url: string; filename: string }> {
    const formData = new FormData();
    formData.append('file', file, file.name);
    return this.http
      .post<{
        url: string;
        filename: string;
      }>(`${this.chatApiUrl}/conversations/${bookingId}/attachments`, formData)
      .pipe(
        catchError((error) => {
          const msg = this.getAttachmentUploadErrorMessage(error);
          this.toast.error(msg);
          return throwError(() => error);
        }),
      );
  }

  /**
   * Get admin transcript for dispute resolution.
   * Requires ADMIN role. Returns full conversation with all messages.
   *
   * @param bookingId The booking ID for the conversation
   * @returns Observable<ConversationDTO> with all messages
   */
  getAdminTranscript(bookingId: string): Observable<ConversationDTO> {
    return this.http
      .get<ConversationDTO>(`${this.chatApiUrl}/admin/conversations/${bookingId}/transcript`)
      .pipe(catchError(this.handleError.bind(this)));
  }

  /**
   * Send message with optimistic update and offline fallback.
   * Optionally includes a mediaUrl for attachment messages.
   */
  sendMessageOptimistic(
    bookingId: string,
    content: string,
    conversationId: number,
    mediaUrl?: string,
  ): Observable<MessageDTO> {
    // Create optimistic message
    const optimisticMsg = this.createOptimisticMessage(content, conversationId);
    if (mediaUrl) {
      optimisticMsg.mediaUrl = mediaUrl;
    }

    // Add to UI immediately
    const activeConv = this.activeConversationSubject.value;
    if (activeConv && activeConv.id === conversationId) {
      const messages = activeConv.messages || [];
      this.activeConversationSubject.next({
        ...activeConv,
        messages: [...messages, optimisticMsg],
        lastMessageAt: optimisticMsg.timestamp,
      });
    }

    // Check if online (only check navigator.onLine, not WS health - REST works independently)
    if (!navigator.onLine) {
      // Truly offline - queue message
      this.queueOfflineMessage(bookingId, content);
      this.markOptimisticMessageFailed(optimisticMsg.optimisticId!);
      return throwError(() => new Error('Offline - message queued'));
    }

    // Send message via REST (independent of WebSocket health)
    const request: SendMessageRequest = { content };
    if (mediaUrl) {
      request.mediaUrl = mediaUrl;
    }
    return this.sendMessage(bookingId, request).pipe(
      tap((serverMessage) => {
        this.replaceOptimisticMessage(optimisticMsg.optimisticId!, serverMessage);
      }),
      catchError((error) => {
        this.markOptimisticMessageFailed(optimisticMsg.optimisticId!);

        // Only queue for network errors, NOT for 4xx client errors (moderation, validation)
        const status = error?.status || error?.error?.status;
        const isClientError = status >= 400 && status < 500;
        if (!isClientError) {
          this.queueOfflineMessage(bookingId, content);
        }

        return throwError(() => error);
      }),
    );
  }

  sendMessageViaWebSocket(bookingId: string, content: string): void {
    if (!this.webSocketService.isConnected()) {
      this.sendMessage(bookingId, { content }).subscribe();
      return;
    }

    // Backend expects /app/chat/{bookingId}/send with SendMessageRequest payload
    this.webSocketService.send(`/app/chat/${bookingId}/send`, {
      content,
    });
  }

  markMessagesAsRead(bookingId: string): Observable<void> {
    return this.http.put<void>(`${this.chatApiUrl}/conversations/${bookingId}/read`, {}).pipe(
      tap(() => {
        const conversations = this.conversationsSubject.value;
        const updated = conversations.map((conv) =>
          conv.bookingId === bookingId ? { ...conv, unreadCount: 0 } : conv,
        );
        this.conversationsSubject.next(updated);

        const activeConv = this.activeConversationSubject.value;
        if (activeConv && activeConv.bookingId === bookingId) {
          this.activeConversationSubject.next({ ...activeConv, unreadCount: 0 });
        }
      }),
      catchError(this.handleError.bind(this)),
    );
  }

  getUserConversations(): Observable<ConversationDTO[]> {
    const currentUser = this.authService.getCurrentUser();
    // Ensure userId is a string for comparison (API returns string IDs)
    const userId = currentUser?.id?.toString();

    return this.http.get<ConversationDTO[]>(`${this.chatApiUrl}/conversations`).pipe(
      map((conversations) => {
        // Filter out malformed conversations with null IDs
        const validConversations = conversations.filter((conv) => {
          if (!conv.ownerId || !conv.renterId) {
            console.warn(`[DATA] Skipping conversation ${conv.id} with null ownerId or renterId`);
            return false;
          }
          return true;
        });

        // RBAC: Verify ownership before accepting conversations
        const validated = validConversations.filter((conv) => {
          // Convert all IDs to strings for reliable comparison
          const convOwnerId = conv.ownerId?.toString();
          const convRenterId = conv.renterId?.toString();

          const isOwner = convOwnerId === userId;
          const isRenter = convRenterId === userId;

          if (!isOwner && !isRenter) {
            console.warn(
              `[SECURITY] User ${userId} received unauthorized conversation ${conv.id} (owner: ${convOwnerId}, renter: ${convRenterId})`,
            );
            return false; // Filter out unauthorized conversations
          }
          return true;
        });

        // Remove any duplicates (defensive)
        const uniqueIds = new Set<number>();
        const deduplicated = validated.filter((conv) => {
          if (uniqueIds.has(conv.id)) {
            console.warn(`[SECURITY] Duplicate conversation ${conv.id} received`);
            return false;
          }
          uniqueIds.add(conv.id);
          return true;
        });

        // Sort by recency
        return deduplicated.sort((a, b) => {
          const dateA = a.lastMessageAt ? new Date(a.lastMessageAt).getTime() : 0;
          const dateB = b.lastMessageAt ? new Date(b.lastMessageAt).getTime() : 0;
          return dateB - dateA;
        });
      }),
      tap((conversations) => {
        this.conversationsSubject.next(conversations);
      }),
      retry({ count: 2, delay: 1000 }),
      catchError((error) => {
        this.toast.error('Neuspešno učitavanje konverzacija. Pokušajte ponovo.');
        return this.handleError(error);
      }),
    );
  }

  /**
   * Admin-only: Fetch ALL conversations (no participant filter).
   * Used by admin oversight / dispute resolution UI.
   */
  getAdminConversations(): Observable<ConversationDTO[]> {
    return this.http.get<ConversationDTO[]>(`${this.chatApiUrl}/admin/conversations`).pipe(
      map((conversations) => {
        // Filter out malformed entries, but skip RBAC owner/renter check (admin sees all)
        const valid = conversations.filter((conv) => conv.id != null);

        // Remove duplicates
        const uniqueIds = new Set<number>();
        const deduplicated = valid.filter((conv) => {
          if (uniqueIds.has(conv.id)) return false;
          uniqueIds.add(conv.id);
          return true;
        });

        // Sort by recency
        return deduplicated.sort((a, b) => {
          const dateA = a.lastMessageAt ? new Date(a.lastMessageAt).getTime() : 0;
          const dateB = b.lastMessageAt ? new Date(b.lastMessageAt).getTime() : 0;
          return dateB - dateA;
        });
      }),
      tap((conversations) => this.conversationsSubject.next(conversations)),
      retry({ count: 2, delay: 1000 }),
      catchError((error) => {
        this.toast.error('Neuspešno učitavanje konverzacija (admin). Pokušajte ponovo.');
        return this.handleError(error);
      }),
    );
  }

  async getOrCreateConversation(
    bookingId: string,
    renterId: string,
    ownerId: string,
    initialMessage?: string,
  ): Promise<ConversationDTO> {
    try {
      return await firstValueFrom(this.getConversation(bookingId));
    } catch (error: any) {
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

  setActiveConversation(conversation: ConversationDTO | null): void {
    this.activeConversationSubject.next(conversation);
  }

  getCurrentUserId(): string {
    return this.authService.getCurrentUser()?.id || '';
  }

  isWebSocketConnected(): boolean {
    return this.webSocketService.isConnected();
  }

  // ===========================================================================
  // Message Handlers
  // ===========================================================================

  /**
   * Handle incoming message from WebSocket.
   *
   * CRITICAL: This method ensures isOwnMessage flag is correctly calculated
   * for the current user. The backend broadcasts with isOwnMessage=false,
   * so we must recalculate it based on comparing senderId with currentUserId.
   *
   * This fixes the inverted message bug where recipients saw messages
   * they didn't send on the left side (thinking they sent them).
   *
   * @param message The raw MessageDTO from WebSocket broadcast
   */
  private handleIncomingMessage(message: MessageDTO): void {
    // CRITICAL: Recalculate isOwnMessage because WebSocket broadcast doesn't know
    // who is receiving it. Backend sends isOwnMessage=false, frontend calculates the real value.
    const currentUser = this.authService.getCurrentUser();
    const correctedMessage = this.enrichMessageWithOwnershipFlag(message, currentUser?.id);

    this.messageSubject.next(correctedMessage);
    this.updateConversationWithNewMessage(correctedMessage);

    // Check if this is our own message that was sent optimistically
    if (correctedMessage.isOwnMessage) {
      // For own messages, try to find and replace the optimistic version
      const activeConv = this.activeConversationSubject.value;
      if (activeConv && activeConv.id === correctedMessage.conversationId) {
        const messages = activeConv.messages || [];
        // Find optimistic message by content match (since optimistic has negative ID)
        const optimisticIdx = messages.findIndex(
          (m) => m.id < 0 && m.content === correctedMessage.content && m.optimisticId,
        );

        if (optimisticIdx >= 0) {
          // Replace the optimistic message with the server message
          const updatedMessages = [...messages];
          updatedMessages[optimisticIdx] = { ...correctedMessage, status: 'sent' as const };
          this.activeConversationSubject.next({
            ...activeConv,
            messages: updatedMessages,
            lastMessageAt: correctedMessage.timestamp,
          });
          return; // Don't add duplicate
        }
      }
    }

    // For other people's messages or if no optimistic found, add normally
    this.addMessageToActiveConversation(correctedMessage);
  }

  /**
   * Ensure isOwnMessage flag is correctly calculated for the current user.
   *
   * The backend broadcast doesn't know who is receiving the message,
   * so it sends isOwnMessage=false. We recalculate it locally by comparing
   * senderId with the current logged-in user's ID.
   *
   * Type-safe comparison:
   * - Converts both IDs to strings (handles number/string mismatch)
   * - Handles undefined currentUserId gracefully
   * - Works with initial load and WebSocket messages
   *
   * @param message The message from WebSocket or API
   * @param currentUserId The ID of the currently logged-in user
   * @returns MessageDTO with correct isOwnMessage flag for this user
   */
  private enrichMessageWithOwnershipFlag(
    message: MessageDTO,
    currentUserId: string | undefined,
  ): MessageDTO {
    if (!currentUserId) {
      // If user not authenticated, default to false (safe default)
      return { ...message, isOwnMessage: false };
    }

    // Type-safe comparison
    // - message.senderId may be number or string from API
    // - currentUserId is string from AuthService
    // - Convert both to string for reliable comparison
    const senderId = message.senderId?.toString();
    const currentUserIdStr = currentUserId.toString();
    const isOwn = senderId === currentUserIdStr;

    return {
      ...message,
      isOwnMessage: isOwn,
    };
  }

  private handleMessageStatusUpdate(statusUpdate: MessageStatusUpdate): void {
    this.messageStatusUpdateSubject.next(statusUpdate);

    const activeConv = this.activeConversationSubject.value;
    if (activeConv && activeConv.id === statusUpdate.conversationId) {
      const messages = activeConv.messages || [];
      const updatedMessages = messages.map((msg) => {
        if (msg.id === statusUpdate.messageId) {
          return {
            ...msg,
            sentAt: statusUpdate.sentAt || msg.sentAt,
            deliveredAt: statusUpdate.deliveredAt || msg.deliveredAt,
            readAt: statusUpdate.readAt || msg.readAt,
            readBy: statusUpdate.readBy || msg.readBy,
          };
        }
        return msg;
      });

      this.activeConversationSubject.next({
        ...activeConv,
        messages: updatedMessages,
      });
    }
  }

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

    updated.sort((a, b) => {
      const dateA = a.lastMessageAt ? new Date(a.lastMessageAt).getTime() : 0;
      const dateB = b.lastMessageAt ? new Date(b.lastMessageAt).getTime() : 0;
      return dateB - dateA;
    });

    this.conversationsSubject.next(updated);

    if (!conversationFound) {
      this.getUserConversations().subscribe();
    }
  }

  private addMessageToActiveConversation(message: MessageDTO): void {
    const activeConv = this.activeConversationSubject.value;
    if (activeConv && activeConv.id === message.conversationId) {
      const messages = activeConv.messages || [];

      // Check for duplicate by ID or by optimisticId match
      // This prevents duplicates when:
      // 1. WebSocket delivers a message that was already added optimistically
      // 2. Server response arrives both via HTTP and WebSocket
      const isDuplicate = messages.some((m) => {
        // Direct ID match
        if (m.id === message.id && message.id > 0) return true;
        // Check if this is the server version of an optimistic message
        if (m.optimisticId && message.content === m.content && m.id < 0) {
          // Replace the optimistic message instead of adding duplicate
          return true;
        }
        return false;
      });

      if (!isDuplicate) {
        this.activeConversationSubject.next({
          ...activeConv,
          messages: [...messages, message],
          lastMessageAt: message.timestamp,
        });
      }
    }
  }

  private handleError(error: HttpErrorResponse): Observable<never> {
    return throwError(() => error);
  }

  private getAttachmentUploadErrorMessage(error: HttpErrorResponse): string {
    const payload = error?.error;
    const rawMessage =
      (typeof payload === 'string' ? payload : payload?.userMessage || payload?.error || payload?.message || '')
        .toString()
        .trim();
    const normalized = rawMessage.toLowerCase();

    if (
      error.status === 413 ||
      normalized.includes('maxuploadsize') ||
      normalized.includes('maximum upload size') ||
      normalized.includes('too large') ||
      normalized.includes('file too large') ||
      normalized.includes('prevelik')
    ) {
      return 'Fajl je prevelik. Maksimalna veličina je 10MB.';
    }

    if (
      error.status === 415 ||
      normalized.includes('invalid file type') ||
      normalized.includes('invalid file extension') ||
      normalized.includes('content does not match declared type')
    ) {
      return 'Dozvoljeni formati su JPG, PNG, GIF, WEBP ili PDF.';
    }

    if (error.status === 0) {
      return 'Nema konekcije. Proverite internet i pokušajte ponovo.';
    }

    if (rawMessage) {
      return rawMessage;
    }

    if (error.status === 400) {
      return 'Neispravan fajl. Proverite format i veličinu.';
    }

    return 'Neuspešno otpremanje fajla. Pokušajte ponovo.';
  }

  // Legacy polling methods (deprecated)
  startPolling(bookingId: string, intervalMs = 3000): void {}
  stopPolling(): void {}
}