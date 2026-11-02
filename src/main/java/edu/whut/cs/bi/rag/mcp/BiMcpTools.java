package edu.whut.cs.bi.rag.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 对外暴露的 MCP 工具。查询工具以连接时携带的用户 token 访问 BuildingInspector；
 * 评定计算是写操作，还要求授权包含 bridge:write，并由后端再次校验网页端权限。
 */
@Component
public class BiMcpTools {

    private static final int MAX_BRIDGES = 30;
    private static final int DEFAULT_DISEASE_LIMIT = 100;
    private static final int MAX_IMAGES_PER_DISEASE = 2;

    private final BiApiClient biApiClient;
    private final BiTokenExchangeClient exchangeClient;
    private final ObjectMapper objectMapper;

    public BiMcpTools(BiApiClient biApiClient, BiTokenExchangeClient exchangeClient, ObjectMapper objectMapper) {
        this.biApiClient = biApiClient;
        this.exchangeClient = exchangeClient;
        this.objectMapper = objectMapper;
    }

    @Tool(name = "whoami", description = "查看当前登录的 BuildingInspector 用户（用户 id、登录名、姓名、部门）以及本次授权的到期时间。")
    public ObjectNode whoami() {
        ObjectNode result = (ObjectNode) biApiClient.getData("/me").deepCopy();
        McpTokenContext.McpIdentity identity = McpTokenContext.get();
        if (identity.tokenExpiresAt() > 0) {
            result.put("authorizationExpiresAt", Instant.ofEpochSecond(identity.tokenExpiresAt())
                    .atZone(ZoneId.of("Asia/Shanghai")).toLocalDateTime().toString());
        }
        return result;
    }

    @Tool(name = "switch_account", description = "退出当前 BuildingInspector 账号（撤销本次授权）。仅在用户明确要求切换账号或退出登录时调用。"
            + "调用后下一次请求会要求重新登录，用户可在弹出的授权页输入其它账号。")
    public ObjectNode switchAccount() {
        McpTokenContext.McpIdentity identity = McpTokenContext.get();
        ObjectNode result = objectMapper.createObjectNode();
        if (identity.accessToken() == null) {
            result.put("revoked", false);
            result.put("message", "当前连接使用调试 token，无法在此退出；请修改客户端配置中的 Authorization。");
            return result;
        }
        exchangeClient.revoke(identity.accessToken());
        result.put("revoked", true);
        result.put("previousUser", identity.loginName());
        result.put("message", "已退出账号 " + identity.loginName() + "。接下来的请求会要求重新授权；"
                + "如果客户端没有自动弹出登录页，请在客户端的 MCP 设置里对该服务重新连接或重新认证。");
        return result;
    }

    @Tool(name = "search_bridges", description = "按桥梁名称关键字模糊查找桥梁，返回桥梁 id、名称、所属桥梁（分幅时）、路线编号。"
            + "其它工具需要 bridgeId 时先用本工具查到 id；结果过多时请让用户给出更精确的名称。")
    public ObjectNode searchBridges(@ToolParam(description = "桥梁名称关键字，例如：大湾1桥、长江大桥") String keyword) {
        JsonNode bridges = biApiClient.getData("/building/{name}", keyword);
        ArrayNode items = objectMapper.createArrayNode();
        for (JsonNode bridge : bridges) {
            if (items.size() >= MAX_BRIDGES) {
                break;
            }
            ObjectNode item = items.addObject();
            item.put("bridgeId", bridge.path("id").asLong());
            item.put("name", bridge.path("name").asText());
            putText(item, "parentName", bridge.path("parentName"));
            putText(item, "line", bridge.path("line"));
            item.put("isLeaf", "1".equals(bridge.path("isLeaf").asText()));
        }
        ObjectNode result = objectMapper.createObjectNode();
        result.put("total", bridges.size());
        result.put("returned", items.size());
        result.set("bridges", items);
        return result;
    }

