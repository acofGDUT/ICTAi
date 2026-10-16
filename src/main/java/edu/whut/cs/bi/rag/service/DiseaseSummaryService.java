package edu.whut.cs.bi.rag.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import jakarta.annotation.PreDestroy;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class DiseaseSummaryService {
    private static final Logger log = LoggerFactory.getLogger(DiseaseSummaryService.class);
    private static final int BEARING_VALIDATION_MAX_ATTEMPTS = 3;
    private static final int LARGE_MODEL_MAX_ATTEMPTS = 3;
    private static final String BOOTSTRAP_MESSAGE =
            "下面是JSON信息。后续请求按指定汇总模式提供分组：上部承重构件按biObjectName与position组合，其他部位仅按biObjectName；本轮无需生成病害小结，只回复“已准备”。";
    private final ObjectMapper objectMapper;
    private final ResponsesApiClient responsesApiClient;
    private final Duration parentMaxAge;
    private final ExecutorService branchExecutor;
    private final String smallGroupModel;
    private final String largeGroupModel;
    private final int largeGroupThreshold;
    private final long largeModelRetryDelayMillis;
    private final Map<ParentKey, ParentSlot> parents = new ConcurrentHashMap<>();

    public DiseaseSummaryService(
            ObjectMapper objectMapper,
            ResponsesApiClient responsesApiClient,
            @Value("${disease-summary.parent-max-age-seconds:86400}") long parentMaxAgeSeconds,
            @Value("${disease-summary.parallelism:3}") int parallelism,
            @Value("${disease-summary.small-group-model:gpt-5-nano}") String smallGroupModel,
            @Value("${disease-summary.large-group-model:gpt-5.6-luna}") String largeGroupModel,
            @Value("${disease-summary.large-group-threshold:40}") int largeGroupThreshold,
            @Value("${disease-summary.large-model-retry-delay-ms:2000}") long largeModelRetryDelayMillis) {
        if (largeGroupThreshold < 0 || smallGroupModel.isBlank() || largeGroupModel.isBlank()
                || largeModelRetryDelayMillis < 0 || largeModelRetryDelayMillis > 60000) {
            throw new IllegalArgumentException("病害小结模型配置不合法");
        }
        this.objectMapper = objectMapper;
        this.responsesApiClient = responsesApiClient;
        this.parentMaxAge = Duration.ofSeconds(parentMaxAgeSeconds);
        this.smallGroupModel = smallGroupModel;
        this.largeGroupModel = largeGroupModel;
        this.largeGroupThreshold = largeGroupThreshold;
        this.largeModelRetryDelayMillis = largeModelRetryDelayMillis;
        this.branchExecutor = Executors.newFixedThreadPool(Math.max(1, parallelism), new SummaryThreadFactory());
    }

    public String summarize(String jsonData, String systemPrompt) {
        GroupedInput groupedInput = groupInput(jsonData);
        List<CompletableFuture<String>> futures = new ArrayList<>();
        for (Map.Entry<GroupKey, ArrayNode> group : groupedInput.groups().entrySet()) {
            futures.add(CompletableFuture.supplyAsync(
                    () -> groupedInput.bearing() ? summarizeBearingGroup(group)
                            : summarizeGroup(group, systemPrompt, buildGroupMessage(group.getKey(), group.getValue())), branchExecutor));
        }
        try {
            List<String> summaries = futures.stream().map(CompletableFuture::join).toList();
            if (groupedInput.bearing()) {
                return BearingSummarySupport.merge(new ArrayList<>(groupedInput.groups().keySet()),
                        summaries, groupedInput.componentOrder());
            }
            return normalizeSingularMeasureWording(
                    mergeSummaries(summaries, groupedInput.componentOrder()));
        } catch (CompletionException exception) {
            futures.forEach(future -> future.cancel(true));
            if (exception.getCause() instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw exception;
        }
    }

    private String summarizeBearingGroup(Map.Entry<GroupKey, ArrayNode> group) {
        List<BearingSummarySupport.Category> categories = BearingSummarySupport.categories(group.getValue());
        String baseMessage = buildBearingGroupMessage(group.getKey(), group.getValue(), categories);
        String userMessage = baseMessage;
        List<String> errors = List.of();
        // 内容校验最多三轮；运输错误继续由原有网络重试策略处理。
        for (int attempt = 1; attempt <= BEARING_VALIDATION_MAX_ATTEMPTS; attempt++) {
            String output = summarizeGroup(group, BearingSummarySupport.SYSTEM_PROMPT, userMessage);
            BearingSummarySupport.Validation validation = BearingSummarySupport.validate(output, categories);
            if (validation.valid()) {
                log.info("承重小结校验通过 component={} position={} attempt={}",
                        group.getKey().component(), group.getKey().position(), attempt);
                return validation.body();
            }
            errors = validation.errors();
            log.warn("承重小结校验失败 component={} position={} attempt={}/{} errors={}",
                    group.getKey().component(), group.getKey().position(), attempt,
                    BEARING_VALIDATION_MAX_ATTEMPTS, errors);
            userMessage = baseMessage + "\n\n上次输出未通过程序校验：" + String.join("；", errors)
                    + "。请重新输出本组全部类别，修正上述问题，只返回病害正文。";
        }
        throw new SummaryValidationException("承重小结校验失败：" + group.getKey().component()
                + "／" + group.getKey().position() + "：" + String.join("；", errors));
    }

    String buildBearingGroupMessage(GroupKey key, ArrayNode records, List<BearingSummarySupport.Category> categories) {
        ArrayNode modelRecords = records.deepCopy();
        modelRecords.forEach(record -> ((ObjectNode) record).remove("id"));
        try {
            return "本组构件：" + key.component() + "；本组位置：" + key.position()
                    + "。构件、位置将由程序添加，输出中不要重复。\n"
                    + "病害JSON：\n" + objectMapper.writeValueAsString(modelRecords)
                    + "\n\n输出清单（逐类核对，数量以此为准）：\n" + BearingSummarySupport.checklist(categories)
                    + "\n请按清单顺序输出，每类一个短句，短句以类别名称、数量和单位开头，用分号连接。";
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("无法序列化承重分组", exception);
        }
    }

    private String summarizeGroup(
            Map.Entry<GroupKey, ArrayNode> group, String systemPrompt, String userMessage) {
        String model = selectModel(group.getValue().size());
        log.info("病害小结模型路由 component={} position={} records={} threshold={} model={}",
                group.getKey().component(), group.getKey().position(), group.getValue().size(), largeGroupThreshold, model);
        if (model.equals(largeGroupModel) && !model.equals(smallGroupModel)) {
            for (int attempt = 1; attempt <= LARGE_MODEL_MAX_ATTEMPTS; attempt++) {
                ParentState parent = null;
                try {
                    parent = ensureParent(model, systemPrompt);
                    // 大模型只由本层重试，避免与旧分组重试叠加为 3×2 次。
                    ResponsesApiClient.ResponseResult result =
                            responsesApiClient.continueFrom(model, parent.id(), userMessage);
                    return finishGroup(group, parent, result);
                } catch (ResponsesApiException exception) {
                    if (Thread.currentThread().isInterrupted()) {
                        throw exception;
                    }
                    if (parent != null && exception.mayBeMissingPreviousResponse()) {
                        invalidateParent(parent);
                    }
                    log.warn("病害小结大模型尝试失败 component={} position={} model={} attempt={}/{} stage={}: {}",
                            group.getKey().component(), group.getKey().position(), model, attempt, LARGE_MODEL_MAX_ATTEMPTS,
                            parent == null ? "parent" : "summary", exception.getMessage());
                    if (attempt < LARGE_MODEL_MAX_ATTEMPTS) {
                        waitBeforeLargeModelRetry(attempt);
                    }
                }
            }
            log.warn("病害小结大模型连续 {} 次失败，回退 component={} position={} records={} fromModel={} toModel={}",
                    LARGE_MODEL_MAX_ATTEMPTS, group.getKey().component(), group.getKey().position(),
                    group.getValue().size(), model, smallGroupModel);
            model = smallGroupModel;
        }
        // 小模型沿用现有的分组重试；回退时独立获取它自己的父响应。
        ParentState parent = ensureParent(model, systemPrompt);
        ResponsesApiClient.ResponseResult result;
        try {
            result = callBranchWithOneRetry(parent, userMessage);
        } catch (ResponsesApiException exception) {
            if (!exception.mayBeMissingPreviousResponse()) {
                throw exception;
            }
            invalidateParent(parent);
            parent = ensureParent(model, systemPrompt);
            result = callBranchWithOneRetry(parent, userMessage);
        }
        return finishGroup(group, parent, result);
    }

    private String finishGroup(Map.Entry<GroupKey, ArrayNode> group, ParentState parent,
                               ResponsesApiClient.ResponseResult result) {
        logCacheMiss(result.cachedTokens());
        log.info("病害小结 component={} position={} records={} model={} parentResponseId={} responseId={} inputTokens={} cachedTokens={}",
                group.getKey().component(), group.getKey().position(), group.getValue().size(), parent.model(), parent.id(), result.id(),
                result.inputTokens(), result.cachedTokens());
        return result.outputText();
    }

    private void waitBeforeLargeModelRetry(int failedAttempts) {
        try {
            // 默认两次间隔分别为 2 秒、4 秒；中断时终止任务，不触发回退。
            Thread.sleep(largeModelRetryDelayMillis * failedAttempts);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new ResponsesApiException("病害小结重试等待被中断", exception);
        }
    }

    String selectModel(int groupRecordCount) {
        return groupRecordCount > largeGroupThreshold ? largeGroupModel : smallGroupModel;
    }

    @PreDestroy
    void shutdownExecutor() {
        branchExecutor.shutdownNow();
    }

    private ResponsesApiClient.ResponseResult callBranchWithOneRetry(ParentState parent, String userMessage) {
        try {
            return responsesApiClient.continueFrom(parent.model(), parent.id(), userMessage);
        } catch (ResponsesApiException exception) {
            if (Thread.currentThread().isInterrupted() || !exception.isRetryable()) {
                throw exception;
            }
            log.warn("病害小结分组请求失败，使用同一父响应重试一次: {}", exception.getMessage());
            return responsesApiClient.continueFrom(parent.model(), parent.id(), userMessage);
        }
    }

    private ParentState ensureParent(String model, String systemPrompt) {
        String promptFingerprint = fingerprint(systemPrompt);
        ParentSlot slot = parents.computeIfAbsent(new ParentKey(model, promptFingerprint), ignored -> new ParentSlot());
        synchronized (slot) {
            ParentState parentState = slot.state;
            Instant now = Instant.now();
            boolean shouldCreate = parentState == null
                    || !parentState.promptFingerprint().equals(promptFingerprint)
                    || Duration.between(parentState.createdAt(), now).compareTo(parentMaxAge) >= 0;
            if (shouldCreate) {
                ResponsesApiClient.ResponseResult parentResult =
                        responsesApiClient.createParent(model, systemPrompt, BOOTSTRAP_MESSAGE);
                slot.state = new ParentState(model, parentResult.id(), promptFingerprint, now);
                log.info("病害小结公共父响应已创建 model={} id={} inputTokens={} cachedTokens={}",
                        model, shortId(parentResult.id()), parentResult.inputTokens(), parentResult.cachedTokens());
            }
            return slot.state;
        }
    }

    private void logCacheMiss(long cachedTokens) {
        if (cachedTokens == 0) {
            log.info("本次父响应前缀未报告缓存命中；该请求本身已重新计算并可供后续同前缀请求复用");
        }
    }

    private void invalidateParent(ParentState parent) {
        ParentSlot slot = parents.get(new ParentKey(parent.model(), parent.promptFingerprint()));
        synchronized (slot) {
            ParentState parentState = slot.state;
            if (parentState != null && parentState.id().equals(parent.id())) {
                slot.state = null;
            }
        }
    }

    GroupedInput groupInput(String jsonData) {
        JsonNode root;
        try {
            root = objectMapper.readTree(jsonData);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("病害数据不是合法 JSON", exception);
        }
        // 旧数组请求缺少部位信息，兼容原有构件＋位置模式，不根据构件名猜测部位。
        boolean includePosition = true;
        boolean bearing = false;
        if (root != null && root.isObject()) {
            JsonNode section = root.path("sectionName");
            if (!section.isTextual() || section.asText().isBlank()) {
                throw new IllegalArgumentException("病害请求对象必须提供非空 sectionName");
            }
            String sectionName = section.asText().trim();
            includePosition = "上部承重构件".equals(sectionName) || "上部承重结构".equals(sectionName);
            bearing = includePosition;
            root = root.path("diseases");
        }
        if (root == null || !root.isArray()) {
            throw new IllegalArgumentException("病害数据必须是 JSON 数组，或包含 sectionName 和 diseases 数组的对象");
        }
        if (root.isEmpty()) {
            throw new IllegalArgumentException("病害数据不能为空数组");
        }

        LinkedHashMap<GroupKey, ArrayNode> groups = new LinkedHashMap<>();
        LinkedHashSet<String> components = new LinkedHashSet<>();
        for (JsonNode item : root) {
            if (!item.isObject()) {
                throw new IllegalArgumentException("病害数组中的每一项都必须是 JSON 对象");
            }
            quantityOf(item);
            String component = item.path("biObjectName").asText("").trim();
            String position = item.path("position").asText("").trim();
            // 用双字段键，避免名称含分隔符时串组；空位置仅在提示中显示占位名称。
            GroupKey key = new GroupKey(component, includePosition ? position : null);
            groups.computeIfAbsent(key, ignored -> objectMapper.createArrayNode()).add(item);
            if (!component.isEmpty()) {
                components.add(component);
            }
        }
        groups.replaceAll((key, records) -> {
            List<JsonNode> sorted = new ArrayList<>();
            records.forEach(sorted::add);
            // 稳定排序，同一 type 内保留原顺序；不改写任何原始记录。
            sorted.sort(Comparator.comparing(item -> item.path("type").asText("")));
            return objectMapper.createArrayNode().addAll(sorted);
        });
        return new GroupedInput(groups, new ArrayList<>(components), bearing);
    }

    String buildGroupMessage(GroupKey key, ArrayNode records) {
        // 只精简模型输入；保留原始 id 供校验、追溯使用。
        ArrayNode modelRecords = records.deepCopy();
        modelRecords.forEach(record -> {
            ((ObjectNode) record).remove("id");
            if (!key.includesPosition()) {
                // 非承重部位不以位置分类；仅移除模型副本中的位置，原始记录保持完整。
                ((ObjectNode) record).remove("position");
            }
        });
        try {
            String scope = key.includesPosition()
                    ? "本轮汇总模式：构件＋位置。本轮只包含一个biObjectName与position组合：biObjectName为“"
                    + key.component() + "”，position为“"
                    + (key.position().isEmpty() ? "未指定位置" : key.position()) + "”。"
                    : "本轮汇总模式：仅构件。biObjectName为“" + key.component()
                    + "”。本组已包含该构件各位置的病害，按type及裂缝方向跨位置合并统计；"
                    + "不得按position或description中的位置再次拆分，不在小结中逐项列位置名。";
            String heading = key.component().isEmpty() ? ""
                    : "只输出一个构件段落，必须以“1）" + key.component()
                    + "：”开头；构件名不得拼接位置名，不得改写为其他构件名称。";
            return "请生成本轮病害小结。" + scope + heading
                    + "只处理以下JSON记录，不累加其他分组或轮次的病害。JSON：\n"
                    + objectMapper.writeValueAsString(modelRecords)
                    + "\n\n本组各 type 的数量统计（程序按 quantity 求和，以下不是新增病害，不得重复累加）：\n"
                    + objectMapper.writeValueAsString(buildQuantityCounts(records))
                    + "\n请按系统规则输出本轮最终小结：数量以 quantityTotal 为准，按构件/方向取匹配分项。"
                    + "每个聚合类别只输出一次汇总，不列原始明细，不另写合计。"
                    + "任何病害只要 description 含长度、面积或宽度就按规则汇总；除此之外只输出名称和数量，不输出角度、比例或其他参数。"
                    + "数量为1条或1处时写“长度/面积”，不得带“总”字；数量大于1时才写“总长度/总面积”。";
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("无法序列化病害分组", exception);
        }
    }

    ArrayNode buildQuantityCounts(ArrayNode records) {
        record DetailKey(String component, String crackType) {}
        LinkedHashMap<String, ObjectNode> totals = new LinkedHashMap<>();
        Map<String, LinkedHashMap<DetailKey, ObjectNode>> details = new LinkedHashMap<>();
        for (JsonNode item : records) {
            String type = item.path("type").asText("");
            long quantity = quantityOf(item);
            ObjectNode total = totals.computeIfAbsent(type, ignored -> {
                ObjectNode node = objectMapper.createObjectNode();
                node.put("type", type);
                node.put("recordCount", 0);
                node.put("quantityTotal", 0L);
                return node;
            });
            DetailKey key = new DetailKey(item.path("biObjectName").asText(""), item.path("crackType").asText(""));
            ObjectNode detail = details.computeIfAbsent(type, ignored -> new LinkedHashMap<>())
                    .computeIfAbsent(key, ignored -> {
                        ObjectNode node = objectMapper.createObjectNode();
                        node.put("biObjectName", key.component());
                        node.put("crackType", key.crackType());
                        node.put("recordCount", 0);
                        node.put("quantityTotal", 0L);
                        return node;
                    });
            for (ObjectNode count : List.of(total, detail)) {
                count.put("recordCount", count.path("recordCount").asInt() + 1);
                try {
                    count.put("quantityTotal", Math.addExact(count.path("quantityTotal").asLong(), quantity));
                } catch (ArithmeticException exception) {
                    throw new IllegalArgumentException("病害数量合计超出整数范围", exception);
                }
            }
        }
        ArrayNode result = objectMapper.createArrayNode();
        totals.forEach((type, total) -> {
            ArrayNode breakdown = total.putArray("details");
            details.get(type).values().forEach(breakdown::add);
            result.add(total);
        });
        return result;
    }

    private long quantityOf(JsonNode item) {
        JsonNode quantity = item.path("quantity");
        if (!quantity.isIntegralNumber() || !quantity.canConvertToLong() || quantity.asLong() < 0) {
            throw new IllegalArgumentException("病害 quantity 必须是非负整数，id=" + item.path("id").asText());
        }
        return quantity.asLong();
    }

    static String mergeSummaries(List<String> summaries, List<String> componentOrder) {
        LinkedHashMap<String, List<String>> bodiesByComponent = new LinkedHashMap<>();
        for (String component : componentOrder) {
            bodiesByComponent.put(component, new ArrayList<>());
        }

        boolean parsedAll = true;
        for (String summary : summaries) {
            List<SummaryBlock> blocks = parseSummaryBlocks(summary);
            if (blocks.isEmpty()) {
                parsedAll = false;
                break;
            }
            for (SummaryBlock block : blocks) {
                List<String> bodies = bodiesByComponent.get(block.component());
                if (bodies == null) {
                    parsedAll = false;
                    break;
                }
                bodies.add(trimEndingPunctuation(block.body()));
            }
            if (!parsedAll) {
                break;
            }
        }

        if (!parsedAll || bodiesByComponent.values().stream().allMatch(List::isEmpty)) {
            return String.join("\n", summaries.stream().map(String::trim).filter(value -> !value.isEmpty()).toList());
        }

        List<String> merged = new ArrayList<>();
        int sequence = 1;
        for (Map.Entry<String, List<String>> entry : bodiesByComponent.entrySet()) {
            if (entry.getValue().isEmpty()) {
                continue;
            }
            merged.add(sequence++ + "）" + entry.getKey() + "："
                    + String.join("；", entry.getValue()) + "。");
        }
        return String.join("\n", merged);
    }

    static String normalizeSingularMeasureWording(String summary) {
        if (summary == null || summary.isEmpty()) {
            return summary;
        }
        return summary.replaceAll("(?<!\\d)(1[处条])，总(长度|面积)", "$1，$2");
    }

    private static List<SummaryBlock> parseSummaryBlocks(String summary) {
        String normalized = summary == null ? "" : summary.trim();
        if (normalized.isEmpty()) {
            return List.of();
        }
        Pattern pattern = Pattern.compile(
                "(?:^|\\R)\\s*\\d+[）)]\\s*([^：:\\r\\n]+)[：:]\\s*(.*?)(?=(?:\\R\\s*\\d+[）)])|\\z)",
                Pattern.DOTALL);
        Matcher matcher = pattern.matcher(normalized);
        List<SummaryBlock> blocks = new ArrayList<>();
        int coveredUntil = 0;
        while (matcher.find()) {
            if (!normalized.substring(coveredUntil, matcher.start()).trim().isEmpty()) {
                return List.of();
            }
            blocks.add(new SummaryBlock(matcher.group(1).trim(), matcher.group(2).trim()));
            coveredUntil = matcher.end();
        }
        if (blocks.isEmpty() || !normalized.substring(coveredUntil).trim().isEmpty()) {
            return List.of();
        }
        return blocks;
    }

    private static String trimEndingPunctuation(String value) {
        return value.trim().replaceFirst("[。；;]+$", "");
    }

    private static String fingerprint(String prompt) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(prompt.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("JVM 不支持 SHA-256", exception);
        }
    }

    private static String shortId(String id) {
        return id.length() <= 16 ? id : id.substring(0, 16) + "...";
    }

    // position 为 null 表示仅按构件汇总；空字符串表示承重构件的未指定位置分组。
    record GroupKey(String component, String position) {
        boolean includesPosition() {
            return position != null;
        }
    }

    record GroupedInput(LinkedHashMap<GroupKey, ArrayNode> groups, List<String> componentOrder, boolean bearing) {
    }

    private record ParentKey(String model, String promptFingerprint) {}

    private static class ParentSlot {
        private ParentState state;
    }

    private record ParentState(String model, String id, String promptFingerprint, Instant createdAt) {}

    private record SummaryBlock(String component, String body) {
    }

    private static class SummaryThreadFactory implements ThreadFactory {
        private final AtomicInteger sequence = new AtomicInteger();

        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "disease-summary-" + sequence.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        }
    }
}
