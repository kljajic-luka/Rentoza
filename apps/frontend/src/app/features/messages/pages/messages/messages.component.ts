import {
  Component,
  OnInit,
  OnDestroy,
  inject,
  signal,
  computed,
  effect,
  ViewChild,
  ChangeDetectorRef,
} from '@angular/core';
import { CommonModule } from '@angular/common';
import { Router, ActivatedRoute } from '@angular/router';
import { BreakpointObserver } from '@angular/cdk/layout';
import { MatSidenavModule } from '@angular/material/sidenav';
import { MatIconModule } from '@angular/material/icon';
import { MatButtonModule } from '@angular/material/button';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatTooltipModule } from '@angular/material/tooltip';
import { Subject, takeUntil, filter, skip } from 'rxjs';

import { ChatService, AuthenticationError } from '@core/services/chat.service';
import { AuthService } from '@core/auth/auth.service';
import { ThemeService } from '@core/services/theme.service';
import { ToastService } from '@core/services/toast.service';
import { ConversationDTO, MessageDTO, MessageStatusUpdate } from '@core/models/chat.model';

// Import new components
import { ConversationListComponent } from '../../components/conversation-list/conversation-list.component';
import { ChatHeaderComponent } from '../../components/chat-header/chat-header.component';
import { MessageViewComponent } from '../../components/message-view/message-view.component';
import { MessageInputComponent } from '../../components/message-input/message-input.component';

/**
 * MessagesComponent - Page Orchestrator
 *
 * Enterprise-grade chat interface following Turo/Airbnb design patterns.
 * Orchestrates child components and manages page-level state.
 *
 * Architecture:
 * - Smart component: manages state, API calls, WebSocket subscriptions
 * - Delegates presentation to child components
 * - Uses Angular signals for reactive state management
 */
@Component({
  selector: 'app-messages',
  standalone: true,
  imports: [
    CommonModule,
    MatSidenavModule,
    MatIconModule,
    MatButtonModule,
    MatProgressSpinnerModule,
    MatTooltipModule,
    // Child components
    ConversationListComponent,
    ChatHeaderComponent,
    MessageViewComponent,
    MessageInputComponent,
  ],
  templateUrl: './messages.component.html',
  styleUrls: ['./messages.component.scss'],
})
export class MessagesComponent implements OnInit, OnDestroy {
  // Injected services
  private readonly chatService = inject(ChatService);
  private readonly authService = inject(AuthService);
  readonly themeService = inject(ThemeService);
  private readonly toast = inject(ToastService);
  private readonly router = inject(Router);
  private readonly route = inject(ActivatedRoute);
  private readonly cdr = inject(ChangeDetectorRef);
  private readonly breakpointObserver = inject(BreakpointObserver);
  private readonly destroy$ = new Subject<void>();

  // ============================================================================
  // STATE - Using Angular Signals for reactive updates
  // ============================================================================

  // Data signals
  conversations = signal<ConversationDTO[]>([]);
  selectedConversationId = signal<number | null>(null);
  messages = signal<MessageDTO[]>([]);
  currentUserId = signal('');
  isAdminMode = signal(false);

  // UI state signals
  isLoading = signal(true);
  isLoadingMessages = signal(false);
  isSendingMessage = signal(false);
  wsConnected = signal(false);
  isMobileView = signal(false);
  showSidebar = signal(true);

  // Typing indicator state
  isTyping = signal(false);
  typingUserName = signal('');

  // ============================================================================
  // DERIVED SIGNALS (Computed)
  // ============================================================================

  selectedConversation = computed(() => {
    const id = this.selectedConversationId();
    return this.conversations().find((c) => c.id === id) || null;
  });

  hasConversations = computed(() => this.conversations().length > 0);

  isMessagingAllowed = computed(() => this.selectedConversation()?.messagingAllowed ?? false);

  totalUnreadCount = computed(() => {
    return this.conversations().reduce((sum, c) => sum + (c.unreadCount || 0), 0);
  });

  // ============================================================================
  // EFFECTS (Reactive side effects)
  // ============================================================================

