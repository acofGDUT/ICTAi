# MCP OAuth 登录实现计划

Status: Task 1–4 已完成。Task 5 部分完成：WorkBuddy 5.8.3 安装时登录已通过；码道桌面端 0.2.3 不会打开授权页，已用 `/oauth/connect` 手动令牌连通。MCP Inspector 未做。

**Goal:** 码道桌面端只配置 URL，即可弹出 BuildingInspector 授权登录页。登录后获得 7 天有效的访问令牌，支持撤销和切换账号。

**Architecture:**

- BuildingInspector 的 `bi-api` 模块实现授权服务器。它只用一张表 `bi_mcp_token`，`client_id` 采用签名编码，授权码放在内存。
- Springai-Rag 的 MCP Server 作为资源服务器。它通过令牌交换拿到 10 分钟有效的 BI JWT，再调用 `/toolCallingApi`。

**Tech Stack:**

- BuildingInspector：Spring Boot 2.5、Shiro、MyBatis XML、Thymeleaf，构建用 JDK 17。
- Springai-Rag：Spring Boot 3.5.16、Spring AI 1.1.8、MCP SDK 0.18.3，Streamable HTTP（2026-10-19 从 3.3 / 1.0.0 / SSE 升级）。
- 不引入 Spring Security 或 Spring Authorization Server。

## Evidence and References

- Spec：[2026-10-19-mcp-oauth-design.md](../specs/2026-10-19-mcp-oauth-design.md)
- 当前路线：[docs/current/ROADMAP.md](../../current/ROADMAP.md)

## File Structure

| 文件 | 操作 | 职责 |
|---|---|---|
| `BuildingInspector-Contest/sql/bi_20261019_mcp_token.sql` | 新建 | 建表 `bi_mcp_token` |
| `bi-api/.../api/domain/McpToken.java` | 新建 | 令牌记录 |
| `bi-api/.../api/mapper/McpTokenMapper.java` 和 `resources/mapper/api/McpTokenMapper.xml` | 新建 | 令牌的增、查、撤销，以及更新最近使用时间 |
| `bi-api/.../api/oauth/OAuthProperties.java` | 新建 | `oauth.*` 配置项 |
| `bi-api/.../api/oauth/OAuthSigner.java` | 新建 | HMAC 签名，用于 `client_id` 编码和授权请求防篡改 |
| `bi-api/.../api/oauth/OAuthRedirects.java` | 新建 | 在已编码的回调地址后追加参数，避免把 `%3A` 编成 `%253A` |
| `bi-api/.../api/oauth/OAuthClientRegistry.java` | 新建 | 动态注册时校验回调地址，编码和解码 `client_id` |
| `bi-api/.../api/oauth/AuthorizationCodeStore.java` | 新建 | 内存授权码存储，5 分钟有效、只能用一次 |
| `bi-api/.../api/oauth/McpTokenService.java` | 新建 | 签发、校验、撤销令牌；PKCE 校验；令牌交换 |
| `bi-api/.../api/oauth/OAuthException.java` | 新建 | OAuth 错误码 |
| `bi-api/.../api/controller/OAuthController.java` | 新建 | 元数据、注册、授权、令牌、撤销五类端点 |
| `bi-api/.../api/controller/McpTokenController.java` | 新建 | AI 授权管理页 |
| `ruoyi-admin/.../templates/oauth/authorize.html`、`templates/oauth/error.html` | 新建 | 授权登录页和错误页 |
| `ruoyi-admin/.../templates/system/mcpToken/mcpToken.html` | 新建 | AI 授权管理页面 |
| `ruoyi-framework/.../config/ShiroConfig.java` | 修改 | `/.well-known/**` 和 `/oauth/**` 设为匿名访问，`/oauth/authorize` 加上 `captchaValidate` |
| `ruoyi-admin/.../application.yml` | 修改 | 新增 `oauth.*` 配置 |
| `bi-api/src/test/.../oauth/*Test.java` | 新建 | 签名、回调地址、PKCE、授权码的单元测试 |
| `Springai-Rag-Contest/.../mcp/McpAuthFilter.java` | 修改 | 用访问令牌做令牌交换，返回带 `WWW-Authenticate` 的 401，保留默认关闭的旧方案开关 |
| `.../mcp/BiTokenExchangeClient.java` | 新建 | 调用令牌交换接口并缓存结果 |
| `.../mcp/McpOAuthProperties.java` | 新建 | `bi-mcp.*` 配置项 |
| `.../mcp/OAuthMetadataController.java` | 新建 | 资源元数据，以及转发授权服务器元数据 |
| `.../mcp/McpTokenContext.java` | 修改 | 保存交换得到的身份信息（BI JWT、用户、原始访问令牌） |
| `.../mcp/BiMcpTools.java` | 修改 | 新增 `switch_account` 工具 |
| `/tmp/oauth_e2e.py` | 临时文件 | 脚本化的端到端测试和负向用例，不提交 |
| `Springai-Rag-Contest/docs/mcp-usage.md`、`docs/current/*` | 修改 | 文档收口 |

