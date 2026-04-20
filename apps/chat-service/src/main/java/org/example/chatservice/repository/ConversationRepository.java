package org.example.chatservice.repository;

import org.example.chatservice.model.Conversation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ConversationRepository extends JpaRepository<Conversation, Long> {

    Optional<Conversation> findByBookingId(Long bookingId);

    @Query("SELECT c FROM Conversation c WHERE c.renterId = :userId OR c.ownerId = :userId ORDER BY c.lastMessageAt DESC NULLS LAST, c.createdAt DESC")
    List<Conversation> findByParticipant(@Param("userId") Long userId);

    boolean existsByBookingId(Long bookingId);
}
