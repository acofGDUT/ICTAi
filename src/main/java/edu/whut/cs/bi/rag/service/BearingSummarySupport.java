package edu.whut.cs.bi.rag.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 承重分组仍由模型生成正文，程序只约束分类、完整性和报告组织。 */
final class BearingSummarySupport {
    private BearingSummarySupport() {}

    static final String SYSTEM_PROMPT = """
            你负责上部承重构件的病害小结。每轮只处理提供的一个构件、一个位置，不累计历史数据。
            父响应初始化消息没有实际病害JSON时，只回复“已准备”。
            本轮输出纯中文病害正文，不输出JSON、标题、构件名称、位置名称、序号、项目符号、解释或建议。
            程序负责添加构件和位置。严格使用本轮“输出清单”中的类别名称和数量单位，每类只写一个短句，短句用分号分隔。
            每个短句必须从“类别名称＋准确整数数量＋条/处”开始，再写该类别的尺寸。例如：
            纵向裂缝2条，总长度3m，宽度为0.14mm；剥落18处，总面积0.8547m²。
            类别及数量来自程序清单；description与分类或数量冲突时以清单为准，不从描述重新计数，不输出其他类别。
            清单指定的必需尺寸必须逐类输出，不能用其他类别的尺寸代替。实际描述另有长度、面积或裂缝宽度也应按规则汇总。
            长度、面积分别合计，宽度输出最小值至最大值。长度统一为m，面积为m²，宽度为mm。
            只提取病害本身尺寸，距梁端、距腹板、桩号、跨号等定位数值不得计入。L、L1、L_1等标记属于长度，S及长×宽属于面积，W属于裂缝宽度。
            每条记录只计算一次。描述已给总量时直接使用；S_1、S_2等多处分项相加一次，不再乘quantity；L型、U型的各病害线段相加，不能把定位距离算进去。
            每类仅在本类记录内计算，不能把一类面积、长度或宽度带入另一类。先逐条核算再求和，最多保留四位有效数字，去掉末尾无意义的零。
            数量为1条/1处时写“长度/面积”，大于1时写“总长度/总面积”。同类宽度一致写“宽度为Xmm”，不一致写“宽度介于X～Ymm”，不得只写最大宽度。
            不输出角度、比例等其他参数。按输出清单顺序给出全部短句，不列原始明细，不另写合计。
            输出前逐类检查：名称一致、数量单位齐全、必需尺寸齐全，不展示检查过程。
            """;

    private static final String NUMBER = "\\d+(?:\\.\\d+)?";
    private static final Pattern LENGTH_SOURCE = Pattern.compile(
            "(?i)(?<![a-z])L(?:_?\\d+)?\\s*[=＝:]\\s*" + NUMBER
                    + "|(?:总)?长度\\s*(?:为|约|[=＝:：])?\\s*" + NUMBER);
    private static final Pattern AREA_SOURCE = Pattern.compile(
            "(?i)(?<![a-z])S(?:_?\\d+)?\\s*[=＝:]\\s*" + NUMBER
                    + "|(?:总)?面积\\s*(?:S(?:_?\\d+)?)?\\s*(?:为|约|[=＝:：])?\\s*" + NUMBER);
    private static final Pattern WIDTH_SOURCE = Pattern.compile(
            "(?i)(?<![a-z])W(?:_?\\d+)?\\s*[=＝:]\\s*" + NUMBER
                    + "|(?:裂缝)?宽度\\s*(?:为|约|介于|[=＝:：])?\\s*" + NUMBER);
    private static final String LENGTH_OUTPUT = "(?:总)?长度\\s*(?:为)?\\s*" + NUMBER + "\\s*m(?![a-zA-Z²2^])";
    private static final String AREA_OUTPUT = "(?:总)?面积\\s*(?:为)?\\s*" + NUMBER + "\\s*m²";
    private static final String WIDTH_OUTPUT = "宽度\\s*(?:为|介于)?\\s*" + NUMBER
            + "(?:\\s*[～~—-]\\s*" + NUMBER + ")?\\s*mm";
    private static final String OUTPUT_PARAMETER = "(?:" + LENGTH_OUTPUT + "|" + AREA_OUTPUT + "|" + WIDTH_OUTPUT + ")";
    private static final Pattern OUTPUT_PARAMETER_PATTERN = Pattern.compile(OUTPUT_PARAMETER);
    private static final Pattern OUTPUT_PARAMETER_LIST = Pattern.compile(
            "^" + OUTPUT_PARAMETER + "(?:\\s*[，,]\\s*" + OUTPUT_PARAMETER + ")*\\s*$");
    private static final Pattern UNSAFE_EXTRA = Pattern.compile(
            "(?:\\d+(?:\\.\\d+)?\\s*(?:条|处|个|m²|m2|m\\^2|mm|m(?![a-zA-Z²2^])))"
                    + "|(?:总)?(?:长度|面积)|(?:最大|最小|总)?宽度|(?i:(?<![a-z])[LSW](?:_?\\d+)?\\s*[=＝:：])");

