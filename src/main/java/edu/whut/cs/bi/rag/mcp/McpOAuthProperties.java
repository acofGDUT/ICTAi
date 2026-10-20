package edu.whut.cs.bi.rag.mcp;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * MCP Server 作为 OAuth 资源服务器的配置（bi-mcp.*）。
 */
@Data
@Component
@ConfigurationProperties(prefix = "bi-mcp")
public class McpOAuthProperties {

    /** 客户端看到的 MCP 地址（不含 /sse），同时作为令牌绑定的 resource */
    private String publicBaseUrl;

    private String scope = "bridge:read";

    private OAuth oauth = new OAuth();

    private Auth auth = new Auth();

    @Data
    public static class OAuth {
        /** BuildingInspector 授权服务器地址 */
        private String issuer;
        private String clientId = "bi-mcp-server";
        private String clientSecret;
        /** 令牌交换结果的最长缓存时间，也就是网页端撤销后最长的生效延迟 */
        private Duration exchangeCacheTtl = Duration.ofSeconds(60);
    }

    @Data
    public static class Auth {
        /** 开发调试用：允许直接传 BuildingInspector 的 /jwt/login token */
        private boolean allowLegacyJwt = false;
    }
}
