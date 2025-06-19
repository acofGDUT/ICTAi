package edu.whut.cs.bi.rag.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.InMemoryChatMemory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class UserSessionService {
    @Autowired
    private RestTemplate restTemplate;
    @Value("${bi-location}")
    private String base_Url;
    private final Map<String, ChatMemory> userChatMemories = new ConcurrentHashMap<>();
    private final Map<String, ChatClient> userChatClients = new ConcurrentHashMap<>();
    @Value("${bi-Username}")
    private String bi_Username;
    @Value("${bi-Password}")
    private String bi_Password;

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

    public String getToken() throws JsonProcessingException {
        String url = base_Url+"/jwt/login?username="+bi_Username+"&password="+bi_Password;
        HttpHeaders headers = new HttpHeaders();
        headers.set("Accept", "*/*");
        headers.set("Accept-Encoding", "gzip, deflate, br");
        headers.set("Connection", "keep-alive");
        HttpEntity entity = new HttpEntity<>(headers);
        ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.POST, entity, String.class);
        String jsonStr = response.getBody();
        ObjectMapper mapper = new ObjectMapper();
        // 将JSON字符串转换为Map
        Map<String, Object> jsonMap = mapper.readValue(jsonStr, Map.class);
        // 从Map中获取token字段
        String token = (String) jsonMap.get("token");
        System.out.println("返回的携带token："+token);
        return token;
    }
} 