    static List<Category> categories(ArrayNode records) {
        Map<String, Category> categories = new LinkedHashMap<>();
        for (JsonNode record : records) {
            String type = record.path("type").asText("").trim();
            String direction = record.path("crackType").asText("").trim();
            if (type.isEmpty()) throw new IllegalArgumentException("承重病害缺少type，id=" + record.path("id").asText());
            if ((type.equals("裂缝") || type.equals("线状裂缝")) && !direction.isEmpty()) type = direction + "裂缝";
            String description = record.path("description").asText("");
            Category prior = categories.get(type);
            long quantity = Math.addExact(prior == null ? 0 : prior.quantity(), record.path("quantity").asLong());
            boolean length = LENGTH_SOURCE.matcher(description).find() || prior != null && prior.length();
            boolean area = AREA_SOURCE.matcher(description).find() || prior != null && prior.area();
            boolean width = WIDTH_SOURCE.matcher(description).find() || prior != null && prior.width();
            categories.put(type, new Category(type, quantity, unitOf(type), length, area, width));
        }
        return categories.values().stream().sorted(Comparator.comparingInt(Category::order)).toList();
    }

    private static String unitOf(String type) {
        return type.contains("裂缝") && !type.contains("网状") ? "条" : "处";
    }

    static String checklist(List<Category> categories) {
        return String.join("\n", categories.stream().map(category ->
                category.name() + category.quantity() + category.unit() + "；必需尺寸："
                        + (category.measurements().isEmpty() ? "无" : String.join("、", category.measurements()))).toList());
    }

    static Validation validate(String output, List<Category> categories) {
        output = normalizeCandidate(output);
        List<String> errors = new ArrayList<>();
        Map<String, String> clauses = new LinkedHashMap<>();
        List<Category> matchingOrder = categories.stream()
                .sorted(Comparator.comparingInt((Category c) -> c.name().length()).reversed()).toList();
        List<String> parts = new ArrayList<>();
        for (String rawPart : (output == null ? "" : output).split("[；;\\r\\n]+")) {
            String part = rawPart.trim().replaceFirst("[。；;]+$", "");
            if (part.isEmpty()) continue;
            boolean startsCategory = matchingOrder.stream().anyMatch(category -> part.startsWith(category.name()));
            if (!startsCategory && !parts.isEmpty() && OUTPUT_PARAMETER_LIST.matcher(part).matches()) {
                int last = parts.size() - 1;
                parts.set(last, parts.get(last) + "，" + part);
            } else {
                parts.add(part);
            }
        }
        for (String clause : parts) {
            if (clause.isEmpty()) continue;
            String rawClause = clause;
            Category category = matchingOrder.stream().filter(c -> rawClause.startsWith(c.name())).findFirst().orElse(null);
            if (category == null) {
                errors.add("含未知类别或标题：" + clause.substring(0, Math.min(60, clause.length())));
                continue;
            }
            if (clauses.containsKey(category.name())) {
                errors.add(category.name() + "重复输出");
                continue;
            }
            var header = Pattern.compile("^" + Pattern.quote(category.name()) + "\\s*(\\d+)\\s*([条处个])(?=[，,。\\s]|$)").matcher(clause);
            if (!header.find()) {
                errors.add(category.name() + "必须以“" + category.name() + category.quantity() + category.unit() + "”开头");
                continue;
            }
            if (!header.group(1).equals(Long.toString(category.quantity())) || !header.group(2).equals(category.unit())) {
                errors.add(category.name() + "数量单位应为" + category.quantity() + category.unit());
            }
            for (String measurement : category.measurements()) {
                String expression = switch (measurement) {
                    case "长度" -> LENGTH_OUTPUT;
                    case "面积" -> AREA_OUTPUT;
                    default -> WIDTH_OUTPUT;
                };
                if (!Pattern.compile(expression).matcher(clause).find()) errors.add(category.name() + "缺少规范的" + measurement);
            }
            boolean prohibitedWidthWording = clause.contains("最大宽度")
                    || clause.contains("最小宽度") || clause.contains("总宽度");
            String normalizedClause = normalizeClauseTail(clause, header);
            if (normalizedClause == null) {
                errors.add(category.name() + "含尺寸之外的额外正文");
                clauses.put(category.name(), clause);
                continue;
            }
            clauses.put(category.name(), normalizedClause);
            clause = normalizedClause;
            if (prohibitedWidthWording) {
                errors.add(category.name() + "宽度必须为单值或范围，不能只写极值或总宽度");
            }
        }
        for (Category category : categories) {
            if (!clauses.containsKey(category.name())) errors.add("遗漏类别：" + category.name());
        }
        if (!errors.isEmpty()) return new Validation("", errors);
        List<String> normalized = new ArrayList<>();
        for (Category category : categories) {
            String clause = clauses.get(category.name());
            clause = clause.replaceAll("(?<!总)(长度|面积)", category.quantity() > 1 ? "总$1" : "$1");
            if (category.quantity() == 1) clause = clause.replaceAll("总(长度|面积)", "$1");
            clause = clause.replaceAll("宽度\\s*(" + NUMBER + "\\s*mm)", "宽度为$1");
            normalized.add(clause);
        }
        return new Validation(String.join("；", normalized), List.of());
    }

