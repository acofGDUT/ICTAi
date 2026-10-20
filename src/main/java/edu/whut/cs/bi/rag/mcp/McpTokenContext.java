package edu.whut.cs.bi.rag.mcp;

import reactor.core.scheduler.Schedulers;

/**
 * 当前 MCP 请求对应的用户身份。
 * MCP SDK 在 boundedElastic 线程上执行工具，HTTP 线程只在 block() 处等待，
 * 所以需要通过 Reactor 调度钩子把身份带到工具线程。
 */
public final class McpTokenContext {

    private static final ThreadLocal<McpIdentity> IDENTITY = new ThreadLocal<>();

    private static final String HOOK_KEY = "bi-mcp-token";

    private McpTokenContext() {
    }

    public static void installSchedulerHook() {
        Schedulers.onScheduleHook(HOOK_KEY, task -> {
            McpIdentity identity = IDENTITY.get();
            if (identity == null) {
                return task;
            }
            return () -> {
                McpIdentity previous = IDENTITY.get();
                IDENTITY.set(identity);
                try {
                    task.run();
                } finally {
                    if (previous == null) {
                        IDENTITY.remove();
                    } else {
                        IDENTITY.set(previous);
                    }
                }
            };
        });
    }

    public static void set(McpIdentity identity) {
        IDENTITY.set(identity);
    }

    public static McpIdentity get() {
        return IDENTITY.get();
    }

    public static void clear() {
        IDENTITY.remove();
    }

    /**
     * @param biToken        调用 /toolCallingApi 使用的 BI JWT
     * @param accessToken    客户端持有的 MCP 访问令牌；旧版直传 JWT 时为 null
     * @param loginName      登录名，用于审计日志
     * @param clientName     授权时的客户端名称
     * @param tokenExpiresAt MCP 访问令牌过期时间（秒级时间戳），旧版直传 JWT 时为 0
     */
    public record McpIdentity(String biToken, String accessToken, String loginName, String clientName,
                              long tokenExpiresAt) {
    }
}
