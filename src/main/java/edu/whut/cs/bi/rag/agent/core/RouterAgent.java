package edu.whut.cs.bi.rag.agent.core;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.whut.cs.bi.rag.agent.utils.SseEventUtil;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;

@Component
@Slf4j
public class RouterAgent {
    
    
    AgentRegistry agentRegistry;
    ChatClient chatClient;
    
    @Resource
    SseEventUtil sseEventUtil;
    private ObjectMapper objectMapper;
    
    public RouterAgent(@Value("${router-agent-model}") String routerAgentModel, AgentRegistry registry, ChatClient.Builder builder, ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        agentRegistry = registry;
        List<String> strings = List.of(
                "请根据用户需求选择最合适的 agent 进行调用，只允许返回 agent 的 name 属性。只能返回一个 agent , 如果没有合适的则默认使用 dialogAgent",
                getAgentInfo()
        );
        chatClient = builder
                .defaultOptions(OpenAiChatOptions.builder().model(routerAgentModel).build())
                .defaultSystem(String.join("\n", strings))
                .build();
    }
    
    
    public BaseAgent run(String prompt, SseEmitter emitter, String chatId, String userId) {
        log.info("开始路由请求，用户ID: {}, 对话ID: {}", userId, chatId);
        sseEventUtil.runAgent(emitter, "RouterAgent");
        
        String agentName = chatClient.prompt()
                .user(prompt)
                .call().content();
        BaseAgent agent = agentRegistry.getAgent(agentName);
        if (agent == null) {
            log.error("agent不存在: {}", agentName);
            sseEventUtil.error(emitter, "服务器内部异常，请联系管理员");
            throw new RuntimeException("agent不存在: " + agentName);
        }
        log.info("路由成功，使用agent: {}", agentName);
        sseEventUtil.agentEnd(emitter);
        return agent;
    }
    
    private String getAgentInfo() {
        Map<String, BaseAgent> availableAgents = agentRegistry.getAllAgents();
        StringBuilder agentInfo = new StringBuilder();
        agentInfo.append("可用的Agent列表:");
        List<BaseAgent> list = availableAgents.values().stream().toList();
        try {
            agentInfo.append(objectMapper.writeValueAsString(list));
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
        return agentInfo.toString();
    }
}