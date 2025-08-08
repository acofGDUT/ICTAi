package edu.whut.cs.bi.rag.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.chroma.vectorstore.ChromaApi;
import org.springframework.ai.chroma.vectorstore.ChromaVectorStore;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestTemplate;

@Configuration
public class SpringConfig {
    @Value("${chroma-url}")
    private String chromaUrl;
    @Bean
    public RestTemplate restTemplate() {
        return new RestTemplate();
    }
    @Bean
    public RestClient.Builder builder() {
        return RestClient.builder().requestFactory(new SimpleClientHttpRequestFactory());
    }


    @Bean
    public ChromaApi chromaApi(RestClient.Builder restClientBuilder, ObjectMapper objectMapper) {
//        String chromaUrl = "http://localhost:8000";
        ChromaApi chromaApi = new ChromaApi(chromaUrl, restClientBuilder,objectMapper);
        return chromaApi;
    }

    @Bean
    public VectorStore chromaVectorStore(EmbeddingModel embeddingModel, ChromaApi chromaApi) {
        return ChromaVectorStore.builder(chromaApi, embeddingModel)
                .collectionName("BiCollection")
                .initializeSchema(true)
                .build();
    }
//    @Bean
//    public VectorStore vectorStore(MilvusServiceClient milvusClient, EmbeddingModel embeddingModel) {
//        return MilvusVectorStore.builder(milvusClient, embeddingModel)
//                .collectionName("test_vector_store")
//                .databaseName("default")
//                .indexType(IndexType.IVF_FLAT)
//                .metricType(MetricType.COSINE)
//                .batchingStrategy(new TokenCountBatchingStrategy())
//                .initializeSchema(true)
//                .autoId( true)
//                .embeddingDimension(1536)
//                .build();
//    }
//
//    @Bean
//    public MilvusServiceClient milvusClient() {
//        return new MilvusServiceClient(ConnectParam.newBuilder()
//                .withAuthorization("minioadmin", "minioadmin")
//                .withHost("59.110.81.142")
////                .withHost("localhost")
//                .build());
//    }
}
