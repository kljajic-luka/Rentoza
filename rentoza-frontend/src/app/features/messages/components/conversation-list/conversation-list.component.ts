import {
  Component,
  input,
  output,
  signal,
  computed,
  inject,
  effect,
  ChangeDetectionStrategy,
} from '@angular/core';
import { CommonModule } from '@angular/common';
import { ScrollingModule } from '@angular/cdk/scrolling';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatIconModule } from '@angular/material/icon';

import { ConversationDTO } from '@core/models/chat.model';
import { ChatUiHelper } from '@core/helpers/chat-ui.helper';
import { ConversationItemComponent } from '../conversation-item/conversation-item.component';


/**
 * ConversationListComponent - Smart Container for Conversation List
 *
 * Uses CDK Virtual Scroll for performant rendering of large lists.
 * Delegates individual item rendering to ConversationItemComponent.
 */
@Component({
  selector: 'app-conversation-list',
  standalone: true,
  imports: [
    CommonModule,
    ScrollingModule,
    MatProgressSpinnerModule,
    MatIconModule,
    ConversationItemComponent,
  ],
  template: `
    <!-- Loading state -->
    @if (isLoading()) {
      <div class="loading-container">
        <mat-spinner diameter="40"></mat-spinner>
        <p>Učitavanje razgovora...</p>
      </div>
    }

    <!-- Empty state -->
    @if (!isLoading() && conversations().length === 0) {
      <div class="empty-state">
        <mat-icon>chat_bubble_outline</mat-icon>
        <p>Nema razgovora</p>
        <small>Rezerviši automobil da počneš razgovor sa vlasnikom.</small>
      </div>
    }

    <!-- Conversation list (using @for instead of CDK virtual scroll due to signal compatibility) -->
    @if (!isLoading() && conversations().length > 0) {
      <div class="conversation-viewport">
        @for (conv of conversations(); track trackByConversation($index, conv)) {
          <app-conversation-item
            [conversation]="conv"
            [isSelected]="selectedConversationId() === conv.id"
            [currentUserId]="currentUserId()"
            (selectConversation)="onSelectConversation($event)"
          ></app-conversation-item>
        }
      </div>
    }
  `,
  styles: [`
    :host {
      display: flex;
      flex-direction: column;
      flex: 1;
      overflow: hidden;
      min-height: 0; /* Critical for CDK virtual scroll in flex container */
    }

    .loading-container {
      display: flex;
      flex-direction: column;
      align-items: center;
      justify-content: center;
      padding: 48px 24px;
      gap: 16px;

      p {
        margin: 0;
        color: #666;
        font-size: 14px;
      }
    }

    .empty-state {
      display: flex;
      flex-direction: column;
      align-items: center;
      justify-content: center;
      padding: 60px 24px;
      text-align: center;

      mat-icon {
        font-size: 56px;
        width: 56px;
        height: 56px;
        color: #e0e0e0;
        margin-bottom: 16px;
      }

      p {
        margin: 0 0 8px 0;
        font-size: 17px;
        font-weight: 600;
        color: #333;
      }

      small {
        color: #888;
        font-size: 14px;
        line-height: 1.5;
        max-width: 240px;
      }
    }

    .conversation-viewport {
      flex: 1;
      overflow-y: auto;
      min-height: 0; /* Critical for CDK virtual scroll in flex container */

      &::-webkit-scrollbar {
        width: 6px;
      }

      &::-webkit-scrollbar-track {
        background: transparent;
      }

      &::-webkit-scrollbar-thumb {
        background: #e0e0e0;
        border-radius: 3px;

        &:hover {
          background: #ccc;
        }
      }
    }
  `],
  // TEMPORARILY DISABLED - signal inputs should auto-track but component not rendering properly
  // changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ConversationListComponent {
  // Inputs - using signal inputs for compatibility
  conversations = input.required<ConversationDTO[]>();
  selectedConversationId = input<number | null>(null);
  currentUserId = input.required<string>();
  isLoading = input<boolean>(false);

  // Outputs
  selectConversation = output<ConversationDTO>();

  constructor() {
    // No initialization needed - signal inputs auto-track
  }

  // Event handlers
  onSelectConversation(conversation: ConversationDTO): void {
    this.selectConversation.emit(conversation);
  }

  // Track by function for virtual scroll
  trackByConversation(index: number, conv: ConversationDTO): number {
    return conv.id;
  }
}
