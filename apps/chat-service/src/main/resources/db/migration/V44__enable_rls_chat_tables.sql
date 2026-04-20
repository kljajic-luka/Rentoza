-- ============================================================================
-- V44__enable_rls_chat_tables.sql
-- Supabase Chat Service RLS Migration - Phase 1
-- ============================================================================
-- Purpose: Enable Row-Level Security on chat service tables
-- Mapping: Supabase auth.uid() (UUID) → users.auth_uid → users.id (BIGINT)
-- Security: Deny by default, explicit grants only
-- ============================================================================

-- ⚠️ OPTIMIZATION TRIGGER:
-- If p95 latency for RLS queries exceeds 100ms, deploy this optimization:
--
-- CREATE FUNCTION get_user_id_from_auth_uid() RETURNS BIGINT AS $$
--   SELECT id FROM users WHERE auth_uid = auth.uid() LIMIT 1;
-- $$ LANGUAGE SQL STABLE SECURITY DEFINER;
--
-- Then replace all RLS policies to use: renter_id = get_user_id_from_auth_uid()
-- See MIGRATION_PLAN_INDEX.md section "RLS Performance Optimization"
-- Monitor via: SELECT avg(duration) FROM rls_performance_log WHERE duration_ms > 100;
-- ============================================================================

-- ============================================================================
-- ENABLE RLS ON ALL TABLES
-- ============================================================================
-- Deny by default - no access without explicit policy grant

ALTER TABLE public.conversations ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.messages ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.message_read_by ENABLE ROW LEVEL SECURITY;


-- ============================================================================
-- SERVICE ROLE BYPASS (For Internal Service Calls)
-- ============================================================================
-- Main backend calls chat service with service_role key
-- These operations bypass RLS for administrative operations
-- Security: Only internal service can create conversations on behalf of users
-- ============================================================================

-- Verify service_role has bypass privileges
DO $$
DECLARE
  role_bypass BOOLEAN;
BEGIN
  SELECT rolbypassrls INTO role_bypass
  FROM pg_roles
  WHERE rolname = 'service_role';
  
  IF role_bypass THEN
    RAISE NOTICE '✅ service_role has RLS bypass enabled';
  ELSE
    RAISE WARNING 'WARNING: service_role does not have RLS bypass - internal service calls may fail';
  END IF;
END $$;


-- ============================================================================
-- CONVERSATIONS TABLE POLICIES (3 policies)
-- ============================================================================

-- ============================================================================
-- Policy 1: Users can SELECT their own conversations
-- ============================================================================
-- Participants (renter OR owner) can view their conversations
-- Non-participants see 0 rows (RLS silently filters)

CREATE POLICY "conversations_participant_read"
ON public.conversations FOR SELECT
USING (
  -- User is renter: Map auth.uid() (UUID) → users.id (BIGINT) → check renter_id
  renter_id IN (
    SELECT id FROM public.users 
    WHERE auth_uid = auth.uid()
  )
  OR
  -- User is owner: Map auth.uid() (UUID) → users.id (BIGINT) → check owner_id
  owner_id IN (
    SELECT id FROM public.users 
    WHERE auth_uid = auth.uid()
  )
);

-- ============================================================================
-- Policy 2: Users can INSERT their own conversations
-- ============================================================================
-- User creating conversation must be renter OR owner
-- Main backend uses service_role which bypasses RLS

CREATE POLICY "conversations_participant_insert"
ON public.conversations FOR INSERT
WITH CHECK (
  -- Inserting user must be renter OR owner
  renter_id IN (
    SELECT id FROM public.users 
    WHERE auth_uid = auth.uid()
  )
  OR
  owner_id IN (
    SELECT id FROM public.users 
    WHERE auth_uid = auth.uid()
  )
);

-- ============================================================================
-- Policy 3: Users can UPDATE their own conversations
-- ============================================================================
-- Participants can update status (PENDING → ACTIVE → CLOSED)
-- Both USING and WITH CHECK ensure user is participant before and after update

CREATE POLICY "conversations_participant_update"
ON public.conversations FOR UPDATE
USING (
  -- User must be participant to update (before update)
  renter_id IN (SELECT id FROM public.users WHERE auth_uid = auth.uid())
  OR owner_id IN (SELECT id FROM public.users WHERE auth_uid = auth.uid())
)
WITH CHECK (
  -- User must still be participant after update
  renter_id IN (SELECT id FROM public.users WHERE auth_uid = auth.uid())
  OR owner_id IN (SELECT id FROM public.users WHERE auth_uid = auth.uid())
);


