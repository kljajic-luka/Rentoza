import {
  Component,
  OnInit,
  OnDestroy,
  inject,
  signal,
  effect,
  ViewChild,
  ElementRef,
  AfterViewChecked,
  ChangeDetectorRef,
} from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Router, ActivatedRoute } from '@angular/router';
import { MatSidenavModule } from '@angular/material/sidenav';
import { MatListModule } from '@angular/material/list';
import { MatIconModule } from '@angular/material/icon';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatBadgeModule } from '@angular/material/badge';
import { MatDividerModule } from '@angular/material/divider';
import { MatTooltipModule } from '@angular/material/tooltip';
import { MatCardModule } from '@angular/material/card';
import { Subject, takeUntil } from 'rxjs';

import { ChatService } from '@core/services/chat.service';
import { AuthService } from '@core/auth/auth.service';
import { ThemeService } from '@core/services/theme.service';
import { ConversationDTO, MessageDTO } from '@core/models/chat.model';
import { ConversationEnrichmentService } from '@core/services/conversation-enrichment.service';

@Component({
  selector: 'app-messages',
  standalone: true,
  imports: [
    CommonModule,
    FormsModule,
    MatSidenavModule,
    MatListModule,
    MatIconModule,
    MatButtonModule,
    MatFormFieldModule,
    MatInputModule,
    MatProgressSpinnerModule,
    MatBadgeModule,
    MatDividerModule,
    MatTooltipModule,
    MatCardModule,
  ],
  templateUrl: './messages.component.html',
  styleUrls: ['./messages.component.scss'],
})
export class MessagesComponent implements OnInit, OnDestroy, AfterViewChecked {
  private readonly chatService = inject(ChatService);
  private readonly authService = inject(AuthService);
  readonly themeService = inject(ThemeService);
  private readonly enrichmentService = inject(ConversationEnrichmentService);
  private readonly router = inject(Router);
  private readonly route = inject(ActivatedRoute);
  private readonly cdr = inject(ChangeDetectorRef);
  private readonly destroy$ = new Subject<void>();

  @ViewChild('messagesContainer') messagesContainer?: ElementRef<HTMLDivElement>;

  conversations = signal<ConversationDTO[]>([]);
  selectedConversation = signal<ConversationDTO | null>(null);
  messages = signal<MessageDTO[]>([]);
  messageContent = signal('');
  currentUserId = signal('');
  isLoading = signal(true);
  isLoadingMessages = signal(false);
  isSendingMessage = signal(false);
  wsConnected = signal(false);

  private shouldScrollToBottom = false;
  private lastMessageCount = 0;

  constructor() {
    // React to active conversation changes
    effect(() => {
      const conv = this.selectedConversation();
      if (conv) {
        this.messages.set(conv.messages || []);
        this.shouldScrollToBottom = true;
        this.markAsRead();
      }
    });
  }

  async ngOnInit(): Promise<void> {
    // Get current user
    this.authService.currentUser$.pipe(takeUntil(this.destroy$)).subscribe((user) => {
      if (user?.id) {
        this.currentUserId.set(user.id);
      }
    });

    // Initialize WebSocket
    try {
      await this.chatService.initializeWebSocket();
      this.wsConnected.set(true);
    } catch (error) {
      console.error('[MessagesComponent] Failed to initialize WebSocket:', error);
      this.wsConnected.set(false);
    }

    // Subscribe to WebSocket messages
    this.chatService.messages$.pipe(takeUntil(this.destroy$)).subscribe((message) => {
      this.handleNewMessage(message);
    });

    // Subscribe to conversations updates
    this.chatService.conversations$.pipe(takeUntil(this.destroy$)).subscribe((convs) => {
      console.log('[MessagesComponent] Conversations updated:', convs.length);
      this.conversations.set(convs);
      this.cdr.detectChanges(); // Trigger change detection
    });

    // Subscribe to active conversation updates
    this.chatService.activeConversation$.pipe(takeUntil(this.destroy$)).subscribe((conv) => {
      if (conv) {
        console.log('[MessagesComponent] Active conversation updated:', conv.id);
        this.selectedConversation.set(conv);
      }
    });

    // Load user conversations
    await this.loadConversations();

    // Check for bookingId in query params (for auto-redirect after booking)
    this.route.queryParams.pipe(takeUntil(this.destroy$)).subscribe((params) => {
      const bookingId = params['bookingId'];
      if (bookingId) {
        this.selectConversationByBookingId(bookingId);
      }
    });
  }

  ngOnDestroy(): void {
    this.destroy$.next();
    this.destroy$.complete();
  }

