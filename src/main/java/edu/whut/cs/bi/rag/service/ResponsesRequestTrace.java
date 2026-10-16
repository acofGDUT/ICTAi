package edu.whut.cs.bi.rag.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.UUID;

/** Optional per-HTTP-attempt evidence for real-model verification; never records headers. */
final class ResponsesRequestTrace {
    private final ObjectMapper mapper;
    private final Path directory;
    private final String apiKey;

    ResponsesRequestTrace(ObjectMapper mapper, String directory, String apiKey) {
        this.mapper = mapper;
        this.directory = directory == null || directory.isBlank() ? null : Path.of(directory);
        this.apiKey = apiKey;
    }

    Attempt begin(String requestJson) {
        if (directory == null) {
            return null;
        }
        Instant startedAt = Instant.now();
        String id = startedAt.toEpochMilli() + "-" + UUID.randomUUID();
        try {
            Files.createDirectories(directory);
            // These are the same UTF-8 JSON bytes passed to the HTTP body publisher.
            Files.writeString(directory.resolve(id + "-request.json"), requestJson, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            return new Attempt(id, startedAt);
        } catch (IOException exception) {
            throw new ResponsesApiException("无法保存 Responses API 测试请求记录", exception);
        }
    }

    final class Attempt {
        private final String id;
        private final Instant startedAt;

        private Attempt(String id, Instant startedAt) {
            this.id = id;
            this.startedAt = startedAt;
        }

        void success(int httpStatus, ResponsesApiClient.ResponseResult result) {
            ObjectNode outcome = outcome("success", httpStatus);
            ObjectNode response = outcome.putObject("responseResult");
            response.put("id", redact(result.id()));
            response.put("outputText", redact(result.outputText()));
            response.put("inputTokens", result.inputTokens());
            response.put("cachedTokens", result.cachedTokens());
            save(outcome);
        }

        void failure(Integer httpStatus, RuntimeException exception) {
            ObjectNode outcome = outcome(httpStatus != null && (httpStatus < 200 || httpStatus >= 300)
                    ? "http_failure" : "exception", httpStatus);
            ObjectNode error = outcome.putObject("error");
            error.put("type", exception.getClass().getSimpleName());
            error.put("message", redact(exception.getMessage()));
            if (exception.getCause() != null) {
                error.put("causeType", exception.getCause().getClass().getSimpleName());
                error.put("causeMessage", redact(exception.getCause().getMessage()));
            }
            save(outcome);
        }

        private ObjectNode outcome(String status, Integer httpStatus) {
            ObjectNode outcome = mapper.createObjectNode();
            outcome.put("attemptId", id);
            outcome.put("requestFile", id + "-request.json");
            outcome.put("startedAt", startedAt.toString());
            outcome.put("completedAt", Instant.now().toString());
            outcome.put("status", status);
            if (httpStatus != null) {
                outcome.put("httpStatus", httpStatus);
            }
            return outcome;
        }

        private void save(ObjectNode outcome) {
            try {
                Files.writeString(directory.resolve(id + "-result.json"),
                        mapper.writerWithDefaultPrettyPrinter().writeValueAsString(outcome),
                        StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            } catch (IOException exception) {
                throw new ResponsesApiException("无法保存 Responses API 测试结果记录", exception);
            }
        }
    }

    private String redact(String text) {
        if (text == null) {
            return null;
        }
        String redacted = apiKey == null || apiKey.isEmpty() ? text : text.replace(apiKey, "[REDACTED]");
        return redacted.replaceAll("(?i)Bearer\\s+[A-Za-z0-9._~+/-]+=*", "Bearer [REDACTED]");
    }
}
