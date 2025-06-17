package assistant.mcp;

import java.util.Map;

public interface McpAction {
    String getName();
    Object execute(Map<String, Object> params);
}
