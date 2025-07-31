package edu.whut.cs.bi.rag.service;


import edu.whut.cs.bi.rag.agent.core.AgentRegistry;
import edu.whut.cs.bi.rag.agent.core.BaseAgent;
import edu.whut.cs.bi.rag.agent.core.RouterAgent;
import edu.whut.cs.bi.rag.agent.utils.SseEventUtil;
import jakarta.annotation.Resource;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.ArrayList;
import java.util.List;

@Service
public class AgentService {
    
    private ChatClient chatClient;
    
    @Resource
    private SseEventUtil sseEventUtil;
    
    @Resource
    private RouterAgent routerAgent;
    

    private AgentRegistry agentRegistry;
    
    private final List<String> systemPrompts = new ArrayList<>();
    
    private MessageChatMemoryAdvisor chatMemoryAdvisor;
    
    public AgentService(ChatClient.Builder builder, AgentRegistry agentRegistry) {
        this.agentRegistry = agentRegistry;
        chatMemoryAdvisor = MessageChatMemoryAdvisor.builder(MessageWindowChatMemory.builder().build()).build();
        chatClient = builder.defaultAdvisors()
                .build();
    }
    

    
    @Async
    public void chat(String prompt, SseEmitter emitter, String chatId, String userId) {
        // 使用路由 agent 进行路由
        String agentName = routerAgent.run(prompt, emitter, chatId, userId, chatMemoryAdvisor);
        BaseAgent agent = agentRegistry.getAgent(agentName);
        if (agent == null) {
            sseEventUtil.error(emitter, "服务器内部异常，请联系管理员");
            return;
        }
        agent.run(prompt, emitter, chatId, userId, chatMemoryAdvisor);
    }
}
