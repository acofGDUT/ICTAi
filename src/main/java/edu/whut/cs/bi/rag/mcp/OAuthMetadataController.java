package edu.whut.cs.bi.rag.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;

/**
 * 客户端发现授权服务器用的元数据：
 * RFC 9728 受保护资源元数据，以及给只认 MCP 同源地址的旧客户端准备的授权服务器元数据转发。
 */
@Slf4j
@RestController
@CrossOrigin(origins = "*")
public class OAuthMetadataController {

    private static final Duration AS_METADATA_CACHE = Duration.ofMinutes(5);

    private final McpOAuthProperties properties;
    private final ObjectMapper objectMapper;
    private final RestTemplate restTemplate;

    private volatile JsonNode cachedAsMetadata;
    private volatile long cachedAtMillis;

    public OAuthMetadataController(McpOAuthProperties properties, ObjectMapper objectMapper, RestTemplate restTemplate) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.restTemplate = restTemplate;
    }

    @GetMapping(value = {"/.well-known/oauth-protected-resource", "/.well-known/oauth-protected-resource/**"},
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ObjectNode> protectedResourceMetadata() {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("resource", properties.getPublicBaseUrl());
        body.putArray("authorization_servers").add(properties.getOauth().getIssuer());
        body.putArray("scopes_supported").add(properties.getScope());
        body.putArray("bearer_methods_supported").add("header");
        body.put("resource_name", "BuildingInspector 桥梁检测 MCP");
        return ResponseEntity.ok().cacheControl(CacheControl.maxAge(AS_METADATA_CACHE)).body(body);
    }

    @GetMapping(value = "/.well-known/oauth-authorization-server", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<JsonNode> authorizationServerMetadata() {
        long now = System.currentTimeMillis();
        JsonNode metadata = cachedAsMetadata;
        if (metadata == null || now - cachedAtMillis > AS_METADATA_CACHE.toMillis()) {
            try {
                metadata = restTemplate.getForObject(
                        properties.getOauth().getIssuer() + "/.well-known/oauth-authorization-server", JsonNode.class);
                cachedAsMetadata = metadata;
                cachedAtMillis = now;
            } catch (RestClientException e) {
                log.warn("获取授权服务器元数据失败：{}", e.getMessage());
                if (metadata == null) {
                    return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
                }
            }
        }
        return ResponseEntity.ok().cacheControl(CacheControl.maxAge(AS_METADATA_CACHE)).body(metadata);
    }
}
