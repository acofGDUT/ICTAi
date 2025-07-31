package edu.whut.cs.bi.rag.agent.core;


import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Agent基础接口
 */
public interface BaseAgent {

    /**
     * 执行Agent业务逻辑。
     * @param prompt 用户输入的提示
     * @return Agent的执行结果，一个包含文本和/或附件的AgentResult对象。
     */
    String run(String prompt, SseEmitter emitter, String chatId, String userId, MessageChatMemoryAdvisor chatMemoryAdvisor);
    
    /**
     * 获取Agent的描述信息
     * @return Agent功能描述
     */
    String getDescription();
    
    /**
     * 获取Agent的名称
     * @return Agent名称
     */
    String getName();
}