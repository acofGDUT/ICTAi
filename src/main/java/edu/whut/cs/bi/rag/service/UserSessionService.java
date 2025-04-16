package edu.whut.cs.bi.rag.service;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.InMemoryChatMemory;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class UserSessionService {
    private final Map<String, ChatMemory> userChatMemories = new ConcurrentHashMap<>();
    private final Map<String, ChatClient> userChatClients = new ConcurrentHashMap<>();

    public ChatMemory getOrCreateChatMemory(String userId) {
        return userChatMemories.computeIfAbsent(userId, key -> new InMemoryChatMemory());
    }

    public ChatClient getOrCreateChatClient(String userId, ChatClient.Builder builder) {
        return userChatClients.computeIfAbsent(userId, key -> 
            builder.defaultAdvisors(new MessageChatMemoryAdvisor(getOrCreateChatMemory(userId)))
                  .build()
        );
    }

    public void clearUserSession(String userId) {
        userChatMemories.remove(userId);
        userChatClients.remove(userId);
    }
} 