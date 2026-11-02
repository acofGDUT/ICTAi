package edu.whut.cs.bi.rag.mcp;

import io.modelcontextprotocol.spec.McpSchema;
import org.springaicommunity.mcp.annotation.McpArg;
import org.springaicommunity.mcp.annotation.McpPrompt;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 用户在客户端里点选的提示模板。这里只展开指令，不取数、不算统计。
 */
@Component
public class BiMcpPrompts {

    static final String THIS_YEAR_EVALUATION = "this-year-evaluation";

    private static final Pattern YEAR = Pattern.compile("(?:19|20)\\d{2}");

    @McpPrompt(name = THIS_YEAR_EVALUATION, title = "今年评定",
            description = "统计一座桥某一年的评定等级、部位得分和病害条数。不填年份时用最近一次已保存的评定。")
    public McpSchema.GetPromptResult thisYearEvaluation(
            @McpArg(name = "bridgeName", description = "桥梁名称。不填则先向用户询问。同名桥或左右幅要先让用户确认，不要合并。", required = false)
            String bridgeName,
            @McpArg(name = "year", description = "检测年份，例如 2026。不填则用最近一次已保存评定的年份。", required = false)
            String year) {
        String bridge = bridgeName == null ? "" : bridgeName.trim();
        if (bridge.isEmpty()) {
            return result("""
                    用户还没有给出桥名。先询问要统计哪一座桥，年份可以不填。确认桥名之前不要调用工具。
                    得到桥名后：用 search_bridges 查找，多座同名或左右幅时停下来问用户，只用叶子桥；再用 get_bridge_evaluation 和带 year 的 query_diseases 做单年统计。不要做跨年对比。
                    """);
        }
        return result(instruction(bridge, year));
    }

    private static McpSchema.GetPromptResult result(String text) {
        return new McpSchema.GetPromptResult(
                "按工具结果写这座桥这一年的评定和病害统计",
                List.of(new McpSchema.PromptMessage(
                        McpSchema.Role.USER,
                        new McpSchema.TextContent(text))));
    }

    private static String instruction(String bridge, String year) {
        return """
                请统计「%s」的单年评定和病害。只依据工具返回的数据写报告，不要编造等级、得分或条数。

                %s

                步骤：
                1. 用 search_bridges 查找上面的桥名。多座同名或左右幅时停下来问用户，只用 isLeaf 为 true 的叶子桥。父桥按分幅分别统计，不要把左右幅合并。
                2. 用确认后的 bridgeId 调 get_bridge_evaluation。这是评定等级和得分的唯一来源。
                3. 用同一座叶子桥、同一年调 query_diseases，并且必须带 year。不带 year 会把各年病害混在一起。汇总以 summary 为准，它覆盖全部记录。summary.total 大于 summary.returned 时，把 limit 设为 summary.total 再查一次。
                4. 工具返回未授权时让用户重新登录，不要连续重试。
                5. 按下面的章节写。不要做跨年对比，也不要跑本地统计脚本。

                口径：
                - evaluated 为 false 时按返回的 message 说明，不要写成 0 分。overall.level 不在就是没填人工等级。
                - get_bridge_profile 卡片上的技术状况等级、结构树上的权重、病害里的 deductPoints 都不是这次检测的评定得分。
                - 条数用 summary.total，按条计，不用 quantity 加权。标度 0 写成「未填写标度」。标度大于等于 3 的条数，把 summary.byLevel 里 3、4、5 的条数相加。
                - 明细被截断时只写汇总，不写逐条清单。

                章节：
                1. 对象与口径：桥名、分幅、年份，数据来自系统查询。
                2. 结论：三到四句，每句带数字。写 overall.systemLevel、overall.systemScore，以及 structures 里上部结构、下部结构、桥面系的等级和得分；再写病害总条数和标度大于等于 3 的条数。
                3. 标度：summary.byLevel。
                4. 构件：summary.byComponent。
                5. 发展趋势：summary.byDevelopmentTrend。出现哪种列哪种。
                6. 重点病害：标度大于等于 3 的条数。明细完整时列出这些条目的构件、类型和标度；不完整就只写条数。
                """.formatted(bridge, yearStep(year));
    }

    private static String yearStep(String year) {
        String text = year == null ? "" : year.trim();
        if (text.isEmpty()) {
            return "用户没有指定年份。get_bridge_evaluation 不传 year。从它返回的 year 取出检测年，query_diseases 必须带这个 year。";
        }
        Matcher matcher = YEAR.matcher(text);
        String found = null;
        int count = 0;
        while (matcher.find()) {
            found = matcher.group();
            count++;
        }
        if (count != 1) {
            return "用户给的年份无法确定（" + text + "）。先向用户确认四位年份，确认前不要调用工具。";
        }
        return "用户指定了 " + found + " 年。get_bridge_evaluation 和 query_diseases 都传 year=" + found + "。";
    }
}
