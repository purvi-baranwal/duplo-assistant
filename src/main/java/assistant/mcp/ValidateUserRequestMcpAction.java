package assistant.mcp;

import assistant.service.QueryExecutionService;
import org.springframework.stereotype.Component;
import java.util.Map;

@Component
public class ValidateUserRequestMcpAction implements McpAction {
    private final QueryExecutionService queryService;

    public ValidateUserRequestMcpAction(QueryExecutionService queryService) {
        this.queryService = queryService;
    }

    @Override
    public String getName() {
        return "validate_user_request";
    }

    @Override
    public Object execute(Map<String, Object> params) {
        String userQuery = (String) params.get("userQuery");
        return queryService.validateUserRequest(userQuery);
    }
}