  constructor() {
    // Effect: When selected conversation changes, load messages
    effect(() => {
      const conv = this.selectedConversation();
      if (conv) {
        this.loadMessagesForConversation(conv);
      }
    });

    // Effect: Hide sidebar on mobile when conversation is selected
    effect(() => {
      const isMobile = this.isMobileView();
      const hasSelection = this.selectedConversationId() !== null;
      if (isMobile && hasSelection) {
        this.showSidebar.set(false);
      }
    });
  }

  // ============================================================================
  // LIFECYCLE HOOKS
  // ============================================================================

  async ngOnInit(): Promise<void> {
    // Get current user and detect admin mode
    this.authService.currentUser$.pipe(takeUntil(this.destroy$)).subscribe((user) => {
      if (user?.id) {
        this.currentUserId.set(user.id);
      }
    });

    // Detect admin mode (admin viewing messages for dispute resolution)
    this.isAdminMode.set(this.authService.hasAnyRole('ADMIN' as any));

    // Monitor breakpoint for responsive layout
    this.breakpointObserver
      .observe(['(max-width: 768px)'])
      .pipe(takeUntil(this.destroy$))
      .subscribe((result) => {
        this.isMobileView.set(result.matches);
        if (!result.matches) {
          this.showSidebar.set(true);
        }
      });

    // Initialize WebSocket
    await this.initializeWebSocket();

    // Subscribe to WebSocket events
    this.subscribeToWebSocketEvents();

    // Load conversations
    await this.loadConversations();

    // Handle query params (for deep linking from booking)
    this.route.queryParams
      .pipe(
        filter((params) => params['bookingId']),
        takeUntil(this.destroy$),
      )
      .subscribe((params) => {
        this.selectConversationByBookingId(params['bookingId']);
      });
  }

  ngOnDestroy(): void {
    this.destroy$.next();
    this.destroy$.complete();
  }

  // ============================================================================
  // INITIALIZATION
  // ============================================================================

  private async initializeWebSocket(): Promise<void> {
    try {
      await this.chatService.initializeWebSocket();
      this.wsConnected.set(true);
    } catch (error) {
      // Handle authentication errors - redirect to login
      if (error instanceof AuthenticationError) {
        console.error('Authentication required, redirecting to login');
        this.router.navigate(['/auth/login']);
        return;
      }

      // Handle other errors - show message
      console.error('WebSocket initialization failed:', error);
      this.wsConnected.set(false);
    }
  }

  private subscribeToWebSocketEvents(): void {
    // New messages
    this.chatService.messages$
      .pipe(takeUntil(this.destroy$))
      .subscribe((message) => this.handleNewMessage(message));

    // Message status updates
    this.chatService.messageStatusUpdates$
      .pipe(takeUntil(this.destroy$))
      .subscribe((update) => this.handleMessageStatusUpdate(update));

    // Conversation list updates from WebSocket (skip initial BehaviorSubject emission)
    this.chatService.conversations$
      .pipe(
        skip(1), // Skip the initial BehaviorSubject value - API call provides initial data
        takeUntil(this.destroy$),
      )
      .subscribe((convs) => {
        this.conversations.set(convs);
      });

    // Active conversation updates
    this.chatService.activeConversation$.pipe(takeUntil(this.destroy$)).subscribe((conv) => {
      if (conv) {
        this.selectedConversationId.set(conv.id);
        this.messages.set(conv.messages || []);
      }
    });

    // Subscribe to typing indicators
    this.chatService.typingIndicators$.pipe(takeUntil(this.destroy$)).subscribe((typing) => {
      if (typing.conversationId === this.selectedConversationId()) {
        this.isTyping.set(typing.isTyping);
        this.typingUserName.set(typing.userName);
      }
    });
  }

  // ============================================================================
  // DATA LOADING
  // ============================================================================

