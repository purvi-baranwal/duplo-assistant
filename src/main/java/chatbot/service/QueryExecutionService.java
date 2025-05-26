package chatbot.service;

import chatbot.model.ConversationHistory;
import chatbot.model.ConversationTurn;
import chatbot.repository.ConversationHistoryRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.model.chat.ChatLanguageModel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Service
public class QueryExecutionService {
    private final JdbcTemplate jdbcTemplate;
    private final ChatLanguageModel chatModel;
//    private final WebClient webClient;
    private final ConversationHistoryRepository conversationHistoryRepository;
    private final ObjectMapper objectMapper;
    private final SchemaService schemaService;
    private final EmbeddingService embeddingService;
    private final RAGService ragService;

    @Value("${llm.api.base-url}")
    private String llmApiBaseUrl;

    @Value("${llm.model.name}")
    private String llmModelName;

    public QueryExecutionService(JdbcTemplate jdbcTemplate,
                                 @Qualifier("sqlOptimizedModel") ChatLanguageModel chatModel,
//                                 WebClient webClient,
                                 ConversationHistoryRepository conversationHistoryRepository,
                                 SchemaService schemaService,
                                 EmbeddingService embeddingService,
                                 RAGService ragService) {
        this.jdbcTemplate = jdbcTemplate;
        this.chatModel = chatModel;
//        this.webClient = webClient;
        this.conversationHistoryRepository = conversationHistoryRepository;
        this.objectMapper = new ObjectMapper();
        this.schemaService = schemaService;
        this.embeddingService = embeddingService;
        this.ragService = ragService;
    }

    public String executeTestQuery(String workOrderId) {
        // test query execution
        UUID workOrderUUID = UUID.fromString(workOrderId);
        String sql = "select wos.display_name from work_order wo \n" +
                "left join work_order_status wos on wos.work_order_status_id = wo.work_order_status_id \n" +
                "where wo.work_order_id = ?";
        return jdbcTemplate.queryForObject(sql, new Object[]{workOrderUUID}, String.class);
    }

    public String processNaturalLanguageQuery(String userQuery, String conversationId) {
        //get conversation history
        ConversationHistory history = conversationHistoryRepository.findByConversationId(conversationId)
                .orElseGet(() -> {
                    ConversationHistory newHistory = new ConversationHistory();
                    newHistory.setConversationId(conversationId);
                    newHistory.setCreatedAt(Instant.now());
                    newHistory.setUserId("anonymous"); // Or fetch actual user ID
                    return newHistory;
                });

        //get relevant previous context for follow-up queries
        String previousContext = history.getHistory().stream()
                .map(turn -> "User: " + turn.getUserQuery() + "\nAI: " + turn.getLlmFormattedResponse())
                .collect(Collectors.joining("\n"));

        //RAG: Retrieve context (schema, examples)
        String databaseSchema = schemaService.getDatabaseSchemaAsPrompt(); // Dynamic schema
        List<String> relevantRAGChunks = ragService.retrieveRelevantContext(userQuery, previousContext);
        String ragContext = String.join("\n", relevantRAGChunks);

        //Construct LLM Prompt
        String prompt = buildLlmPrompt(userQuery, databaseSchema, ragContext, previousContext);
        log.info("Prompt: {}", prompt);

        //Call LLM to generate SQL
        String generatedSql = chatModel.generate(prompt);
        log.info("LLM Response - generatedSql: {}", generatedSql);

        //Execute SQL & Process Results
        String rawDbResultJson = executeSqlAndFormatResults(generatedSql);
        log.info("Raw DB Result: {}", rawDbResultJson);

        //Call LLM again to format results for output
        String llmFormattedResponse = callLlmForFormatting(userQuery, generatedSql, rawDbResultJson);
        log.info("LLM Formatted Response: {}", llmFormattedResponse);

        //save conversation history to mongo
        ConversationTurn currentTurn = new ConversationTurn();
        currentTurn.setTurn(history.getHistory().size() + 1);
        currentTurn.setUserQuery(userQuery);
        currentTurn.setGeneratedSql(generatedSql);
        currentTurn.setRawDbResult(rawDbResultJson);
        currentTurn.setLlmFormattedResponse(llmFormattedResponse);
        currentTurn.setTimestamp(Instant.now());
        // For follow-up queries, you might want to summarize the rawDbResult and store it in contextFromPreviousTurn
        currentTurn.setContextFromPreviousTurn(summarizeResultForContext(rawDbResultJson));

        history.getHistory().add(currentTurn);
        conversationHistoryRepository.save(history);

        return llmFormattedResponse;
    }

