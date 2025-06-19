package edu.whut.cs.bi.rag.service;
import com.fasterxml.jackson.core.JsonProcessingException;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Instant;
@Component
public class TokenManager {
    private volatile String token;
    // 令牌获取时间
    private volatile Instant tokenObtainTime;

    @Value("${bi-tokenExpirationInSeconds}")
    private long tokenExpirationInSeconds;
    @Autowired
    private UserSessionService userSessionService;

    @PostConstruct
    public void initToken() throws JsonProcessingException {
        // 在程序初始化时 初始化token
        token = userSessionService.getToken();
        tokenObtainTime = Instant.now();
    }

    public String getToken() throws JsonProcessingException {
        if(IsTokenExpired() == true){
            refreshToken();
            return token;
        }else{
            return token;
        }
    }

    public void refreshToken() throws JsonProcessingException {
        token = userSessionService.getToken();
        tokenObtainTime = Instant.now();
    }

    private Boolean IsTokenExpired() {
        Instant now = Instant.now();
        return now.isAfter(tokenObtainTime.plusSeconds(tokenExpirationInSeconds));
    }

}
