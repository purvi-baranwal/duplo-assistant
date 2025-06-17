package assistant.mcp;

import org.springframework.stereotype.Service;
import java.util.Map;

@Service
public class McpActionDispatcher {
    private final McpActionRegistry registry;

    public McpActionDispatcher(McpActionRegistry registry) {
        this.registry = registry;
    }

    public Object dispatch(String actionName, Map<String, Object> params) {
        McpAction action = registry.getAction(actionName);
        if (action == null) {
            throw new IllegalArgumentException("Unknown MCP action: " + actionName);
        }
        return action.execute(params);
    }
}
