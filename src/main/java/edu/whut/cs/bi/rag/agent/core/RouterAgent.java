package edu.whut.cs.bi.rag.agent.core;

import edu.whut.cs.bi.rag.agent.utils.SseEventUtil;
import jakarta.annotation.Resource;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;

@Component
public class RouterAgent {
    
    AgentRegistry agentRegistry;
    ChatClient chatClient;
    
    @Resource
    private SseEventUtil sseEventUtil;
    
    
    public RouterAgent(AgentRegistry registry, ChatClient.Builder builder) {
        chatClient = builder.build();
        agentRegistry = registry;
        
        systemPrompt = List.of(
                "请根据用户需求选择合适的 agent 进行调用，只返回 agent 的名称",
                getAgentInfo()
        );
    }
    
    List<String> systemPrompt = null;
    
    public String run(String prompt, SseEmitter emitter, String chatId, String userId, MessageChatMemoryAdvisor chatMemoryAdvisor) {
        sseEventUtil.runAgent(emitter, "routerAgent");
        String content = chatClient.prompt()
                .advisors(chatMemoryAdvisor)
                .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, chatId))
                .options(OpenAiChatOptions.builder().model("gpt-4o-mini").build())
                .system(systemPrompt.toString())
                .user(prompt)
                .call().content();
        sseEventUtil.agentEnd(emitter);
        return content;
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
