package edu.whut.cs.bi.rag.service;


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
    
    @Async
    public void chat(String prompt, SseEmitter emitter, String chatId, String userId) {
        log.info("开始处理用户请求，用户ID: {}, 对话ID: {}, 用户消息: {}", userId, chatId, prompt);
        try {
            // 使用路由 agent 进行路由
            BaseAgent agent = routerAgent.run(prompt, emitter, chatId, userId);
            log.info("开始执行Agent: {}", agent.getName());
            agent.run(prompt, emitter, chatId, userId);
            log.info("Agent执行完成: {}", agent.getName());
        } catch (Exception e) {
            log.error("处理用户请求时发生异常，用户ID: {}, 对话ID: {}", userId, chatId, e);
            log.error("用户消息: {}", prompt);
            sseEventUtil.error(emitter, "服务器内部异常，请联系管理员");
            emitter.complete();
        }
    }
}