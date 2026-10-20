package edu.whut.cs.bi.rag.mcp;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * MCP 端点要求 OAuth 访问令牌：Authorization: Bearer bimcp_xxx。
 * 每个请求都把访问令牌交换成短期 BI JWT（结果短暂缓存），工具以该用户身份调用 BuildingInspector。
 * 缺少或无效令牌时返回 401 + WWW-Authenticate，客户端据此找到授权服务器并弹出登录页。
 */
@Slf4j
public class McpAuthFilter extends OncePerRequestFilter {

    private static final String BEARER_PREFIX = "Bearer ";
    private static final String MCP_TOKEN_PREFIX = "bimcp_";

    private final BiTokenExchangeClient exchangeClient;
    private final BiApiClient biApiClient;
    private final McpOAuthProperties properties;
    private final String mcpEndpoint;

    public McpAuthFilter(BiTokenExchangeClient exchangeClient, BiApiClient biApiClient, McpOAuthProperties properties,
                         String mcpEndpoint) {
        this.exchangeClient = exchangeClient;
        this.biApiClient = biApiClient;
        this.properties = properties;
        this.mcpEndpoint = mcpEndpoint;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return "OPTIONS".equals(request.getMethod()) || !request.getRequestURI().equals(mcpEndpoint);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String token = extractToken(request);
        if (token == null) {
            unauthorized(response, null, "需要登录：请在 AI 客户端中完成 BuildingInspector 授权");
            return;
        }
        McpTokenContext.McpIdentity identity;
        try {
            identity = authenticate(token);
        } catch (BiTokenExchangeClient.InvalidTokenException e) {
            unauthorized(response, "invalid_token", e.getMessage());
            return;
        } catch (BiTokenExchangeClient.AuthServerUnavailableException e) {
            log.error("MCP 鉴权失败：{}", e.getMessage());
            writeError(response, HttpServletResponse.SC_SERVICE_UNAVAILABLE, "temporarily_unavailable", "授权服务器暂不可用，请稍后重试");
            return;
        }
        McpTokenContext.set(identity);
        try {
            chain.doFilter(request, response);
        } finally {
            McpTokenContext.clear();
        }
    }

    private McpTokenContext.McpIdentity authenticate(String token) {
        if (token.startsWith(MCP_TOKEN_PREFIX)) {
            return exchangeClient.exchange(token);
        }
        if (!properties.getAuth().isAllowLegacyJwt()) {
            throw new BiTokenExchangeClient.InvalidTokenException("访问令牌无效，请重新授权");
        }
        try {
            biApiClient.getDataAs(token, "/me");
        } catch (IllegalStateException e) {
            throw new BiTokenExchangeClient.InvalidTokenException(e.getMessage());
        }
        return new McpTokenContext.McpIdentity(token, null, "legacy-jwt", null, 0);
    }

    private String extractToken(HttpServletRequest request) {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header == null || header.isBlank()) {
            return null;
        }
        String token = header.regionMatches(true, 0, BEARER_PREFIX, 0, BEARER_PREFIX.length())
                ? header.substring(BEARER_PREFIX.length()) : header;
        return token.isBlank() ? null : token.trim();
    }

    private void unauthorized(HttpServletResponse response, String error, String description) throws IOException {
        StringBuilder challenge = new StringBuilder("Bearer resource_metadata=\"")
                .append(properties.getPublicBaseUrl()).append("/.well-known/oauth-protected-resource\"")
                .append(", scope=\"").append(properties.getScope()).append('"');
        if (error != null) {
            challenge.append(", error=\"").append(error).append('"');
        }
        response.setHeader(HttpHeaders.WWW_AUTHENTICATE, challenge.toString());
        writeError(response, HttpServletResponse.SC_UNAUTHORIZED, error == null ? "unauthorized" : error, description);
    }

    private void writeError(HttpServletResponse response, int status, String error, String description) throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write("{\"error\":\"" + error + "\",\"error_description\":\""
                + description.replace("\\", "\\\\").replace("\"", "'") + "\"}");
    }
}
