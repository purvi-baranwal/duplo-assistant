package assistant.service;

import assistant.mcp.McpActionDispatcher;
import assistant.model.ConversationHistory;
import assistant.model.ConversationTurn;
import assistant.repository.ConversationHistoryRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.model.chat.ChatLanguageModel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Slf4j
@Service
public class OldQueryExecutionService {
    private final JdbcTemplate jdbcTemplate;
    private final ChatLanguageModel chatModel;
//    private final WebClient webClient;
    private final ConversationHistoryRepository conversationHistoryRepository;
    private final ObjectMapper objectMapper;
    private final SchemaService schemaService;
    private final EmbeddingService embeddingService;
    private final RAGService ragService;
    private final McpActionDispatcher mcpActionDispatcher;

    @Value("${llm.api.base-url}")
    private String llmApiBaseUrl;

    @Value("${llm.model.name}")
    private String llmModelName;

    public OldQueryExecutionService(JdbcTemplate jdbcTemplate,
                                    @Qualifier("sqlOptimizedModel") ChatLanguageModel chatModel,
//                                 WebClient webClient,
                                    ConversationHistoryRepository conversationHistoryRepository,
                                    SchemaService schemaService,
                                    EmbeddingService embeddingService,
                                    RAGService ragService,
                                    McpActionDispatcher mcpActionDispatcher) {
        this.jdbcTemplate = jdbcTemplate;
        this.chatModel = chatModel;
//        this.webClient = webClient;
        this.conversationHistoryRepository = conversationHistoryRepository;
        this.objectMapper = new ObjectMapper();
        this.schemaService = schemaService;
        this.embeddingService = embeddingService;
        this.ragService = ragService;
        this.mcpActionDispatcher = mcpActionDispatcher;
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
//        String databaseSchema = schemaService.getDatabaseSchemaAsPrompt(); // Dynamic schema
        List<String> relevantRAGChunks = ragService.retrieveRelevantContext(userQuery, previousContext);
        String ragContext = String.join("\n", relevantRAGChunks);

        // retrieve relevant schema for the RAG context
        String databaseSchema = schemaService.getRelevantSchemaFromContext(ragContext);

        //Construct LLM Prompt
        String prompt = buildLlmPrompt(userQuery, databaseSchema, ragContext, previousContext, conversationId);
        log.info("Prompt: {}", prompt);

        //Call LLM to generate SQL
        String llmResponse = chatModel.generate(prompt);
        log.info("LLM Response - llmResponse: {}", llmResponse);

        try {
            JsonNode node = objectMapper.readTree(llmResponse);
            if (node.has("action")) {
                String action = node.get("action").asText();
                Map<String, Object> params = objectMapper.convertValue(node.get("params"), Map.class);
                log.info("Detected action: {}, with params: {}", action, params);
                Object mcpResult = mcpActionDispatcher.dispatch(action, params);
                return objectMapper.writeValueAsString(mcpResult);
            }
        } catch (Exception e) {
            // Not a JSON action, treat as direct answer
            log.info("LLM response is not an action, treating as direct answer");
        }

        //Extract JSON from LLM response
        String rawDbResultJson;
        String llmFormattedResponse;
        try {
            llmResponse = extractCodeBlockFromResponse(llmResponse);
            log.info("Extracted code from LLM response: {}", llmResponse);
            //Execute SQL & Process Results
            rawDbResultJson = executeSqlAndFormatResults(llmResponse, conversationId);
            log.info("Raw DB Result: {}", rawDbResultJson);

            //Call LLM again to format results for output
            llmFormattedResponse = callLlmForFormatting(userQuery, databaseSchema, ragContext, previousContext, llmResponse, rawDbResultJson);
            log.info("LLM Formatted Response: {}", llmFormattedResponse);

        } catch (IllegalArgumentException e) {
            log.error("Error extracting code from LLM response: {}", e.getMessage());
            return "Error: " + e.getMessage();
        } catch (Exception e) {
            // Fallback: call MCP action if SQL execution fails
            log.info("Error executing SQL: {}, falling back to MCP action", e.getMessage());
            try {
                Map<String, Object> params = Map.of(
                        "userQuery", userQuery,
                        "conversationId", conversationId
                );
                Object mcpResult = mcpActionDispatcher.dispatch("execute_query", params);
                return objectMapper.writeValueAsString(mcpResult);
            } catch (Exception mcpEx) {
                return "Error in fallback: " + mcpEx.getMessage();
            }
        }

        //save conversation history to mongo
        ConversationTurn currentTurn = new ConversationTurn();
        currentTurn.setTurn(history.getHistory().size() + 1);
        currentTurn.setUserQuery(userQuery);
        currentTurn.setGeneratedSql(llmResponse);
        currentTurn.setRawDbResult(rawDbResultJson);
        currentTurn.setLlmFormattedResponse(llmFormattedResponse);
        currentTurn.setTimestamp(Instant.now());
        // For follow-up queries, you might want to summarize the rawDbResult and store it in contextFromPreviousTurn
        currentTurn.setContextFromPreviousTurn(summarizeResultForContext(rawDbResultJson));

        history.getHistory().add(currentTurn);
        conversationHistoryRepository.save(history);

        return llmFormattedResponse;
    }

