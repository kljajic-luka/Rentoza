# Chat Microservice Summary

## Overview
Transaction-gated messaging system for Rentoza car rental platform, modeled after Turo's chat functionality. Communication between renter and owner is locked until a booking request is made.

## Architecture
- **Framework**: Spring Boot 3.2.0
- **Database**: PostgreSQL (separate database: `rentoza_chat`)
- **Real-time**: WebSocket (STOMP) + HTTP polling fallback
- **Security**: JWT-based authentication (shared secret with main service)
- **Port**: 8081 (main service on 8080)

## Quick Start

### 1. Database Setup
```bash
# Run the SQL script to create database and tables
psql -U postgres -f database-setup.sql
```

### 2. Configure Application
Edit `src/main/resources/application.properties`:
- Set database credentials
- Ensure JWT secret matches main service
- Verify CORS origins

### 3. Run Chat Service
```bash
cd chat-service
mvn clean install
mvn spring-boot:run
```

Service will start on `http://localhost:8081`

### 4. Frontend Integration
See `/rentoza-frontend/CHAT_INTEGRATION_GUIDE.md` for detailed integration steps.

## Chat Lifecycle States

### 1. Pre-Booking
- **Status**: No chat access
- **Behavior**: Chat interface hidden or disabled

### 2. Booking Request Sent
- **Status**: `PENDING`
- **Behavior**: Chat thread created, messaging available
- **Features**: Renter can include initial message with booking request

### 3. Booking Confirmed
- **Status**: `ACTIVE`
- **Behavior**: Full real-time messaging enabled for both parties

### 4. Trip Completed/Canceled
- **Status**: `CLOSED`
- **Behavior**: Chat becomes read-only, messages remain visible

## Core Entities

### Conversation
```java
- id: Long
- bookingId: String (unique)
- renterId: String
- ownerId: String
- status: ConversationStatus (PENDING, ACTIVE, CLOSED)
- createdAt: LocalDateTime
- lastMessageAt: LocalDateTime
```

### Message
```java
- id: Long
- conversationId: Long
- senderId: String
- content: String (max 2000 chars)
- timestamp: LocalDateTime
- readBy: Set<String>
- mediaUrl: String (optional)
```

## REST API Endpoints

### Create Conversation
```
POST /api/conversations
Authorization: Bearer <JWT>
Body: {
  "bookingId": "string",
  "renterId": "string",
  "ownerId": "string",
  "initialMessage": "string (optional)"
}
Response: 201 Created + ConversationDTO
```

### Get Conversation with Messages
```
GET /api/conversations/{bookingId}?page=0&size=50
Authorization: Bearer <JWT>
Response: 200 OK + ConversationDTO (with messages)
```

### Send Message
```
POST /api/conversations/{bookingId}/messages
Authorization: Bearer <JWT>
Body: {
  "content": "string",
  "mediaUrl": "string (optional)"
}
Response: 201 Created + MessageDTO
```

### Mark Messages as Read
```
PUT /api/conversations/{bookingId}/read
Authorization: Bearer <JWT>
Response: 204 No Content
```

### Update Conversation Status
```
PUT /api/conversations/{bookingId}/status?status=ACTIVE
Authorization: Bearer <JWT>
Response: 204 No Content
```

### Get User's Conversations
```
GET /api/conversations
Authorization: Bearer <JWT>
Response: 200 OK + List<ConversationDTO>
```

## WebSocket Configuration

### Connection Endpoint
```
ws://localhost:8081/ws
```

### Subscribe to Conversation
```
/topic/conversation/{bookingId}
```

### Subscribe to Status Updates
```
/topic/conversation/{bookingId}/status
```

### Message Format
Real-time messages are automatically broadcast to subscribed clients when sent via REST API.

## Security & Authorization

### JWT Validation
- All endpoints (except `/ws/**` and `/api/health`) require valid JWT token
- Token must be in `Authorization: Bearer <token>` header
- JWT secret must match main Rentoza service

### Access Control
- Only conversation participants (renter or owner) can:
  - View conversation and messages
  - Send messages
  - Mark messages as read
- Messaging only allowed in `PENDING` and `ACTIVE` states

## Setup Instructions

### 1. Database Setup
```sql
CREATE DATABASE rentoza_chat;
```

### 2. Configure JWT Secret
Update `application.properties`:
```properties
jwt.secret=<same-secret-as-main-service>
```

