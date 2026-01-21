-- ============================================================================
-- V43__chat_schema_init.sql (MODIFIED)
-- Supabase Chat Service Schema Migration - Phase 1
-- ============================================================================
-- Purpose: Create chat tables in shared Supabase PostgreSQL database
-- Database: Same Supabase project as main backend
-- User IDs: BIGINT (references main backend users.id)
-- RLS: Not yet enabled (Phase 2 - see V44)
-- ============================================================================

-- ============================================================================
-- PRE-FLIGHT SAFETY CHECKS
-- ============================================================================

DO $$ 
BEGIN
  -- Verify users table exists
  IF NOT EXISTS (
    SELECT 1 FROM information_schema.tables 
    WHERE table_schema = 'public' AND table_name = 'users'
  ) THEN
    RAISE EXCEPTION 'ERROR: users table not found. Cannot create FK constraints.';
  END IF;
  
  -- Verify users.id is BIGINT
  IF NOT EXISTS (
    SELECT 1 FROM information_schema.columns 
    WHERE table_schema = 'public' 
      AND table_name = 'users' 
      AND column_name = 'id' 
      AND data_type = 'bigint'
  ) THEN
    RAISE EXCEPTION 'ERROR: users.id is not BIGINT. Schema mismatch.';
  END IF;
  
  -- Verify bookings table exists
  IF NOT EXISTS (
    SELECT 1 FROM information_schema.tables 
    WHERE table_schema = 'public' AND table_name = 'bookings'
  ) THEN
    RAISE WARNING 'WARNING: bookings table not found. Foreign key validation will be limited.';
  END IF;
  
  RAISE NOTICE '✅ Pre-flight checks passed';
END $$;


-- ============================================================================
-- 1. CONVERSATIONS TABLE
-- ============================================================================
-- Stores conversation metadata between renters and car owners
-- One conversation per booking (1:1 relationship)

CREATE TABLE public.conversations (
  id BIGSERIAL PRIMARY KEY,
  
  -- External booking reference (unique per conversation)
  booking_id BIGINT NOT NULL UNIQUE,
  
  -- Participant user IDs (BIGINT - references users.id from main backend)
  renter_id BIGINT NOT NULL,
  owner_id BIGINT NOT NULL,
  
  -- Conversation state: PENDING (requested), ACTIVE (confirmed), CLOSED (completed)
  status VARCHAR(20) NOT NULL DEFAULT 'PENDING'
    CHECK (status IN ('PENDING', 'ACTIVE', 'CLOSED')),
  
  -- Timestamps (TIMESTAMPTZ for timezone awareness)
  created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
  last_message_at TIMESTAMPTZ,
  
  -- Foreign key constraints (enforces referential integrity)
  -- ON DELETE RESTRICT: Cannot delete user if they have conversations
  CONSTRAINT fk_conversations_renter 
    FOREIGN KEY (renter_id) 
    REFERENCES public.users(id) 
    ON DELETE RESTRICT,
  
  CONSTRAINT fk_conversations_owner 
    FOREIGN KEY (owner_id) 
    REFERENCES public.users(id) 
    ON DELETE RESTRICT
);


-- Indexes for performance
CREATE INDEX idx_conversations_booking_id 
  ON public.conversations(booking_id);

-- Composite index for user's conversation list sorted by recent activity
CREATE INDEX idx_conversations_renter_lastmessage 
  ON public.conversations(renter_id, last_message_at DESC NULLS LAST);

CREATE INDEX idx_conversations_owner_lastmessage 
  ON public.conversations(owner_id, last_message_at DESC NULLS LAST);

CREATE INDEX idx_conversations_status 
  ON public.conversations(status);


-- Table comments for documentation
COMMENT ON TABLE public.conversations 
  IS 'Stores conversation metadata between renters and car owners';
COMMENT ON COLUMN public.conversations.booking_id 
  IS 'External booking reference (unique per conversation)';
COMMENT ON COLUMN public.conversations.renter_id 
  IS 'Rentoza user ID (BIGINT) - references users.id';
COMMENT ON COLUMN public.conversations.owner_id 
  IS 'Rentoza user ID (BIGINT) - references users.id';
COMMENT ON COLUMN public.conversations.status 
  IS 'Conversation state: PENDING (requested), ACTIVE (confirmed), CLOSED (completed)';


-- ============================================================================
-- 2. MESSAGES TABLE
-- ============================================================================
-- Individual messages within conversations

