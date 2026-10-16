package edu.whut.cs.bi.rag.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

class OpenAiResponsesApiClientTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final List<JsonNode> requests = new CopyOnWriteArrayList<>();
    private final List<String> rawRequests = new CopyOnWriteArrayList<>();
    @TempDir
    Path traceDirectory;
    private String cacheErrorBody = "{\"error\":\"Unsupported parameter: prompt_cache_options\"}";
    private String successBody = "data: {\"type\":\"response.completed\",\"response\":{\"id\":\"response-id\","
            + "\"output\":[{\"content\":[{\"type\":\"output_text\",\"text\":\"OK\"}]}]}}\n\n";
    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) server.stop(0);
    }

    @Test
    void sendsModelSpecificCacheOptionsAndOnlyUserOnBranches() throws Exception {
        OpenAiResponsesApiClient client = startClient(false);
        client.createParent("gpt-5-nano", "system", "bootstrap");
        client.createParent("gpt-5.6-luna", "system", "bootstrap");
        client.continueFrom("gpt-5.6-luna", "response-id", "json");

        JsonNode nano = requests.get(0);
        assertEquals("gpt-5-nano", nano.path("model").asText());
        assertEquals("in_memory", nano.path("prompt_cache_retention").asText());
        assertFalse(nano.has("prompt_cache_options"));
        JsonNode luna = requests.get(1);
        assertEquals("gpt-5.6-luna", luna.path("model").asText());
        assertEquals("30m", luna.path("prompt_cache_options").path("ttl").asText());
        assertFalse(luna.has("prompt_cache_retention"));
        assertNotEquals(nano.path("prompt_cache_key"), luna.path("prompt_cache_key"));
        assertEquals("system", luna.path("input").get(0).path("role").asText());
        JsonNode branch = requests.get(2);
        assertEquals("gpt-5.6-luna", branch.path("model").asText());
        assertEquals("response-id", branch.path("previous_response_id").asText());
        assertEquals(luna.path("prompt_cache_key"), branch.path("prompt_cache_key"));
        assertEquals(1, branch.path("input").size());
        assertEquals("user", branch.path("input").get(0).path("role").asText());
        assertTrue(branch.path("store").asBoolean());
    }

    @Test
    void scopesCacheKeysBySystemPromptAndModelAndReusesTheParentKeyOnBranches() throws Exception {
        OpenAiResponsesApiClient client = startClient(false);

        for (String systemPrompt : List.of("bearing-prompt", "general-prompt")) {
            for (String model : List.of("gpt-5-nano", "gpt-5.6-luna")) {
                client.createParent(model, systemPrompt, "bootstrap");
                client.continueFrom(model, "response-id", "json");
            }
        }

        HashSet<String> parentKeys = new HashSet<>();
        for (int i = 0; i < requests.size(); i += 2) {
            String parentKey = requests.get(i).path("prompt_cache_key").asText();
            String branchKey = requests.get(i + 1).path("prompt_cache_key").asText();
            assertFalse(parentKey.isBlank());
            assertEquals(parentKey, branchKey);
            parentKeys.add(parentKey);
        }
        assertEquals(4, parentKeys.size());
    }

    @Test
    void rejectedLunaCacheOptionsDoNotDisableNanoCachingOrChangeModel() throws Exception {
        OpenAiResponsesApiClient client = startClient(true);
        client.createParent("gpt-5.6-luna", "system", "bootstrap");
        client.continueFrom("gpt-5.6-luna", "luna-parent", "json");
        client.createParent("gpt-5-nano", "system", "bootstrap");

        assertEquals(4, requests.size());
        for (int i : List.of(1, 2)) {
            JsonNode request = requests.get(i);
            assertEquals("gpt-5.6-luna", request.path("model").asText());
            assertFalse(request.has("prompt_cache_options"));
            assertFalse(request.has("prompt_cache_key"));
        }
        assertEquals("luna-parent", requests.get(2).path("previous_response_id").asText());
        assertEquals("in_memory", requests.get(3).path("prompt_cache_retention").asText());
        assertTrue(requests.get(3).has("prompt_cache_key"));
    }

    @Test
    void traceRecordsBothCacheFallbackAttemptsExactlyAndRedactsErrorCredentials() throws Exception {
        cacheErrorBody = "{\"error\":\"Unsupported parameter: prompt_cache_options; token=test-key\"}";
        OpenAiResponsesApiClient client = startClient(true);
        client.configureTraceDirectory(traceDirectory.toString());

        assertEquals("OK", client.createParent("gpt-5.6-luna", "system", "bootstrap").outputText());

        List<Path> requestFiles = traceFiles("-request.json");
        assertEquals(2, requestFiles.size());
        assertEquals(2, traceFiles("-result.json").size());
        for (Path requestFile : requestFiles) {
            String exactRequest = Files.readString(requestFile);
            assertTrue(rawRequests.contains(exactRequest), "Saved JSON must match the actual HTTP body byte for byte");
            JsonNode request = mapper.readTree(exactRequest);
            JsonNode outcome = pairedOutcome(requestFile);
            boolean rejected = request.has("prompt_cache_options");
            assertEquals(rejected ? "http_failure" : "success", outcome.path("status").asText());
            assertEquals(rejected ? 400 : 200, outcome.path("httpStatus").asInt());
            if (rejected) {
                assertTrue(outcome.path("error").path("message").asText().contains("[REDACTED]"));
            } else {
                assertEquals("response-id", outcome.path("responseResult").path("id").asText());
                assertEquals("OK", outcome.path("responseResult").path("outputText").asText());
            }
            assertFalse(exactRequest.contains("test-key"));
            assertFalse(outcome.toString().contains("test-key"));
            assertFalse(exactRequest.contains("Authorization"));
        }
    }

    @Test
    void tracePairsConcurrentAttemptsWithoutOverwritingInputs() throws Exception {
        OpenAiResponsesApiClient client = startClient(false);
        client.configureTraceDirectory(traceDirectory.toString());
        ExecutorService executor = Executors.newFixedThreadPool(4);
        try {
            List<CompletableFuture<ResponsesApiClient.ResponseResult>> futures = IntStream.range(0, 12)
                    .mapToObj(i -> CompletableFuture.supplyAsync(
                            () -> client.continueFrom("gpt-5-nano", "parent", "分组JSON-" + i), executor))
                    .toList();
            futures.forEach(future -> assertEquals("OK", future.join().outputText()));
        } finally {
            executor.shutdownNow();
        }

        List<Path> requestFiles = traceFiles("-request.json");
        assertEquals(12, requestFiles.size());
        assertEquals(12, traceFiles("-result.json").size());
        HashSet<String> savedRequests = new HashSet<>();
        HashSet<String> attemptIds = new HashSet<>();
        for (Path requestFile : requestFiles) {
            savedRequests.add(Files.readString(requestFile));
            JsonNode outcome = pairedOutcome(requestFile);
            assertTrue(attemptIds.add(outcome.path("attemptId").asText()));
            assertEquals("success", outcome.path("status").asText());
            assertFalse(Files.readString(requestFile).contains("test-key"));
        }
        assertEquals(new HashSet<>(rawRequests), savedRequests);
    }

    @Test
    void traceCapturesMalformedSuccessStreamAsException() throws Exception {
        successBody = "data: {\"type\":\"response.output_text.delta\",\"delta\":\"partial\"}\n\n";
        OpenAiResponsesApiClient client = startClient(false);
        client.configureTraceDirectory(traceDirectory.toString());

        assertThrows(ResponsesApiException.class,
                () -> client.continueFrom("gpt-5-nano", "parent", "group"));

        JsonNode outcome = pairedOutcome(traceFiles("-request.json").get(0));
        assertEquals("exception", outcome.path("status").asText());
        assertEquals(200, outcome.path("httpStatus").asInt());
        assertTrue(outcome.path("error").path("message").asText().contains("response.completed"));
    }

    @Test
    void enabledTraceMustSaveInputBeforeSendingHttpRequest() throws Exception {
        OpenAiResponsesApiClient client = startClient(false);
        Path regularFile = traceDirectory.resolve("not-a-directory");
        Files.writeString(regularFile, "occupied");
        client.configureTraceDirectory(regularFile.toString());

        assertThrows(ResponsesApiException.class,
                () -> client.continueFrom("gpt-5-nano", "parent", "group"));
        assertTrue(requests.isEmpty());
    }

    private List<Path> traceFiles(String suffix) throws Exception {
        try (var files = Files.list(traceDirectory)) {
            return files.filter(path -> path.getFileName().toString().endsWith(suffix)).toList();
        }
    }

    private JsonNode pairedOutcome(Path requestFile) throws Exception {
        String fileName = requestFile.getFileName().toString();
        JsonNode outcome = mapper.readTree(Files.readString(
                requestFile.resolveSibling(fileName.replace("-request.json", "-result.json"))));
        assertEquals(fileName, outcome.path("requestFile").asText());
        return outcome;
    }

    private OpenAiResponsesApiClient startClient(boolean rejectLunaCache) throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/responses", exchange -> {
            String rawRequest = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            rawRequests.add(rawRequest);
            JsonNode request = mapper.readTree(rawRequest);
            requests.add(request);
            boolean reject = rejectLunaCache && request.has("prompt_cache_options");
            String body = reject ? cacheErrorBody : successBody;
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(reject ? 400 : 200, bytes.length);
            try (var output = exchange.getResponseBody()) {
                output.write(bytes);
            }
            exchange.close();
        });
        server.start();
        return new OpenAiResponsesApiClient(mapper, "http://127.0.0.1:" + server.getAddress().getPort(),
                "test-key", "disease-summary-test", "in_memory", 10);
    }
}
