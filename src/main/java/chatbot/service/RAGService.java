package chatbot.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

@Service
public class RAGService {

    private final EmbeddingService embeddingService;
    private final ObjectMapper objectMapper;

    // In a real application, this would be a vector database or a dedicated pgvector setup.
    // For demonstration, an in-memory "vector store"
    private final List<Map<String, Object>> knowledgeBase = new ArrayList<>();

    @Autowired
    public RAGService(EmbeddingService embeddingService, ObjectMapper objectMapper) {
        this.embeddingService = embeddingService;
        this.objectMapper = objectMapper;
        // Populate a sample knowledge base
        initializeKnowledgeBase();
    }

    private void initializeKnowledgeBase() {
        // Schema descriptions (can be dynamically loaded from SchemaService)
        Map<String, Object> knowledgeBaseEntry = new java.util.HashMap<>();
        knowledgeBaseEntry.put("text", "The work_order table stores details about work_order_status, due_date, created_by, tasks, assignee and priority.");
        knowledgeBaseEntry.put("type", "schema_desc");
        knowledgeBase.add(knowledgeBaseEntry);
//        knowledgeBase.add(Map.of("text", "The work_orders table stores details about tasks or jobs, including title, description, assignee, status, and priority.", "type", "schema_desc"));
//        knowledgeBase.add(Map.of("text", "The work_order_tasks table breaks down work orders into smaller steps, with task_name, status, and last_status_update.", "type", "schema_desc"));
//        knowledgeBase.add(Map.of("text", "work_orders.assignee_id links to users.user_id.", "type", "relationship"));
//        knowledgeBase.add(Map.of("text", "work_order_tasks.work_order_id links to work_orders.work_order_id.", "type", "relationship"));
//
//        // Example queries
//        knowledgeBase.add(Map.of("text", "How many workorders are assigned to John?", "sql_example", "SELECT COUNT(*) FROM work_orders wo JOIN users u ON wo.assignee_id = u.user_id WHERE u.name = 'John';"));
//        knowledgeBase.add(Map.of("text", "List tasks for workorder 'Fix Printer'.", "sql_example", "SELECT wot.task_name, wot.status FROM work_order_tasks wot JOIN work_orders wo ON wot.work_order_id = wo.work_order_id WHERE wo.title = 'Fix Printer';"));
//        knowledgeBase.add(Map.of("text", "What is the status of task 'Install OS' for new hire laptop?", "sql_example", "SELECT wot.status FROM work_order_tasks wot JOIN work_orders wo ON wot.work_order_id = wo.work_order_id WHERE wo.title = 'Set up new hire laptop' AND wot.task_name = 'Install OS';"));

        // Pre-compute embeddings for the knowledge base
        for (Map<String, Object> entry : knowledgeBase) {
            List<Double> embedding = embeddingService.getEmbedding((String) entry.get("text"));
            if (!embedding.isEmpty()) {
                entry.put("embedding", embedding);
            }
        }
    }

    public List<String> retrieveRelevantContext(String userQuery, String previousContext) {
        List<String> relevantChunks = new ArrayList<>();
        String queryForEmbedding = userQuery;
        if (!previousContext.isEmpty()) {
            queryForEmbedding = previousContext + "\n" + userQuery; // Combine for better context
        }

        List<Double> queryEmbedding = embeddingService.getEmbedding(queryForEmbedding);

        if (queryEmbedding.isEmpty()) {
            return Collections.emptyList();
        }

        // Simple similarity search (cosine similarity)
        List<Map.Entry<Double, String>> scoredChunks = new ArrayList<>();
        for (Map<String, Object> entry : knowledgeBase) {
            List<Double> kbEmbedding = (List<Double>) entry.get("embedding");
            if (kbEmbedding != null && !kbEmbedding.isEmpty()) {
                double similarity = cosineSimilarity(queryEmbedding, kbEmbedding);
                scoredChunks.add(Map.entry(similarity, (String) entry.get("text")));
                // Also add SQL examples if present
                if (entry.containsKey("sql_example")) {
                    scoredChunks.add(Map.entry(similarity, "Example SQL: " + entry.get("sql_example")));
                }
            }
        }

        // Sort by similarity and take top N
        scoredChunks.sort(Comparator.comparing(Map.Entry::getKey, Comparator.reverseOrder()));

        // Limit to a reasonable number of chunks for the prompt
        int maxChunks = 3;
        for (int i = 0; i < Math.min(scoredChunks.size(), maxChunks); i++) {
            relevantChunks.add(scoredChunks.get(i).getValue());
        }

        return relevantChunks;
    }

    // Simple Cosine Similarity calculation
    private double cosineSimilarity(List<Double> vectorA, List<Double> vectorB) {
        double dotProduct = 0.0;
        double normA = 0.0;
        double normB = 0.0;
        for (int i = 0; i < vectorA.size(); i++) {
            dotProduct += vectorA.get(i) * vectorB.get(i);
            normA += Math.pow(vectorA.get(i), 2);
            normB += Math.pow(vectorB.get(i), 2);
        }
        return dotProduct / (Math.sqrt(normA) * Math.sqrt(normB));
    }
}
