package edu.whut.cs.bi.rag.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.Set;

/**
 * 从 BuildingInspector 预筛选的《桥梁病害成因分析与处治建议》候选条目中选出与本次病害最接近的一条。
 * 只返回候选 id，成因和处治建议原文由调用方按 id 取用。
 */
@Service
public class DiseaseCauseMatchService {
    private static final Logger log = LoggerFactory.getLogger(DiseaseCauseMatchService.class);
    private static final int MAX_ATTEMPTS = 2;
    static final String SYSTEM_PROMPT = """
            你是桥梁检测报告的病害成因匹配助手。
            输入 JSON 含 disease 和 candidates：
            - disease 是本次病害：template 桥梁模板，path 构件路径（结构/部件/构件），biObjectName 构件名称，position 位置，type 病害类型，crackType 裂缝方向（仅裂缝），level 评定标度，description 病害描述。
            - candidates 是从《桥梁病害成因分析与处治建议》预筛选的条目：id，sheet，category 类型，component 部件，defectType 缺损类型，defectName 病害名称，condition 判定条件，cause 成因。
            任务：从 candidates 中选出与本次病害最接近的一条。判断依据按优先级：
            1. 病害性质一致：缺损类型、病害名称与病害类型和描述说的是同一种病害；裂缝要区分方向和所在位置（如纵向/横向/竖向/斜向，腹板/顶板/底板/翼缘/支点/跨中）。
            2. 部件与构件路径、构件名称、位置一致。
            3. 判定条件与描述相符。
            没有任何候选描述同一种病害时返回 null，不要勉强选择。
            只输出一个 JSON 对象，不要输出其他文字：{"matchedId":"候选id或null","reason":"不超过60字的匹配理由"}""";

    private final ObjectMapper objectMapper;
    private final ResponsesApiClient responsesApiClient;
    private final String model;
    private final String reasoningEffort;

    /**
     * 默认值依据 7 条带标注样例：gpt-5.6-luna + low 全对、均约 4s；gpt-5-nano/gpt-5-mini 会漏选或选错部件子类型。
     */
    public DiseaseCauseMatchService(ObjectMapper objectMapper, ResponsesApiClient responsesApiClient,
                                    @Value("${disease-cause-match.model:gpt-5.6-luna}") String model,
                                    @Value("${disease-cause-match.reasoning-effort:low}") String reasoningEffort) {
        if (model.isBlank()) {
            throw new IllegalArgumentException("病害成因匹配模型未配置");
        }
        this.objectMapper = objectMapper;
        this.responsesApiClient = responsesApiClient;
        this.model = model;
        this.reasoningEffort = reasoningEffort;
    }

    /**
     * @param jsonData {"disease":{...},"candidates":[{"id":...},...]}
     * @return {"matchedId":"候选id或null","reason":"..."}
     */
    public String match(String jsonData) {
        JsonNode input;
        try {
            input = objectMapper.readTree(jsonData);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("请求不是合法 JSON", exception);
        }
        if (input == null || !input.path("disease").isObject() || !input.path("candidates").isArray()
                || input.path("candidates").isEmpty()) {
            throw new IllegalArgumentException("请求需包含 disease 对象和非空 candidates 数组");
        }
        Set<String> candidateIds = new HashSet<>();
        for (JsonNode candidate : input.path("candidates")) {
            String id = candidate.path("id").asText("");
            if (id.isBlank()) {
                throw new IllegalArgumentException("候选条目缺少 id");
            }
            candidateIds.add(id);
        }

        String userMessage = input.toString();
        String lastError = "";
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            String output;
            try {
                output = responsesApiClient.respond(model, reasoningEffort, SYSTEM_PROMPT, userMessage).outputText();
            } catch (ResponsesApiException exception) {
                if (!exception.isRetryable() || attempt == MAX_ATTEMPTS) {
                    throw exception;
                }
                log.warn("病害成因匹配第 {} 次请求失败，重试: {}", attempt, exception.getMessage());
                continue;
            }
            try {
                return parseAnswer(output, candidateIds).toString();
            } catch (IllegalStateException exception) {
                lastError = exception.getMessage();
                log.warn("病害成因匹配第 {} 次输出无效: {}", attempt, lastError);
            }
        }
        throw new ResponsesApiException("病害成因匹配输出无效：" + lastError, 502);
    }

    private ObjectNode parseAnswer(String output, Set<String> candidateIds) {
        String text = output == null ? "" : output.trim();
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start < 0 || end < start) {
            throw new IllegalStateException("未找到 JSON 对象：" + abbreviate(text));
        }
        JsonNode answer;
        try {
            answer = objectMapper.readTree(text.substring(start, end + 1));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("JSON 解析失败：" + abbreviate(text));
        }
        JsonNode matchedId = answer.path("matchedId");
        String id = matchedId.isTextual() ? matchedId.asText().trim() : "";
        if ("null".equalsIgnoreCase(id)) {
            id = "";
        }
        if (!id.isEmpty() && !candidateIds.contains(id)) {
            throw new IllegalStateException("matchedId 不在候选中：" + id);
        }
        ObjectNode result = objectMapper.createObjectNode();
        if (id.isEmpty()) {
            result.putNull("matchedId");
        } else {
            result.put("matchedId", id);
        }
        result.put("reason", answer.path("reason").asText(""));
        return result;
    }

    private static String abbreviate(String value) {
        return value.length() <= 200 ? value : value.substring(0, 200) + "...";
    }
}
