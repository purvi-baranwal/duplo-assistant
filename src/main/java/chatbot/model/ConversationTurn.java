package chatbot.model;

import lombok.Data;

import java.time.Instant;

@Data
public class ConversationTurn {
    private int turn;
    private String userQuery;
    private String generatedSql; // Can be a list if multiple queries
    private String rawDbResult; // Store as JSON string or similar
    private String llmFormattedResponse;
    private Instant timestamp;
    private String contextFromPreviousTurn; // Relevant data/summaries from previous turns

    // Getters and Setters
    public int getTurn() { return turn; }
    public void setTurn(int turn) { this.turn = turn; }
    public String getUserQuery() { return userQuery; }
    public void setUserQuery(String userQuery) { this.userQuery = userQuery; }
    public String getGeneratedSql() { return generatedSql; }
    public void setGeneratedSql(String generatedSql) { this.generatedSql = generatedSql; }
    public String getRawDbResult() { return rawDbResult; }
    public void setRawDbResult(String rawDbResult) { this.rawDbResult = rawDbResult; }
    public String getLlmFormattedResponse() { return llmFormattedResponse; }
    public void setLlmFormattedResponse(String llmFormattedResponse) { this.llmFormattedResponse = llmFormattedResponse; }
    public Instant getTimestamp() { return timestamp; }
    public void setTimestamp(Instant timestamp) { this.timestamp = timestamp; }
    public String getContextFromPreviousTurn() { return contextFromPreviousTurn; }
    public void setContextFromPreviousTurn(String contextFromPreviousTurn) { this.contextFromPreviousTurn = contextFromPreviousTurn; }
}
