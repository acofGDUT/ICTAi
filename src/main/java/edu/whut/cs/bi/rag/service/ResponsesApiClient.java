package edu.whut.cs.bi.rag.service;

public interface ResponsesApiClient {

    ResponseResult createParent(String model, String systemPrompt, String bootstrapMessage);

    ResponseResult continueFrom(String model, String previousResponseId, String userMessage);

    /**
     * 单轮请求，不作为后续 continueFrom 的父响应。
     *
     * @param reasoningEffort 推理强度（如 low），为空时使用模型默认值
     */
    default ResponseResult respond(String model, String reasoningEffort, String systemPrompt, String userMessage) {
        return createParent(model, systemPrompt, userMessage);
    }

    record ResponseResult(String id, String outputText, long inputTokens, long cachedTokens) {
    }
}
