package org.example.chatservice.exception;

public class MessagingNotAllowedException extends RuntimeException {
    public MessagingNotAllowedException(String message) {
        super(message);
    }
}
