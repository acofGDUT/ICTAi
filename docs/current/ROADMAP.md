# Springai-Rag 路线图

> 本文只记录未来计划、未完成能力、阻塞和仍需验收的风险。

最后更新：2026-10-19

## 1. 当前焦点

已有能力（本地脚本测试，以及 WorkBuddy、码道桌面端实测）：

- MCP Server：Streamable HTTP 传输（`/mcp`，协议 `2025-06-18`），8 个工具（7 个只读 + `switch_account`），`initialize` 时下发调用顺序说明。
- OAuth 登录：BuildingInspector 作为授权服务器。访问令牌 7 天有效，只存哈希，可撤销，可切换账号。网页端「个人中心 → AI 授权管理」可查看和撤销。
- WorkBuddy 5.8.3：只填地址即可在安装时打开授权页。令牌保存在 `~/.workbuddy/connectors/<用户 id>/.credentials.v3.json`。
- 码道桌面端 0.2.3：能识别 401，但不会打开授权页。用 `/oauth/connect` 生成带令牌的配置，`oauth` 设为 `false`。
- 依赖升级：Spring Boot 3.3 → 3.5.16，Spring AI 1.0.0 → 1.1.8，原聊天接口回归正常。

## 2. 近期优先级

| 优先级 | 能力 | 状态 | 下一步 | 设计/计划 |
|--------|------|------|--------|-----------|
| P0 | MCP OAuth 接入 | WorkBuddy 安装时登录已通过；码道用手动令牌已连通 | MCP Inspector 仍未走；码道切换账号未在对话里实测 | [spec](../superpowers/specs/2026-10-19-mcp-oauth-design.md) / [plan](../superpowers/plans/2026-10-19-mcp-oauth-plan.md) |
| P1 | MCP 分析类工具 | 未设计 | 先设计 `compare_disease_history` 和 `precheck_report` | 未创建 |

## 3. 当前阻塞和遗留

| 项目 | 状态 | 阻塞/剩余边界 | 解除条件或下一步 |
|------|------|---------------|------------------|
| 码道桌面端 0.2.3 不打开授权页 | 已确认 | 内核收到 `resource_metadata` 后停在 Authentication required，界面没有授权按钮 | 当前用 `/oauth/connect` 手动令牌。等桌面端调用它自己的 OAuth 接口后再改为只填地址 |
| 正式部署 | 未开始 | 两个服务需在 HTTPS 反向代理之后；密钥须用环境变量提供 | 确定对外域名后配置 `issuer`、`public-base-url` 和两把密钥 |
| `/toolCallingApi/project/{id}` 和 `/project/{projectId}/task` 越权 | 存在 | 不校验调用者，原聊天 Agent 仍依赖这两个接口 | 单独设计修复。MCP 的 `list_project_tasks` 在修复前同样受影响 |
| Maven 中央仓库 | 本机限制 | 本机网络访问 `repo.maven.apache.org` 超时 | 构建时用阿里云镜像（`mvn -s <镜像 settings>`） |
