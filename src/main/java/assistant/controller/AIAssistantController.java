package assistant.controller;

import assistant.service.QueryExecutionService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/assistant")
public class AIAssistantController {

    private final QueryExecutionService queryService;

    public AIAssistantController(QueryExecutionService queryExecutionService) {
        this.queryService = queryExecutionService;
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
}