    /**
     * 只修正含义确定的常见模型措辞，不计算或猜测数值。
     */
    private static String normalizeCandidate(String output) {
        if (output == null) return "";
        return output
                .replace('：', '，')
                .replaceAll("平方米", "m²")
                .replaceAll("(?i)m\\s*(?:\\^\\s*2|2)(?!\\d)", "m²")
                .replaceAll("(?:总)?长度\\s*[/／]\\s*((?:总)?面积)", "$1")
                .replaceAll("((?:总)?(?:长度|面积))\\s*(?:约为|约|为)\\s*(?=" + NUMBER + ")", "$1")
                .replaceAll("宽度\\s*约为?\\s*(?=" + NUMBER + ")", "宽度为");
    }

    /**
     * 从类别短句中保留规范尺寸，删除不含数值的解释性尾句。任何未被识别的数量或尺寸仍判失败，
     * 防止把定位距离、错误数量或未识别单位静默写入报告。
     */
    private static String normalizeClauseTail(String clause, Matcher header) {
        String tail = clause.substring(header.end());
        Matcher parameterMatcher = OUTPUT_PARAMETER_PATTERN.matcher(tail);
        List<String> parameters = new ArrayList<>();
        StringBuilder residual = new StringBuilder();
        int cursor = 0;
        while (parameterMatcher.find()) {
            residual.append(tail, cursor, parameterMatcher.start());
            parameters.add(parameterMatcher.group().trim());
            cursor = parameterMatcher.end();
        }
        residual.append(tail.substring(cursor));
        String extra = residual.toString().replaceAll("[\\s，,。；;：:（）()、]+", "");
        if (UNSAFE_EXTRA.matcher(extra).find()) return null;
        return header.group().trim() + (parameters.isEmpty() ? "" : "，" + String.join("，", parameters));
    }

    /** 构件及位置取自请求，绝不从模型标题反推。 */
    static String merge(List<DiseaseSummaryService.GroupKey> keys, List<String> bodies, List<String> componentOrder) {
        Map<String, List<String>> byComponent = new LinkedHashMap<>();
        componentOrder.forEach(component -> byComponent.put(component, new ArrayList<>()));
        for (int i = 0; i < keys.size(); i++) {
            var key = keys.get(i);
            String position = key.position() == null || key.position().equals(key.component()) ? "" : key.position();
            byComponent.computeIfAbsent(key.component(), ignored -> new ArrayList<>()).add(position + bodies.get(i));
        }
        List<String> paragraphs = new ArrayList<>();
        for (var entry : byComponent.entrySet()) {
            if (!entry.getValue().isEmpty()) paragraphs.add((paragraphs.size() + 1) + "）" + entry.getKey() + "："
                    + String.join("；", entry.getValue()) + "。");
        }
        return String.join("\n", paragraphs);
    }

    record Category(String name, long quantity, String unit, boolean length, boolean area, boolean width) {
        List<String> measurements() {
            List<String> result = new ArrayList<>();
            if (length) result.add("长度");
            if (area) result.add("面积");
            if (width) result.add("宽度");
            return result;
        }
        int order() { return unit.equals("条") ? 0 : area || name.contains("网状裂缝") ? 1 : 2; }
    }

    record Validation(String body, List<String> errors) {
        boolean valid() { return errors.isEmpty(); }
    }
}
