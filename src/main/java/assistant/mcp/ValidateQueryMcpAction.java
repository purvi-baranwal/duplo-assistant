package assistant.mcp;

import assistant.service.QueryExecutionService;

import java.util.Map;

public class ValidateQueryMcpAction implements McpAction {
    private final QueryExecutionService queryService;

    public ValidateQueryMcpAction(QueryExecutionService queryService) {
        this.queryService = queryService;
    }

    @Override
    public String getName() {
        return "validate_query";
    }

    @Override
    public Object execute(Map<String, Object> params) {
        String sql = (String) params.get("sql");
        return queryService.validateQuery(sql);
    }
}
