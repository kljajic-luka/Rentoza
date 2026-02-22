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

  ngOnInit(): void {
    this.authService.currentUser$.pipe(takeUntil(this.destroy$)).subscribe((user) => {
      if (user?.id) {
        this.currentUserId.set(user.id);
      }
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
      // Only add if not already in list (avoid duplicates from polling)
      if (!currentMessages.find((m) => m.id === message.id)) {
        this.messages.set([...currentMessages, message]);
        this.scrollToBottom();
      }
    });
  }

  sendMessage(): void {
    const content = this.messageContent().trim();
    if (!content || !this.conversation()?.messagingAllowed) {
      return;
    }

    this.chatService.sendMessage(this.bookingId, { content }).subscribe({
      next: (message) => {
        // Optimistic UI update
        const currentMessages = this.messages();
        if (!currentMessages.find((m) => m.id === message.id)) {
          this.messages.set([...currentMessages, message]);
        }
        this.messageContent.set('');
        this.scrollToBottom();
      },
      error: (err) => {
        console.error('Error sending message:', err);
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