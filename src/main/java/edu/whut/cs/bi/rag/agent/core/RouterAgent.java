package edu.whut.cs.bi.rag.agent.core;

import edu.whut.cs.bi.rag.agent.model.AgentEnum;
import edu.whut.cs.bi.rag.agent.utils.SseEventUtil;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;
import java.util.Objects;

@Component
@Slf4j
public class RouterAgent {
    
    AgentRegistry agentRegistry;
    ChatClient chatClient;
    
    @Resource
    SseEventUtil sseEventUtil;
    
    public RouterAgent(AgentRegistry registry, ChatClient.Builder builder) {
        agentRegistry = registry;
        List<String> strings = List.of(
                "请根据用户需求选择最合适的 agent 进行调用，只返回 agent 的名称，不要有任何其他文字",
                getAgentInfo()
        );
        chatClient = builder
                .defaultOptions(OpenAiChatOptions.builder().model("gpt-4o-mini").build())
                .defaultSystem(String.join("\n", strings))
                .build();
    }
    
    
    public AgentEnum run(String prompt, SseEmitter emitter, String chatId, String userId) {
        log.info("开始路由请求，用户ID: {}, 对话ID: {}", userId, chatId);
        sseEventUtil.runAgent(emitter, "routerAgent");
        
        AgentEnum entity = chatClient.prompt()
                .user(prompt)
                .call().entity(AgentEnum.class);
        
        log.info("路由完成，选择的Agent: {}", Objects.requireNonNull(entity).getAgentName());
        sseEventUtil.agentEnd(emitter);
        return entity;
    }
    
    private String getAgentInfo() {
        Map<String, BaseAgent> availableAgents = agentRegistry.getAllAgents();
        StringBuilder agentInfo = new StringBuilder();
        agentInfo.append("可用的Agent列表:\n");
        for (Map.Entry<String, BaseAgent> entry : availableAgents.entrySet()) {
            BaseAgent agent = entry.getValue();
            agentInfo.append(String.format("- %s: %s\n", agent.getName(), agent.getDescription()));
        }
        return agentInfo.toString();
    }
}