    @Tool(name = "get_bridge_profile", description = "获取桥梁档案：基础数据、行政识别、技术指标（桥长、跨径、桥宽等）、"
            + "结构信息、档案资料、养护处治记录，以及桥梁正面和侧面照片链接。"
            + "档案里的技术状况等级是桥梁卡片上的记录。本次检测的评定等级和得分用 get_bridge_evaluation。")
    public ObjectNode getBridgeProfile(@ToolParam(description = "桥梁 id，可通过 search_bridges 获得") Long bridgeId) {
        JsonNode data = biApiClient.getData("/building/id/{id}/property", bridgeId);
        JsonNode property = data.path("property");
        ObjectNode result = objectMapper.createObjectNode();
        result.put("bridgeId", bridgeId);
        result.put("name", property.path("name").asText());
        ObjectNode groups = result.putObject("properties");
        for (JsonNode group : property.path("children")) {
            ObjectNode fields = objectMapper.createObjectNode();
            flattenProperties(group.path("children"), "", fields);
            if (!fields.isEmpty()) {
                groups.set(group.path("name").asText(), fields);
            }
        }
        ObjectNode images = result.putObject("images");
        images.set("front", data.path("images").path("newfront"));
        images.set("side", data.path("images").path("newside"));
        return result;
    }

    @Tool(name = "get_structure_tree", description = "获取桥梁结构分解：结构（上部/下部/桥面系）→ 部位 → 构件类型，含结构分解上的权重和构件数量。"
            + "用于回答桥梁由哪些部件组成。部位得分、构件分数用 get_bridge_evaluation。")
    public ObjectNode getStructureTree(@ToolParam(description = "桥梁 id，可通过 search_bridges 获得") Long bridgeId) {
        JsonNode root = biApiClient.getData("/building/id/{id}/object", bridgeId);
        ObjectNode result = objectMapper.createObjectNode();
        result.put("bridgeId", bridgeId);
        result.put("name", root.path("name").asText());
        ArrayNode structures = result.putArray("structures");
        for (JsonNode structure : activeChildren(root)) {
            ObjectNode structureNode = structures.addObject();
            structureNode.put("name", structure.path("name").asText());
            structureNode.put("weight", structure.path("weight").asDouble());
            ArrayNode parts = structureNode.putArray("parts");
            for (JsonNode part : activeChildren(structure)) {
                ObjectNode partNode = parts.addObject();
                partNode.put("name", part.path("name").asText());
                partNode.put("weight", part.path("weight").asDouble());
                partNode.put("componentCount", part.path("count").asInt());
                ArrayNode types = partNode.putArray("componentTypes");
                for (JsonNode type : activeChildren(part)) {
                    types.add(type.path("name").asText());
                }
            }
        }
        return result;
    }

    @Tool(name = "get_bridge_evaluation", description = "查询桥梁评定得分的标准接口。读取已经保存的评定，不重新计算。"
            + "返回系统评定等级和得分、上部/下部/桥面系的等级和得分，以及各部位的等级、得分、权重、构件数量。"
            + "componentScores 只包含有病害且参与评定的构件编号和分数，没有病害的构件按满分处理，不在列表里。"
            + "问评定等级、系统得分、部位得分或构件分数时用本工具。"
            + "evaluated 为 false 表示该桥或该年还没有评定结果。使用叶子桥（分幅）的 id。")
    public ObjectNode getBridgeEvaluation(
            @ToolParam(description = "桥梁 id，使用 search_bridges 返回的叶子桥（分幅）") Long bridgeId,
            @ToolParam(description = "检测年份，例如 2025；不填则返回最近一次已保存的评定", required = false) Integer year) {
        JsonNode data = year == null
                ? biApiClient.getData("/building/id/{id}/evaluation", bridgeId)
                : biApiClient.getData("/building/id/{id}/evaluation?year={year}", bridgeId, year);
        return (ObjectNode) data.deepCopy();
    }

    @Tool(name = "list_my_projects", description = "列出当前用户参与的检测项目（检测、编写、审核、批准任一角色），返回项目 id、名称、年份、编号和起止日期。")
    public ObjectNode listMyProjects() {
        JsonNode data = biApiClient.getData("/project/mine");
        ObjectNode result = objectMapper.createObjectNode();
        result.put("userId", data.path("userId").asLong());
        ArrayNode projects = result.putArray("projects");
        for (JsonNode project : data.path("projects")) {
            ObjectNode item = projects.addObject();
            item.put("projectId", project.path("id").asLong());
            item.put("name", project.path("name").asText());
            item.put("year", project.path("year").asInt());
            putText(item, "code", project.path("code"));
            putText(item, "startDate", project.path("startDate"));
            putText(item, "endDate", project.path("endDate"));
        }
        return result;
    }

