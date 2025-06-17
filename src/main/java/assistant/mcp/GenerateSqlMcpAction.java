package assistant.mcp;

import assistant.service.QueryExecutionService;
import org.springframework.stereotype.Component;
import java.util.Map;

@Component
public class GenerateSqlMcpAction implements McpAction {
    private final QueryExecutionService queryService;

    public GenerateSqlMcpAction(QueryExecutionService queryService) {
        this.queryService = queryService;
    }

    @Override
    public String getName() {
        return "generate_sql";
    }

    @Override
    public Object execute(Map<String, Object> params) {
        String userQuery = (String) params.get("userQuery");
        String failureReason = (String) params.getOrDefault("failureReason", null);
        String databaseSchema = (String) params.getOrDefault("databaseSchema", null);
        String ragContext = (String) params.getOrDefault("ragContext", null);
        String previousContext = (String) params.getOrDefault("previousContext", null);
        String conversationId = (String) params.getOrDefault("conversationId", null);
        return queryService.generateSql(
                userQuery,
                failureReason,
                databaseSchema,
                ragContext,
                previousContext,
                conversationId
        );
    }
}
