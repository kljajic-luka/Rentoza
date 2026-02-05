import {
  groupMessages,
  generateOptimisticId,
  createOptimisticMessage,
  formatMessagePreview,
  sortConversationsByRecent,
  countTotalUnread,
  findConversationByBookingId,
} from './message.helper';
import { MessageDTO, ConversationDTO } from '@core/models/chat.model';

describe('MessageHelper', () => {
  describe('groupMessages', () => {
    it('should return empty array for empty messages', () => {
      expect(groupMessages([], 'user1')).toEqual([]);
    });

    it('should group consecutive messages from same sender', () => {
      const messages: MessageDTO[] = [
        { id: 1, content: 'Hello', senderId: 'user1', timestamp: new Date().toISOString(), conversationId: 1, isRead: false, readBy: [] },
        { id: 2, content: 'World', senderId: 'user1', timestamp: new Date().toISOString(), conversationId: 1, isRead: false, readBy: [] },
      ];

      const groups = groupMessages(messages, 'user2');
      expect(groups.length).toBe(1);
      expect(groups[0].messages.length).toBe(2);
    });

    it('should not group messages from different senders', () => {
      const messages: MessageDTO[] = [
        { id: 1, content: 'Hello', senderId: 'user1', timestamp: new Date().toISOString(), conversationId: 1, isRead: false, readBy: [] },
        { id: 2, content: 'Hi', senderId: 'user2', timestamp: new Date().toISOString(), conversationId: 1, isRead: false, readBy: [] },
      ];

      const groups = groupMessages(messages, 'user1');
      expect(groups.length).toBe(2);
    });

    it('should not group messages more than 5 minutes apart', () => {
      const now = Date.now();
      const messages: MessageDTO[] = [
        { id: 1, content: 'Hello', senderId: 'user1', timestamp: new Date(now).toISOString(), conversationId: 1, isRead: false, readBy: [] },
        { id: 2, content: 'World', senderId: 'user1', timestamp: new Date(now + 6 * 60 * 1000).toISOString(), conversationId: 1, isRead: false, readBy: [] },
      ];

      const groups = groupMessages(messages, 'user2');
      expect(groups.length).toBe(2);
    });

    it('should mark groups as own when sender matches current user', () => {
      const messages: MessageDTO[] = [
        { id: 1, content: 'Hello', senderId: 'user1', timestamp: new Date().toISOString(), conversationId: 1, isRead: false, readBy: [] },
      ];

      const groups = groupMessages(messages, 'user1');
      expect(groups[0].isOwn).toBe(true);
    });
  });

  describe('generateOptimisticId', () => {
    it('should generate unique IDs', () => {
      const id1 = generateOptimisticId();
      const id2 = generateOptimisticId();
      expect(id1).not.toBe(id2);
    });

    it('should start with "optimistic-" prefix', () => {
      const id = generateOptimisticId();
      expect(id.startsWith('optimistic-')).toBe(true);
    });
  });

  describe('createOptimisticMessage', () => {
    it('should create a message with optimistic status', () => {
      const msg = createOptimisticMessage('Hello', 'user1', 1);
      expect(msg.status).toBe('optimistic');
      expect(msg.content).toBe('Hello');
      expect(msg.senderId).toBe('user1');
      expect(msg.conversationId).toBe(1);
      expect(msg.optimisticId).toBeDefined();
    });
  });

  describe('formatMessagePreview', () => {
    it('should return full content if within limit', () => {
      expect(formatMessagePreview('Hello', 50)).toBe('Hello');
    });

    it('should truncate with ellipsis if over limit', () => {
      const long = 'A'.repeat(100);
      expect(formatMessagePreview(long, 50)).toBe('A'.repeat(50) + '...');
    });

    it('should return "No message" for empty content', () => {
      expect(formatMessagePreview('')).toBe('No message');
    });
  });

  describe('sortConversationsByRecent', () => {
    it('should sort by lastMessageAt descending', () => {
      const convs = [
        { id: 1, lastMessageAt: '2025-12-01T10:00:00Z' } as ConversationDTO,
        { id: 2, lastMessageAt: '2025-12-08T10:00:00Z' } as ConversationDTO,
        { id: 3, lastMessageAt: '2025-12-05T10:00:00Z' } as ConversationDTO,
      ];

      const sorted = sortConversationsByRecent(convs);
      expect(sorted[0].id).toBe(2);
      expect(sorted[1].id).toBe(3);
      expect(sorted[2].id).toBe(1);
    });
  });

  describe('countTotalUnread', () => {
    it('should sum all unread counts', () => {
      const convs = [
        { id: 1, unreadCount: 5 } as ConversationDTO,
        { id: 2, unreadCount: 3 } as ConversationDTO,
        { id: 3, unreadCount: 0 } as ConversationDTO,
      ];

      expect(countTotalUnread(convs)).toBe(8);
    });
  });

  describe('findConversationByBookingId', () => {
    it('should find conversation by booking ID', () => {
      const convs = [
        { id: 1, bookingId: '123' } as ConversationDTO,
        { id: 2, bookingId: '456' } as ConversationDTO,
      ];

      const found = findConversationByBookingId(convs, '456');
      expect(found?.id).toBe(2);
    });

    it('should return undefined if not found', () => {
      const convs = [{ id: 1, bookingId: '123' } as ConversationDTO];
      expect(findConversationByBookingId(convs, '999')).toBeUndefined();
    });
  });
});
