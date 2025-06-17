package assistant.mcp;

import org.springframework.stereotype.Component;
import java.util.HashMap;
import java.util.Map;

@Component
public class McpActionRegistry {
    private final Map<String, McpAction> actions = new HashMap<>();

    public void register(McpAction action) {
        actions.put(action.getName(), action);
    }

    public McpAction getAction(String name) {
        return actions.get(name);
    }
}