    @Tool(name = "list_project_tasks", description = "列出某个项目下的检测任务，每个任务对应一座桥，返回任务 id、桥梁 id、桥梁名称和任务状态。")
    public ObjectNode listProjectTasks(@ToolParam(description = "项目 id，可通过 list_my_projects 获得") Long projectId) {
        JsonNode data = biApiClient.getData("/project/{id}/task", projectId);
        ObjectNode result = objectMapper.createObjectNode();
        result.put("projectId", projectId);
        ArrayNode tasks = result.putArray("tasks");
        for (JsonNode task : data.path("tasks")) {
            ObjectNode item = tasks.addObject();
            item.put("taskId", task.path("id").asLong());
            item.put("bridgeId", task.path("buildingId").asLong());
            item.put("bridgeName", task.path("building").path("name").asText());
            putText(item, "parentName", task.path("building").path("parentName"));
            putText(item, "status", task.path("status"));
            if (!result.has("projectName")) {
                result.put("projectName", task.path("project").path("name").asText());
                result.put("year", task.path("project").path("year").asInt());
            }
        }
        return result;
    }

    @Tool(name = "list_project_progress", description = "按当前用户的部门业务查询一个项目的进度：每座桥是否已提交检测数据、是否已评定，以及已评定的系统等级和得分。"
            + "调用前必须先向用户问到项目名称。没有名称时不要调用。"
            + "范围与网页端部门业务一致：承担单位或所属单位是用户所在部门，或是该部门的直接上级。"
            + "不填年份时按今年查。返回 chooseProject 为 true 时有多个相符项目，先让用户选定，再用 projectId 重查，不要自己挑。"
            + "submitted 为 true 表示检测数据已上传。未评定时看 submitted：false 是还没提交，true 是已提交但还没评定。"
            + "返回的 bridgeId 和 year 可继续用于 get_bridge_evaluation、query_diseases、get_bridge_profile、get_structure_tree。")
    public ObjectNode listProjectProgress(
            @ToolParam(description = "项目名称。用户还没说时不要调用本工具", required = false) String projectName,
            @ToolParam(description = "项目年份，例如 2026。不填则查今年", required = false) Integer year,
            @ToolParam(description = "用户选定后的项目 id。有这个 id 时按它查询，不再按名称搜索", required = false) Long projectId) {
        String name = projectName == null ? "" : projectName.trim();
        if (projectId == null && name.isEmpty()) {
            throw new IllegalArgumentException("先向用户询问项目名称，确认后再按部门业务查询进度");
        }
        JsonNode data;
        if (projectId != null && year != null) {
            data = biApiClient.getData("/project/progress?year={year}&projectId={projectId}", year, projectId);
        } else if (projectId != null) {
            data = biApiClient.getData("/project/progress?projectId={projectId}", projectId);
        } else if (year != null) {
            data = biApiClient.getData("/project/progress?year={year}&projectName={projectName}", year, name);
        } else {
            data = biApiClient.getData("/project/progress?projectName={projectName}", name);
        }
        return (ObjectNode) data.deepCopy();
    }

