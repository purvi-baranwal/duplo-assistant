package assistant.controller;

import assistant.mcp.McpActionDispatcher;
import assistant.service.QueryExecutionService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

@CrossOrigin(origins = "http://localhost:9090/index.html") // or *
@RestController
@RequestMapping("/assistant")
public class AIAssistantController {

    private final QueryExecutionService queryService;
    private final McpActionDispatcher mcpActionDispatcher;

    public AIAssistantController(QueryExecutionService queryExecutionService,
                                 McpActionDispatcher mcpActionDispatcher) {
        this.queryService = queryExecutionService;
        this.mcpActionDispatcher = mcpActionDispatcher;
    }

    @PostMapping("/test/{workOrderId}")
    public ResponseEntity<?> executeTestQuery(@PathVariable String workOrderId) {
        String result = queryService.executeTestQuery(workOrderId);
        return ResponseEntity.ok(result);
    }

    @PostMapping
    public ResponseEntity<String> queryDatabase(
//            @RequestParam String query,
            @RequestParam(required = false) String conversationId, // Allow optional conversationId for new chats
            @RequestBody String query) {
        if (conversationId == null || conversationId.isEmpty()) {
            conversationId = UUID.randomUUID().toString(); // Generate new if not provided
        }
        String response = queryService.processNaturalLanguageQuery(query, conversationId);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/mcp")
    public ResponseEntity<?> executeMcpAction(@RequestBody Map<String, Object> request) {
        String action = (String) request.get("action");
        Map<String, Object> params = (Map<String, Object>) request.get("params");
        Object result = mcpActionDispatcher.dispatch(action, params);
        return ResponseEntity.ok(result);
    }
}
