package edu.whut.cs.bi.rag.service;


import edu.whut.cs.bi.rag.agent.core.AgentRegistry;
import edu.whut.cs.bi.rag.agent.core.BaseAgent;
import edu.whut.cs.bi.rag.agent.core.RouterAgent;
import edu.whut.cs.bi.rag.agent.utils.SseEventUtil;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Slf4j
@Service
public class AgentService {
    
    @Resource
    private SseEventUtil sseEventUtil;
    
    @Resource
    private RouterAgent routerAgent;

    private AgentRegistry agentRegistry;
    
    public AgentService(AgentRegistry agentRegistry) {
        this.agentRegistry = agentRegistry;
    }
    
    @Async
    public void chat(String prompt, SseEmitter emitter, String chatId, String userId) {
        // 使用路由 agent 进行路由
        sseEventUtil.sendSseEvent(emitter, "start", null);
        String agentName = routerAgent.run(prompt, emitter, chatId, userId).getAgentName();
        BaseAgent agent = agentRegistry.getAgent(agentName);
        if (agent == null) {
            log.info("agent不存在: {}", agentName);
            sseEventUtil.error(emitter, "服务器内部异常，请联系管理员");
            return;
        }
        long startTime = System.currentTimeMillis();
        agent.run(prompt, emitter, chatId, userId);
        long endTime = System.currentTimeMillis();
        log.info("agent执行时间: {} ms", (endTime - startTime));
    }
}
