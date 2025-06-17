package assistant.mcp;

import assistant.service.QueryExecutionService;

import java.util.List;
import java.util.Map;

public class SummarizeResultsMcpAction implements McpAction {
    private final QueryExecutionService queryService;

    public SummarizeResultsMcpAction(QueryExecutionService queryService) {
        this.queryService = queryService;
    }

    @Override
    public String getName() {
        return "summarize_results";
    }

    @Override
    public Object execute(Map<String, Object> params) {
        List<Map<String, Object>> results = (List<Map<String, Object>>) params.get("results");
        return queryService.summarizeResults(results);
    }
}