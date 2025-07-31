package edu.whut.cs.bi.rag.controller;

import edu.whut.cs.bi.rag.service.AgentService;
import jakarta.servlet.http.HttpSession;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;

@RestController
@RequestMapping("/api-ai")
@CrossOrigin
public class ChatBotController {

    private final AgentService agentService;

    public ChatBotController(AgentService agentService) {
        this.agentService = agentService;
    }

    @GetMapping("/chat-stream")
    public SseEmitter chatStream(@RequestParam String prompt, @RequestParam String chatId, @RequestParam String userId) {
        // 创建一个 SseEmitter，超时时间设置为 5 分钟
        SseEmitter emitter = new SseEmitter(300_000L);
        // 调用异步服务来处理 AI 逻辑
        agentService.chat(prompt, emitter, chatId, userId);
        // 立即返回 emitter 对象，HTTP 连接保持打开
        return emitter;
    }
}