  ngAfterViewChecked(): void {
    if (this.shouldScrollToBottom) {
      this.scrollToBottom();
      this.shouldScrollToBottom = false;
    }
  }

  private async loadConversations(): Promise<void> {
    this.isLoading.set(true);
    console.log('[MessagesComponent] Loading conversations...');

    try {
      const conversations = await this.chatService.getUserConversations().toPromise();
      console.log('[MessagesComponent] Loaded conversations:', conversations);

      // Enrich conversations with booking and user details
      const enriched = await this.enrichmentService
        .enrichConversations(conversations || [])
        .toPromise();
      console.log('[MessagesComponent] Enriched conversations:', enriched);

      this.conversations.set(enriched || []);
      this.isLoading.set(false);

      // Manually trigger change detection after loading
      this.cdr.detectChanges();

      // Auto-select first conversation if none selected
      if (!this.selectedConversation() && this.conversations().length > 0) {
        console.log('[MessagesComponent] Auto-selecting first conversation');
        this.selectConversation(this.conversations()[0]);
      }
    } catch (error) {
      console.error('[MessagesComponent] Error loading conversations:', error);
      this.isLoading.set(false);
      this.cdr.detectChanges();
    }
  }

  selectConversation(conversation: ConversationDTO): void {
    if (this.selectedConversation()?.id === conversation.id) {
      return; // Already selected
    }

    this.isLoadingMessages.set(true);
    this.selectedConversation.set(conversation);
    this.chatService.setActiveConversation(conversation);

    // Load conversation messages
    this.chatService.getConversation(conversation.bookingId).subscribe({
      next: (conv) => {
        this.selectedConversation.set(conv);
        this.messages.set(conv.messages || []);
        this.isLoadingMessages.set(false);
        this.shouldScrollToBottom = true;
        this.lastMessageCount = conv.messages?.length || 0;
      },
      error: (err) => {
        console.error('[MessagesComponent] Error loading conversation:', err);
        this.isLoadingMessages.set(false);
      },
    });
  }

  selectConversationByBookingId(bookingId: string): void {
    const conversation = this.conversations().find((c) => c.bookingId === bookingId);
    if (conversation) {
      this.selectConversation(conversation);
    }
  }

  sendMessage(): void {
    const content = this.messageContent().trim();
    const conv = this.selectedConversation();

    if (!content || !conv || !conv.messagingAllowed) {
      return;
    }

    this.isSendingMessage.set(true);

    // Send via HTTP (WebSocket can be added later for real-time)
    this.chatService.sendMessage(conv.bookingId, { content }).subscribe({
      next: (message) => {
        // Message is handled by WebSocket or optimistic update in service
        this.messageContent.set('');
        this.isSendingMessage.set(false);
        this.shouldScrollToBottom = true;
      },
      error: (err) => {
        console.error('[MessagesComponent] Error sending message:', err);
        this.isSendingMessage.set(false);
      },
    });
  }

  private handleNewMessage(message: MessageDTO): void {
    const currentMessages = this.messages();
    const selectedConv = this.selectedConversation();

    // Only add if for current conversation and not duplicate
    if (selectedConv && selectedConv.id === message.conversationId) {
      if (!currentMessages.find((m) => m.id === message.id)) {
        this.messages.set([...currentMessages, message]);
        this.shouldScrollToBottom = true;
        this.lastMessageCount = this.messages().length;

        // Mark as read if conversation is active
        this.markAsRead();
      }
    }
  }

  private markAsRead(): void {
    const conv = this.selectedConversation();
    if (conv && conv.unreadCount > 0) {
      this.chatService.markMessagesAsRead(conv.bookingId).subscribe();
    }
  }

  private scrollToBottom(): void {
    try {
      if (this.messagesContainer) {
        const container = this.messagesContainer.nativeElement;
        container.scrollTop = container.scrollHeight;
      }
    } catch (err) {
      console.error('[MessagesComponent] Error scrolling to bottom:', err);
    }
  }

  isOwnMessage(message: MessageDTO): boolean {
    return message.senderId === this.currentUserId();
  }

  formatTime(timestamp: string): string {
    const date = new Date(timestamp);
    const now = new Date();
    const diffMs = now.getTime() - date.getTime();
    const diffMins = Math.floor(diffMs / 60000);
    const diffHours = Math.floor(diffMs / 3600000);
    const diffDays = Math.floor(diffMs / 86400000);

    if (diffMins < 1) return 'Just now';
    if (diffMins < 60) return `${diffMins}m ago`;
    if (diffHours < 24) return `${diffHours}h ago`;
    if (diffDays < 7) return `${diffDays}d ago`;

    return date.toLocaleDateString();
  }

