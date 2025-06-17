package assistant.mcp;

import assistant.service.QueryExecutionService;
import org.springframework.stereotype.Component;
import java.util.Map;

@Component
public class QueryMcpAction implements McpAction {
    private final QueryExecutionService queryService;

    public QueryMcpAction(QueryExecutionService queryService) {
        this.queryService = queryService;
    }

    @Override
    public String getName() {
        return "execute_query";
    }

    @Override
    public Object execute(Map<String, Object> params) {
        String sql = (String) params.get("sql");
        return queryService.executeQuery(sql);
    }
}
