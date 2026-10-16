package edu.whut.cs.bi.rag.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class OpenAiResponsesApiClient implements ResponsesApiClient {
    private static final Logger log = LoggerFactory.getLogger(OpenAiResponsesApiClient.class);

    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final URI responsesUri;
    private final String apiKey;
    private final String promptCacheKey;
    private final String promptCacheRetention;
    private final Duration requestTimeout;
    private final Set<String> cacheOptionsDisabledModels = ConcurrentHashMap.newKeySet();
    private final Map<String, String> parentPromptCacheKeys = new ConcurrentHashMap<>();
    private volatile ResponsesRequestTrace requestTrace;

    public OpenAiResponsesApiClient(
            ObjectMapper objectMapper,
            @Value("${spring.ai.openai.base-url}") String baseUrl,
            @Value("${spring.ai.openai.api-key}") String apiKey,
            @Value("${disease-summary.prompt-cache-key:disease-summary-v1}") String promptCacheKey,
            @Value("${disease-summary.prompt-cache-retention:in_memory}") String promptCacheRetention,
            @Value("${disease-summary.request-timeout-seconds:180}") long requestTimeoutSeconds) {
        this.objectMapper = objectMapper;
        this.responsesUri = URI.create(stripTrailingSlash(baseUrl) + "/v1/responses");
        this.apiKey = apiKey;
        this.promptCacheKey = promptCacheKey;
        this.promptCacheRetention = promptCacheRetention;
        this.requestTimeout = Duration.ofSeconds(requestTimeoutSeconds);
        this.requestTrace = new ResponsesRequestTrace(objectMapper, "", apiKey);
        this.httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(15))
                .build();
    }

    @Value("${disease-summary.trace-directory:}")
    void configureTraceDirectory(String directory) {
        this.requestTrace = new ResponsesRequestTrace(objectMapper, directory, apiKey);
    }

    @Override
    public ResponseResult createParent(String model, String systemPrompt, String bootstrapMessage) {
        String scopedPromptCacheKey = scopedPromptCacheKey(model, systemPrompt);
        ObjectNode body = baseBody(model, scopedPromptCacheKey);
        ArrayNode input = body.putArray("input");
        input.add(message("system", systemPrompt));
        input.add(message("user", bootstrapMessage));
        ResponseResult result = executeWithCacheOptionFallback(body);
        if (result.id() != null && !result.id().isBlank()) {
            parentPromptCacheKeys.put(result.id(), scopedPromptCacheKey);
        }
        return result;
    }

    @Override
    public ResponseResult respond(String model, String reasoningEffort, String systemPrompt, String userMessage) {
        ObjectNode body = baseBody(model, scopedPromptCacheKey(model, systemPrompt));
        if (reasoningEffort != null && !reasoningEffort.isBlank()) {
            body.putObject("reasoning").put("effort", reasoningEffort);
        }
        ArrayNode input = body.putArray("input");
        input.add(message("system", systemPrompt));
        input.add(message("user", userMessage));
        return executeWithCacheOptionFallback(body);
    }

    @Override
    public ResponseResult continueFrom(String model, String previousResponseId, String userMessage) {
        String scopedPromptCacheKey = parentPromptCacheKeys.get(previousResponseId);
        ObjectNode body = baseBody(model, scopedPromptCacheKey);
        body.put("previous_response_id", previousResponseId);
        ArrayNode input = body.putArray("input");
        input.add(message("user", userMessage));
        return executeWithCacheOptionFallback(body);
    }

    private ObjectNode baseBody(String model, String scopedPromptCacheKey) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("model", model);
        body.put("stream", true);
        body.put("store", true);
        if (!cacheOptionsDisabledModels.contains(model)) {
            if (scopedPromptCacheKey != null && !scopedPromptCacheKey.isBlank()) {
                body.put("prompt_cache_key", scopedPromptCacheKey);
            }
            if (model.startsWith("gpt-5.6")) {
                body.putObject("prompt_cache_options").put("ttl", "30m");
            } else if (!promptCacheRetention.isBlank()) {
                body.put("prompt_cache_retention", promptCacheRetention);
            }
        }
        return body;
    }

    private String scopedPromptCacheKey(String model, String systemPrompt) {
        if (promptCacheKey.isBlank()) {
            return "";
        }
        return promptCacheKey + ":" + fingerprint(systemPrompt).substring(0, 12) + ":" + model;
    }

    private static String fingerprint(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("JVM 不支持 SHA-256", exception);
        }
    }

    private ObjectNode message(String role, String text) {
        ObjectNode message = objectMapper.createObjectNode();
        message.put("role", role);
        message.put("content", text);
        return message;
    }

    private ResponseResult executeWithCacheOptionFallback(ObjectNode body) {
        try {
            return execute(body);
        } catch (ResponsesApiException exception) {
            if ((body.has("prompt_cache_options") || body.has("prompt_cache_retention") || body.has("prompt_cache_key"))
                    && exception.getStatusCode() != null
                    && exception.getStatusCode() == 400
                    && mentionsUnsupportedCacheOption(exception.getMessage())) {
                String model = body.path("model").asText();
                cacheOptionsDisabledModels.add(model);
                body.remove("prompt_cache_key");
                body.remove("prompt_cache_retention");
                body.remove("prompt_cache_options");
                log.warn("Responses API model={} 不支持显式缓存参数，该模型回退为服务商默认缓存策略", model);
                return execute(body);
            }
            throw exception;
        }
    }

    private ResponseResult execute(ObjectNode body) {
        HttpRequest request;
        String requestJson;
        try {
            requestJson = objectMapper.writeValueAsString(body);
            request = HttpRequest.newBuilder(responsesUri)
                    .timeout(requestTimeout)
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .header("Accept", "text/event-stream")
                    .POST(HttpRequest.BodyPublishers.ofString(requestJson, StandardCharsets.UTF_8))
                    .build();
        } catch (IOException exception) {
            throw new ResponsesApiException("无法构造 Responses API 请求", exception);
        }

        ResponsesRequestTrace.Attempt trace = requestTrace.begin(requestJson);
        Integer httpStatus = null;
        ResponseResult result;
        try {
            HttpResponse<InputStream> response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
            httpStatus = response.statusCode();
            try (InputStream stream = response.body()) {
                if (response.statusCode() < 200 || response.statusCode() >= 300) {
                    String errorBody = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
                    throw new ResponsesApiException(
                            "Responses API HTTP " + response.statusCode() + ": " + abbreviate(errorBody),
                            response.statusCode());
                }
                result = parseEventStream(stream);
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            ResponsesApiException failure = new ResponsesApiException("Responses API 请求被中断", exception);
            if (trace != null) trace.failure(httpStatus, failure);
            throw failure;
        } catch (IOException exception) {
            ResponsesApiException failure = new ResponsesApiException("Responses API 传输失败: " + exception.getMessage(), exception);
            if (trace != null) trace.failure(httpStatus, failure);
            throw failure;
        } catch (RuntimeException exception) {
            if (trace != null) trace.failure(httpStatus, exception);
            throw exception;
        }
        if (trace != null) trace.success(httpStatus, result);
        return result;
    }

    private ResponseResult parseEventStream(InputStream stream) throws IOException {
        StringBuilder deltas = new StringBuilder();
        JsonNode completedResponse = null;
        StringBuilder eventData = new StringBuilder();

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isEmpty()) {
                    if (!eventData.isEmpty()) {
                        JsonNode event = parseEvent(eventData.toString());
                        eventData.setLength(0);
                        String type = event.path("type").asText();
                        if ("response.output_text.delta".equals(type)) {
                            deltas.append(event.path("delta").asText());
                        } else if ("response.completed".equals(type)) {
                            completedResponse = event.path("response");
                        } else if ("response.failed".equals(type) || "error".equals(type)) {
                            throw new ResponsesApiException("Responses API 返回失败事件: " + abbreviate(event.toString()), 502);
                        }
                    }
                    continue;
                }
                if (line.startsWith("data:")) {
                    if (!eventData.isEmpty()) {
                        eventData.append('\n');
                    }
                    eventData.append(line.substring(5).trim());
                }
            }
        }

        if (completedResponse == null) {
            throw new ResponsesApiException("Responses API 流式响应未包含 response.completed", 502);
        }
        String outputText = extractOutputText(completedResponse);
        if (outputText.isBlank()) {
            outputText = deltas.toString();
        }
        String responseId = completedResponse.path("id").asText();
        if (responseId.isBlank()) {
            throw new ResponsesApiException("Responses API 响应缺少 id", 502);
        }
        JsonNode usage = completedResponse.path("usage");
        long inputTokens = usage.path("input_tokens").asLong(0);
        long cachedTokens = usage.path("input_tokens_details").path("cached_tokens").asLong(0);
        return new ResponseResult(responseId, outputText.trim(), inputTokens, cachedTokens);
    }

    private JsonNode parseEvent(String data) throws IOException {
        if ("[DONE]".equals(data)) {
            return objectMapper.createObjectNode();
        }
        return objectMapper.readTree(data);
    }

    private String extractOutputText(JsonNode response) {
        StringBuilder output = new StringBuilder();
        for (JsonNode item : response.path("output")) {
            for (JsonNode content : item.path("content")) {
                if ("output_text".equals(content.path("type").asText())) {
                    output.append(content.path("text").asText());
                }
            }
        }
        return output.toString();
    }

    private static boolean mentionsUnsupportedCacheOption(String message) {
        String lower = message.toLowerCase();
        return lower.contains("prompt_cache_key") || lower.contains("prompt_cache_retention")
                || lower.contains("prompt_cache_options");
    }

    private static String stripTrailingSlash(String value) {
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    private static String abbreviate(String value) {
        String normalized = value.replaceAll("\\s+", " ").trim();
        return normalized.length() <= 800 ? normalized : normalized.substring(0, 800) + "...";
    }
}