## Tasks

### Task 1：授权服务器核心逻辑（P0）

- [x] 建表，并在本地库执行。
- [x] 实现 `OAuthSigner`、`OAuthClientRegistry`、`AuthorizationCodeStore`、`McpTokenService`。
- [x] 单元测试（7 个通过）：
  - 篡改过的 `client_id` 无法通过校验。
  - 回调地址白名单：localhost、https、自定义 scheme 放行；`javascript:` 拒绝。
  - PKCE S256 与 RFC 7636 附录 B 的示例结果一致。
  - 授权码第二次使用失败，过期后使用也失败。
- 验证：`mvn -pl bi-api test`

### Task 2：授权服务器端点和授权登录页（P0）

- [x] 实现 `OAuthController` 的元数据、注册、授权（GET/POST）、令牌（授权码、令牌交换）、撤销端点。
- [x] 新建授权登录页模板（放在 `bi-api/src/main/resources/templates/oauth/`），样式沿用若依；页面上包含验证码、“登录并授权”和“拒绝”。
- [x] 修改 Shiro 规则，补充配置项。端到端脚本 32 项通过。
- 验证：以 `--shiro.user.captchaEnabled=false` 启动 BuildingInspector，用 `/tmp/oauth_e2e.py` 依次执行：注册、GET 授权页、POST 登录、兑换令牌、令牌交换、撤销，以及 spec 中列出的各项负向用例。

### Task 3：MCP Server 资源服务器（P0）

- [x] 实现元数据端点、令牌交换客户端（含缓存）、`McpAuthFilter` 的改造和 `switch_account` 工具。
- [x] 默认不再接受直接传入的 BI JWT。
- [x] 升级 Spring AI 1.1.8，传输改为 Streamable HTTP（`/mcp`）。端到端 23 项通过，原聊天接口回归正常。
- 验证：
  - 端到端脚本：拿到 OAuth 令牌后经 MCP 调用 `whoami`。
  - 没有令牌时返回 401，并带 `WWW-Authenticate`。
  - 调用 `switch_account` 后，下一次请求返回 401。
  - 撤销后 60 秒内失效。
  - 原聊天接口仍然正常。

### Task 4：AI 授权管理页（P1）

- [x] 实现 `/system/mcpToken`：列出本人有效的授权，可以撤销；入口是个人中心的“AI 授权管理”标签页。端到端 7 项通过。
- 验证：通过网页登录后撤销授权，端到端脚本中对应令牌失效。

### Task 5：真实客户端验收和文档收口（P0）

- [ ] 用 MCP Inspector 走完整流程。
- [x] WorkBuddy 5.8.3：只填 `url`，安装时打开授权页，登录后 8 个工具可用。令牌写在 `~/.workbuddy/connectors/<用户 id>/.credentials.v3.json`。
- [x] 码道桌面端 0.2.3：确认不会打开授权页。用 `/oauth/connect` 生成配置（`oauth: false` 加 `Authorization`）后连接成功。
- [ ] 码道上的切换账号还没在对话里实测。
- [x] 更新 `docs/mcp-usage.md`、`docs/current/ROADMAP.md`。

## Execution Constraints

- 不修改 `/jwt/login`、`JwtFilter` 和现有 `/toolCallingApi` 的行为。
- 不在日志里输出令牌、密码或 BI JWT。
- 开发用的默认密钥只写在 `application.yml` 的占位默认值里；正式部署必须用环境变量覆盖。
- 每完成一批，都要先重新构建两个服务、重启并跑端到端脚本，再进入下一批。
