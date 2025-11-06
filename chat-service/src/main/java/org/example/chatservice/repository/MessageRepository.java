package org.example.chatservice.repository;

import org.example.chatservice.model.Message;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface MessageRepository extends JpaRepository<Message, Long> {

    Page<Message> findByConversationIdOrderByTimestampDesc(Long conversationId, Pageable pageable);

    @Query("SELECT COUNT(m) FROM Message m WHERE m.conversationId = :conversationId " +
           "AND m.senderId != :userId AND :userId NOT MEMBER OF m.readBy")
    long countUnreadMessages(@Param("conversationId") Long conversationId, @Param("userId") String userId);
}