    @Tool(name = "calculate_bridge_evaluation", description = "计算一座桥梁的技术状况评定（写操作）。"
            + "必须先用 list_project_progress 确认该桥 submitted=true 且 evaluated=false，并把桥名、taskId 复述给用户确认后再调用。"
            + "服务端按网页任务页的评定流程，由任务解析桥幅和结构根节点，必要时先修正权重并锁定，再计算评定。"
            + "taskId 必填，来自 list_project_progress 的 bridges[].taskId；bridgeId 可选，用于校验任务归属。"
            + "返回 taskId、bridgeId、bridgeName、year、evaluated、overall.systemLevel、overall.systemScore 和 message。"
            + "计算后用 get_bridge_evaluation（带 year）读取系统等级、得分和部位分数，再用 list_project_progress 刷新进度。"
            + "evaluated 仍为 false 时按 message 说明原因，不要写成 0 分。没有 bridge:write 授权时会返回明确错误，不要假装成功。")
    public ObjectNode calculateBridgeEvaluation(
            @ToolParam(description = "任务 id，来自 list_project_progress 的 bridges[].taskId") Long taskId,
            @ToolParam(description = "桥梁 id，来自 bridges[].bridgeId，用于校验任务归属", required = false) Long bridgeId) {
        requireWriteScope("计算桥梁评定");
        ObjectNode body = objectMapper.createObjectNode();
        body.put("taskId", taskId);
        if (bridgeId != null) {
            body.put("bridgeId", bridgeId);
        }
        return (ObjectNode) biApiClient.postData("/building/evaluation/calculate", body).deepCopy();
    }

    @Tool(name = "calculate_project_evaluations", description = "批量补算一个项目下未评定的桥（写操作）。"
            + "只处理还没有保存评定记录的桥；onlySubmitted=true（默认）时跳过未提交检测数据的桥。"
            + "调用前必须把候选桥列表复述给用户确认，不能未经确认直接批量计算。"
            + "单座失败不阻断其它桥，返回 successes、failures、skippedEvaluated、skippedUnsubmitted 和 summary。"
            + "完成后用 list_project_progress 刷新 complete 和 byLevel。没有 bridge:write 授权时会返回明确错误。")
    public ObjectNode calculateProjectEvaluations(
            @ToolParam(description = "项目 id，来自 list_project_progress 的 projects[].projectId") Long projectId,
            @ToolParam(description = "只计算这些任务 id，可选；不填表示项目下全部未评定", required = false) List<Long> taskIds,
            @ToolParam(description = "只计算这些桥梁 id，可选", required = false) List<Long> bridgeIds,
            @ToolParam(description = "是否跳过未提交检测数据的桥，默认 true", required = false) Boolean onlySubmitted) {
        requireWriteScope("批量补算项目评定");
        ObjectNode body = objectMapper.createObjectNode();
        body.put("projectId", projectId);
        if (taskIds != null && !taskIds.isEmpty()) {
            ArrayNode ids = body.putArray("taskIds");
            for (Long id : taskIds) {
                ids.add(id);
            }
        }
        if (bridgeIds != null && !bridgeIds.isEmpty()) {
            ArrayNode ids = body.putArray("bridgeIds");
            for (Long id : bridgeIds) {
                ids.add(id);
            }
        }
        body.put("onlySubmitted", onlySubmitted == null || onlySubmitted);
        return (ObjectNode) biApiClient.postData("/building/evaluation/calculate-project", body).deepCopy();
    }

    private void requireWriteScope(String action) {
        McpTokenContext.McpIdentity identity = McpTokenContext.get();
        if (identity == null) {
            throw new IllegalStateException("当前 MCP 请求未通过授权，请重新登录");
        }
        if (!identity.hasScope("bridge:write")) {
            throw new IllegalStateException("当前授权只有查询权限，不能" + action
                    + "。请在 AI 客户端中重新授权，并允许 bridge:write（写操作）权限。");
        }
    }

    // ---------- 单桥检测报告 ----------

    @Tool(name = "list_report_templates", description = "列出可用的单桥检测报告模板（二级梁桥 / 二级拱桥 / 一级梁桥），"
            + "返回 templates 数组，每项含 templateId、name、bridgeKind（beam/arch）、level（模板类别 1/2，不是桥的属性）和 fillKind。"
            + "桥梁实体没有一二级之分，创建报告前必须让用户选定模板类别，不要从桥梁档案推断。"
            + "创建报告前先用本工具让用户选定模板。")
    public ObjectNode listReportTemplates(
            @ToolParam(description = "按模板名中的桥型过滤：beam 梁桥 / arch 拱桥；不填返回全部", required = false) String bridgeKind,
            @ToolParam(description = "按报告模板类别过滤：1 一级模板 / 2 二级模板；不是桥的属性；不填返回全部", required = false) Integer level) {
        JsonNode data;
        if (bridgeKind != null && !bridgeKind.isBlank() && level != null) {
            data = biApiClient.getData("/report/templates?bridgeKind={bridgeKind}&level={level}", bridgeKind, level);
        } else if (bridgeKind != null && !bridgeKind.isBlank()) {
            data = biApiClient.getData("/report/templates?bridgeKind={bridgeKind}", bridgeKind);
        } else if (level != null) {
            data = biApiClient.getData("/report/templates?level={level}", level);
        } else {
            data = biApiClient.getData("/report/templates");
        }
        // BI 返回数组；MCP 工具统一包成对象，避免 ArrayNode 强转 ObjectNode 失败
        ObjectNode result = objectMapper.createObjectNode();
        ArrayNode templates = result.putArray("templates");
        if (data != null && data.isArray()) {
            data.forEach(templates::add);
        }
        return result;
    }

