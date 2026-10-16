package edu.whut.cs.bi.rag.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DiseaseCauseMatchServiceTest {
    private static final String REQUEST = """
            {"disease":{"template":"梁式桥","path":"上部结构/上部承重构件/T梁","position":"腹板","type":"裂缝","crackType":"竖向"},
             "candidates":[{"id":"BEAM-12","defectName":"腹板竖向裂缝"},{"id":"BEAM-13","defectName":"腹板斜向裂缝"}]}
            """;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void returnsCandidateIdFromFencedOutput() throws Exception {
        ResponsesApiClient client = mock(ResponsesApiClient.class);
        when(client.respond(eq("gpt-5-nano"), eq("low"), eq(DiseaseCauseMatchService.SYSTEM_PROMPT), contains("BEAM-12")))
                .thenReturn(result("```json\n{\"matchedId\":\"BEAM-12\",\"reason\":\"腹板竖向裂缝\"}\n```"));

        JsonNode answer = objectMapper.readTree(service(client).match(REQUEST));

        assertEquals("BEAM-12", answer.path("matchedId").asText());
        assertEquals("腹板竖向裂缝", answer.path("reason").asText());
    }

    @Test
    void stringNullMeansNoMatch() throws Exception {
        ResponsesApiClient client = mock(ResponsesApiClient.class);
        when(client.respond(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(result("{\"matchedId\":\"null\",\"reason\":\"无同类病害\"}"));

        JsonNode answer = objectMapper.readTree(service(client).match(REQUEST));

        assertTrue(answer.path("matchedId").isNull());
    }

    @Test
    void retriesOnceWhenIdIsNotACandidate() throws Exception {
        ResponsesApiClient client = mock(ResponsesApiClient.class);
        when(client.respond(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(result("{\"matchedId\":\"BEAM-99\"}"))
                .thenReturn(result("{\"matchedId\":\"BEAM-13\",\"reason\":\"斜向\"}"));

        JsonNode answer = objectMapper.readTree(service(client).match(REQUEST));

        assertEquals("BEAM-13", answer.path("matchedId").asText());
        verify(client, times(2)).respond(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void retriesOnceOnRetryableTransportError() throws Exception {
        ResponsesApiClient client = mock(ResponsesApiClient.class);
        when(client.respond(anyString(), anyString(), anyString(), anyString()))
                .thenThrow(new ResponsesApiException("Responses API 流式响应未包含 response.completed", 502))
                .thenReturn(result("{\"matchedId\":\"BEAM-12\",\"reason\":\"竖向\"}"));

        JsonNode answer = objectMapper.readTree(service(client).match(REQUEST));

        assertEquals("BEAM-12", answer.path("matchedId").asText());
        verify(client, times(2)).respond(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void failsAfterRepeatedInvalidOutput() {
        ResponsesApiClient client = mock(ResponsesApiClient.class);
        when(client.respond(anyString(), anyString(), anyString(), anyString())).thenReturn(result("无法判断"));

        ResponsesApiException exception = assertThrows(ResponsesApiException.class, () -> service(client).match(REQUEST));

        assertEquals(502, exception.getStatusCode());
        verify(client, times(2)).respond(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void rejectsRequestWithoutCandidates() {
        ResponsesApiClient client = mock(ResponsesApiClient.class);

        assertThrows(IllegalArgumentException.class,
                () -> service(client).match("{\"disease\":{},\"candidates\":[]}"));
        verify(client, never()).respond(anyString(), anyString(), anyString(), anyString());
    }

    private DiseaseCauseMatchService service(ResponsesApiClient client) {
        return new DiseaseCauseMatchService(objectMapper, client, "gpt-5-nano", "low");
    }

    private static ResponsesApiClient.ResponseResult result(String output) {
        return new ResponsesApiClient.ResponseResult("resp-1", output, 0, 0);
    }
}