    private String buildLlmPrompt(String userQuery, String schema, String ragContext, String previousContext) {
        // This prompt engineering is crucial. Be very specific.
        // You can add more examples here to improve accuracy.
        return String.format(
                """
                You are a SQL query generator for a PostgreSQL database.
                Your task is to convert natural language questions into accurate SQL queries.
                
                DATABASE SCHEMA:
                -- Relationships:
                -- work_order.asset_id refers to asset.asset_id
                -- task_exec.work_order_id refers to work_order.work_order_id

                %s
                
                RAG CONTEXT:
                %s

                CONVERSATION HISTORY:
                %s

                User Query: %s
                Generated SQL:
                """
                , schema, ragContext, previousContext, userQuery);
    }

//    private String callLlmForSqlGeneration(String prompt) {
//        // This uses WebClient for non-blocking HTTP calls to your self-hosted LLM API
//        // Adapt this to your specific LLM API's request/response format
//        Map<String, Object> requestBody = Map.of(
//                "model", llmModelName,
//                "prompt", prompt,
//                "stream", false // Set to true if you want streaming, but for SQL generation, false is fine
//        );
//
//        // Expecting a JSON response from Ollama (or similar) with the generated text
//        // Example Ollama response: {"model":"llama3","created_at":"...","response":"SELECT ...","done":true}
//        return webClient.post()
//                .uri("/generate")
//                .bodyValue(requestBody)
//                .retrieve()
//                .bodyToMono(Map.class) // Assuming a Map response
//                .map(response -> (String) response.get("response"))
//                .block(); // Block for simplicity, consider async handling in a real app
//    }

    private String executeSqlAndFormatResults(String sql) {
        if (sql == null || sql.trim().isEmpty()) {
            return "No SQL generated.";
        }

        List<Map<String, Object>> rows = new ArrayList<>();
        try {
            // Basic SQL validation (prevent DDL/DML, only allow SELECT)
            if (!sql.trim().toUpperCase().startsWith("SELECT")) {
                throw new IllegalArgumentException("Only SELECT queries are allowed.");
            }

            rows = jdbcTemplate.queryForList(sql);
            return objectMapper.writeValueAsString(rows); // Convert result to JSON string
        } catch (IllegalArgumentException e) {
            return "Error: Invalid SQL query. " + e.getMessage();
        } catch (Exception e) {
            // Log the error
            e.printStackTrace();
            return "Error executing SQL: " + e.getMessage();
        }
    }

    private String callLlmForFormatting(String userQuery, String generatedSql, String rawDbResultJson) {
        // This prompt instructs the LLM to format the data for the user.
        String formattingPrompt = String.format(
                """
                The user asked: "%s"
                The SQL executed was:
                %s
                The raw data from the database is:
                %s

                Please summarize and present the data clearly and concisely, directly answering the user's original question.
                For work orders, include their title, status, and assigned user.
                For tasks, include task name, status, and calculate 'days not progressed' from 'last_status_update' to current date.
                """,
                userQuery, generatedSql, rawDbResultJson);

//        Map<String, Object> requestBody = Map.of(
//                "model", llmModelName,
//                "prompt", formattingPrompt,
//                "stream", false
//        );
//
//        return webClient.post()
//                .uri("/generate")
//                .bodyValue(requestBody)
//                .retrieve()
//                .bodyToMono(Map.class)
//                .map(response -> (String) response.get("response"))
//                .block();
        log.info("Formatting Prompt: {}", formattingPrompt);
        return chatModel.generate(formattingPrompt); // Use the chat model to generate the formatted response
    }

    private String summarizeResultForContext(String rawDbResultJson) {
        // This method can be enhanced to create a more useful summary for follow-up queries.
        // For example, if it's a list of work orders, just list their IDs or titles.
        return "Previous result: " + rawDbResultJson.substring(0, Math.min(rawDbResultJson.length(), 200)) + "...";
    }
}