    @Tool(name = "resolve_report_users", description = "解析项目下的可选审核人/批准人。"
            + "返回 users 列表（userId、loginName、userName）；命中 preferredLoginName 时给出 defaultReviewerId / defaultApproverId，"
            + "未命中时 defaults 为空并附 message，此时必须让用户从 users 中选定，不要自己猜。")
    public ObjectNode resolveReportUsers(
            @ToolParam(description = "项目 id，可通过 list_my_projects 获得") Long projectId,
            @ToolParam(description = "期望的默认登录名，不填默认 crh@znjc", required = false) String preferredLoginName) {
        if (preferredLoginName == null || preferredLoginName.isBlank()) {
            return (ObjectNode) biApiClient.getData("/report/resolveUsers?projectId={projectId}", projectId).deepCopy();
        }
        return (ObjectNode) biApiClient.getData(
                "/report/resolveUsers?projectId={projectId}&preferredLoginName={preferredLoginName}",
                projectId, preferredLoginName).deepCopy();
    }

    @Tool(name = "create_single_bridge_report", description = "创建单桥检测报告（写操作）。"
            + "需要 bridge:write 授权和网页端 biz:report:add 权限。taskId 与 bridgeId 二选一；"
            + "用 bridgeId 时该桥在项目下必须唯一对应一个任务，否则报错并要求改用 taskId。"
            + "审核人、批准人必须来自 resolve_report_users 返回的 users。"
            + "返回 reportId、fillPath、fillTitle 等；fillPath 需要在 BuildingInspector 网页打开继续填报，本工具不生成 Word 文档。")
    public ObjectNode createSingleBridgeReport(
            @ToolParam(description = "报告名称") String name,
            @ToolParam(description = "项目 id") Long projectId,
            @ToolParam(description = "检测任务 id，可通过 list_project_tasks 获得；与 bridgeId 二选一", required = false) Long taskId,
            @ToolParam(description = "桥梁 id；与 taskId 二选一，该桥在项目下必须唯一对应一个任务", required = false) Long bridgeId,
            @ToolParam(description = "报告模板 id，可通过 list_report_templates 获得") Long reportTemplateId,
            @ToolParam(description = "审核人用户 id，可通过 resolve_report_users 获得") Long reviewerId,
            @ToolParam(description = "批准人用户 id，可通过 resolve_report_users 获得") Long approverId) {
        requireWriteScope("创建单桥检测报告");
        ObjectNode body = objectMapper.createObjectNode();
        body.put("name", name);
        body.put("projectId", projectId);
        if (taskId != null) {
            body.put("taskId", taskId);
        }
        if (bridgeId != null) {
            body.put("bridgeId", bridgeId);
        }
        body.put("reportTemplateId", reportTemplateId);
        body.put("reviewerId", reviewerId);
        body.put("approverId", approverId);
        return (ObjectNode) biApiClient.postData("/report/createSingle", body).deepCopy();
    }

    @Tool(name = "get_report", description = "读取报告基本信息：模板、任务/桥、填报入口（fillPath / fillTitle）、"
            + "审核人批准人，templateLevel（报告模板类别，不是桥的属性），"
            + "以及已保存的重点病害选择（focusDiseases，构件与病害类型粒度）。")
    public ObjectNode getReport(@ToolParam(description = "报告 id") Long reportId) {
        return (ObjectNode) biApiClient.getData("/report/{reportId}", reportId).deepCopy();
    }

