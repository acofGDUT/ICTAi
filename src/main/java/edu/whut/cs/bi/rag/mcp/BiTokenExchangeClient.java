package edu.whut.cs.bi.rag.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 用客户端的 MCP 访问令牌向 BuildingInspector 换取短期 BI JWT（RFC 8693），并在短时间内缓存结果。
 */
@Slf4j
@Component
public class BiTokenExchangeClient {

    private static final String GRANT_TOKEN_EXCHANGE = "urn:ietf:params:oauth:grant-type:token-exchange";
    private static final String TOKEN_TYPE_ACCESS_TOKEN = "urn:ietf:params:oauth:token-type:access_token";
    private static final long EXPIRY_MARGIN_MILLIS = 30_000;

    private final McpOAuthProperties properties;
    private final ObjectMapper objectMapper;
    private final RestTemplate restTemplate;
    private final Map<String, CachedIdentity> cache = new ConcurrentHashMap<>();

    public BiTokenExchangeClient(McpOAuthProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(3000);
        factory.setReadTimeout(10000);
        this.restTemplate = new RestTemplate(factory);
    }

    public McpTokenContext.McpIdentity exchange(String accessToken) {
        String key = sha256(accessToken);
        long now = System.currentTimeMillis();
        CachedIdentity cached = cache.get(key);
        if (cached != null && cached.expiresAtMillis > now) {
            return cached.identity;
        }
        cache.values().removeIf(entry -> entry.expiresAtMillis <= now);

        JsonNode body = postToken(form(Map.of(
                "grant_type", GRANT_TOKEN_EXCHANGE,
                "subject_token", accessToken,
                "subject_token_type", TOKEN_TYPE_ACCESS_TOKEN,
                "resource", properties.getPublicBaseUrl())), true);
        McpTokenContext.McpIdentity identity = new McpTokenContext.McpIdentity(
                body.path("access_token").asText(),
                accessToken,
                body.path("login_name").asText(),
                body.path("client_name").asText(null),
                body.path("subject_expires_at").asLong());
        long ttl = Math.min(properties.getOauth().getExchangeCacheTtl().toMillis(),
                body.path("expires_in").asLong() * 1000 - EXPIRY_MARGIN_MILLIS);
        ttl = Math.min(ttl, identity.tokenExpiresAt() * 1000 - now);
        if (ttl > 0) {
            cache.put(key, new CachedIdentity(identity, now + ttl));
        }
        log.info("MCP 令牌交换：user={}, client={}", identity.loginName(), identity.clientName());
        return identity;
    }

    /**
     * 撤销访问令牌（RFC 7009），同时清掉本地缓存。
     */
    public void revoke(String accessToken) {
        cache.remove(sha256(accessToken));
        postToken(form(Map.of("token", accessToken)), false);
    }

    private JsonNode postToken(MultiValueMap<String, String> form, boolean exchange) {
        String issuer = properties.getOauth().getIssuer();
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        if (exchange) {
            headers.setBasicAuth(properties.getOauth().getClientId(), properties.getOauth().getClientSecret(),
                    StandardCharsets.UTF_8);
        }
        String url = issuer + (exchange ? "/oauth/token" : "/oauth/revoke");
        try {
            String body = restTemplate.postForEntity(url, new HttpEntity<>(form, headers), String.class).getBody();
            return body == null || body.isBlank() ? objectMapper.createObjectNode() : objectMapper.readTree(body);
        } catch (HttpStatusCodeException e) {
            String description = describe(e.getResponseBodyAsString());
            if (e.getStatusCode().value() == 400) {
                throw new InvalidTokenException(description);
            }
            log.error("BuildingInspector 令牌接口返回 {}：{}", e.getStatusCode(), description);
            throw new AuthServerUnavailableException("授权服务器拒绝了 MCP Server：" + e.getStatusCode());
        } catch (ResourceAccessException e) {
            throw new AuthServerUnavailableException("无法连接授权服务器：" + e.getMessage());
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new AuthServerUnavailableException("授权服务器返回的不是 JSON");
        }
    }

    private String describe(String body) {
        try {
            JsonNode error = objectMapper.readTree(body);
            return error.path("error_description").asText(error.path("error").asText("访问令牌无效"));
        } catch (Exception e) {
            return "访问令牌无效";
        }
    }

    private static MultiValueMap<String, String> form(Map<String, String> values) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        values.forEach(form::add);
        return form;
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private record CachedIdentity(McpTokenContext.McpIdentity identity, long expiresAtMillis) {
    }

    public static class InvalidTokenException extends RuntimeException {
        public InvalidTokenException(String message) {
            super(message);
        }
    }

    public static class AuthServerUnavailableException extends RuntimeException {
        public AuthServerUnavailableException(String message) {
            super(message);
        }
    }
}
