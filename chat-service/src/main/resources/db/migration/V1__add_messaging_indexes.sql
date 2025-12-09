-- ============================================================================
-- V1__add_messaging_indexes.sql
-- Flyway migration for chat-service performance optimization
-- ============================================================================
-- These indexes address the performance issues identified in the audit:
-- 1. Slow message pagination queries
-- 2. Slow user conversation listing
-- 3. Slow unread message count queries
-- ============================================================================

-- Composite index for efficient message pagination by conversation
-- Used by: getConversation() - message pagination ordered by timestamp
CREATE INDEX IF NOT EXISTS idx_messages_conversation_timestamp 
ON messages(conversation_id, timestamp DESC);

-- Index for user conversation listing (renter side)
-- Used by: getUserConversations() - find all conversations where user is renter
CREATE INDEX IF NOT EXISTS idx_conversations_renter_lastmessage
ON conversations(renter_id, last_message_at DESC NULLS LAST);

-- Index for user conversation listing (owner side)
-- Used by: getUserConversations() - find all conversations where user is owner
CREATE INDEX IF NOT EXISTS idx_conversations_owner_lastmessage
ON conversations(owner_id, last_message_at DESC NULLS LAST);

-- Index for booking ID lookup
-- Used by: findByBookingId() - primary conversation lookup
CREATE INDEX IF NOT EXISTS idx_conversations_booking_id
ON conversations(booking_id);

-- Index for unread message queries (message_read_by ElementCollection)
-- Used by: countUnreadMessages(), markMessagesAsRead()
CREATE INDEX IF NOT EXISTS idx_message_read_by_message
ON message_read_by(message_id);
