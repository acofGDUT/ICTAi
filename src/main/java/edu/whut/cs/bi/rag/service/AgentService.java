package edu.whut.cs.bi.rag.service;


import edu.whut.cs.bi.rag.agent.core.AgentExecutor;
import edu.whut.cs.bi.rag.agent.core.AgentRegistry;
import edu.whut.cs.bi.rag.agent.core.BaseAgent;
import edu.whut.cs.bi.rag.agent.utils.SseEventUtil;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpSession;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
public class AgentService {
    
    private ChatClient chatClient;
    
    @Resource
    private SseEventUtil sseEventUtil;
    
    @Resource
    private AgentExecutor agentExecutor;
    
    @Resource
    private AgentRegistry agentRegistry;
    
    private final List<String> systemPrompts = new ArrayList<>();
    
    public AgentService(ChatClient.Builder builder) {
        ChatMemory chatMemory = MessageWindowChatMemory.builder().build();
        chatClient = builder.defaultAdvisors(MessageChatMemoryAdvisor.builder(chatMemory).build())
                .build();
    }
    
    @Async
    public void processAgentRequest(String prompt, SseEmitter emitter, String chatId, String userId) {
        systemPrompts.add("你是一个智能的AI调度中心。你的任务是分析用户问题，并调用最合适的Agent来处理。请使用 'callAgent' 工具来调用专有Agent。在最终呈现给用户时，请使用标准的Markdown语法，进行合理的排版。使用![]()语法渲染图片。分点的内容注意加粗");
        systemPrompts.add("当前userId是:[" + userId + "]，调用agent的时候将用户id作为提示词提供");
        try {
            chatClient
                    .prompt()
                    .system(systemPrompts + getAgentInfo())
                    .user(prompt)
                    .tools(agentExecutor)
                    .toolContext(Map.of("emitter", emitter))
                    .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, chatId))
                    .stream().content()
                    .doOnNext(content -> {
                        if (content != null) {
                            sseEventUtil.sendText(emitter, content);
                        }
                    })
                    .doOnComplete(() -> {
                        sseEventUtil.callEnd(emitter);
                        emitter.complete();
                    })
                    .doOnError(throwable -> {
                        sseEventUtil.error(emitter, "处理时发生错误");
                        emitter.completeWithError(throwable);
                    })
                    .subscribe();
        } catch (Exception e) {
            emitter.completeWithError(e);
        }
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
