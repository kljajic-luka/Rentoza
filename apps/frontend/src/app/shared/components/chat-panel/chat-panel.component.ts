import {
  Component,
  Input,
  OnInit,
  OnDestroy,
  inject,
  ChangeDetectionStrategy,
  signal,
} from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatBadgeModule } from '@angular/material/badge';
import { ChatService } from '@core/services/chat.service';
import { AuthService } from '@core/auth/auth.service';
import { WebSocketService, WebSocketConnectionStatus } from '@core/services/websocket.service';
import { ConversationDTO, MessageDTO } from '@core/models/chat.model';
import { ChatUiHelper } from '@core/helpers/chat-ui.helper';
import { Subject, takeUntil } from 'rxjs';

@Component({
  selector: 'app-chat-panel',
  standalone: true,
  imports: [
    CommonModule,
    FormsModule,
    MatButtonModule,
    MatFormFieldModule,
    MatInputModule,
    MatIconModule,
    MatProgressSpinnerModule,
    MatBadgeModule,
  ],
  templateUrl: './chat-panel.component.html',
  styleUrls: ['./chat-panel.component.scss'],
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ChatPanelComponent implements OnInit, OnDestroy {
  private readonly chatService = inject(ChatService);
  private readonly authService = inject(AuthService);
  private readonly webSocketService = inject(WebSocketService);
  private readonly destroy$ = new Subject<void>();

  @Input() bookingId!: string;
  @Input() renterId!: string;
  @Input() ownerId!: string;
  @Input() conversationStatus?: 'PENDING' | 'ACTIVE' | 'CLOSED';

  conversation = signal<ConversationDTO | null>(null);
  messages = signal<MessageDTO[]>([]);
  isLoading = signal(false);
  messageContent = signal('');
  currentUserId = signal('');
  isOffline = signal(false);
  sendError = signal<string | null>(null);

  ngOnInit(): void {
    this.authService.currentUser$.pipe(takeUntil(this.destroy$)).subscribe((user) => {
      if (user?.id) {
        this.currentUserId.set(user.id);
      }
    });

    // Track WebSocket connection status for inline offline banner
    this.webSocketService.status$.pipe(takeUntil(this.destroy$)).subscribe((status) => {
      this.isOffline.set(
        status === WebSocketConnectionStatus.DISCONNECTED ||
        status === WebSocketConnectionStatus.ERROR ||
        status === WebSocketConnectionStatus.RECONNECTING,
      );
    });

    if (this.bookingId) {
      this.loadConversation();
      this.subscribeToNewMessages();
    }
  }

  ngOnDestroy(): void {
    this.chatService.stopPolling();
    this.destroy$.next();
    this.destroy$.complete();
  }

  loadConversation(): void {
    this.isLoading.set(true);
    this.chatService.getConversation(this.bookingId).subscribe({
      next: (conv) => {
        this.conversation.set(conv);
        this.messages.set(conv.messages || []);
        this.isLoading.set(false);

        // Start polling for new messages if conversation is active
        if (conv.messagingAllowed) {
          this.chatService.startPolling(this.bookingId);
        }
      },
      error: (err) => {
        console.error('Error loading conversation:', err);
        this.isLoading.set(false);
      },
    });
  }

  private subscribeToNewMessages(): void {
    this.chatService.messages$.pipe(takeUntil(this.destroy$)).subscribe((message) => {
      const currentMessages = this.messages();
      // Replace pending optimistic echo for own messages before appending a server copy.
      if (message.isOwnMessage) {
        const optimisticIndex = currentMessages.findIndex(
          (m) =>
            m.id < 0 &&
            !!m.optimisticId &&
            m.content === message.content &&
            m.senderId === message.senderId,
        );
        if (optimisticIndex >= 0) {
          const updatedMessages = [...currentMessages];
          updatedMessages[optimisticIndex] = { ...message, status: 'sent' as const };
          this.messages.set(updatedMessages);
          this.scrollToBottom();
          return;
        }
      }

      // Only add if not already in list (avoid duplicates from polling/WebSocket)
      if (!currentMessages.find((m) => m.id === message.id)) {
        this.messages.set([...currentMessages, message]);
        this.scrollToBottom();
      }
    });
  }

  sendMessage(): void {
    const content = this.messageContent().trim();
    const conversation = this.conversation();
    if (!content || !conversation?.messagingAllowed) {
      return;
    }

    this.sendError.set(null);

    const optimisticId = `opt_${Date.now()}_${Math.random().toString(36).slice(2, 11)}`;

    // Optimistic message with 'sending' status
    const optimisticMessage: MessageDTO = {
      id: -Date.now(),
      conversationId: conversation.id,
      senderId: this.currentUserId(),
      content,
      timestamp: new Date().toISOString(),
      readBy: [],
      isOwnMessage: true,
      isRead: false,
      optimisticId,
      status: this.isOffline() ? 'optimistic' : 'sending',
    };
    this.messages.update((msgs) => [...msgs, optimisticMessage]);
    this.messageContent.set('');
    this.scrollToBottom();

    this.chatService.sendMessage(this.bookingId, { content }).subscribe({
      next: (message) => {
        this.messages.update((msgs) => {
          const replaced = msgs.map((m) =>
            m.optimisticId === optimisticId ? { ...message, status: 'sent' as const } : m,
          );

          // Keep the newest copy if the same server message already arrived via polling/WebSocket.
          const deduped: MessageDTO[] = [];
          const seenIds = new Set<number>();
          for (let i = replaced.length - 1; i >= 0; i--) {
            const candidate = replaced[i];
            if (candidate.id > 0) {
              if (seenIds.has(candidate.id)) {
                continue;
              }
              seenIds.add(candidate.id);
            }
            deduped.unshift(candidate);
          }

          return deduped;
        });
        this.scrollToBottom();
      },
      error: (err) => {
        console.error('Error sending message:', err);
        // Mark optimistic message as failed
        this.messages.update((msgs) =>
          msgs.map((m) => (m.optimisticId === optimisticId ? { ...m, status: 'failed' as const } : m)),
        );
        this.sendError.set('Poruka nije poslata. Pokušajte ponovo.');
      },
    });
  }

  markAsRead(): void {
    if (this.bookingId) {
      this.chatService.markMessagesAsRead(this.bookingId).subscribe({
        next: () => {
          const conv = this.conversation();
          if (conv) {
            conv.unreadCount = 0;
            this.conversation.set(conv);
          }
        },
        error: (err) => {
          console.error('Error marking messages as read:', err);
        },
      });
    }
  }

  private scrollToBottom(): void {
    setTimeout(() => {
      const messagesContainer = document.querySelector('.messages-container');
      if (messagesContainer) {
        messagesContainer.scrollTop = messagesContainer.scrollHeight;
      }
    }, 100);
  }

  get isMessagingLocked(): boolean {
    return !this.conversation()?.messagingAllowed;
  }

  get displayInfo() {
    const conv = this.conversation();
    if (!conv) return null;
    return ChatUiHelper.getDisplayInfo(conv, this.currentUserId());
  }

  get statusMessage(): string {
    const info = this.displayInfo;
    return info ? info.subtitle : '';
  }
}
