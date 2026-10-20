# 桥梁检测 MCP 服务使用说明

本服务把桥梁检测系统（BuildingInspector）的数据，以 MCP（Model Context Protocol）协议提供给码道（CodeArts）、Cursor、MCP Inspector 等 AI 客户端。AI 助手以你的身份只读查询你有权限的桥梁、项目、任务和病害，权限与你在网页端一致。

> **当前状态（2026-10-19）**：OAuth 登录已在本地实现。WorkBuddy 5.8.3 只填地址即可在安装时打开授权页并完成登录。码道桌面端 0.2.3 会认出需要登录，但不会打开授权页，要用手动令牌。设计见 [设计文档](superpowers/specs/2026-10-19-mcp-oauth-design.md)。

## 1. 接入步骤

服务使用 MCP 的 Streamable HTTP 传输（协议版本 2025-06-18），只有一个端点 `/mcp`，旧的 `/sse` 已下线。本地开发环境的地址：

- MCP 服务：`http://127.0.0.1:8081/mcp`
- 授权页所在的桥梁检测系统：`http://127.0.0.1`

正式部署后两者都应换成对外的 https 地址。账号密码只在桥梁检测系统自己的页面上输入。

各客户端的配置文件格式不一样。MCP 协议没有规定配置文件长什么样，下面按实测过的客户端分别写。

### 1.1 WorkBuddy：只填地址，安装时登录

编辑 `~/.workbuddy/mcp.json`，不要写 `Authorization`。写了令牌之后，WorkBuddy 会直接用令牌，不再打开登录页。

```json
{
  "mcpServers": {
    "building-inspector": {
      "type": "streamable-http",
      "url": "http://127.0.0.1:8081/mcp"
    }
  }
}
```

保存后打开这个 MCP。WorkBuddy 收到 401 和 `WWW-Authenticate` 后，会读取 `/.well-known/oauth-protected-resource`，动态注册，并打开 `http://127.0.0.1/oauth/authorize`。输入桥梁检测系统的账号和密码，登录成功后浏览器跳回 `workbuddy://...`。如果系统询问是否打开 WorkBuddy，选择打开。

完成后界面上的「需要认证」消失，8 个工具可用。令牌由 WorkBuddy 自己保存，不写进 `mcp.json`：

- 文件：`~/.workbuddy/connectors/<用户 id>/.credentials.v3.json`（权限 `0600`）
- `mcpOAuth` 里是加密后的访问令牌（AES-256-GCM）和过期时间
- `mcpClientInfo` 里是动态注册得到的 `client_id` 和回调地址
- 没有刷新令牌。7 天到期后需要重新授权

### 1.2 码道（CodeArts）桌面端 0.2.3：网页生成令牌

这个版本收到 401 后会把连接器标成 “Authentication required”，然后停止。内核日志里能看到 `resource_metadata`，但桌面界面不会打开浏览器，也没有授权按钮。配置里写 `"oauth": {}` 不能让它弹出登录页。

改用手动令牌：

1. 浏览器打开 `http://127.0.0.1/oauth/connect`，也可以从网页端「个人中心 → AI 授权管理 → 生成访问令牌」进入。
2. 选择「码道 CodeArts」，输入账号和密码，点击「登录并生成令牌」。
3. 在「码道桌面端」页复制配置。码道自定义配置的外层是服务名，不要贴 WorkBuddy 的 `mcpServers`。磁盘上的文件是 `~/.codearts/mcp/mcp_settings.json`，保存后会再包一层 `"mcp"`。

```json
{
  "bridge": {
    "type": "remote",
    "url": "http://127.0.0.1:8081/mcp",
    "enabled": true,
    "headers": { "Authorization": "Bearer bimcp_..." },
    "oauth": false
  }
}
```

`oauth: false` 表示不再走它做不到的自动登录。贴好后码道会重新连接，工具列表出现即可提问。

手动令牌和 OAuth 令牌是同一种令牌：7 天有效、只读、权限跟随账号，出现在「AI 授权管理」里，名称带「（手动令牌）」。令牌只在结果页显示一次。换账号时用另一个账号重新生成，再替换 `Authorization`。

### 1.3 Cursor

本轮没有在 Cursor 里做安装时登录的实测。可以在 `/oauth/connect` 选择 Cursor，把结果页的配置贴进 `~/.cursor/mcp.json`。形状是 `mcpServers` 加 `url` 和 `headers`。

### 1.4 授权有效期、切换账号与撤销

- **有效期**：一次授权 7 天有效，期间不需要重复登录。没有自动续期。到期后 WorkBuddy 会再次要求授权；码道需要回到 `/oauth/connect` 重新生成配置。
- **切换账号**：
  - WorkBuddy：在对话里说「切换账号」或「退出登录」，AI 会调用 `switch_account` 撤销当前令牌。关掉再打开该 MCP，授权页会再出现，输入另一个账号。
  - 码道：用另一个账号打开 `/oauth/connect`，用新配置替换原来的 `Authorization`。
  - 授权页每次都要求输入账号密码，不会沿用浏览器里网页端的登录状态。
- **撤销授权**：在桥梁检测系统网页端进入「个人中心 → AI 授权管理」，可以看到自己授权过的客户端，点击「撤销」即可，最迟 60 秒生效。

## 2. 部署与配置

