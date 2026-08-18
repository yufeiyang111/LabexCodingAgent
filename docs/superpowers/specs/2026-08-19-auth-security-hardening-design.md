# LabexAgent 认证模块安全增强设计

## 1. 目标与已确认决策

本迭代只增强登录/注册认证模块，不改 Agent 工作区、项目文件、终端、SSE、Provider、运行时状态机或其他业务模块。

已确认决策：

- 登录注册采用参考项目的左右分栏构图。
- 保留 LabexAgent 现有品牌文案、暖纸色调、字体语气和认证卡片质感。
- 注册增加可选邮箱字段；邮箱只用于资料和 OAuth 冲突提示，不发送邮箱验证码。
- 图形验证码由后端生成，在登录失败达到阈值或注册风险升高时按需出现。
- 第三方登录支持 GitHub 和 Google；凭证可选，未配置时按钮隐藏且不阻断启动。
- OAuth 按 `(provider, subject)` 绑定，不按邮箱自动合并；邮箱冲突时要求先登录本地账号，再完成绑定。
- 认证接口使用 Redis 分布式限流；生产环境必须配置 Redis。
- 本期保留现有 JWT/Bearer 和 `localStorage` 会话契约，以保证既有路由、请求层和工作区不失效。HttpOnly Cookie 和 WebSocket ticket 延后到独立迭代。
- XSS 防护只覆盖认证输入、认证输出和认证响应头；不重构工作区 Markdown、Mermaid 或预览渲染。

## 2. 范围与非目标

### 2.1 范围

前端认证模块：

- `Auth.vue` 页面编排；
- 登录/注册表单及认证专用子组件；
- `authApi` 中的认证接口封装；
- 认证相关的样式和局部状态；
- 现有 `/login` 路由下的 OAuth query 回调处理。

后端认证模块：

- `AuthController`、`AuthService` 及其认证内部实现；
- 图形验证码、Redis 限流、OAuth Provider 和 OAuth 绑定；
- `t_user` 的可选邮箱字段和 OAuth 绑定表；
- 认证专用配置、Redis 依赖和安全响应头；
- 认证单元/集成测试和部署文档。

### 2.2 非目标

以下模块在本迭代不得修改：

- `AgentLoopEngine`、Provider、Tool、权限、任务、SSE 和持久化运行时；
- `CloudWorkspace`、项目 API、文件系统和终端协议；
- `router/index.js` 的公共守卫逻辑；
- `utils/request.js` 的全局拦截器；
- `stores/user.js` 的现有 token 存储和会话方法；
- `useTerminal.js` 的 WebSocket 协议；
- 工作区 Markdown/Mermaid/预览渲染链路。

## 3. 参考实现与适配说明

参考项目为 `D:\workproject\work\work-5238`，只学习设计思想和交互结构，不复制实质代码：

- `frontend/src/views/Login.vue`：登录 Page 的表单、OAuth 分隔区和第三方按钮；
- `frontend/src/views/Register.vue`：注册字段校验、可用性检查和协议勾选；
- `frontend/src/components/auth/AuthLayout.vue`：品牌区/表单区具名插槽布局；
- `frontend/src/components/auth/AuthFormContainer.vue`：标题与表单容器；
- `frontend/src/components/auth/BrandPanel.vue`：品牌叙事区；
- `frontend/src/components/auth/FormInput.vue`、`PasswordInput.vue`、`SubmitButton.vue`：字段和提交控件；
- `backend/app/routes/oauth.py`：Provider/subject 绑定、邮箱冲突拒绝和 state 回调思路；
- `backend/app/services/rate_limit.py`：按作用域、来源和 actor 组合限流键的思路。

LabexAgent 适配：

- 使用 Vue 3 + Element Plus + 现有 Wabi-Sabi 主题，不引入参考项目的 CSS 框架；
- 使用现有 `Result`、MyBatis-Plus 和 JWT 工具，不引入 Flask/Authlib；
- 不复制参考项目源码；仅复刻左右分栏、组件职责、OAuth 不自动合并和可配置限流等不变量；
- OAuth 回调返回一次性 `oauthCode`，登录页通过既有 `userStore.login({ oauthCode })` 兑换，避免触碰公共会话 Store。

本迭代不涉及 OpenCode Agent runtime 的主循环、上下文、工具或事件协议，因此不复制 OpenCode 实质代码，也不改变其持久化事实源。

## 4. 模块与接口

### 4.1 前端组件树