  formatMessageTime(timestamp: string): string {
    const date = new Date(timestamp);
    return date.toLocaleTimeString('en-US', { hour: '2-digit', minute: '2-digit' });
  }

  /**
   * Get the other participant's name for display
   */
  getOtherParticipantName(conv: ConversationDTO): string {
    const isOwner = conv.ownerId === this.currentUserId();

    // Use enriched data if available
    if (isOwner && conv.renterName) {
      return conv.renterName;
    } else if (!isOwner && conv.ownerName) {
      return conv.ownerName;
    }

    // Fallback to generic name
    return isOwner ? 'Renter' : 'Owner';
  }

  /**
   * Determine trip status based on booking dates
   */
  getTripStatus(conv: ConversationDTO): 'current' | 'future' | 'past' | 'unknown' {
    if (!conv.startDate || !conv.endDate) {
      return 'unknown';
    }

    const now = new Date();
    now.setHours(0, 0, 0, 0); // Normalize to midnight

    const startDate = new Date(conv.startDate);
    startDate.setHours(0, 0, 0, 0);

    const endDate = new Date(conv.endDate);
    endDate.setHours(23, 59, 59, 999); // End of day

    if (now < startDate) {
      return 'future';
    } else if (now > endDate) {
      return 'past';
    } else {
      return 'current';
    }
  }

  /**
   * Get formatted conversation title
   */
  getConversationTitle(conv: ConversationDTO): string {
    return this.getOtherParticipantName(conv);
  }

  /**
   * Get formatted conversation subtitle with trip context
   */
  getConversationSubtitle(conv: ConversationDTO): string {
    const tripStatus = this.getTripStatus(conv);
    const carInfo = this.getCarInfo(conv);

    switch (tripStatus) {
      case 'current':
        return `Current trip${carInfo ? ' with ' + carInfo : ''}`;
      case 'future':
        return `Future trip${carInfo ? ' with ' + carInfo : ''}`;
      case 'past':
        return `Past trip${carInfo ? ' with ' + carInfo : ''}`;
      default:
        return conv.status === 'ACTIVE'
          ? 'Active'
          : conv.status === 'PENDING'
          ? 'Pending'
          : 'Closed';
    }
  }

  /**
   * Get car information string
   */
  getCarInfo(conv: ConversationDTO): string {
    if (!conv.carBrand || !conv.carModel) {
      return '';
    }

    const year = conv.carYear ? `${conv.carYear} ` : '';
    return `${year}${conv.carBrand} ${conv.carModel}`;
  }

  /**
   * Get status badge color based on trip status
   */
  getStatusBadgeColor(conv: ConversationDTO): string {
    const tripStatus = this.getTripStatus(conv);

    switch (tripStatus) {
      case 'current':
        return 'accent'; // Green for current trips
      case 'future':
        return 'primary'; // Blue for future trips
      case 'past':
        return 'warn'; // Orange for past trips
      default:
        return conv.status === 'ACTIVE' ? 'accent' : '';
    }
  }

  get hasConversations(): boolean {
    return this.conversations().length > 0;
  }

  get isMessagingAllowed(): boolean {
    return this.selectedConversation()?.messagingAllowed ?? false;
  }

  get statusMessage(): string {
    const status = this.selectedConversation()?.status;
    if (!status) return '';

    switch (status) {
      case 'PENDING':
        return 'Booking request sent. You can message the owner.';
      case 'ACTIVE':
        return 'Conversation is active';
      case 'CLOSED':
        return 'Trip completed – chat is read-only';
      default:
        return '';
    }
  }

  /**
   * Get message delivery status icon
   */
  getMessageStatusIcon(message: MessageDTO): string {
    // Only show status for own messages
    if (!this.isOwnMessage(message)) {
      return '';
    }

    // If message was read (either has readAt or is in readBy array)
    if (message.readAt || (message.readBy && message.readBy.length > 0)) {
      return 'done_all'; // Double check (read)
    }

    // For now, all sent messages show as delivered since we don't track delivery separately
    // In the future, you can add deliveredAt field
    if (message.timestamp) {
      return 'done_all'; // Double check (delivered)
    }

    return 'schedule'; // Sending
  }

  /**
   * Get message status class for styling
   */
  getMessageStatusClass(message: MessageDTO): string {
    if (!this.isOwnMessage(message)) {
      return '';
    }

    // If message was read
    if (message.readAt || (message.readBy && message.readBy.length > 0)) {
      return 'read'; // Blue
    }

    // If message was sent/delivered
    if (message.timestamp) {
      return 'delivered'; // Gray
    }

    return 'sending'; // Gray
  }
}
