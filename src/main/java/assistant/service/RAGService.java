package assistant.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class RAGService {

    private final EmbeddingService embeddingService;
    private final SchemaService schemaService;
    private final ObjectMapper objectMapper;

    // In a real application, this would be a vector database or a dedicated pgvector setup.
    // For demonstration, an in-memory "vector store"
    private final List<Map<String, Object>> knowledgeBase = new ArrayList<>();

    @Autowired
    public RAGService(EmbeddingService embeddingService, SchemaService schemaService, ObjectMapper objectMapper) {
        this.embeddingService = embeddingService;
        this.schemaService = schemaService;
        this.objectMapper = objectMapper;
        // Populate a sample knowledge base
        initializeKnowledgeBase();
    }

    private void initializeKnowledgeBase() {
        // Add custom knowledge entries
//        knowledgeBase.add(new HashMap<>(Map.of("text", "The work_order table stores details about work_order_status, due_date, created_by, tasks, assignee and priority.")));
//        knowledgeBase.add(new HashMap<>(Map.of("text", "For work_order_status use the work_order_status_id to get the status of a work order, use the display_name field for a human-readable status. work_order.work_order_status_id relates to work_order_status.work_order_status_id")));
//        knowledgeBase.addAll(schemaService.getSchemaDescriptions());

        // Load schema descriptions dynamically from SchemaService
        List<Map<String, Object>> schemaDescriptions = schemaService.getSchemaDescriptions();
        knowledgeBase.addAll(schemaDescriptions);

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