  private async loadConversations(): Promise<void> {
    this.isLoading.set(true);

    try {
      // Admin mode: load ALL conversations (no participant filter)
      // Regular mode: load only conversations where user is renter/owner
      const conversations$ = this.isAdminMode()
        ? this.chatService.getAdminConversations()
        : this.chatService.getUserConversations();

      return await new Promise<void>((resolve, reject) => {
        conversations$.subscribe({
          next: (conversations) => {
            if (!conversations || conversations.length === 0) {
              this.conversations.set([]);
              this.isLoading.set(false);
              resolve();
              return;
            }

            // Service already sorts, but re-sort to ensure consistency
            const sorted = [...conversations].sort((a, b) => {
              const timeA = a.lastMessageAt ? new Date(a.lastMessageAt).getTime() : 0;
              const timeB = b.lastMessageAt ? new Date(b.lastMessageAt).getTime() : 0;
              return timeB - timeA;
            });

            this.conversations.set(sorted);
            this.isLoading.set(false);

            // Auto-select first conversation if none selected
            if (!this.selectedConversationId() && sorted.length > 0) {
              this.selectConversation(sorted[0]);
            }

            resolve();
          },
          error: (error) => {
            console.error('Failed to load conversations:', error);
            this.isLoading.set(false);
            reject(error);
          },
        });
      });
    } catch (error) {
      console.error('Failed to load conversations:', error);
      this.isLoading.set(false);
    }
  }

  private loadMessagesForConversation(conv: ConversationDTO): void {
    this.isLoadingMessages.set(true);

    // Subscribe to conversation-specific WebSocket topics
    this.chatService.subscribeToConversation(conv.bookingId);

    // Admin mode: use admin transcript endpoint (read-only, no participant check)
    // Regular user mode: use participant-scoped endpoint
    const messageSource$ = this.isAdminMode()
      ? this.chatService.getAdminTranscript(conv.bookingId)
      : this.chatService.getConversation(conv.bookingId);

    messageSource$.subscribe({
      next: (updatedConv) => {
        this.messages.set(updatedConv.messages || []);
        this.isLoadingMessages.set(false);
        if (!this.isAdminMode()) {
          this.markAsRead();
        }
      },
      error: (err) => {
        console.error('Failed to load messages:', err);
        this.isLoadingMessages.set(false);
      },
    });
  }

  // ============================================================================
  // CONVERSATION SELECTION
  // ============================================================================

  selectConversation(conv: ConversationDTO): void {
    if (this.selectedConversationId() === conv.id) {
      return; // Already selected
    }

    // Clear any pending attachment from previous conversation (privacy: prevent cross-conversation leak)
    this.pendingAttachmentUrl = null;

    this.selectedConversationId.set(conv.id);
    this.chatService.setActiveConversation(conv);

    // On mobile, hide sidebar
    if (this.isMobileView()) {
      this.showSidebar.set(false);
    }
  }

  selectConversationByBookingId(bookingId: string): void {
    const conv = this.conversations().find((c) => c.bookingId === bookingId);
    if (conv) {
      this.selectConversation(conv);
    }
  }

  // ============================================================================
  // MESSAGE SENDING
  // ============================================================================

  /** URL of a pending uploaded attachment (set after successful upload) */
  private pendingAttachmentUrl: string | null = null;

  /**
   * Handle file attachment selection from MessageInputComponent.
   * Uploads the file immediately and stores the returned URL for
   * inclusion in the next message send.
   */
  /**
   * Handle attachment removal from MessageInputComponent.
   * Clears the pending uploaded URL so it is not attached to the next message.
   */
  onAttachmentRemoved(): void {
    this.pendingAttachmentUrl = null;
  }

  onAttachmentSelected(file: File): void {
    const conv = this.selectedConversation();
    if (!conv) return;

    this.isSendingMessage.set(true);

    this.chatService.uploadAttachment(conv.bookingId, file).subscribe({
      next: (result) => {
        this.pendingAttachmentUrl = result.url;
        this.isSendingMessage.set(false);
        this.toast.info('Fajl otpremljen. Pošaljite poruku da priložite.');
      },
      error: (err) => {
        console.error('Attachment upload failed:', err);
        this.isSendingMessage.set(false);
        this.pendingAttachmentUrl = null;
      },
    });
  }

