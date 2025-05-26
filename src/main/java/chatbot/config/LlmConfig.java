package chatbot.config;

import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.ollama.OllamaChatModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

@Configuration
public class LlmConfig {

    @Value("${ollama.base-url}")
    private String ollamaBaseUrl;

    @Bean(name = "sqlOptimizedModel")
    public ChatLanguageModel sqlOptimizedModel(
            @Value("${ollama.model-code}") String modelName,
            @Value("${ollama.temperature}") double temperature) {
        return OllamaChatModel.builder()
                .baseUrl(ollamaBaseUrl)
                .modelName(modelName)
                .temperature(temperature)
                .build();
    }

    @Bean
    public ChatLanguageModel defaultModel(
            @Value("${ollama.model-default}") String modelName,
            @Value("${ollama.temperature}") double temperature) {
        return OllamaChatModel.builder()
                .baseUrl(ollamaBaseUrl)
                .modelName(modelName)
                .temperature(temperature)
                .build();
    }

//    @Bean
//    public OllamaChatModel llamaModel() {
//        return OllamaChatModel.builder().baseUrl(ollamaUrl).modelName("llama3.2").temperature(0.3).build();
//    }

    @Bean
    public WebClient ollamaWebClient() {
        return WebClient.builder().baseUrl(ollamaBaseUrl).build();
    }
}