| 服务 | 配置项 | 说明 |
|---|---|---|
| BuildingInspector | `oauth.issuer`（环境变量 `BI_OAUTH_ISSUER`） | 授权服务器对外地址，默认 `http://127.0.0.1` |
| BuildingInspector | `oauth.mcp-resource-uris`（`BI_MCP_RESOURCE_URI`） | MCP 服务对外地址，不含 `/mcp` |
| BuildingInspector | `oauth.signing-secret`（`BI_OAUTH_SIGNING_SECRET`） | 签名密钥，至少 32 个字符 |
| 两边一致 | `oauth.mcp-server-client-secret` / `bi-mcp.oauth.client-secret`（`BI_MCP_CLIENT_SECRET`） | MCP Server 做令牌交换时使用的密钥 |
| 本服务 | `bi-mcp.public-base-url`（`BI_MCP_PUBLIC_BASE_URL`） | 与 `oauth.mcp-resource-uris` 相同 |
| 本服务 | `bi-mcp.oauth.issuer`（`BI_OAUTH_ISSUER`） | 与 BuildingInspector 的 `oauth.issuer` 相同 |

- `application.yml` 里的默认密钥只能用于本地开发，正式部署必须用环境变量覆盖。
- 数据库需要先执行 `BuildingInspector/sql/bi_20261019_mcp_token.sql`，创建 `bi_mcp_token` 表。表里只保存令牌的哈希。
- 调试时如需临时直接传 `/jwt/login` 拿到的 token，可以设置 `bi-mcp.auth.allow-legacy-jwt=true`，默认关闭。

## 3. 可用工具

除 `switch_account` 外，所有工具都是只读的。

| 工具 | 参数 | 返回内容 |
|---|---|---|
| `whoami` | 无 | 当前登录用户的 id、登录名、姓名、部门，以及本次授权的到期时间 |
| `switch_account` | 无 | 撤销当前授权，用于切换账号或退出登录 |
| `search_bridges` | `keyword`：桥名关键字 | 匹配的桥梁 id、名称、所属桥梁（分幅时）、路线编号，最多 30 条 |
| `get_bridge_profile` | `bridgeId` | 桥梁档案：基础数据、行政识别（含技术状况等级）、技术指标、结构信息、养护记录，以及正面和侧面照片 |
| `get_structure_tree` | `bridgeId` | 结构（上部/下部/桥面系）、部位、构件类型，含评定权重和构件数量 |
| `list_my_projects` | 无 | 你参与的项目（检测、编写、审核、批准任一角色） |
| `list_project_tasks` | `projectId` | 项目下的检测任务及对应桥梁 |
| `query_diseases` | `bridgeId`，以及可选的 `year`、`minLevel`、`componentKeyword`、`limit` | 病害汇总（按标度、发展趋势、构件统计）和明细（构件、位置、类型、标度、描述、成因、维修建议、图片） |

服务端会在连接时向 AI 助手下发调用顺序说明，例如先查桥梁 id 再查病害、桥名有歧义时先问用户，一般不需要你手动指定工具。

## 4. 示例问题

- 我是谁？我的授权什么时候到期？
- 我参与了哪些项目？
- 大广北高速 2025 年定期检查项目里有哪些桥？
- 大湾1桥的技术状况等级是多少？桥长和跨径组合是什么？
- 大湾1桥由哪些部件组成？上部承重构件在评定里的权重是多少？
- 大湾1桥 2025 年有哪些 3 级及以上的病害？
- 大湾1桥的伸缩缝有哪些病害？哪些是新增的？
- 帮我切换账号。

## 5. 常见问题

**码道显示 Authentication required，没有打开浏览器**

码道桌面端 0.2.3 的预期行为。它认出了 401，但不会继续注册和打开授权页。按第 1.2 节用 `/oauth/connect` 生成配置，并设置 `"oauth": false`。不要把 WorkBuddy 的 `mcpServers` 配置贴进码道，码道会把 `mcpServers` 当成服务名。

**WorkBuddy 停在「授权中…」**

登录已经成功，但跳回 WorkBuddy 的地址没有对上。回调地址里如果已有 `%3A`，授权服务器不能再编码成 `%253A`。当前版本已按原样拼接查询参数。重新开关一次 `building-inspector` 再授权。Safari 询问是否打开 WorkBuddy 时要选择打开。

**连接时没有弹出浏览器，直接报 401**

- 先确认 MCP 地址能访问：`curl -i -X POST http://127.0.0.1:8081/mcp` 应返回 401，且带有 `WWW-Authenticate: Bearer resource_metadata="..."` 响应头。
- 再确认 `http://127.0.0.1:8081/.well-known/oauth-protected-resource` 能返回授权服务器地址。
- WorkBuddy 的配置里如果已经写了 `Authorization`，它不会再打开登录页。要走安装时登录，就删掉请求头，只留地址。

**授权页提示“授权请求无效或已超过 10 分钟”**

授权页打开太久，或者回调地址与客户端注册时不一致。回到客户端重新连接即可。

**授权页提示“客户端无效”**

授权服务器的签名密钥换过，之前注册的客户端失效了。在客户端里删除这个 MCP 配置后重新添加。

**工具返回“BuildingInspector 拒绝了当前登录凭证”**

授权已过期、被撤销，或者账号被停用。在客户端里重新认证。

**`list_my_projects` 返回空列表**

当前账号不是任何项目的成员。`admin_ai` 这类服务账号就是这种情况，换成实际参与项目的账号即可。

**查不到某座桥，或者查到多座**

`search_bridges` 是模糊匹配，分幅桥（如“左幅”“右幅”）会分别列出。请使用更完整的桥名，或者从结果里选定具体的 `bridgeId`。

**病害结果太多**

在提问时加上条件，例如年份、“3 级以上”或构件名称，对应 `query_diseases` 的 `year`、`minLevel`、`componentKeyword` 参数。

**图片打不开**

图片链接指向系统的文件服务（MinIO），需要你的网络能访问该地址。