CREATE TABLE public.messages (
  id BIGSERIAL PRIMARY KEY,
  
  -- Conversation reference (cascade delete when conversation deleted)
  conversation_id BIGINT NOT NULL,
  
  -- Sender user ID (BIGINT - may be orphaned if user deleted, for audit trail)
  sender_id BIGINT NOT NULL,
  
  -- Message content (max 2000 characters)
  content TEXT NOT NULL CHECK (char_length(content) <= 2000),
  
  -- Message timestamp
  timestamp TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
  
  -- Optional media attachment URL
  media_url VARCHAR(500),
  
  -- Foreign key constraints
  CONSTRAINT fk_messages_conversation 
    FOREIGN KEY (conversation_id) 
    REFERENCES public.conversations(id) 
    ON DELETE CASCADE
  
  -- Note: No FK for sender_id to allow orphaned messages (audit trail)
  -- Recommendation: Keep messages even if sender deleted
);


-- Indexes for messages
CREATE INDEX idx_messages_conversation_timestamp 
  ON public.messages(conversation_id, timestamp DESC);

CREATE INDEX idx_messages_sender_id 
  ON public.messages(sender_id);

CREATE INDEX idx_messages_timestamp 
  ON public.messages(timestamp DESC);


-- Table comments
COMMENT ON TABLE public.messages 
  IS 'Individual messages within conversations';
COMMENT ON COLUMN public.messages.sender_id 
  IS 'Rentoza user ID (BIGINT) - may be orphaned if user deleted (audit trail)';
COMMENT ON COLUMN public.messages.content 
  IS 'Message text content, max 2000 characters';
COMMENT ON COLUMN public.messages.media_url 
  IS 'Optional URL to attached media (images, documents)';


-- ============================================================================
-- 3. MESSAGE READ RECEIPTS TABLE
-- ============================================================================
-- Tracks which users have read which messages (Phase 1 implementation)

CREATE TABLE public.message_read_by (
  -- Composite primary key (message_id + user_id)
  message_id BIGINT NOT NULL,
  user_id BIGINT NOT NULL,
  
  -- Read timestamp
  read_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
  
  PRIMARY KEY (message_id, user_id),
  
  -- Foreign key constraint (cascade delete when message deleted)
  CONSTRAINT fk_message_read_by_message 
    FOREIGN KEY (message_id) 
    REFERENCES public.messages(id) 
    ON DELETE CASCADE
  
  -- Note: No FK for user_id to track reads by deleted users (optional)
);


-- Indexes for read receipts
CREATE INDEX idx_message_read_by_message_id 
  ON public.message_read_by(message_id);

CREATE INDEX idx_message_read_by_user_id 
  ON public.message_read_by(user_id);


-- Table comments
COMMENT ON TABLE public.message_read_by 
  IS 'Tracks which users have read which messages (Phase 1 implementation)';
COMMENT ON COLUMN public.message_read_by.user_id 
  IS 'Rentoza user ID (BIGINT) - no FK (allows tracking reads by deleted users)';


-- ============================================================================
-- 4. TRIGGERS
-- ============================================================================
-- Auto-update updated_at timestamp on conversations

CREATE OR REPLACE FUNCTION update_conversations_updated_at()
RETURNS TRIGGER AS $$
BEGIN
  NEW.updated_at = CURRENT_TIMESTAMP;
  RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER conversations_update_updated_at
  BEFORE UPDATE ON public.conversations
  FOR EACH ROW
  EXECUTE FUNCTION update_conversations_updated_at();


-- ============================================================================
-- POST-MIGRATION VERIFICATION
-- ============================================================================

DO $$
DECLARE
  table_count INT;
  index_count INT;
BEGIN
  -- Count created tables
  SELECT COUNT(*) INTO table_count
  FROM information_schema.tables
  WHERE table_schema = 'public'
    AND table_name IN ('conversations', 'messages', 'message_read_by');
  
  -- Count created indexes
  SELECT COUNT(*) INTO index_count
  FROM pg_indexes
  WHERE schemaname = 'public'
    AND (indexname LIKE 'idx_conversations%' 
         OR indexname LIKE 'idx_messages%' 
         OR indexname LIKE 'idx_message_read%');
  
  IF table_count = 3 AND index_count = 10 THEN
    RAISE NOTICE '✅ Migration V43 successful: 3 tables, 10 indexes, 1 trigger created';
  ELSE
    RAISE WARNING 'Migration incomplete: tables=%, indexes=% (expected 3 tables, 10 indexes)', 
      table_count, index_count;
  END IF;
END $$;


-- ============================================================================
-- MIGRATION COMPLETE
-- ============================================================================
-- Total tables: 3
-- Total indexes: 10
-- Total triggers: 1
-- Total FK constraints: 3
--
-- Next step: V44 (Enable RLS policies)
-- ============================================================================