    private String buildLlmPrompt(String userQuery, String schema, String ragContext, String previousContext, String conversationId) {
        // This prompt engineering is crucial. Be very specific.
        // You can add more examples here to improve accuracy.
        // -- {table_name}_aud refers to audit table for {table_name}. Use this only for auditing changes in the {table_name} table.
        String conversationHistorySection = (previousContext != null && !previousContext.isEmpty())
                ? String.format("CONVERSATION HISTORY:\n%s\n", previousContext)
                : "";

//        return String.format(
//                """
//                You are a SQL query generator for a PostgreSQL database.
//                Your task is to convert natural language questions into accurate SQL queries.
//
//                Relationships reference:
//                -- paid, ibms-media-id, scrid, property-id refers to asset_metadata_type.code which is work_order.search_identifier_id
//                -- The work_order table stores details about work_order_status, due_date, created_by, tasks, assignee and priority
//                -- work_order.work_order_status_id refers to work_order_status.work_order_status_id
//                -- Use the display_name field for a human-readable status
//                -- work_order.asset_id refers to asset.asset_id
//                -- task_exec.work_order_id refers to work_order.work_order_id
//
//                DATABASE SCHEMA:
//                %s
//
//                RAG CONTEXT:
//                %s
//
//                %s
//                User Query: %s
//                Generate SQL following these rules:
//                1. Use EXACT table/column names from schema
//                2. Use only the requested column(s) in SELECT
//                3. Use LEFT JOINs if necessary, to join with related tables to extract display_name
//                4. Don't select ids unless explicitly asked
//                5. Consider previous questions and answers
//                6. For follow-ups, maintain consistency
//                7. Include LIMIT 100 unless specified
//                8. Generate a JSON response with:
//                   {
//                      "sql": "SELECT...", // only the executable SQL query"
//                      "explanation": "..." // optional
//                   }
//                """,
//                schema, ragContext, conversationHistorySection, userQuery
//        );
        return String.format(
                """
               You are an intelligent assistant for a PostgreSQL database with access to the following tools (MCP actions):
                        
               Available actions:
               - validate_query: Validate a SQL query for safety and correctness.
               - explain_query: Get the execution plan for a SQL query.
               - execute_query: Execute a SQL query and return results.
               - summarize_results: Summarize a large result set.

               Instructions:
               - If a user asks for a query to be run, first use validate_query.
               - If validation passes, use explain_query to show the plan.
               - If the user confirms or requests execution, use execute_query.
               - If the result set is large or the user asks for a summary, use summarize_results.
               - Always respond with a JSON object for actions, e.g.:
                 {
                   "action": "validate_query",
                   "params": {
                     "sql": "<SQL statement>"
                   }
                 }
                - Do not return SQL directly or outside JSON.
    
                DATABASE SCHEMA:
                %s
    
                RAG CONTEXT:
                %s
    
                %s
                User Query: %s
    
                Example (correct):
                User: What is the status of workorder wo-123.
                Response:
                {
                  "action": "execute_query",
                  "params": {
                    "userQuery": "What is the status of workorder wo-123.",
                    "conversationId": %s
                  }
                }
                
                Example (incorrect, do NOT do this):
                SELECT * FROM work_order WHERE status = 'open';
                """,
                schema, ragContext, conversationHistorySection, userQuery, conversationId
        );
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

    public String executeSqlAndFormatResults(String sql, String conversationId) {
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
            return String.format(
                    "Query failed! Error: Invalid SQL query: %s. Please provide more context or clarify your request. (conversationId: %s)",
                    e.getMessage(), conversationId
            );
        } catch (Exception e) {
            // Log the error
            e.printStackTrace();
            return String.format(
                    "Query failed! Error executing SQL: %s. Please provide more context or clarify your request. (conversationId: %s)",
                    e.getMessage(), conversationId
            );
        }
    }

