export interface ConversationStatus {
  PENDING: 'PENDING';
  ACTIVE: 'ACTIVE';
  CLOSED: 'CLOSED';
}

export interface MessageDTO {
  id: number;
  conversationId: number;
  senderId: string;
  senderName?: string; // For display in message bubbles
  content: string;
  timestamp: string;
  readBy: string[];
  mediaUrl?: string;
  isOwnMessage?: boolean; // Legacy field
  isRead: boolean; // true if message has been read by recipient

  // Optimistic update fields
  optimisticId?: string; // Client-side ID before server assigns real ID
  status?: 'optimistic' | 'sending' | 'sent' | 'delivered' | 'read' | 'failed';

  // Message status tracking (timestamps)
  sentAt?: string;
  deliveredAt?: string;
  readAt?: string;
}


export interface ConversationDTO {
  id: number;
  bookingId: string;
  renterId: string;
  ownerId: string;
  status: 'PENDING' | 'ACTIVE' | 'CLOSED';
  createdAt: string;
  lastMessageAt?: string;
  messages?: MessageDTO[];
  unreadCount: number;
  messagingAllowed: boolean;

  // Extended fields for UI context (enriched from backend)
  carBrand?: string;
  carModel?: string;
  carYear?: number;
  renterName?: string;
  ownerName?: string;
  renterProfilePicUrl?: string;  // Profile picture URL for renter
  ownerProfilePicUrl?: string;   // Profile picture URL for owner
  startTime?: string;  // ISO-8601 datetime
  endTime?: string;    // ISO-8601 datetime
  lastMessageContent?: string; // Preview of last message for conversation list
  tripStatus?: string; // "Current", "Future", "Past", or "Unknown"
}

export interface SendMessageRequest {
  content: string;
  mediaUrl?: string;
}

export interface CreateConversationRequest {
  bookingId: string;
  renterId: string;
  ownerId: string;
  initialMessage?: string;
}

export interface MessageStatusUpdate {
  messageId: number;
  conversationId: number;
  sentAt?: string;
  deliveredAt?: string;
  readAt?: string;
  readBy?: string[];
}

/**
 * TypingIndicatorDTO - WebSocket payload for typing indicators
 */
export interface TypingIndicatorDTO {
  conversationId: number;
  userId: string;
  userName: string;
  isTyping: boolean;
  timestamp: string;
}

/**
 * OfflineQueueItem - Locally queued message for offline support
 */
export interface OfflineQueueItem {
  id: string; // UUID for local tracking
  bookingId: string;
  content: string;
  timestamp: string;
  retryCount: number;
  status: 'queued' | 'sending' | 'failed';
}