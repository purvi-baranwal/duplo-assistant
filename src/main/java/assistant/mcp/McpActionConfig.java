package assistant.mcp;

import jakarta.annotation.PostConstruct;
import org.springframework.context.annotation.Configuration;
import java.util.List;

@Configuration
public class McpActionConfig {
    private final McpActionRegistry registry;
    private final List<McpAction> actions;

    public McpActionConfig(McpActionRegistry registry, List<McpAction> actions) {
        this.registry = registry;
        this.actions = actions;
    }

    @PostConstruct
    public void registerActions() {
        actions.forEach(registry::register);
    }
}
