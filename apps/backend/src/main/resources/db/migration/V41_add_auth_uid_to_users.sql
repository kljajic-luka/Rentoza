-- V41_add_auth_uid_to_users.sql
-- Date: January 15, 2026
-- Purpose: Add Supabase Auth integration to existing user accounts
-- Context: Greenfield MVP with zero existing users

-- Add auth_uid column (UUID linking to auth.users)
ALTER TABLE public.users
ADD COLUMN auth_uid UUID UNIQUE;

-- Create foreign key to Supabase auth.users table
-- ON DELETE CASCADE ensures user cleanup when Supabase user is deleted
ALTER TABLE public.users
ADD CONSTRAINT fk_users_auth_uid 
    FOREIGN KEY (auth_uid) REFERENCES auth.users(id) ON DELETE CASCADE;

-- Create index for fast lookups (essential for RLS policies)
-- RLS policies will frequently check: WHERE auth_uid = auth.uid()
CREATE INDEX idx_users_auth_uid ON public.users(auth_uid);

-- For greenfield (zero users), no existing mapping needed
-- In production migration with existing users, would populate like this:
-- UPDATE public.users 
-- SET auth_uid = (SELECT id FROM auth.users WHERE email = public.users.email)
-- WHERE email IN (SELECT email FROM auth.users);

-- Verify schema
-- SELECT column_name, data_type, is_nullable 
-- FROM information_schema.columns 
-- WHERE table_name = 'users' AND column_name = 'auth_uid';
