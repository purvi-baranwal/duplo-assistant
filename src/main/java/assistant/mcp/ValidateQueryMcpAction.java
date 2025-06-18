package assistant.mcp;

import assistant.service.QueryExecutionService;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class ValidateQueryMcpAction implements McpAction {
    private final QueryExecutionService queryService;

    public ValidateQueryMcpAction(QueryExecutionService queryService) {
        this.queryService = queryService;
    }

    @Override
    public String getName() {
        return "check_query";
    }

    @Override
    public Object execute(Map<String, Object> params) {
        String sql = (String) params.get("sql");
        return queryService.validateQuery(sql);
    }
}