    @Tool(name = "list_focus_disease_candidates", description = "列出单桥报告任务下可选为重点关注病害的病害明细："
            + "diseaseId、构件、病害类型、标度、位置、描述。口径与网页填报页 4.1.6 的病害选择器一致。"
            + "让用户确认后再调用 set_report_focus_diseases 写入。")
    public ObjectNode listFocusDiseaseCandidates(
            @ToolParam(description = "报告 id") Long reportId,
            @ToolParam(description = "最低标度（1-5），只返回标度大于等于该值的病害", required = false) Integer minLevel) {
        if (minLevel != null) {
            return (ObjectNode) biApiClient.getData("/report/{reportId}/focusDiseaseCandidates?minLevel={minLevel}",
                    reportId, minLevel).deepCopy();
        }
        return (ObjectNode) biApiClient.getData("/report/{reportId}/focusDiseaseCandidates", reportId).deepCopy();
    }

    @Tool(name = "set_report_focus_diseases", description = "把用户确认的重点关注病害写入报告（写操作）。"
            + "需要 bridge:write 授权和网页端 biz:report:edit 权限。存储字段与网页填报页 4.1.6 相同，"
            + "网页打开填报页能看到已选，后续生成 Word 能读到。注意网页存储粒度为构件+病害类型："
            + "选中一条病害会勾选其所在构件的对应病害类型组合。返回已保存条数与摘要。")
    public ObjectNode setReportFocusDiseases(
            @ToolParam(description = "报告 id") Long reportId,
            @ToolParam(description = "重点关注病害的 diseaseId 列表，来自 list_focus_disease_candidates") List<Long> diseaseIds) {
        requireWriteScope("填写重点关注病害");
        ObjectNode body = objectMapper.createObjectNode();
        ArrayNode ids = body.putArray("diseaseIds");
        if (diseaseIds != null) {
            diseaseIds.forEach(ids::add);
        }
        return (ObjectNode) biApiClient.postData("/report/" + reportId + "/focusDiseases", body).deepCopy();
    }

    @Tool(name = "query_diseases", description = "查询某座桥某一年检测记录的病害，可按最低标度和构件名称筛选。"
            + "返回汇总（按标度、发展趋势、构件、项目年份统计）和病害明细（年份、构件、位置、类型、标度/最大标度、数量、描述、发展趋势、成因、维修建议、图片）。"
            + "deductPoints 是单条病害按《公路桥梁技术状况评定标准》表4.1.1用标度和最大标度计算的扣分，不读取病害上保存的扣分字段。"
            + "桥梁评定等级和得分用 get_bridge_evaluation。")
    public ObjectNode queryDiseases(
            @ToolParam(description = "桥梁 id，可通过 search_bridges 获得") Long bridgeId,
            @ToolParam(description = "检测年份，例如 2025；不填则返回所有年份", required = false) Integer year,
            @ToolParam(description = "最低标度（1-5），只返回标度大于等于该值的病害", required = false) Integer minLevel,
            @ToolParam(description = "构件名称关键字，例如：主梁、支座、伸缩缝", required = false) String componentKeyword,
            @ToolParam(description = "最多返回多少条明细，默认 100", required = false) Integer limit) {
        JsonNode diseases = year == null
                ? biApiClient.getData("/building/{id}/disease", bridgeId)
                : biApiClient.getData("/building/{id}/disease?year={year}", bridgeId, year);
        int maxItems = limit == null || limit <= 0 ? DEFAULT_DISEASE_LIMIT : limit;

        Map<String, Integer> byLevel = new TreeMap<>();
        Map<String, Integer> byTrend = new TreeMap<>();
        Map<String, Integer> byComponent = new TreeMap<>();
        Map<String, Integer> byYear = new TreeMap<>();
        ArrayNode items = objectMapper.createArrayNode();
        int matched = 0;
        for (JsonNode disease : diseases) {
            int level = disease.path("level").asInt();
            String component = disease.path("biObjectName").asText("");
            if (minLevel != null && level < minLevel) {
                continue;
            }
            if (componentKeyword != null && !componentKeyword.isBlank() && !component.contains(componentKeyword)) {
                continue;
            }
            matched++;
            byLevel.merge(String.valueOf(level), 1, Integer::sum);
            byTrend.merge(disease.path("developmentTrend").asText("未填写"), 1, Integer::sum);
            byComponent.merge(component, 1, Integer::sum);
            if (disease.path("year").isNumber()) {
                byYear.merge(disease.path("year").asText(), 1, Integer::sum);
            }
            if (items.size() < maxItems) {
                items.add(toDiseaseItem(disease));
            }
        }

        ObjectNode result = objectMapper.createObjectNode();
        result.put("bridgeId", bridgeId);
        if (year != null) {
            result.put("year", year);
        }
        ObjectNode summary = result.putObject("summary");
        summary.put("total", matched);
        summary.put("returned", items.size());
        summary.set("byLevel", objectMapper.valueToTree(byLevel));
        summary.set("byDevelopmentTrend", objectMapper.valueToTree(byTrend));
        summary.set("byComponent", objectMapper.valueToTree(byComponent));
        summary.set("byYear", objectMapper.valueToTree(byYear));
        result.set("diseases", items);
        return result;
    }