-- ============================================================================
-- MESSAGES TABLE POLICIES (2 policies)
-- ============================================================================

-- ============================================================================
-- Policy 1: Users can SELECT messages from their conversations
-- ============================================================================
-- User can see messages if they're a participant in the conversation
-- Nested subquery: conversation_id IN (user's conversations)

CREATE POLICY "messages_participant_read"
ON public.messages FOR SELECT
USING (
  conversation_id IN (
    -- Select all conversations where user is renter OR owner
    SELECT id FROM public.conversations
    WHERE renter_id IN (SELECT id FROM public.users WHERE auth_uid = auth.uid())
       OR owner_id IN (SELECT id FROM public.users WHERE auth_uid = auth.uid())
  )
);

-- ============================================================================
-- Policy 2: Users can INSERT messages only in their conversations
-- ============================================================================
-- Two conditions:
-- 1. Message is in user's conversation
-- 2. Message sender is the authenticated user (prevents impersonation)

CREATE POLICY "messages_participant_insert"
ON public.messages FOR INSERT
WITH CHECK (
  -- Condition 1: Conversation must belong to user
  conversation_id IN (
    SELECT id FROM public.conversations
    WHERE renter_id IN (SELECT id FROM public.users WHERE auth_uid = auth.uid())
       OR owner_id IN (SELECT id FROM public.users WHERE auth_uid = auth.uid())
  )
  AND
  -- Condition 2: Sender must be authenticated user (prevents impersonation)
  sender_id IN (
    SELECT id FROM public.users 
    WHERE auth_uid = auth.uid()
  )
);


-- ============================================================================
-- MESSAGE READ RECEIPTS TABLE POLICIES (2 policies)
-- ============================================================================

-- ============================================================================
-- Policy 1: Users can SELECT their own read receipts
-- ============================================================================
-- User can see which messages they've read

CREATE POLICY "message_read_by_own_read"
ON public.message_read_by FOR SELECT
USING (
  user_id IN (
    SELECT id FROM public.users 
    WHERE auth_uid = auth.uid()
  )
);

-- ============================================================================
-- Policy 2: Users can INSERT their own read receipts
-- ============================================================================
-- User can mark messages as read (only for themselves)

CREATE POLICY "message_read_by_own_insert"
ON public.message_read_by FOR INSERT
WITH CHECK (
  user_id IN (
    SELECT id FROM public.users 
    WHERE auth_uid = auth.uid()
  )
);


-- ============================================================================
-- POST-MIGRATION VERIFICATION
-- ============================================================================

DO $$
DECLARE
  policy_count INT;
  rls_enabled_count INT;
BEGIN
  -- Count RLS policies
  SELECT COUNT(*) INTO policy_count
  FROM pg_policies
  WHERE schemaname = 'public'
    AND tablename IN ('conversations', 'messages', 'message_read_by');
  
  -- Count tables with RLS enabled
  SELECT COUNT(*) INTO rls_enabled_count
  FROM pg_tables
  WHERE schemaname = 'public'
    AND tablename IN ('conversations', 'messages', 'message_read_by')
    AND rowsecurity = true;
  
  IF policy_count = 7 AND rls_enabled_count = 3 THEN
    RAISE NOTICE '✅ RLS migration V44 successful: 3 tables secured, 7 policies active';
  ELSE
    RAISE WARNING 'RLS migration incomplete: policies=%, rls_enabled=% (expected 7 policies, 3 tables)', 
      policy_count, rls_enabled_count;
  END IF;
END $$;


-- ============================================================================
-- RLS MIGRATION COMPLETE
-- ============================================================================
-- Total policies: 7
-- conversations: 3 (SELECT, INSERT, UPDATE)
-- messages: 2 (SELECT, INSERT)
-- message_read_by: 2 (SELECT, INSERT)
--
-- Mapping pattern: auth.uid() UUID → users.auth_uid → users.id BIGINT
-- Every policy uses nested subquery (can optimize with function later)
--
-- IMPORTANT: Messages and read receipts are immutable (no UPDATE/DELETE policies)
-- Conversations can be updated but not deleted (closed instead)
--
-- Security: Deny by default, explicit grants only
-- Performance: Monitor RLS overhead, optimize if p95 > 50ms
-- ============================================================================
