package chatbot.service;

import ai.djl.Application;
import ai.djl.inference.Predictor;
import ai.djl.modality.nlp.embedding.TextEmbedding;
import ai.djl.ndarray.NDArray;
import ai.djl.repository.zoo.Criteria;
import ai.djl.repository.zoo.ZooModel;
import ai.djl.translate.TranslateException;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.Collections;
import java.util.List;

@Slf4j
@Service
public class EmbeddingService {

    @Value("${rag.embedding.model-name}")
    private String embeddingModelName;

    private ZooModel<String, float[]> model;
    private Predictor<String, float[]> predictor;

    @PostConstruct
    public void init() throws IOException {
        try {
            // Load a sentence-transformers model from Hugging Face
            // Ensure you have the necessary DJL dependencies for PyTorch/Hugging Face
            log.info("Loading embedding model: {}", embeddingModelName);
            Criteria<String, float[]> criteria = Criteria.builder()
                    .setTypes(String.class, float[].class)
                    .optApplication(Application.NLP.TEXT_EMBEDDING)
                    .optModelUrls("djl://ai.djl.huggingface.pytorch/" + embeddingModelName)
                    .optEngine("PyTorch")
                    .build();
            model = criteria.loadModel();
            predictor = model.newPredictor();
        } catch (Exception e) {
            System.err.println("Failed to load embedding model: " + e.getMessage());
            // Consider throwing a custom exception or making this recoverable
        }
    }

    @PreDestroy
    public void destroy() {
        if (predictor != null) {
            predictor.close();
        }
        if (model != null) {
            model.close();
        }
    }

    public List<Double> getEmbedding(String text) {
        if (predictor == null) {
            log.error("Embedding predictor not initialized.");
            return Collections.emptyList();
        }
        try {
            float[] embeddingsArray = predictor.predict(text);
            List<Double> embeddingsList = new java.util.ArrayList<>(embeddingsArray.length);
            for (float value : embeddingsArray) {
                embeddingsList.add((double) value);
            }
            return embeddingsList;
        } catch (TranslateException e) {
            log.error("Error generating embedding: {}", e.getMessage());
            return Collections.emptyList();
        }
    }
}
