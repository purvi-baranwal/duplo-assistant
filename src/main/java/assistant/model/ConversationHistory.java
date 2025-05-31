package assistant.model;

import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Data
@Document(collection = "conversation_history")
public class ConversationHistory {
    @Id
    private String id;
    private String conversationId; // Unique ID for a conversation session
    private String userId; // Optional, to link conversations to users
    private Instant createdAt;
    private List<ConversationTurn> history = new ArrayList<>();

    // Getters and Setters
    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getConversationId() { return conversationId; }
    public void setConversationId(String conversationId) { this.conversationId = conversationId; }
    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public List<ConversationTurn> getHistory() { return history; }
    public void setHistory(List<ConversationTurn> history) { this.history = history; }
}

