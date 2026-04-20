-- Chat Service Database Setup Script
-- Run this to create the database and tables for the chat microservice

-- Create database (run as postgres superuser)
CREATE DATABASE rentoza_chat;

-- Connect to the database
\c rentoza_chat;

-- Create conversations table
CREATE TABLE conversations (
    id BIGSERIAL PRIMARY KEY,
    booking_id BIGINT NOT NULL UNIQUE,
    renter_id BIGINT NOT NULL,
    owner_id BIGINT NOT NULL,
    status VARCHAR(20) NOT NULL CHECK (status IN ('PENDING', 'ACTIVE', 'CLOSED')),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- Create messages table
CREATE TABLE messages (
    id BIGSERIAL PRIMARY KEY,
    conversation_id BIGINT NOT NULL REFERENCES conversations(id) ON DELETE CASCADE,
    sender_id BIGINT NOT NULL,
    content TEXT NOT NULL CHECK (char_length(content) <= 2000),
    timestamp TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    media_url VARCHAR(500),
    CONSTRAINT fk_conversation FOREIGN KEY (conversation_id) REFERENCES conversations(id)
);

-- Create read_by junction table for tracking message read status
CREATE TABLE message_read_by (
    message_id BIGINT NOT NULL REFERENCES messages(id) ON DELETE CASCADE,
    user_id BIGINT NOT NULL,
    read_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (message_id, user_id)
);

-- Create indexes for better query performance
CREATE INDEX idx_conversations_booking_id ON conversations(booking_id);
CREATE INDEX idx_conversations_renter_id ON conversations(renter_id);
CREATE INDEX idx_conversations_owner_id ON conversations(owner_id);
CREATE INDEX idx_conversations_status ON conversations(status);

CREATE INDEX idx_messages_conversation_id ON messages(conversation_id);
CREATE INDEX idx_messages_sender_id ON messages(sender_id);
CREATE INDEX idx_messages_timestamp ON messages(timestamp DESC);

CREATE INDEX idx_message_read_by_message_id ON message_read_by(message_id);
CREATE INDEX idx_message_read_by_user_id ON message_read_by(user_id);

-- Create trigger to auto-update updated_at timestamp
CREATE OR REPLACE FUNCTION update_updated_at_column()
RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = CURRENT_TIMESTAMP;
    RETURN NEW;
END;
$$ language 'plpgsql';

CREATE TRIGGER update_conversations_updated_at 
    BEFORE UPDATE ON conversations 
    FOR EACH ROW 
    EXECUTE FUNCTION update_updated_at_column();

-- Grant privileges (adjust username as needed)
-- GRANT ALL PRIVILEGES ON DATABASE rentoza_chat TO your_app_user;
-- GRANT ALL PRIVILEGES ON ALL TABLES IN SCHEMA public TO your_app_user;
-- GRANT ALL PRIVILEGES ON ALL SEQUENCES IN SCHEMA public TO your_app_user;

-- Insert test data (optional)
-- INSERT INTO conversations (booking_id, renter_id, owner_id, status) 
-- VALUES (1, 100, 200, 'ACTIVE');

-- INSERT INTO messages (conversation_id, sender_id, content) 
-- VALUES (1, 100, 'Hi! Is the car still available?');

-- INSERT INTO messages (conversation_id, sender_id, content) 
-- VALUES (1, 200, 'Yes, it is! When do you need it?');

COMMENT ON TABLE conversations IS 'Stores conversation metadata between renters and car owners';
COMMENT ON TABLE messages IS 'Stores individual messages within conversations';
COMMENT ON TABLE message_read_by IS 'Tracks which users have read which messages';

COMMENT ON COLUMN conversations.booking_id IS 'Unique reference to the booking in the main service';
COMMENT ON COLUMN conversations.status IS 'PENDING: booking requested, ACTIVE: booking confirmed, CLOSED: trip completed';
COMMENT ON COLUMN messages.content IS 'Message text content, max 2000 characters';
COMMENT ON COLUMN messages.media_url IS 'Optional URL to attached media (images, documents)';