```text
Auth.vue                         Page 编排、模式切换、OAuth query 处理
└─ AuthLayout                    左右分栏和响应式容器
   ├─ AuthBrandPanel             LabexAgent 品牌叙事
   └─ AuthFormContainer          标题、说明和表单槽位
      ├─ LoginForm               用户名、密码、按需验证码
      │  ├─ AuthField
      │  ├─ PasswordField
      │  ├─ CaptchaField
      │  └─ AuthSubmitButton
      ├─ RegisterForm             用户名、可选邮箱、显示名、密码确认
      │  ├─ AuthField
      │  ├─ PasswordField
      │  ├─ CaptchaField
      │  ├─ AgreementField
      │  └─ AuthSubmitButton
      ├─ OAuthButtons              只显示后端启用的 Provider
      ├─ AuthFeedback              aria-live 的错误/成功/限流提示
      └─ AuthSwitchLink            登录/注册模式切换
```

`Auth.vue` 只负责组合和调用 `useUserStore` 的现有 `login/register` 方法。字段校验、验证码显示和 OAuth query 处理留在认证模块内部。

### 4.2 后端内部模块

- `AuthApplicationService`：编排注册、登录、OAuth 兑换和绑定；
- `CredentialPolicy`：用户名、邮箱、显示名、密码的服务端校验；
- `CaptchaService`：生成图片、摘要保存、单次校验和 TTL；
- `LoginRiskService`：失败计数、验证码触发和冷却判断；
- `AuthRateLimitService`：Redis 计数和 `Retry-After` 数据；
- `OAuthProviderRegistry`：GitHub/Google 配置和 Provider Adapter；
- `OAuthIdentityService`：subject 查找、邮箱冲突判断和绑定；
- `AuthResponseFactory`：保持现有 `{token,userInfo}` 响应形状并隐藏敏感字段。

Controller 不直接访问 Redis、HTTP Provider 或 Mapper。

### 4.3 HTTP 接口

保留 `/api` context path 和现有 `Result` 包装。认证业务错误使用现有非零 `code`，不改变全局请求拦截器的成功判断。

```text
GET  /auth/captcha?scene=login|register
POST /auth/login
POST /auth/register
GET  /auth/oauth/providers
GET  /auth/oauth/{provider}/authorize
GET  /auth/oauth/{provider}/callback
POST /auth/oauth/{provider}/bind
```

登录请求：

```json
{
  "username": "alice",
  "password": "password",
  "captchaId": "optional",
  "captchaCode": "optional",
  "oauthCode": "optional"
}
```

`oauthCode` 是兼容性凭证分支：当它存在时，`AuthService` 委托给 OAuth 兑换逻辑；普通用户名密码路径保持原行为。这样页面仍调用现有 `userStore.login()`，不需要修改 Store 或公共请求层。一次性 code 的消费、Provider、state 和 TTL 校验全部留在认证内部模块。

注册请求：

```json
{
  "username": "alice",
  "email": "alice@example.com",
  "displayName": "Alice",
  "password": "password",
  "captchaId": "optional",
  "captchaCode": "optional"
}
```

成功响应继续为：

```json
{
  "token": "现有 JWT",
  "userInfo": {
    "userId": 1,
    "username": "alice",
    "displayName": "Alice",
    "email": "alice@example.com",
    "role": "USER"
  }
}
```

认证错误码：

```text
AUTH_INVALID_CREDENTIALS
AUTH_CAPTCHA_REQUIRED
AUTH_CAPTCHA_INVALID
AUTH_RATE_LIMITED
AUTH_USERNAME_EXISTS
AUTH_EMAIL_EXISTS
AUTH_OAUTH_NOT_CONFIGURED
AUTH_OAUTH_EMAIL_CONFLICT
AUTH_OAUTH_BIND_EXPIRED
```

### 4.4 OAuth 流程

1. 登录页从 `/auth/oauth/providers` 获取启用列表；
2. 点击 Provider 后跳转 `/auth/oauth/{provider}/authorize`；
3. 后端生成并在 Redis 保存 state，完成 GitHub/Google 授权；
4. 回调只生成一次性 `oauthCode`，重定向到现有 `/login?oauth_code=...`；
5. `Auth.vue` 调用现有 `userStore.login({ oauthCode })`；
6. 邮箱对应本地账号时不自动合并，改为 `/login?oauth_bind_code=...`；
7. 本地登录成功后，页面调用绑定接口完成 `(provider, subject)` 绑定。

JWT 不出现在 OAuth URL、日志或第三方回调参数中。

授权开始时后端同时写入短期 `HttpOnly + SameSite=Lax` state Cookie；回调必须同时匹配 Cookie 与 Redis 中的 state，完成后立即清除 Cookie，用于降低登录 CSRF 风险。

## 5. Redis 与数据模型

### 5.1 Redis Key

