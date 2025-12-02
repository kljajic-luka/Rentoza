export interface ConversationStatus {
  PENDING: 'PENDING';
  ACTIVE: 'ACTIVE';
  CLOSED: 'CLOSED';
}

export interface MessageDTO {
  id: number;
  conversationId: number;
  senderId: string;
  content: string;
  timestamp: string;
  readBy: string[];
  mediaUrl?: string;
  isOwnMessage: boolean;

  // Message status tracking
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
  startTime?: string;  // ISO-8601 datetime
  endTime?: string;    // ISO-8601 datetime
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
