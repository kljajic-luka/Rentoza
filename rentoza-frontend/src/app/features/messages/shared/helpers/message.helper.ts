import { MessageDTO, ConversationDTO } from '@core/models/chat.model';

/**
 * Helper functions for message operations.
 * Centralized logic to avoid duplication across components.
 */

/**
 * Group messages by sender within a time window.
 * Messages from the same sender within 5 minutes are grouped together.
 */
export interface MessageGroup {
  senderId: string;
  senderName: string;
  messages: MessageDTO[];
  timestamp: string;
  isOwn: boolean;
}

export function groupMessages(messages: MessageDTO[], currentUserId: string): MessageGroup[] {
  if (!messages || messages.length === 0) return [];

  const groups: MessageGroup[] = [];
  let currentGroup: MessageGroup | null = null;
  const GROUP_TIME_WINDOW_MS = 5 * 60 * 1000; // 5 minutes

  messages.forEach((msg) => {
    const msgTime = new Date(msg.timestamp).getTime();
    const isOwn = msg.senderId === currentUserId;

    const shouldGroup = currentGroup &&
      currentGroup.senderId === msg.senderId &&
      currentGroup.messages.length > 0 &&
      msgTime - new Date(currentGroup.messages[currentGroup.messages.length - 1].timestamp).getTime() < GROUP_TIME_WINDOW_MS;

    if (shouldGroup) {
      currentGroup!.messages.push(msg);
    } else {
      if (currentGroup) {
        groups.push(currentGroup);
      }
      currentGroup = {
        senderId: msg.senderId,
        senderName: isOwn ? 'You' : msg.senderName || 'Other',
        messages: [msg],
        timestamp: msg.timestamp,
        isOwn,
      };
    }
  });

  if (currentGroup) {
    groups.push(currentGroup);
  }

  return groups;
}

/**
 * Generate a unique optimistic ID for a new message.
 */
export function generateOptimisticId(): string {
  return `optimistic-${Date.now()}-${Math.random().toString(36).substr(2, 9)}`;
}

/**
 * Create an optimistic message object.
 */
export function createOptimisticMessage(
  content: string,
  senderId: string,
  conversationId: number,
): MessageDTO {
  return {
    id: 0, // Will be replaced by server
    optimisticId: generateOptimisticId(),
    content,
    senderId,
    conversationId,
    timestamp: new Date().toISOString(),
    status: 'optimistic',
    isRead: false,
    readBy: [],
  };
}

/**
 * Format message for display (truncate, escape, etc.)
 */
export function formatMessagePreview(content: string, maxLength = 50): string {
  if (!content) return 'No message';
  const trimmed = content.trim();
  if (trimmed.length <= maxLength) return trimmed;
  return trimmed.substring(0, maxLength) + '...';
}

/**
 * Check if a message has any media attachments.
 */
export function hasMedia(message: MessageDTO): boolean {
  return !!(message as any).mediaUrl || !!(message as any).attachments?.length;
}

/**
 * Sort conversations by last message time (most recent first).
 */
export function sortConversationsByRecent(conversations: ConversationDTO[]): ConversationDTO[] {
  return [...conversations].sort((a, b) => {
    const timeA = a.lastMessageAt ? new Date(a.lastMessageAt).getTime() : 0;
    const timeB = b.lastMessageAt ? new Date(b.lastMessageAt).getTime() : 0;
    return timeB - timeA;
  });
}

/**
 * Count total unread messages across all conversations.
 */
export function countTotalUnread(conversations: ConversationDTO[]): number {
  return conversations.reduce((sum, conv) => sum + (conv.unreadCount || 0), 0);
}

/**
 * Find a conversation by booking ID.
 */
export function findConversationByBookingId(
  conversations: ConversationDTO[],
  bookingId: string | number,
): ConversationDTO | undefined {
  const id = String(bookingId);
  return conversations.find(c => String(c.bookingId) === id);
}
