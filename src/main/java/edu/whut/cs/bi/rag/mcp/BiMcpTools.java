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
import java.util.Map;
import java.util.TreeMap;

/**
 * 对外暴露的 MCP 工具（只读）。所有调用都以连接时携带的用户 token 访问 BuildingInspector。
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

    @Tool(name = "get_bridge_profile", description = "获取桥梁档案：基础数据、行政识别（含技术状况等级、最近评定日期）、技术指标（桥长、跨径、桥宽等）、"
            + "结构信息、档案资料、养护处治记录，以及桥梁正面和侧面照片链接。")
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

    @Tool(name = "get_structure_tree", description = "获取桥梁结构分解：结构（上部/下部/桥面系）→ 部位 → 构件类型，含评定权重和构件数量。"
            + "用于回答桥梁由哪些部件组成、各部件在评定中的权重。")
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

    @Tool(name = "query_diseases", description = "查询某座桥某一年检测记录的病害，可按最低标度和构件名称筛选。"
            + "返回汇总（按标度、发展趋势、构件统计）和病害明细（构件、位置、类型、标度/最大标度、数量、描述、发展趋势、成因、维修建议、图片）。")
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
        result.set("diseases", items);
        return result;
    }

    private ObjectNode toDiseaseItem(JsonNode disease) {
        ObjectNode item = objectMapper.createObjectNode();
        item.put("diseaseId", disease.path("id").asLong());
        item.put("component", disease.path("biObjectName").asText());
        putText(item, "position", disease.path("position"));
        item.put("type", disease.path("diseaseType").path("name").asText(disease.path("type").asText()));
        item.put("level", disease.path("level").asInt());
        item.put("maxScale", disease.path("diseaseType").path("maxScale").asInt());
        item.put("quantity", disease.path("quantity").asInt());
        putText(item, "description", disease.path("description"));
        putText(item, "nature", disease.path("nature"));
        putText(item, "developmentTrend", disease.path("developmentTrend"));
        putText(item, "cause", disease.path("cause"));
        putText(item, "repairRecommendation", disease.path("repairRecommendation"));
        item.put("deductPoints", disease.path("deductPoints").asInt());
        JsonNode images = disease.path("images");
        item.put("imageCount", images.size());
        ArrayNode imageSample = item.putArray("images");
        for (int i = 0; i < images.size() && i < MAX_IMAGES_PER_DISEASE; i++) {
            imageSample.add(images.get(i).asText());
        }
        return item;
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
