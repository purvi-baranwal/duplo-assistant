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
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Slf4j
@Service
public class QueryExecutionService {
    private final JdbcTemplate jdbcTemplate;
    private final ChatLanguageModel chatModel;
    private final ConversationHistoryRepository conversationHistoryRepository;
    private final ObjectMapper objectMapper;
    private final SchemaService schemaService;
    private final RAGService ragService;
    private final McpActionDispatcher mcpActionDispatcher;

    @Value("${llm.model.name}")
    private String llmModelName;

    public QueryExecutionService(JdbcTemplate jdbcTemplate,
                                 @Qualifier("sqlOptimizedModel") ChatLanguageModel chatModel,
                                 ConversationHistoryRepository conversationHistoryRepository,
                                 SchemaService schemaService,
                                 RAGService ragService,
                                 McpActionDispatcher mcpActionDispatcher) {
        this.jdbcTemplate = jdbcTemplate;
        this.chatModel = chatModel;
        this.conversationHistoryRepository = conversationHistoryRepository;
        this.objectMapper = new ObjectMapper();
        this.schemaService = schemaService;
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
        ConversationHistory history = conversationHistoryRepository.findByConversationId(conversationId)
                .orElseGet(() -> {
                    ConversationHistory newHistory = new ConversationHistory();
                    newHistory.setConversationId(conversationId);
                    newHistory.setCreatedAt(Instant.now());
                    newHistory.setUserId("anonymous");
                    return newHistory;
                });

        String previousContext = history.getHistory().stream()
                .map(turn -> "User: " + turn.getUserQuery() + "\nAI: " + turn.getLlmFormattedResponse())
                .collect(Collectors.joining("\n"));

        List<String> relevantRAGChunks = ragService.retrieveRelevantContext(userQuery, previousContext);
        String ragContext = String.join("\n", relevantRAGChunks);
        String databaseSchema = schemaService.getRelevantSchemaFromContext(ragContext);

        String prompt = buildLlmPrompt(userQuery, databaseSchema, ragContext, previousContext, conversationId);
        log.info("Generated LLM prompt: {}", prompt);
        String llmResponse = chatModel.generate(prompt);
        log.info("LLM response: {}", llmResponse);

        String finalResult = null;
        String lastAction = null;
        Map<String, Object> lastParams = null;

        try {
            while (true) {
                JsonNode node = objectMapper.readTree(llmResponse);
                if (!node.has("action")) {
                    finalResult = llmResponse;
                    break;
                }
                String action = node.get("action").asText();
                Map<String, Object> params = objectMapper.convertValue(node.get("params"), Map.class);
                log.info("LLM requested action: {}, params: {}", action, params);

                Object mcpResult = mcpActionDispatcher.dispatch(action, params);
                log.info("MCP action result: {}", mcpResult);

                // Save turn in history
                ConversationTurn turn = new ConversationTurn();
                turn.setTurn(history.getHistory().size() + 1);
                turn.setUserQuery(userQuery);
                turn.setLlmFormattedResponse(objectMapper.writeValueAsString(mcpResult));
                turn.setTimestamp(Instant.now());
                history.getHistory().add(turn);
                conversationHistoryRepository.save(history);

                // Terminal actions: if action is execute_query or summarize_results, return result
                if ("execute_query".equals(action) || "summarize_results".equals(action)) {
                    finalResult = objectMapper.writeValueAsString(mcpResult);
                    break;
                }

                // If action failed and LLM should retry (e.g., generate_sql after execute_query fails)
                if (mcpResult instanceof Map && ((Map<?, ?>) mcpResult).containsKey("error")) {
                    params.put("failureReason", ((Map<?, ?>) mcpResult).get("error"));
                }

                // Prepare next LLM prompt with the result of the last action
                String nextPrompt = buildFollowupPrompt(userQuery, databaseSchema, ragContext, previousContext, conversationId, action, mcpResult);
                log.info("Followup LLM prompt: {}", nextPrompt);
                llmResponse = chatModel.generate(nextPrompt);
                log.info("Followup LLM response: {}", llmResponse);

                lastAction = action;
                lastParams = params;
            }
        } catch (Exception e) {
            log.error("Error in LLM orchestration: {}", e.getMessage(), e);
            finalResult = "Error: " + e.getMessage();
        }

        return finalResult;
    }