    private ObjectNode toDiseaseItem(JsonNode disease) {
        ObjectNode item = objectMapper.createObjectNode();
        item.put("diseaseId", disease.path("id").asLong());
        if (disease.path("year").isNumber()) {
            item.put("year", disease.path("year").asInt());
        }
        item.put("component", disease.path("biObjectName").asText());
        putText(item, "position", disease.path("position"));
        item.put("type", disease.path("diseaseType").path("name").asText(disease.path("type").asText()));
        int level = disease.path("level").asInt();
        int maxScale = disease.path("diseaseType").path("maxScale").asInt();
        item.put("level", level);
        item.put("maxScale", maxScale);
        Integer deductPoints = deductPoints(maxScale, level);
        if (deductPoints != null) {
            item.put("deductPoints", deductPoints);
        }
        item.put("quantity", disease.path("quantity").asInt());
        putText(item, "description", disease.path("description"));
        putText(item, "nature", disease.path("nature"));
        putText(item, "developmentTrend", disease.path("developmentTrend"));
        putText(item, "cause", disease.path("cause"));
        putText(item, "repairRecommendation", disease.path("repairRecommendation"));
        JsonNode images = disease.path("images");
        item.put("imageCount", images.size());
        ArrayNode imageSample = item.putArray("images");
        for (int i = 0; i < images.size() && i < MAX_IMAGES_PER_DISEASE; i++) {
            imageSample.add(images.get(i).asText());
        }
        return item;
    }

    /** 表 4.1.1。标度或最大标度不在表内时返回 null，不把库里的 0 当成扣分。 */
    static Integer deductPoints(int maxScale, int level) {
        return switch (maxScale) {
            case 3 -> switch (level) {
                case 1 -> 0;
                case 2 -> 20;
                case 3 -> 35;
                default -> null;
            };
            case 4 -> switch (level) {
                case 1 -> 0;
                case 2 -> 25;
                case 3 -> 40;
                case 4 -> 50;
                default -> null;
            };
            case 5 -> switch (level) {
                case 1 -> 0;
                case 2 -> 35;
                case 3 -> 45;
                case 4 -> 60;
                case 5 -> 100;
                default -> null;
            };
            default -> null;
        };
    }

    private void flattenProperties(JsonNode nodes, String prefix, ObjectNode target) {
        for (JsonNode node : nodes) {
            String name = prefix + node.path("name").asText();
            JsonNode children = node.path("children");
            if (children.isArray() && !children.isEmpty()) {
                flattenProperties(children, name + "/", target);
            } else {
                putText(target, name, node.path("value"));
            }
        }
    }

    private Iterable<JsonNode> activeChildren(JsonNode node) {
        ArrayNode active = objectMapper.createArrayNode();
        for (JsonNode child : node.path("children")) {
            if ("0".equals(child.path("delFlag").asText("0"))) {
                active.add(child);
            }
        }
        return active;
    }

    private static void putText(ObjectNode target, String field, JsonNode value) {
        if (value != null && !value.isNull() && !value.isMissingNode() && !value.asText().isBlank()) {
            target.put(field, value.asText());
        }
    }
}
