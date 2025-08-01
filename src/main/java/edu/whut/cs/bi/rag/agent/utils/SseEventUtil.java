package edu.whut.cs.bi.rag.agent.utils;


import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Map;
import java.util.Optional;


@Component
public class SseEventUtil {
    
    /**
     * 发送SSE事件
     */
    public void sendSseEvent(SseEmitter emitter, String eventName, Object data) {
            try {
                emitter.send(SseEmitter.event().name(eventName).data(Optional.ofNullable(data).orElse(Map.of())));
            } catch (IOException e) {
                emitter.completeWithError(e);
            }
        
    }
    
    /**
     * 发送文本消息到指定的SSE连接
     *
     * @param emitter 目标SSE连接的发射器对象，用于向客户端推送消息
     * @param text 要发送的文本消息内容
     */
    public void sendText(SseEmitter emitter, String text) {
        sendSseEvent(emitter, "text", Map.of("text", text));
    }
    
    
    public void callEnd(SseEmitter emitter) {
        sendSseEvent(emitter, "callEnd", null);
    }
    
    public void error(SseEmitter emitter, String message) {
        sendSseEvent(emitter, "error", message);
    }
    
    public void runAgent(SseEmitter emitter, String agentName) {
        sendSseEvent(emitter, "runAgent", Map.of("agentName", agentName));
    }
    
    public void agentEnd(SseEmitter emitter) {
        sendSseEvent(emitter, "agentEnd", null);
    }
    
    public void knowledgeReference(SseEmitter emitter, String reference) {
        sendSseEvent(emitter, "reference", Map.of("reference", reference));
    }
    
    
    public void toolCall(SseEmitter emitter, String toolName) {
        sendSseEvent(emitter, "toolCall", Map.of("toolName", toolName));
    }
    

}