    private String buildLlmPrompt(String userQuery, String schema, String ragContext, String previousContext, String conversationId) {
        String conversationHistorySection = (previousContext != null && !previousContext.isEmpty())
                ? String.format("CONVERSATION HISTORY:\n%s\n", previousContext)
                : "";
        return String.format(
                """
                You are an intelligent assistant for a PostgreSQL database with access to the following tools (MCP actions):

                Available actions:
                - validate_user_request: Check if the user query is actionable.
                - generate_sql: Generate a SQL statement for a valid user query.
                - check_query: Check the generated SQL query for safety and correctness.
                - execute_query: Execute a SQL query and return results.
                - explain_query: Get the execution plan for a SQL query.
                - summarize_results: Summarize a large result set.

                Instructions:
                - Always start with validate_user_request.
                - Respond only with one JSON object for action.
                - If valid, use generate_sql to create SQL.
                - Validate the generated SQL with check_query.
                - Then use execute_query to run the SQL.
                - If execute_query fails, use generate_sql again with the failure reason.
                - If the result is too large, use explain_query and summarize_results.
                - Do not wait for user confirmation.
                - Always respond with a JSON object for actions, e.g.:
                  {
                    "action": "validate_user_request",
                    "params": {
                      "userQuery": "<user query>"
                    }
                  }
                - Do not return SQL directly or outside JSON.

                DATABASE SCHEMA:
                %s

                RAG CONTEXT:
                %s

                %s
                User Query: %s
                
                conversationId: %s
                """,
                schema, ragContext, conversationHistorySection, userQuery, conversationId
        );
    }

    private String buildFollowupPrompt(String userQuery, String schema, String ragContext, String previousContext,
                                       String conversationId, String lastAction, Object lastResult) {
        String conversationHistorySection = (previousContext != null && !previousContext.isEmpty())
                ? String.format("CONVERSATION HISTORY:\n%s\n", previousContext)
                : "";
        return String.format(
                """
                You are an intelligent assistant for a PostgreSQL database with access to the following tools (MCP actions):
    
                Available actions:
                - validate_user_request: Check if the user query is actionable.
                - generate_sql: Generate a SQL statement for a valid user query.
                - check_query: Check the generated SQL query for safety and correctness.
                - execute_query: Execute a SQL query and return results.
                - explain_query: Get the execution plan for a SQL query.
                - summarize_results: Summarize a large result set.
    
                Instructions:
                - Always start with validate_user_request.
                - Respond only with one JSON object for action.
                - If valid, use generate_sql to create SQL.
                - Check the generated SQL with check_query.
                - Then use execute_query to run the SQL.
                - If execute_query fails, use generate_sql again with the failure reason.
                - If the result is too large, use explain_query and summarize_results.
                - Do not wait for user confirmation.
                - Always respond with a JSON object for actions.
                - Do not return SQL directly or outside JSON.
                - When building params for generate_sql, always copy the entire DATABASE SCHEMA and RAG CONTEXT sections exactly as provided above into the corresponding fields.
    
                JSON examples for each action:
                {
                  "action": "validate_user_request",
                  "params": {
                    "userQuery": "<user query>"
                  }
                }
                {
                  "action": "generate_sql",
                  "params": {
                    "userQuery": "<user query>",
                    "failureReason": "<error message or null>",
                    "databaseSchema": "<schema>",
                    "ragContext": "<rag context>",
                    "previousContext": "<conversation history>",
                    "conversationId": "<conversation id>"
                  }
                }
                {
                  "action": "check_query",
                  "params": {
                    "sql": "<sql statement>"
                  }
                }
                {
                  "action": "execute_query",
                  "params": {
                    "sql": "<sql statement>"
                  }
                }
                {
                  "action": "explain_query",
                  "params": {
                    "sql": "<sql statement>"
                  }
                }
                {
                  "action": "summarize_results",
                  "params": {
                    "results": "<result set>"
                  }
                }
    
                CONTEXT:
                Last action: %s
                Result: %s
    
                DATABASE SCHEMA:
                %s
    
                RAG CONTEXT:
                %s
    
                %s
                User Query: %s
    
                conversationId: %s
    
                Based on the above, provide the next MCP action as a JSON object.
                """,
                lastAction, lastResult, schema, ragContext, conversationHistorySection, userQuery, conversationId
        );
    }

