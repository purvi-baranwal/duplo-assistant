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

    public boolean isLikelySchemaRelevant(String userQuery) {
        List<String> schemaTerms = schemaService.getAllTableAndColumnNames();
        String normalizedQuery = userQuery.toLowerCase().replaceAll("[^a-z0-9 ]", "").replaceAll("\\s+", " ");

        for (String term : schemaTerms) {
            String normalizedTerm = term.toLowerCase().replace("_", " ").replaceAll("\\s+", " ");
            if (normalizedQuery.contains(normalizedTerm) || normalizedQuery.contains(normalizedTerm.replace(" ", ""))) {
                return true;
            }

            // Try handling plural/singular variants
            if (normalizedQuery.contains(normalizedTerm + "s") || normalizedQuery.contains(normalizedTerm.replace("s", ""))) {
                return true;
            }
        }

        return false;
    }

    public List<String> retrieveRelevantContext(String userQuery, String previousContext) {
        List<String> relevantChunks = new ArrayList<>();
        String queryForEmbedding = previousContext.isEmpty()
                ? userQuery
                : previousContext + "\n" + userQuery;

        List<Double> queryEmbedding = embeddingService.getEmbedding(queryForEmbedding);
        if (queryEmbedding.isEmpty()) {
            return Collections.emptyList();
        }

        List<Map.Entry<Double, String>> scoredChunks = new ArrayList<>();
        for (Map<String, Object> entry : knowledgeBase) {
            List<Double> kbEmbedding = (List<Double>) entry.get("embedding");
            String text = (String) entry.get("text");

            if (kbEmbedding != null && !kbEmbedding.isEmpty()) {
                double similarity = cosineSimilarity(queryEmbedding, kbEmbedding);
                if (similarity > 0.7) {
                    scoredChunks.add(Map.entry(similarity, text));
                }
            }
        }

        scoredChunks.sort(Comparator.comparing(Map.Entry::getKey, Comparator.reverseOrder()));

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
