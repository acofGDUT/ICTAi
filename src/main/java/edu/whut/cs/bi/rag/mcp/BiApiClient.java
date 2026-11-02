package edu.whut.cs.bi.rag.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;

/**
 * 以当前 MCP 用户的身份调用 BuildingInspector 的 /toolCallingApi。
 */
@Slf4j
@Component
public class BiApiClient {

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final String baseUrl;

    public BiApiClient(RestTemplate restTemplate, ObjectMapper objectMapper, @Value("${bi-location}") String baseUrl) {
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
        this.baseUrl = baseUrl;
    }

    /**
     * 返回 AjaxResult 中的 data 字段；后台返回失败时抛出带原因的异常，交给 MCP 作为工具错误返回。
     */
    public JsonNode getData(String path, Object... uriVariables) {
        String token = token();
        log.info("MCP 调用 BI：user={}, path={}", McpTokenContext.get().loginName(), path);
        return getDataAs(token, path, uriVariables);
    }

    public JsonNode getDataAs(String token, String path, Object... uriVariables) {
        URI uri = UriComponentsBuilder.fromHttpUrl(baseUrl + "/toolCallingApi" + path)
                .encode()
                .buildAndExpand(uriVariables)
                .toUri();
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.AUTHORIZATION, token);
        String body;
        try {
            body = restTemplate.exchange(uri, HttpMethod.GET, new HttpEntity<>(headers), String.class).getBody();
        } catch (HttpStatusCodeException e) {
            if (e.getStatusCode() == HttpStatus.UNAUTHORIZED) {
                throw new IllegalStateException("BuildingInspector 拒绝了当前登录凭证，请在 AI 客户端中重新授权");
            }
            throw new IllegalStateException("调用 BuildingInspector 失败：" + e.getStatusCode());
        }
        return parseData(body);
    }

    /**
     * 以当前用户身份向 BuildingInspector 发起写操作。失败时抛出带中文原因的异常。
     */
    public JsonNode postData(String path, Object body) {
        String token = token();
        log.info("MCP 调用 BI：user={}, path={} (POST)", McpTokenContext.get().loginName(), path);
        URI uri = UriComponentsBuilder.fromHttpUrl(baseUrl + "/toolCallingApi" + path)
                .encode()
                .build()
                .toUri();
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.AUTHORIZATION, token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        String response;
        try {
            response = restTemplate.exchange(uri, HttpMethod.POST, new HttpEntity<>(body, headers), String.class).getBody();
        } catch (HttpStatusCodeException e) {
            if (e.getStatusCode() == HttpStatus.UNAUTHORIZED) {
                throw new IllegalStateException("BuildingInspector 拒绝了当前登录凭证，请在 AI 客户端中重新授权");
            }
            if (e.getStatusCode() == HttpStatus.FORBIDDEN) {
                throw new IllegalStateException("BuildingInspector 拒绝了这个操作，当前用户没有对应权限");
            }
            throw new IllegalStateException("调用 BuildingInspector 失败：" + e.getStatusCode());
        }
        return parseData(response);
    }

    private JsonNode parseData(String body) {
        try {
            JsonNode result = objectMapper.readTree(body);
            if (result.path("code").asInt(-1) != 0) {
                throw new IllegalStateException(result.path("msg").asText("BuildingInspector 返回失败"));
            }
            return result.path("data");
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("BuildingInspector 返回的不是 JSON");
        }
    }

    private String token() {
        McpTokenContext.McpIdentity identity = McpTokenContext.get();
        if (identity == null) {
            throw new IllegalStateException("当前 MCP 请求未通过授权");
        }
        return identity.biToken();
    }
}
