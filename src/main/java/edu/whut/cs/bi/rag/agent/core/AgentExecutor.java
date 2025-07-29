package edu.whut.cs.bi.rag.agent.core;


import edu.whut.cs.bi.rag.agent.model.AgentResult;
import edu.whut.cs.bi.rag.agent.utils.SseEventUtil;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 动态Agent工具注册器
 * 自动注册所有Agent为Tool方法
 */
@Component
@Slf4j
public class AgentExecutor {
    
    @Resource
    private AgentRegistry agentRegistry;
    
    @Resource
    private SseEventUtil sseEventUtil;
    
    @PostConstruct
    public void initializeDynamicTools() {
        Map<String, BaseAgent> agents = agentRegistry.getAllAgents();
    }
    
    /**
     * 调用指定的Agent来处理用户请求
     *
     * @param agentName   Agent名称
     * @param prompt      提供给 Agent 的上下文
     * @param toolContext 工具上下文，包含emitter等信息
     * @return Agent处理结果
     */
    @Tool(description = "调用指定的专有Agent来处理用户请求,这是执行任何具体任务的主要入口。")
    public String callAgent(@ToolParam(description = "要调用的Agent名称") String agentName,
                            @ToolParam(description = "提供给Agent的上下文或指令") String prompt,
                            ToolContext toolContext) {
        SseEmitter emitter = getEmitter(toolContext);
        if (emitter == null) {
            return "agent出错，请联系管理员";
        }
        String agentRunId = UUID.randomUUID().toString();
        BaseAgent agent = agentRegistry.getAgent(agentName);
        if (agent == null) {
            String availableAgents = String.join(", ", agentRegistry.getAllAgents().keySet());
            return String.format("Agent不存在: %s | 可用列表: %s", agentName, availableAgents);
        }
        
        // 发送状态通知
        sseEventUtil.runAgent(emitter, agentName, agentRunId);
        
        try {
            AgentResult result = agent.run(prompt);
            if (result == null) {
                return "agent出错，请联系管理员";
            }
            log.info("Agent {} 运行结果: {}", agentName, result);
            // 处理附件
            String attachmentsSummary = "";
            List<String> attachments = result.getAttachments();
            if (attachments != null && !attachments.isEmpty()) {
                attachmentsSummary = processAttachments(agentName, attachments, emitter, agentRunId);
            }
            
            // 处理 reference
            String referenceInfo = "";
            List<String> references = result.getReferences();
            if (references != null && !references.isEmpty()) {
                referenceInfo = processReferences(references, emitter);
            }
            
            
            
            sseEventUtil.agentEnd(emitter, agentRunId, result);

            return "工具 " + agentName + " 调用完成。Agent 返回结果: " + result;
        } catch (Exception e) {
            return "Agent执行失败: " + e.getMessage();
        }
    }
    
    private String processAttachments(String agentName, List<String> attachments, SseEmitter emitter, String agentRunId) {
        StringBuilder summaryBuilder = new StringBuilder("\n附件引用如下:\n");
        for (String attachment : attachments) {
            // 调用修改后的方法并获取 Markdown 引用
            // TODO
        }
        return summaryBuilder.toString();
    }
    
    private String processReferences(List<String> references, SseEmitter emitter) {
        StringBuilder summaryBuilder = new StringBuilder("\n知识来源:\n");
        references.forEach(reference -> {
            sseEventUtil.knowledgeReference(emitter,reference);
            summaryBuilder.append(reference).append('\n');
        });
        return summaryBuilder.toString();
    }
    
    
    /**
     * 从ToolContext中提取Emitter
     */
    private SseEmitter getEmitter(ToolContext toolContext) {
        return Optional.ofNullable(toolContext)
                .map(ToolContext::getContext)
                .map(context -> context.get("emitter"))
                .filter(SseEmitter.class::isInstance)
                .map(SseEmitter.class::cast)
                .orElse(null);
    }
    
}
