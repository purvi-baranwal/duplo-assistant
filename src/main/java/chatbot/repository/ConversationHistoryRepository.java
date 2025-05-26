package chatbot.repository;

import chatbot.model.ConversationHistory;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface ConversationHistoryRepository extends MongoRepository<ConversationHistory, String> {
    Optional<ConversationHistory> findByConversationId(String conversationId);
}