```text
labex:auth:rate:login:{ip}:{usernameHash}
labex:auth:rate:register:{ip}
labex:auth:rate:captcha:{ip}
labex:auth:login-fail:{ip}:{usernameHash}
labex:auth:captcha:{captchaId}
labex:auth:oauth-state:{state}
labex:auth:oauth-code:{code}
labex:auth:oauth-bind:{code}
```

验证码和 OAuth 临时码只允许消费一次；所有 Key 都有配置化 TTL；限流键不保存密码、token 或请求正文。

### 5.2 数据库

在 `t_user` 增加可选 `email` 字段和唯一索引（允许多个 NULL）。新增：

```sql
t_user_oauth_binding (
  binding_id,
  user_id,
  provider,
  subject,
  provider_email,
  display_name,
  create_time,
  update_time,
  UNIQUE(provider, subject),
  INDEX(user_id)
)
```

迁移采用现有加性 schema/migrator 模式，不清表、不删除旧字段。

## 6. 安全与兼容性

- 密码继续使用 BCrypt，长度保持当前 6-72 兼容范围；
- 用户名、邮箱和显示名执行长度、字符集和控制字符校验；
- 认证页面只使用 Vue 文本插值，不渲染服务端 HTML；
- 错误消息使用固定用户可见文案，不回传异常栈、SQL、Provider 响应或 Redis 信息；
- 认证响应增加 `Cache-Control: no-store`、`X-Content-Type-Options: nosniff`、`Referrer-Policy`；
- GitHub/Google 密钥只读后端配置，前端只接收启用 Provider 名称；
- `Auth.vue` 的 OAuth query 只接受固定枚举和短码格式，未知参数被忽略；
- 保留现有 Bearer/localStorage 是本期明确的兼容取舍，XSS 成功后的 token 读取风险不会在本期被消除；
- 不修改路由守卫、全局 request 拦截器、工作区渲染和终端 token 传输，以避免现有功能回归。

## 7. 配置

新增 `AuthSecurityProperties`，默认值集中管理：

```text
LABEX_AGENT_AUTH_REDIS_URL
LABEX_AGENT_AUTH_REDIS_TIMEOUT
LABEX_AGENT_AUTH_REDIS_CONNECT_TIMEOUT
LABEX_AGENT_AUTH_LOGIN_RATE_LIMIT
LABEX_AGENT_AUTH_OAUTH_EXCHANGE_RATE_LIMIT
LABEX_AGENT_AUTH_REGISTER_RATE_LIMIT
LABEX_AGENT_AUTH_CAPTCHA_RATE_LIMIT
LABEX_AGENT_AUTH_LOGIN_FAILURE_THRESHOLD
LABEX_AGENT_AUTH_FAILURE_WINDOW_SECONDS
LABEX_AGENT_AUTH_CAPTCHA_TTL_SECONDS
LABEX_AGENT_AUTH_OAUTH_STATE_TTL_SECONDS
LABEX_AGENT_AUTH_OAUTH_CODE_TTL_SECONDS
LABEX_AGENT_AUTH_GITHUB_CLIENT_ID
LABEX_AGENT_AUTH_GITHUB_CLIENT_SECRET
LABEX_AGENT_AUTH_GOOGLE_CLIENT_ID
LABEX_AGENT_AUTH_GOOGLE_CLIENT_SECRET
LABEX_AGENT_AUTH_OAUTH_FRONTEND_CALLBACK
```

生产 profile 在 Redis URL 缺失时 fail-fast；本地 profile 输出明确配置错误。GitHub/Google 凭证为空时 Provider 自动关闭，不影响启动。

## 8. 验证计划

先运行认证定向测试：

```text
mvn -Dtest=AuthControllerTest,AuthServiceTest,AuthCaptchaServiceTest,AuthRateLimitServiceTest test
npm test
```

再运行项目现有门槛：

```text
mvn test
npm test
npm run build
```

必须覆盖：

- 普通登录和注册仍返回当前 token/userInfo；
- 可选邮箱为空、重复和非法格式；
- 登录失败阈值、验证码正确/错误/过期/重复提交；
- Redis 限流、Retry-After 数据和 Redis 不可用错误；
- GitHub/Google 未配置时按钮隐藏；
- OAuth state、code 单次消费和邮箱冲突拒绝；
- OAuth 绑定过期和重复绑定；
- 认证输入中包含 HTML/脚本时只作为文本处理；
- 现有路由、Store、请求层、Agent、终端和工作区测试无回归。

## 9. 变更边界与退出条件

本期兼容代码只有 `oauthCode` 作为 `/auth/login` 的第二种认证凭证。待公共会话层允许迁移到 HttpOnly Cookie 后，单独迭代新增 OAuth exchange endpoint，并删除该兼容分支；删除前需要完成旧客户端观测窗口和回归验证。

除上述认证文件、认证配置、加性 schema 和认证测试外，不得修改其他模块。