  onSendMessage(content: string): void {
    const conv = this.selectedConversation();
    if (!content.trim() || !conv || !conv.messagingAllowed) {
      return;
    }

    this.isSendingMessage.set(true);

    const mediaUrl = this.pendingAttachmentUrl ?? undefined;
    this.pendingAttachmentUrl = null; // Clear after consuming

    // Use optimistic update with offline queue fallback
    this.chatService
      .sendMessageOptimistic(conv.bookingId, content.trim(), conv.id, mediaUrl)
      .subscribe({
        next: () => {
          this.isSendingMessage.set(false);
        },
        error: (err) => {
          console.error('Failed to send message:', err);
          this.isSendingMessage.set(false);

          // Handle content moderation errors with user-friendly messages
          if (err?.error?.errorCode === 'CONTENT_MODERATION') {
            const userMessage =
              err.error.userMessage || err.error.message || 'Poruka nije dozvoljena.';
            this.toast.warning(userMessage);
          } else if (err?.message === 'Offline - message queued') {
            this.toast.info('Niste povezani. Poruka će biti poslata kada budete online.');
          } else {
            this.toast.error('Poruka nije poslata. Pokušajte ponovo.');
          }
        },
      });
  }

  // ============================================================================
  // WEBSOCKET HANDLERS
  // ============================================================================

  private handleNewMessage(message: MessageDTO): void {
    const currentMessages = this.messages();
    const selectedId = this.selectedConversationId();

    // Only add if for current conversation and not duplicate
    if (selectedId && message.conversationId === selectedId) {
      if (!currentMessages.find((m) => m.id === message.id)) {
        this.messages.set([...currentMessages, message]);
        this.markAsRead();
      }
    }

    // Update conversation list (bump to top, update unread count)
    this.updateConversationWithMessage(message);
  }

  private handleMessageStatusUpdate(update: MessageStatusUpdate): void {
    const currentMessages = this.messages();
    const selectedId = this.selectedConversationId();

    if (selectedId && update.conversationId === selectedId) {
      const updatedMessages = currentMessages.map((msg) => {
        if (msg.id === update.messageId) {
          return {
            ...msg,
            sentAt: update.sentAt || msg.sentAt,
            deliveredAt: update.deliveredAt || msg.deliveredAt,
            readAt: update.readAt || msg.readAt,
            readBy: update.readBy || msg.readBy,
          };
        }
        return msg;
      });

      this.messages.set(updatedMessages);
      this.cdr.detectChanges();
    }
  }

  private updateConversationWithMessage(message: MessageDTO): void {
    const conversations = this.conversations();
    const updated = conversations.map((c) => {
      if (c.id === message.conversationId) {
        return {
          ...c,
          lastMessageAt: message.timestamp,
          unreadCount:
            message.senderId !== this.currentUserId() ? (c.unreadCount || 0) + 1 : c.unreadCount,
        };
      }
      return c;
    });

    // Re-sort by last message time
    updated.sort((a, b) => {
      const timeA = a.lastMessageAt ? new Date(a.lastMessageAt).getTime() : 0;
      const timeB = b.lastMessageAt ? new Date(b.lastMessageAt).getTime() : 0;
      return timeB - timeA;
    });

    this.conversations.set(updated);
  }

  // ============================================================================
  // READ RECEIPTS
  // ============================================================================

  private markAsRead(): void {
    const conv = this.selectedConversation();
    if (conv && conv.unreadCount > 0) {
      this.chatService.markMessagesAsRead(conv.bookingId).subscribe({
        next: () => {
          // Update local unread count
          this.conversations.update((convs) =>
            convs.map((c) => (c.id === conv.id ? { ...c, unreadCount: 0 } : c)),
          );
        },
        error: (err) => console.error('Failed to mark messages as read:', err),
      });
    }
  }

  // ============================================================================
  // NAVIGATION
  // ============================================================================

  onGoBack(): void {
    if (this.isMobileView()) {
      this.showSidebar.set(true);
      this.selectedConversationId.set(null);
    }
  }

  onViewBookingDetails(): void {
    const conv = this.selectedConversation();
    if (conv?.bookingId) {
      this.router.navigate(['/bookings/' + conv.bookingId]);
    }
  }

  onReportUser(): void {
    // TODO Phase 5+: Implement user reporting
    console.log('Report user clicked');
  }

  // ============================================================================
  // TYPING INDICATORS
  // ============================================================================

  onTypingStarted(): void {
    const conv = this.selectedConversation();
    if (conv) {
      this.chatService.sendTypingIndicator(conv.bookingId, true);
    }
  }

  onTypingStopped(): void {
    const conv = this.selectedConversation();
    if (conv) {
      this.chatService.sendTypingIndicator(conv.bookingId, false);
    }
  }
}