    private String callLlmForFormatting(String userQuery, String databaseSchema, String ragContext,
                                        String previousContext, String generatedSql, String rawDbResultJson) {
        // This prompt instructs the LLM to format the data for the user.
        String formattingPrompt = String.format(
                """
                The user asked: "%s"
                The SQL executed was:
                %s
                The raw data from the database is:
                %s

                Please summarize and present the data clearly and concisely, directly answering the user's original question.
                Extract only the requested details from the raw data.
                Don't include any SQL code in the response.
                Don't respond outside the scope of raw data provided.
                For work order(s), include their search_identifier value, status and assignee display name.
                For tasks, include task name, status, and calculate 'days not progressed' from 'last_status_update' to current date.
                """,
//                userQuery, generatedSql, rawDbResultJson, databaseSchema, ragContext, previousContext);
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

    private String extractCodeBlockFromResponse(String llmResponse) {
        // Try to directly find a SELECT statement anywhere in the response
        Pattern selectPattern = Pattern.compile("(?im)^\\s*SELECT[\\s\\S]*?(;|$)");
        Matcher matcher = selectPattern.matcher(llmResponse);
        if (matcher.find()) {
            return matcher.group().trim();
        }

        // If not found, try to extract from code blocks
        String[] codeBlocks = llmResponse.split("```");
        if (codeBlocks.length >= 2) {
            String blockContent = codeBlocks[1].replaceFirst("(?i)json\\s*", "").trim();
            matcher = selectPattern.matcher(blockContent);
            if (matcher.find()) {
                return matcher.group().trim();
            }
        }

        // If still not found, try to extract JSON and get the "sql" field
        Pattern jsonPattern = Pattern.compile("\\{(?:[^{}]|\\{[^{}]*\\})*\\}");
        Matcher jsonMatcher = jsonPattern.matcher(llmResponse);
        if (jsonMatcher.find()) {
            String json = jsonMatcher.group();
            try {
                JsonNode node = new ObjectMapper().readTree(json);
                for (Iterator<String> it = node.fieldNames(); it.hasNext(); ) {
                    String field = it.next();
                    if (field.equalsIgnoreCase("sql")) {
                        return node.get(field).asText().trim();
                    }
                }
            } catch (Exception e) {
                throw new IllegalArgumentException("Found JSON but failed to parse: " + e.getMessage());
            }
        }
        throw new IllegalArgumentException("No SQL query found in LLM response");
    }

//    private String extractJsonFromResponse(String llmResponse) {
//        // Pattern to match JSON objects (including those with nested structures)
//        Pattern jsonPattern = Pattern.compile("\\{(?:[^{}]|\\{(?:[^{}]|\\{[^{}]*\\})*\\})*\\}");
//        Matcher matcher = jsonPattern.matcher(llmResponse);
//
//        if (matcher.find()) {
//            String potentialJson = matcher.group(0);
//
//            // Validate it's properly formatted JSON
//            if (isValidJson(potentialJson)) {
//                return potentialJson;
//            }
//        }
//
//        // Try extracting from markdown code blocks
//        String[] codeBlocks = llmResponse.split("```");
//        if (codeBlocks.length >= 2) {
//            for (int i = 1; i < codeBlocks.length; i += 2) {
//                String blockContent = codeBlocks[i].replaceFirst("(?i)json\\s*", "").trim();
//                if (isValidJson(blockContent)) {
//                    return blockContent;
//                }
//            }
//        }
//
//        throw new IllegalArgumentException("No valid JSON found in LLM response");
//    }

    private boolean isValidJson(String json) {
        try {
            new ObjectMapper().readTree(json);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public String validateUserRequest(String userQuery) {
        // Implement logic to check if userQuery is relevant (e.g., not chit-chat, not off-topic)
        if (userQuery == null || userQuery.trim().isEmpty()) {
            return "Invalid: Query is empty.";
        }
        // Add more checks as needed
        return "Valid";
    }

    public String generateSql(String userQuery, String failureReason) {
        // Call LLM with userQuery and optional failureReason for context
        // For now, just return a stub SQL
        if (failureReason != null) {
            // Use failureReason to improve SQL generation
        }
        return "SELECT * FROM some_table WHERE ..."; // Replace with actual LLM call
    }

    // Validates SQL for safety (stub: checks for forbidden keywords)
    public String validateQuery(String sql) {
        String lower = sql.toLowerCase();
        if (lower.contains("drop ") || lower.contains("delete ") || lower.contains("truncate ")) {
            return "Query contains potentially dangerous operations.";
        }
        return "Query is valid.";
    }

    // Summarizes a list of result rows (stub: returns row count)
    public String summarizeResults(List<Map<String, Object>> results) {
        if (results == null) return "No results.";
        return "Total rows: " + results.size();
    }

    // Returns the query plan for a SQL statement
    public String explainQuery(String sql) {
        try {
            String explainSql = "EXPLAIN " + sql;
            StringBuilder sb = new StringBuilder();
            jdbcTemplate.query(explainSql, rs -> {
                sb.append(rs.getString(1)).append("\n");
            });
            return sb.toString();
        } catch (Exception e) {
            throw new RuntimeException("Failed to explain query: " + e.getMessage(), e);
        }
    }

    // Converts a user query to SQL (stub: just returns the input for now)
    public String parseToSql(String userQuery) {
        //Implement actual parsing logic or LLM call
        return userQuery;
    }
}
