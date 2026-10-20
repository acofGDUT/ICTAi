package edu.whut.cs.bi.rag.mcp;

import jakarta.annotation.PostConstruct;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class McpServerConfig {

    @PostConstruct
    public void installTokenPropagation() {
        McpTokenContext.installSchedulerHook();
    }

    @Bean
    public ToolCallbackProvider biMcpToolCallbackProvider(BiMcpTools biMcpTools) {
        return MethodToolCallbackProvider.builder().toolObjects(biMcpTools).build();
    }

    @Bean
    public FilterRegistrationBean<McpAuthFilter> mcpAuthFilter(
            BiTokenExchangeClient exchangeClient,
            BiApiClient biApiClient,
            McpOAuthProperties properties,
            @Value("${spring.ai.mcp.server.streamable-http.mcp-endpoint:/mcp}") String mcpEndpoint) {
        FilterRegistrationBean<McpAuthFilter> registration = new FilterRegistrationBean<>(
                new McpAuthFilter(exchangeClient, biApiClient, properties, mcpEndpoint));
        registration.addUrlPatterns(mcpEndpoint);
        return registration;
    }
}
