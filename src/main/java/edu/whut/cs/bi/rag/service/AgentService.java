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
        log.info("开始处理用户请求，用户ID: {}, 对话ID: {}", userId, chatId);
        try {
            // 使用路由 agent 进行路由
            sseEventUtil.sendSseEvent(emitter, "start", null);
            String agentName = routerAgent.run(prompt, emitter, chatId, userId).getAgentName();
            log.info("路由结果，选择的Agent: {}", agentName);
            BaseAgent agent = agentRegistry.getAgent(agentName);
            if (agent == null) {
                log.info("请求的agent不存在: {}", agentName);
                sseEventUtil.error(emitter, "服务器内部异常，请联系管理员");
                return;
            }
            log.info("开始执行Agent: {}", agentName);
            agent.run(prompt, emitter, chatId, userId);
            log.info("Agent执行完成: {}", agentName);
            // 发送结束信号
            sseEventUtil.sendSseEvent(emitter, "end", null);
        } catch (Exception e) {
            log.error("处理用户请求时发生异常，用户ID: {}, 对话ID: {}", userId, chatId, e);
            try {
                sseEventUtil.error(emitter, "服务器内部异常，请联系管理员");
            } catch (Exception emitterException) {
                log.error("向客户端发送错误信息时发生异常，用户ID: {}, 对话ID: {}", userId, chatId, emitterException);
            } finally {
                emitter.complete();
            }
        }
    }
}