### 3. Build and Run
```bash
cd chat-service
mvn clean install
mvn spring-boot:run
```

### 4. Verify Service
```bash
curl http://localhost:8081/api/health
```

## Integration with Main Service

### Booking Event Handling
When booking status changes in main service, call chat service:

```java
// On booking request created
POST http://localhost:8081/api/conversations
{
  "bookingId": "booking-123",
  "renterId": "user-456",
  "ownerId": "user-789",
  "initialMessage": "Hi, I'd like to rent your car..."
}

// On booking confirmed
PUT http://localhost:8081/api/conversations/booking-123/status?status=ACTIVE

// On booking completed/canceled
PUT http://localhost:8081/api/conversations/booking-123/status?status=CLOSED
```

### JWT Token Sharing
Ensure both services use the same JWT secret for seamless authentication.

## Frontend Integration Points

### Angular Service Methods Needed
```typescript
- createConversation(bookingId, renterId, ownerId, initialMessage?)
- getConversation(bookingId, page?, size?)
- sendMessage(bookingId, content, mediaUrl?)
- markAsRead(bookingId)
- getUserConversations()
- connectWebSocket(bookingId)
- subscribeToMessages(bookingId, callback)
```

### WebSocket Client Setup
```typescript
import * as SockJS from 'sockjs-client';
import * as Stomp from '@stomp/stompjs';

const socket = new SockJS('http://localhost:8081/ws');
const stompClient = Stomp.over(socket);

stompClient.connect({}, () => {
  stompClient.subscribe('/topic/conversation/' + bookingId, (message) => {
    // Handle new message
  });
});
```

## Error Responses

### 404 Not Found
```json
{
  "timestamp": "2025-11-06T...",
  "status": 404,
  "error": "Not Found",
  "message": "Conversation not found for booking: xxx"
}
```

### 403 Forbidden
```json
{
  "timestamp": "2025-11-06T...",
  "status": 403,
  "error": "Forbidden",
  "message": "You are not a participant in this conversation"
}
```

### 400 Bad Request
```json
{
  "timestamp": "2025-11-06T...",
  "status": 400,
  "error": "Bad Request",
  "message": "Messaging is not allowed in this conversation state"
}
```

## Pagination
Messages are paginated (default 50 per page) and returned in reverse chronological order (newest first in DB, reversed for display).

## Message Read Receipts
- Sender automatically marks their own messages as read
- `readBy` field contains set of user IDs who have read the message
- Use `markAsRead` endpoint to mark all messages as read

## Performance Considerations
- Database indexes on `bookingId`, `conversationId`
- Message pagination prevents loading all messages at once
- WebSocket for real-time updates reduces polling overhead
- Read receipts batch-updated (up to 100 messages per request)

## Future Enhancements (Not in MVP)
- ✅ File/photo uploads (mediaUrl field ready)
- ✅ Push notifications integration
- ✅ Message search functionality
- ✅ Admin moderation access
- ✅ Message reactions
- ✅ Typing indicators
- ✅ Voice messages

## Monitoring & Logging
- All operations logged with user ID and booking ID
- Errors logged with full stack traces
- Debug level logging for development
- WebSocket connection tracking

## Docker Deployment
```dockerfile
FROM openjdk:17-jdk-slim
COPY target/chat-service-1.0.0.jar app.jar
ENTRYPOINT ["java","-jar","/app.jar"]
```

```yaml
# docker-compose.yml addition
chat-service:
  build: ./chat-service
  ports:
    - "8081:8081"
  environment:
    - SPRING_DATASOURCE_URL=jdbc:postgresql://db:5432/rentoza_chat
    - JWT_SECRET=${JWT_SECRET}
  depends_on:
    - db
```

## Testing Endpoints

### Create Test Conversation
```bash
curl -X POST http://localhost:8081/api/conversations \
  -H "Authorization: Bearer <JWT>" \
  -H "Content-Type: application/json" \
  -d '{
    "bookingId": "test-booking-1",
    "renterId": "user-1",
    "ownerId": "user-2",
    "initialMessage": "Hi, interested in renting!"
  }'
```

### Send Test Message
```bash
curl -X POST http://localhost:8081/api/conversations/test-booking-1/messages \
  -H "Authorization: Bearer <JWT>" \
  -H "Content-Type: application/json" \
  -d '{
    "content": "When is the car available?"
  }'
```

---

**Note**: This is a minimal, production-ready implementation. All core functionality is complete. Focus on integration and deployment next.
