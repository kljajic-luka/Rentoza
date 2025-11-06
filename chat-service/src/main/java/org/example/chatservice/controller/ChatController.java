package org.example.chatservice.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.chatservice.dto.*;
import org.example.chatservice.model.ConversationStatus;
import org.example.chatservice.service.ChatService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
@Slf4j
public class ChatController {

    private final ChatService chatService;

    @PostMapping("/conversations")
    public ResponseEntity<ConversationDTO> createConversation(
            @Valid @RequestBody CreateConversationRequest request,
            Authentication authentication
    ) {
        String userId = authentication.getName();
        log.info("Creating conversation for booking {} by user {}", request.getBookingId(), userId);
        
        ConversationDTO conversation = chatService.createConversation(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(conversation);
    }

    @GetMapping("/conversations/{bookingId}")
    public ResponseEntity<ConversationDTO> getConversation(
            @PathVariable String bookingId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size,
            Authentication authentication
    ) {
        String userId = authentication.getName();
        log.info("Getting conversation for booking {} by user {}", bookingId, userId);
        
        ConversationDTO conversation = chatService.getConversation(bookingId, userId, page, size);
        return ResponseEntity.ok(conversation);
    }

    @PostMapping("/conversations/{bookingId}/messages")
    public ResponseEntity<MessageDTO> sendMessage(
            @PathVariable String bookingId,
            @Valid @RequestBody SendMessageRequest request,
            Authentication authentication
    ) {
        String userId = authentication.getName();
        log.info("Sending message in conversation for booking {} by user {}", bookingId, userId);
        
        MessageDTO message = chatService.sendMessage(bookingId, userId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(message);
    }

    @PutMapping("/conversations/{bookingId}/read")
    public ResponseEntity<Void> markAsRead(
            @PathVariable String bookingId,
            Authentication authentication
    ) {
        String userId = authentication.getName();
        log.info("Marking messages as read in conversation for booking {} by user {}", bookingId, userId);
        
        chatService.markMessagesAsRead(bookingId, userId);
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/conversations/{bookingId}/status")
    public ResponseEntity<Void> updateConversationStatus(
            @PathVariable String bookingId,
            @RequestParam ConversationStatus status
    ) {
        log.info("Updating conversation status for booking {} to {}", bookingId, status);
        
        chatService.updateConversationStatus(bookingId, status);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/conversations")
    public ResponseEntity<List<ConversationDTO>> getUserConversations(
            Authentication authentication
    ) {
        String userId = authentication.getName();
        log.info("Getting all conversations for user {}", userId);
        
        List<ConversationDTO> conversations = chatService.getUserConversations(userId);
        return ResponseEntity.ok(conversations);
    }

    @GetMapping("/health")
    public ResponseEntity<String> health() {
        return ResponseEntity.ok("Chat service is running");
    }
}