    private String buildGenerateSqlLlmPrompt(String userQuery, String failureReason, String databaseSchema, String ragContext, String previousContext, String conversationId) {
        StringBuilder sb = new StringBuilder();
        sb.append("You are an expert SQL generator for a PostgreSQL database.\n");
        sb.append("Given the following context, generate a single, safe, executable SELECT SQL statement that answers the user's question.\n\n");
        sb.append("DATABASE SCHEMA:\n").append(databaseSchema).append("\n\n");
        if (ragContext != null && !ragContext.isEmpty()) {
            sb.append("RAG CONTEXT:\n").append(ragContext).append("\n\n");
        }
        if (previousContext != null && !previousContext.isEmpty()) {
            sb.append("CONVERSATION HISTORY:\n").append(previousContext).append("\n\n");
        }
        sb.append("User Query: ").append(userQuery).append("\n");
        if (failureReason != null && !failureReason.isEmpty()) {
            sb.append("Previous SQL execution failed. Error: ").append(failureReason).append("\n");
            sb.append("Regenerate a correct SQL statement that avoids this error.\n");
        }
        sb.append("Rules:\n");
        sb.append("1. Use only SELECT statements.\n");
        sb.append("2. Use exact table and column names from the schema.\n");
        sb.append("3. Do not include DDL or DML statements.\n");
        sb.append("4. Add LIMIT 100 unless otherwise specified.\n");
        sb.append("5. Output only the SQL, either as plain text, in a code block, or as a JSON field named 'sql'.\n");
        sb.append("6. Do not include explanations or comments.\n");
        sb.append("7. Do not ask for user confirmation.\n");
        sb.append("conversationId: ").append(conversationId).append("\n");
        return sb.toString();
    }

    public String extractCodeBlockFromResponse(String llmResponse) {
        // 1. Try to extract from code block
        String[] codeBlocks = llmResponse.split("```");
        if (codeBlocks.length >= 2) {
            String blockContent = codeBlocks[1].replaceFirst("(?i)^sql\\s*", "").trim();
            if (blockContent.toUpperCase().startsWith("SELECT")) {
                return blockContent.replaceAll("\\s+", " ").trim();
            }
        }

        // 2. Try to extract JSON and get the "sql" field
        Pattern jsonPattern = Pattern.compile("\\{(?:[^{}]|\\{[^{}]*\\})*\\}");
        Matcher jsonMatcher = jsonPattern.matcher(llmResponse);
        if (jsonMatcher.find()) {
            String json = jsonMatcher.group();
            try {
                JsonNode node = new ObjectMapper().readTree(json);
                for (Iterator<String> it = node.fieldNames(); it.hasNext(); ) {
                    String field = it.next();
                    if (field.equalsIgnoreCase("sql")) {
                        return node.get(field).asText().replaceAll("\\s+", " ").trim();
                    }
                }
            } catch (Exception e) {
                throw new IllegalArgumentException("Found JSON but failed to parse: " + e.getMessage());
            }
        }

        // 3. Try to find a SELECT statement ending with ;
        Pattern selectPattern = Pattern.compile("(?is)SELECT[\\s\\S]*?;");
        Matcher matcher = selectPattern.matcher(llmResponse);
        if (matcher.find()) {
            return matcher.group().replaceAll("\\s+", " ").trim();
        }

        throw new IllegalArgumentException("No SQL query found in LLM response");
    }

    // Validates if the user query is relevant and actionable
    public String validateUserRequest(String userQuery) {
        if (userQuery == null || userQuery.trim().isEmpty()) {
            return "Invalid: Query is empty.";
        }
        // Add more advanced checks as needed (e.g., off-topic, chit-chat detection)
        return "Valid";
    }

    // Uses LLM to generate SQL from the user query (optionally with failure reason)
    public String generateSql(String userQuery, String failureReason, String databaseSchema, String ragContext, String previousContext, String conversationId) {
        String prompt = buildGenerateSqlLlmPrompt(userQuery, failureReason, databaseSchema, ragContext, previousContext, conversationId);
        String llmResponse = chatModel.generate(prompt);
        return extractCodeBlockFromResponse(llmResponse);
    }

    // Validates the SQL for safety and correctness
    public String validateQuery(String sql) {
        // Basic check for forbidden keywords, etc.
        if (sql == null || sql.trim().isEmpty()) {
            return "Invalid: SQL is empty.";
        }
        if (sql.toLowerCase().contains("drop") || sql.toLowerCase().contains("delete")) {
            return "Invalid: Dangerous SQL detected.";
        }
        // Add more validation as needed
        return "Query is valid.";
    }

    // Executes the SQL and returns the result
    public Object executeQuery(String sql) {
        try {
            List<Map<String, Object>> result = jdbcTemplate.queryForList(sql);
            return result;
        } catch (Exception e) {
            return Map.of("error", e.getMessage());
        }
    }

    // Returns the execution plan for the SQL
    public Object explainQuery(String sql) {
        try {
            List<Map<String, Object>> plan = jdbcTemplate.queryForList("EXPLAIN " + sql);
            return plan;
        } catch (Exception e) {
            return Map.of("error", e.getMessage());
        }
    }

    // Summarizes a large result set (simple example)
    public String summarizeResults(List<Map<String, Object>> results) {
        if (results == null || results.isEmpty()) {
            return "No results to summarize.";
        }
        // Simple summary: number of rows and columns
        int rowCount = results.size();
        int colCount = results.get(0).size();
        return "Rows: " + rowCount + ", Columns: " + colCount;
    }
}
