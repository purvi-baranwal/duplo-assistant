package assistant.mcp;

import assistant.service.QueryExecutionService;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class ExplainQueryMcpAction implements McpAction {
    private final QueryExecutionService queryService;

    public ExplainQueryMcpAction(QueryExecutionService queryService) {
        this.queryService = queryService;
    }

    @Override
    public String getName() {
        return "explain_query";
    }

    @Override
    public Object execute(Map<String, Object> params) {
        String sql = (String) params.get("sql");
        return queryService.explainQuery(sql);
    }
}
