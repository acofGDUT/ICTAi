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
                "请根据用户需求选择合适的 agent 进行调用，只返回 agent 的名称",
                getAgentInfo()
        );
        chatClient = builder
                .defaultOptions(OpenAiChatOptions.builder().model("gpt-4o-mini").build())
                .defaultSystem(strings.toString())
                .build();
    }
    
    
    public AgentEnum run(String prompt, SseEmitter emitter, String chatId, String userId) {
        sseEventUtil.runAgent(emitter, "routerAgent");
        
        AgentEnum entity = chatClient.prompt()
                .user(prompt)
                .call().entity(AgentEnum.class);
        
        sseEventUtil.agentEnd(emitter);
        return entity;
    }
    
    private String getAgentInfo() {
        Map<String, BaseAgent> availableAgents = agentRegistry.getAllAgents();
        StringBuilder agentInfo = new StringBuilder();
        agentInfo.append("可用的Agent列表: ");
        for (Map.Entry<String, BaseAgent> entry : availableAgents.entrySet()) {
            BaseAgent agent = entry.getValue();
            agentInfo.append(String.format("agent名称：%s，agent功能描述%s\n", agent.getName(), agent.getDescription()));
        }
        return agentInfo.toString();
    }
}
