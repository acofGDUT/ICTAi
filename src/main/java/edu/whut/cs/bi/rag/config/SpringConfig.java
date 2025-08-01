package edu.whut.cs.bi.rag.config;

import io.milvus.client.MilvusServiceClient;
import io.milvus.param.ConnectParam;
import io.milvus.param.IndexType;
import io.milvus.param.MetricType;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.TokenCountBatchingStrategy;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.milvus.MilvusVectorStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;

@Configuration
public class SpringConfig {
    @Bean
    public RestTemplate restTemplate() {
        return new RestTemplate();
    }
    @Bean
    public VectorStore vectorStore(MilvusServiceClient milvusClient, EmbeddingModel embeddingModel) {
        return MilvusVectorStore.builder(milvusClient, embeddingModel)
                .collectionName("test_vector_store")
                .databaseName("default")
                .indexType(IndexType.IVF_FLAT)
                .metricType(MetricType.COSINE)
                .batchingStrategy(new TokenCountBatchingStrategy())
                .initializeSchema(true)
                .autoId( true)
                .embeddingDimension(1536)
                .build();
    }

    @Bean
    public MilvusServiceClient milvusClient() {
        return new MilvusServiceClient(ConnectParam.newBuilder()
                .withAuthorization("minioadmin", "minioadmin")
                .withHost("59.110.81.142")
                .build());
    }
}
