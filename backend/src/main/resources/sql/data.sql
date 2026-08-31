-- 教程种子数据：Spring Boot 启动时由 spring.sql.init 自动执行（幂等）。
-- 约定：每行正文以隐藏标记 <!-- labex-tutorial-seed:v3 --> 开头。
-- 首次启动插入；旧版无标记内容原地升级一次；带标记的内容（含运维后续编辑）永不覆盖。

-- 1. 快速开始
INSERT INTO t_tutorial_document (slug, title, summary, category, content_markdown, sort_order, status, published_at)
VALUES ('getting-started', '快速开始', '从注册登录到完成第一次开发任务的完整路径。', '开始使用', '<!-- labex-tutorial-seed:v3 -->

# 快速开始

LabexAgent 是一个运行在云端的 AI 开发工作台：你在浏览器里创建项目，Agent 帮你阅读代码、修改文件、执行命令和运行测试。这篇教程带你走完从注册到第一次任务交付的完整流程。

## 1. 注册并登录

1. 打开平台首页，点击「注册」。
2. 支持手机号/邮箱注册，也可以使用 GitHub 或 Google 账号一键登录。
3. 注册后使用账号密码或第三方登录进入项目空间。

> 如果启用了邀请码，注册时需要填写有效邀请码。遇到验证码看不清可以点击图片刷新。

## 2. 创建你的第一个项目

登录后进入「项目空间」，有两种方式建立项目：

| 方式 | 适用场景 | 操作 |
| --- | --- | --- |
| 新建空白项目 | 从零开始写 Demo、做练习 | 点击「新建项目」，填写项目名称 |
| 上传压缩包 | 已有代码想继续开发 | 打包为 `.zip` 后上传，详见「项目上传与打包下载」 |

## 3. 进入工作区

在项目卡片上点击「进入工作区」，你会看到三个区域：

- 左侧：文件树、会话列表等功能面板；
- 中间：代码编辑器与 Agent 对话区；
- 底部：任务输入框，在这里向 Agent 描述你要做的事。

界面每个区域的作用，见「工作区界面导览」。

## 4. 配置模型（首次必做）

Agent 需要一个大语言模型才能工作。第一次使用请先进入「模型配置」：

1. 填写供应商、接口地址、模型名称和 API Key；
2. 点击「保存并测试」，提示连接成功即可；
3. 详细步骤见「配置模型与 API Key」。

## 5. 发起第一个任务

推荐第一次从只读任务开始，先熟悉 Agent 的行为方式：

```text
帮我列出当前项目的目录结构，并解释这个项目的用途和技术栈。
```

确认 Agent 能正常读取文件后，再尝试小范围修改任务：

```text
阅读 src/utils/format.js，给其中的日期格式化函数补充单元测试，只修改测试文件。
```

## 6. 确认改动

Agent 修改文件后会生成差异（diff）。请在「改动」面板中逐个查看：

- 符合预期：点击「应用」写入文件；
- 不符合预期：点击「拒绝」，并在对话里说明原因让 Agent 重做；
- 应用后发现不对：使用「撤销」回滚到之前的状态。

详见「改动审查与应用」。

## 推荐的学习路径

1. 快速开始（本文）；
2. 工作区界面导览 —— 认识每个区域；
3. 配置模型与 API Key —— 让 Agent 能跑起来；
4. 完成第一次 Agent 任务 —— 学会写清楚需求；
5. 权限与安全 —— 了解哪些操作需要你审批。',
 10, 1, NOW())
ON DUPLICATE KEY UPDATE
  title = IF(content_markdown LIKE '%labex-tutorial-seed:v2%', title, VALUES(title)),
  summary = IF(content_markdown LIKE '%labex-tutorial-seed:v2%', summary, VALUES(summary)),
  category = IF(content_markdown LIKE '%labex-tutorial-seed:v2%', category, VALUES(category)),
  sort_order = IF(content_markdown LIKE '%labex-tutorial-seed:v2%', sort_order, VALUES(sort_order)),
  content_markdown = IF(content_markdown LIKE '%labex-tutorial-seed:v2%', content_markdown, VALUES(content_markdown));

-- 3. 配置模型与 API Key（手把手）
INSERT INTO t_tutorial_document (slug, title, summary, category, content_markdown, sort_order, status, published_at)
VALUES ('api-key-and-provider', '配置模型与 API Key', '从打开配置页到测试成功的每一步点击，六个基础字段逐项说明。', '模型配置', '<!-- labex-tutorial-seed:v3 -->

# 配置模型与 API Key（手把手）

目标：15 分钟内配好一个能用的模型。跟着下面的步骤一步步点就行。

## 开始前，准备三样东西

| 要准备 | 去哪里拿 | 示例 |
| --- | --- | --- |
| 服务商账号 | 服务商官网注册 | DeepSeek / 通义 / OpenAI 等 |
| API Key | 控制台「API Keys」页创建 | `sk-xxxxxxxx` |
| 模型 ID | 控制台「模型列表」复制 | `deepseek-chat` |

> 密钥通常只在创建时完整显示一次，务必立刻复制保存。

## 第 1 步：打开模型配置页

1. 登录后进入任意项目的**工作区**；
2. 找到右侧 AI 面板顶部的**模型选择器**（显示当前模型名称的下拉框）；
3. 点开它，进入「模型设置」；
4. 点击「新增模型配置」。

## 第 2 步：逐项填写六个基础字段

### ① 配置名称

- **作用**：给这套配置起名字，方便以后在多套配置之间区分。
- **示例值**：`我的 DeepSeek`
- **建议**：名称带上服务商和用途，例如「DeepSeek-日常开发」。

### ② 提供商

- **作用**：声明接口协议类型。
- **当前固定为** `OpenAI Compatible`：绝大多数国内外服务商都兼容该协议，保持默认即可。

### ③ Base URL（接口地址）

- **作用**：告诉平台把请求发到哪里。只填**根地址**，不要带 `/chat/completions` 路径。
- **示例值**：

| 服务商 | Base URL 示例 |
| --- | --- |
| DeepSeek | `https://api.deepseek.com/v1` |
| 通义百炼 | `https://dashscope.aliyuncs.com/compatible-mode/v1` |
| 月之暗面 | `https://api.moonshot.cn/v1` |
| MiniMax | `https://api.minimaxi.com/v1` |
| OpenAI | `https://api.openai.com/v1` |

### ④ 模型名称

- **作用**：指定实际调用的模型 ID，必须与服务商的标识完全一致。
- **推荐填法（用获取模型按钮）**：
  1. 先填好 Base URL 和 API Key；
  2. 在「官方模型列表」一行点击「获取模型」按钮；
  3. 平台会自动拉取该账号可用的模型列表；
  4. 在下拉框里直接选中，ID 自动填入，不会拼错。
- **手动填法**：从服务商控制台复制完整 ID 粘贴，例如 `deepseek-chat`。

### ⑤ 模型列表 URL

- **作用**：「获取模型」按钮实际请求的地址。
- **建议**：**留空即可**，平台会根据 Base URL 自动推导；编辑已有配置时，获取模型会复用已保存的 API Key。

### ⑥ API Key

- **作用**：调用凭证，决定请求能否通过鉴权。
- **操作**：把创建密钥时复制的完整字符串原样粘贴进输入框（密码框，保存后只显示脱敏结果）。
- **三条铁律**：
  1. 不要加 `Bearer ` 前缀；
  2. 首尾不要有空格或换行；
  3. 编辑旧配置时留空 = 保持原 Key 不变（输入框下方会提示「已设置」）。

## 第 3 步：高级参数先不动

Max Tokens、Temperature、推理程度、上下文窗口、压缩策略等参数，第一次配置**全部保持默认或留空**。跑通之后，再按「模型参数与压缩策略详解」逐项调优。

## 第 4 步：保存并测试

1. 点击底部「创建」（编辑已有配置时是「更新」）；
2. 回到配置列表，对该配置点「测试」；
3. 提示成功即完成；失败按下面报错对照表排查。

## 第 5 步：设为默认模型（可选）

在配置中勾选「设为默认模型」，之后新会话默认使用它。

## 常见报错对照表

| 报错现象 | 最可能原因 | 处理方式 |
| --- | --- | --- |
| 401 / 未授权 | Key 错误、过期或被撤销 | 重新生成密钥并原样粘贴 |
| 404 / model not found | 模型 ID 拼写错误或已下线 | 用「获取模型」下拉选择 |
| 连接超时 | 地址错误或网络不通 | 核对根地址，检查是否多写了路径 |
| 429 / 限流 | 额度用尽或并发超限 | 充值或稍后重试 |

## 安全说明

- API Key 由后端加密存储，页面只显示脱敏结果；
- 不要把真实密钥写进对话、项目文件、截图或公开教程；
- 怀疑泄露时立即到服务商控制台吊销重发。',
 20, 1, NOW())
ON DUPLICATE KEY UPDATE
  title = IF(content_markdown LIKE '%labex-tutorial-seed:v2%', title, VALUES(title)),
  summary = IF(content_markdown LIKE '%labex-tutorial-seed:v2%', summary, VALUES(summary)),
  category = IF(content_markdown LIKE '%labex-tutorial-seed:v2%', category, VALUES(category)),
  sort_order = IF(content_markdown LIKE '%labex-tutorial-seed:v2%', sort_order, VALUES(sort_order)),
  content_markdown = IF(content_markdown LIKE '%labex-tutorial-seed:v2%', content_markdown, VALUES(content_markdown));
-- 4. 接口地址与模型名称
INSERT INTO t_tutorial_document (slug, title, summary, category, content_markdown, sort_order, status, published_at)
VALUES ('provider-fields', '接口地址与模型名称', '接口根地址的书写规则、常见服务商示例与模型 ID 核对方法。', '模型配置', '<!-- labex-tutorial-seed:v3 -->

# 接口地址与模型名称

模型请求失败，十有八九出在接口地址和模型名称这两个字段上。这篇教程把两者讲透。

## 接口地址

填写的是**兼容接口的根地址**，平台会自动在其后拼接标准路径。

### 正确示例

```text
https://api.openai.com/v1
https://api.deepseek.com/v1
https://dashscope.aliyuncs.com/compatible-mode/v1
https://api.minimaxi.com/v1
```

### 错误写法

| 错误写法 | 问题 |
| --- | --- |
| `https://api.openai.com/v1/chat/completions` | 多写了完整路径，会重复拼接 |
| `https://platform.openai.com` | 这是控制台网页地址，不是接口地址 |
| `http://localhost:11434/v1` | 只在你本机有效，云端无法访问（本地 Ollama 场景除外） |
| 地址里带着 `sk-xxx` | 密钥绝不能出现在地址里 |

> 不确定就查服务商文档里的「OpenAI 兼容」或「base_url」说明，通常直接复制即可。

## 模型名称

模型名称必须是服务商**模型列表里的准确 ID**，不能凭宣传名猜测。

### 常见坑

- 大小写不一致：`gpt-4o-mini` 写成 `GPT-4o-Mini`；
- 版本号缺失或多写：`deepseek-chat` 与 `deepseek-v3` 是不同 ID；
- 把展示名当 ID：「通义千问-Max」是展示名，真实 ID 形如 `qwen-max`；
- 使用了已下线的旧别名。

### 核对方法

1. 登录服务商控制台，找到「模型列表」或「模型广场」；
2. 复制模型 ID 一栏的完整字符串；
3. 粘贴到模型名称输入框，不要手敲。

## 推理与输出参数

不同模型支持的推理档位、思考预算和最大输出不同：

- 遇到「参数不支持」类报错，先关闭高级推理参数再试；
- 输出被截断时，检查模型的 max output 上限，不要盲目调大；
- 同一任务反复失败时，换一个稳定模型对比测试，排除模型侧问题。',
 30, 1, NOW())
ON DUPLICATE KEY UPDATE
  title = IF(content_markdown LIKE '%labex-tutorial-seed:v2%', title, VALUES(title)),
  summary = IF(content_markdown LIKE '%labex-tutorial-seed:v2%', summary, VALUES(summary)),
  category = IF(content_markdown LIKE '%labex-tutorial-seed:v2%', category, VALUES(category)),
  sort_order = IF(content_markdown LIKE '%labex-tutorial-seed:v2%', sort_order, VALUES(sort_order)),
  content_markdown = IF(content_markdown LIKE '%labex-tutorial-seed:v2%', content_markdown, VALUES(content_markdown));

-- 模型参数与压缩策略详解（sort_order=35，归属模型配置分类）
INSERT INTO t_tutorial_document (slug, title, summary, category, content_markdown, sort_order, status, published_at)
VALUES ('model-params-reference', '模型参数与压缩策略详解', 'Max Tokens、Temperature、推理程度、上下文窗口与压缩策略每一项的作用与示例值。', '模型配置', '<!-- labex-tutorial-seed:v3 -->

# 模型参数与压缩策略详解

模型配置页里的每个参数都影响 Agent 的表现和花费。这篇逐项讲清楚：它管什么、示例值怎么填、什么场景调什么。

## Max Tokens（单次输出上限）

- **作用**：限制模型单次回复最多生成多少 token，不含输入上下文。
- **示例值**：`32768`（输入框默认提示）
- **怎么调**：

| 场景 | 建议值 |
| --- | --- |
| 日常开发、代码任务 | 16384 ~ 65536 |
| 长文档生成 | 保持 32768 或更高 |
| 模型本身上限较低 | 严格按服务商标注的上限填写 |

- **注意**：必须小于「上下文窗口 Tokens」；报 max_tokens 超限类错误就调小。

## Temperature（随机度）

- **作用**：控制输出随机性，0 最确定，2 最发散，步长 0.1。
- **示例值**：`0.7`（默认）
- **怎么调**：

| 场景 | 建议值 |
| --- | --- |
| 改 Bug、写关键逻辑（求稳） | 0 ~ 0.3 |
| 日常开发 | 0.7 |
| 头脑风暴、起名 | 0.9 ~ 1.2 |

## 推理程度（思考深度）

- **作用**：推理模型的思考档位，越深越聪明但更慢更贵。
- **可选值**：`low` / `medium` / `high`（部分模型还支持 `xhigh`）。
- **示例值**：留空等于 `medium`。
- **怎么调**：
  - 简单问答、小改动 → `low`，出结果快且省 token；
  - 复杂调试、架构设计、疑难 Bug → `high`；
  - 自定义档位名需配合下方「高级请求配置 JSON」中的 reasoning 路径一起设置。

## 多模态能力（支持图片理解）

- **作用**：开关。开启后 Agent 的图片理解工具会使用这套模型配置。
- **建议**：仅给具备视觉能力的模型开启（如 gpt-4o、Gemini、qwen-vl 系列）；纯文本模型开启后会调用失败。

## 上下文窗口 Tokens

- **作用**：模型单次能容纳的总容量（输入+输出），平台据此计算上下文预算和压缩时机。**必须大于 Max Tokens，必须填真实值。**
- **示例值**：

| 窗口规模 | 填写值 |
| --- | --- |
| 32K | `32768` |
| 64K | `65536` |
| 128K | `131072` |

- **填错的后果**：填太大 → 实际请求超限报错；填太小 → 频繁触发压缩，Agent 变得健忘。
- **不确定时**：查服务商文档里 Context Window 一栏。

## 上下文压缩策略

对话接近窗口上限时，平台自动压缩历史腾出空间。

| 配置项 | 作用 | 示例值 | 建议 |
| --- | --- | --- | --- |
| 接近上限时自动压缩 | 总开关 | 开启 | 保持开启 |
| 优先清理旧工具结果 | 先丢弃可重新获取的文件内容与命令输出，保留结论 | 开启 | 保持开启 |
| 最近保留回合 | 最近几轮对话原文保留不压缩 | `20` | 10 ~ 30 |
| 自动压缩触发阈值 (%) | 占用到多少开始压缩 | `80` | 75 ~ 85 |
| 最近上下文 Token 预算 | 最近内容的 token 预算 | 留空=自动 | 一般留空 |
| 压缩安全缓冲 Tokens | 窗口末尾预留的安全空间 | 留空=窗口的 10% | 自动限制在 2048 ~ 8192 |

- **有效自动压缩线**：面板会实时显示最终生效值，取百分比线、输入容量线、安全缓冲线三者的最小值，以它为准。
- **压缩专用模型**：执行摘要压缩时用哪个模型。选便宜快速的小模型即可（如 `deepseek-chat`）；它只会做总结、拿不到任何工具权限，安全又省钱。

## 高级请求配置 JSON

- **作用**：自定义推理参数写入请求体的路径与档位，适配特殊服务商。默认**完全不填**。
- **完整示例**：

```json
{
  "reasoning": {
    "path": "/reasoning_effort",
    "allowedLevels": ["low", "medium", "high", "xhigh"]
  },
  "thinking": {
    "path": "/thinking/enabled",
    "defaultEnabled": false
  },
  "budget": { "enabled": false },
  "requestOverrides": { "body": {}, "headers": {} }
}
```

- **各键含义**：
  - `reasoning.path`：「推理程度」写入请求体的 JSON 路径；
  - `allowedLevels`：允许使用的档位列表；
  - `thinking.path`：思考模式开关的字段路径；
  - `requestOverrides.body / headers`：追加或覆盖请求体字段与请求头。
- **警告**：认证类字段（如 Authorization）与传输保护字段由服务端强制拒绝覆盖。

## 启用 Prompt Cache Key

- **作用**：供应商支持缓存路由时，提升多轮对话的 KV 缓存命中率，响应更快、费用更低。
- **建议**：仅当服务商文档明确支持 `prompt_cache_key` 时开启；其他情况关闭没有任何影响。

## 设为默认模型

- **作用**：新会话默认使用这套配置。
- **建议**：把最稳定、性价比最高的一套设为默认；实验性配置不要设为默认。

## 推荐组合速查

| 场景 | 推理程度 | Temperature | 其他 |
| --- | --- | --- | --- |
| 日常开发（默认） | 留空（medium） | 0.7 | 全部默认 |
| 改 Bug 求稳 | low | 0.2 | 快且稳 |
| 疑难杂症深挖 | high | 0.3 | 更慢更贵但更深 |
| 长会话大项目 | medium | 0.7 | 开启自动压缩并清理旧工具结果',
 35, 1, NOW())
ON DUPLICATE KEY UPDATE
  title = IF(content_markdown LIKE '%labex-tutorial-seed:v2%', title, VALUES(title)),
  summary = IF(content_markdown LIKE '%labex-tutorial-seed:v2%', summary, VALUES(summary)),
  category = IF(content_markdown LIKE '%labex-tutorial-seed:v2%', category, VALUES(category)),
  sort_order = IF(content_markdown LIKE '%labex-tutorial-seed:v2%', sort_order, VALUES(sort_order)),
  content_markdown = IF(content_markdown LIKE '%labex-tutorial-seed:v2%', content_markdown, VALUES(content_markdown));
-- 5. 完成第一次 Agent 任务
INSERT INTO t_tutorial_document (slug, title, summary, category, content_markdown, sort_order, status, published_at)
VALUES ('first-task', '完成第一次 Agent 任务', '用目标、范围、约束、验收四要素写出高质量需求。', 'Agent 使用', '<!-- labex-tutorial-seed:v3 -->

# 完成第一次 Agent 任务

同一个需求，写得模糊和写得清楚，结果差距巨大。这篇教程教你把需求说到 Agent 能准确执行。

## 需求四要素

一条好的任务描述至少包含四部分：

```text
目标：为登录页增加"连续失败 5 次锁定 10 分钟"的提示。
范围：只修改登录注册模块，不改动其他页面。
约束：保留现有接口与视觉设计；补充对应单元测试。
验收：运行后端测试与前端构建全部通过，并列出修改的文件。
```

| 要素 | 回答的问题 | 反例 |
| --- | --- | --- |
| 目标 | 做成什么样算完成？ | 「优化一下登录」 |
| 范围 | 允许动哪些文件？ | 不说范围，Agent 全项目乱翻 |
| 约束 | 不能破坏什么？ | 不提约束，改完接口全挂 |
| 验收 | 怎么证明做对了？ | 「我觉得行就行」 |

## 推荐的三阶段工作流

复杂任务不要一句话甩出去，按阶段推进：

1. **分析阶段**：让 Agent 先读代码、给出实现计划，你确认方案可行；
2. **实现阶段**：批准计划后让它动手，期间关注权限确认弹窗；
3. **验证阶段**：要求运行相关测试和构建，查看 diff 后应用改动。

## 权限确认怎么处理

Agent 执行删除文件、安装依赖、访问外网等敏感操作时会暂停等待你的决定：

- **批准**：先看清命令内容、工作目录和影响范围再点；
- **拒绝**：不确定就拒绝，并在对话里说明顾虑，让 Agent 换方案；
- 审批不会打断任务状态，处理后 Agent 会自动继续。

## 结果验收清单

- [ ] diff 是否只包含需求范围内的文件？
- [ ] 新增代码风格是否与项目一致？
- [ ] 相关测试是否真的跑过并通过？
- [ ] 有没有顺手改动无关内容？

发现问题不要重新描述整个任务，直接指出具体文件和现象让 Agent 修正即可。',
 40, 1, NOW())
ON DUPLICATE KEY UPDATE
  title = IF(content_markdown LIKE '%labex-tutorial-seed:v2%', title, VALUES(title)),
  summary = IF(content_markdown LIKE '%labex-tutorial-seed:v2%', summary, VALUES(summary)),
  category = IF(content_markdown LIKE '%labex-tutorial-seed:v2%', category, VALUES(category)),
  sort_order = IF(content_markdown LIKE '%labex-tutorial-seed:v2%', sort_order, VALUES(sort_order)),
  content_markdown = IF(content_markdown LIKE '%labex-tutorial-seed:v2%', content_markdown, VALUES(content_markdown));

-- 6. 对话与上下文管理
INSERT INTO t_tutorial_document (slug, title, summary, category, content_markdown, sort_order, status, published_at)
VALUES ('conversation-and-context', '对话与上下文管理', '管理会话、理解上下文占用与自动压缩机制。', 'Agent 使用', '<!-- labex-tutorial-seed:v3 -->

# 对话与上下文管理

## 会话是什么

每个会话是一条独立的对话线，拥有自己的消息历史和上下文。左侧「会话列表」可以：

- **新建会话**：开始一个互不干扰的新话题；
- **切换会话**：回到之前的讨论继续追问，历史都在；
- **删除会话**：清理不需要的记录。

> 一个话题一个会话是最佳实践。把「修 Bug」「加功能」「问问题」混在一个会话里，上下文会被无关内容占满。

## 上下文占用

模型单次能「看到」的内容长度有限，称为上下文窗口。面板上的上下文指示器显示当前占用比例：

- **绿色**：占用健康，正常使用；
- **黄色**：偏高，注意精简对话；
- **红色**：接近上限，建议新开会话或主动总结。

## 自动压缩

占用过高时，平台会对较早的对话进行压缩摘要，保留关键结论、释放空间：

- 压缩后 Agent 仍记得任务目标和重要约定；
- 特别久远的细节可能丢失，重要约束请在最近的消息里重申一遍；
- 压缩过程会持久化保存，刷新页面、重启服务都不会丢。

## 长任务建议

1. 启动前先让 Agent 给出分步计划；
2. 每完成一个里程碑，用一句话确认结论再继续；
3. 单个会话专注单一功能，做完即收尾；
4. 需要长期记忆的项目约定，写进项目里的说明文件比反复口头强调更可靠。',
 42, 1, NOW())
ON DUPLICATE KEY UPDATE
  title = IF(content_markdown LIKE '%labex-tutorial-seed:v2%', title, VALUES(title)),
  summary = IF(content_markdown LIKE '%labex-tutorial-seed:v2%', summary, VALUES(summary)),
  category = IF(content_markdown LIKE '%labex-tutorial-seed:v2%', category, VALUES(category)),
  sort_order = IF(content_markdown LIKE '%labex-tutorial-seed:v2%', sort_order, VALUES(sort_order)),
  content_markdown = IF(content_markdown LIKE '%labex-tutorial-seed:v2%', content_markdown, VALUES(content_markdown));
-- 2. 工作区界面导览
INSERT INTO t_tutorial_document (slug, title, summary, category, content_markdown, sort_order, status, published_at)
VALUES ('interface-tour', '工作区界面导览', '认识工作区的每个区域：文件树、编辑器、终端与 AI 面板。', '开始使用', '<!-- labex-tutorial-seed:v3 -->

# 工作区界面导览

进入项目后，工作区分为几个固定区域。了解每个区域的职责，能让你更快找到需要的功能。

## 整体布局

工作区从上到下、从左到右分为四个区域，各区域职责如下：

| 区域 | 主要内容 | 你在这里做什么 |
| --- | --- | --- |
| 顶栏 | 返回、项目名、主题设置、教程入口 | 全局导航与个性化设置 |
| 左侧栏 | 文件树、会话列表 | 浏览项目文件、切换历史对话 |
| 中间主区 | 代码编辑器 / Diff 查看器、终端 / 预览 | 写代码、审查改动、执行命令 |
| 右侧 AI 面板 | 对话消息流、任务输入框 | 与 Agent 协作完成开发任务 |

## 左侧栏

- **文件树**：展示当前项目的全部文件。单击打开文件，右键可以进行新建、重命名、删除等操作。文件较多时用顶部筛选框按名称过滤。
- **会话列表**：每次与 Agent 的对话都是一个会话。点击「新建会话」开始独立话题，历史会话可以随时切换回去继续聊。

## 中间主区

- **代码编辑器**：基于 Monaco（与 VS Code 同内核），支持语法高亮、多标签页。你可以直接手动编辑文件，也可以查看 Agent 提交的 diff。
- **Diff 查看器**：Agent 修改文件后，这里以红绿对比的方式展示改动，是你决定「应用 / 拒绝」的依据。
- **终端**：一个真实的命令行环境，工作目录就是当前项目。可以自己敲命令，也可以让 Agent 代为执行（高风险命令会请求你的确认）。
- **预览**：启动开发服务器后，通过预览入口在浏览器里访问项目页面，不用自己拼端口。

## 右侧 AI 面板

- **消息流**：展示 Agent 的计划、执行过程、工具调用结果和最终答复。修改类操作会产生可展开的 diff 卡片。
- **输入框**：支持多行文本，可以拖拽或粘贴图片作为附件。`Enter` 发送，`Shift+Enter` 换行。
- **状态指示**：任务执行中会显示进行中的工具与耗时；上下文占用接近上限时会有提示。

## 其他入口

- **主题设置**：右上角调色板图标，可以切换明暗模式、强调色、圆角与字体，所有页面统一生效。
- **使用教程**：悬浮按钮，随时打开本教程中心。

## 区域大小调整

面板之间的分隔条可以拖动。觉得文件树太窄、对话区太宽时，直接拖到舒服的位置即可，布局会在本次会话内保持。',
 15, 1, NOW())
ON DUPLICATE KEY UPDATE
  title = IF(content_markdown LIKE '%labex-tutorial-seed:v2%', title, VALUES(title)),
  summary = IF(content_markdown LIKE '%labex-tutorial-seed:v2%', summary, VALUES(summary)),
  category = IF(content_markdown LIKE '%labex-tutorial-seed:v2%', category, VALUES(category)),
  sort_order = IF(content_markdown LIKE '%labex-tutorial-seed:v2%', sort_order, VALUES(sort_order)),
  content_markdown = IF(content_markdown LIKE '%labex-tutorial-seed:v2%', content_markdown, VALUES(content_markdown));

-- 7. Agent 功能说明
INSERT INTO t_tutorial_document (slug, title, summary, category, content_markdown, sort_order, status, published_at)
VALUES ('agent-guide', 'Agent 功能说明', 'Agent 能做什么：读写文件、执行命令、搜索、联网与测试。', 'Agent 使用', '<!-- labex-tutorial-seed:v3 -->

# Agent 功能说明

Agent 不是聊天机器人，而是一个能真正动手的开发助手。它通过一组内置工具完成实际操作。

## 能力总览

| 类别 | 具体能力 | 典型指令示例 |
| --- | --- | --- |
| 文件 | 读取、创建、编辑、精确替换 | 「把 config.js 里的端口改成 8081」 |
| 搜索 | 按文件名、按内容全文检索 | 「找到所有用到 getUserInfo 的地方」 |
| 命令 | 在项目终端里执行任意命令 | 「跑一下 npm run build」 |
| 测试 | 运行测试并分析失败原因 | 「执行单元测试，修复失败的用例」 |
| 联网 | 搜索资料、抓取网页内容 | 「查一下 Vite 5 的迁移要点」 |
| 图片 | 理解截图和设计稿 | （附上截图）「按这张图还原样式」 |
| 扩展 | MCP 外部工具、自定义技能 | 「用数据库工具查询订单表结构」 |

## 工作过程是透明的

Agent 的每一步都会实时显示在消息流里：

- **计划**：复杂任务会先列出步骤；
- **工具调用**：正在读哪个文件、执行什么命令，一目了然；
- **思考与结论**：最终答复会汇总改动和验证结果。

## 权限与审批

危险操作不会静默执行。删除文件、安装依赖、访问工作区之外等动作会弹出确认卡片，由你决定放行或拒绝。详见「权限与安全」。

## 随时可以打断

任务执行过程中可以点击停止按钮中断当前操作。已产生的文件修改不会丢失，你可以在改动面板中审查处理。

## 让它做得更好的技巧

- 给上下文：「这是 Spring Boot 项目，用 Maven 构建」；
- 给边界：「只改 utils 目录，别动配置文件」；
- 给验收：「改完跑 mvn test 确认全部通过」；
- 不满意就直接说哪里不对，迭代比重来快。',
 45, 1, NOW())
ON DUPLICATE KEY UPDATE
  title = IF(content_markdown LIKE '%labex-tutorial-seed:v2%', title, VALUES(title)),
  summary = IF(content_markdown LIKE '%labex-tutorial-seed:v2%', summary, VALUES(summary)),
  category = IF(content_markdown LIKE '%labex-tutorial-seed:v2%', category, VALUES(category)),
  sort_order = IF(content_markdown LIKE '%labex-tutorial-seed:v2%', sort_order, VALUES(sort_order)),
  content_markdown = IF(content_markdown LIKE '%labex-tutorial-seed:v2%', content_markdown, VALUES(content_markdown));

-- 8. 改动审查与应用
INSERT INTO t_tutorial_document (slug, title, summary, category, content_markdown, sort_order, status, published_at)
VALUES ('changes-review', '改动审查与应用', '查看 diff、应用或拒绝改动、随时撤销回滚。', 'Agent 使用', '<!-- labex-tutorial-seed:v3 -->

# 改动审查与应用

Agent 对文件的每次修改都不会直接覆盖你的项目，而是先以「待应用改动」的形式保存，由你审查后决定去留。

## 改动是怎么产生的

当 Agent 调用写入类工具（新建文件、编辑文件、打补丁）时，平台会：

1. 记录文件的原始内容作为基线；
2. 生成新内容并计算差异（diff）；
3. 把 diff 展示给你，等待审查。

## 怎么看 diff

打开「改动」面板，每个文件一张卡片：

- <span style="color:#e34d59">红色行</span>：将被删除的内容；
- 绿色行：将要写入的内容；
- 卡片头部显示文件路径与增删行数统计。

点击文件可以在 diff 视图和完整文件内容之间切换。

## 三种处置方式

| 操作 | 效果 | 适用场景 |
| --- | --- | --- |
| 应用 | 把改动写入真实文件 | 审查无误 |
| 拒绝 | 丢弃这处改动，文件保持原样 | 方向不对，让 Agent 重做 |
| 撤销 | 回滚**已经应用**的改动 | 应用后反悔了 |

> 撤销按改动集回滚，多次应用的改动会按顺序逆向恢复，不用担心越撤越乱。

## 审查清单

1. 文件范围对不对——有没有改到无关文件？
2. 删除的代码是不是真的该删？
3. 有没有硬编码的临时值、调试输出残留？
4. 关键逻辑变更是否配套了测试？

## 与对话联动

拒绝改动时最好在对话里说明原因，例如「这个函数不要删，改成兼容旧参数」，Agent 会带着这个反馈修正方案，而不是盲目重试。',
 47, 1, NOW())
ON DUPLICATE KEY UPDATE
  title = IF(content_markdown LIKE '%labex-tutorial-seed:v2%', title, VALUES(title)),
  summary = IF(content_markdown LIKE '%labex-tutorial-seed:v2%', summary, VALUES(summary)),
  category = IF(content_markdown LIKE '%labex-tutorial-seed:v2%', category, VALUES(category)),
  sort_order = IF(content_markdown LIKE '%labex-tutorial-seed:v2%', sort_order, VALUES(sort_order)),
  content_markdown = IF(content_markdown LIKE '%labex-tutorial-seed:v2%', content_markdown, VALUES(content_markdown));

-- 9. 项目、文件与编辑器
INSERT INTO t_tutorial_document (slug, title, summary, category, content_markdown, sort_order, status, published_at)
VALUES ('workspace-and-files', '项目、文件与编辑器', '文件树操作、编辑器使用与大文件注意事项。', '基础操作', '<!-- labex-tutorial-seed:v3 -->

# 项目、文件与编辑器

## 文件树

左侧文件树展示当前项目的完整目录：

- **单击**文件在编辑器中打开；
- **右键**菜单提供新建文件/文件夹、重命名、删除、下载等操作；
- 顶部筛选框支持按名称快速过滤；
- 你手动做的修改和 Agent 做的修改都在同一个工作区里，互相可见。

## 编辑器

编辑器基于 Monaco 内核（与 VS Code 同源）：

- 支持主流语言语法高亮与基础补全；
- 多标签页切换，`Ctrl+S` 保存；
- 手动编辑的内容即刻生效，Agent 下次读取到的就是最新版本。

> 你和 Agent 可以接力工作：先手动搭好骨架，再让 Agent 补细节；或者让它先出初版，你再微调。

## 大文件与二进制文件

| 类型 | 建议 |
| --- | --- |
| 超大日志、数据集 | 不要整份发给 Agent，让它用命令按需截取片段 |
| 图片、字体、压缩包 | 作为附件或放在项目目录引用即可，无需打开阅读 |
| node_modules 等生成物 | 保持在工作区但不要让 Agent 逐个阅读 |

## 文件安全

- 密钥文件、私钥、数据库备份不要上传到不需要它们的项目；
- 删除操作进入回收逻辑前会有确认，重要项目建议先打包下载一份备份；
- 文件打不开时先刷新页面确认路径与权限，不要急着清空工作区。',
 50, 1, NOW())
ON DUPLICATE KEY UPDATE
  title = IF(content_markdown LIKE '%labex-tutorial-seed:v2%', title, VALUES(title)),
  summary = IF(content_markdown LIKE '%labex-tutorial-seed:v2%', summary, VALUES(summary)),
  category = IF(content_markdown LIKE '%labex-tutorial-seed:v2%', category, VALUES(category)),
  sort_order = IF(content_markdown LIKE '%labex-tutorial-seed:v2%', sort_order, VALUES(sort_order)),
  content_markdown = IF(content_markdown LIKE '%labex-tutorial-seed:v2%', content_markdown, VALUES(content_markdown));

-- 10. 项目上传与打包下载
INSERT INTO t_tutorial_document (slug, title, summary, category, content_markdown, sort_order, status, published_at)
VALUES ('project-upload', '项目上传与打包下载', '上传 zip 压缩包创建项目，或把整个项目下载到本地。', '基础操作', '<!-- labex-tutorial-seed:v3 -->

# 项目上传与打包下载

## 上传已有项目

1. 在项目空间点击「上传项目」；
2. 选择打包好的 `.zip` 压缩包（单包不超过 100MB）；
3. 系统自动解压为工作区根目录，上传完成后即可进入工作区。

### 打包建议

- 在项目根目录打包，保证解压后直接就是源码目录；
- 打包前排除 `node_modules`、`target`、`dist`、`.git` 等生成物，体积小、上传快；
- Windows 推荐右键「压缩为 ZIP」或使用 `Compress-Archive`，macOS/Linux 用 `zip -r project.zip project/ -x "node_modules/*"`。

## 下载项目

在项目列表或工作区菜单中选择「下载」，平台会把当前工作区打包为 zip 返回浏览器。

- 下载内容是**当前实时状态**：包括你手动编辑和 Agent 应用的所有改动；
- 定期下载一份备份是好习惯，尤其是让 Agent 做大重构之前。

## 常见问题

| 问题 | 原因与处理 |
| --- | --- |
| 上传后目录多了一层 | 打包时选中的是上层文件夹，重新从项目根目录打包 |
| 上传失败提示超时 | 压缩包过大或网络不稳，排除依赖目录后重试 |
| 解压后中文文件名乱码 | 使用系统自带压缩工具重新打包，避免老式编码工具',
 55, 1, NOW())
ON DUPLICATE KEY UPDATE
  title = IF(content_markdown LIKE '%labex-tutorial-seed:v2%', title, VALUES(title)),
  summary = IF(content_markdown LIKE '%labex-tutorial-seed:v2%', summary, VALUES(summary)),
  category = IF(content_markdown LIKE '%labex-tutorial-seed:v2%', category, VALUES(category)),
  sort_order = IF(content_markdown LIKE '%labex-tutorial-seed:v2%', sort_order, VALUES(sort_order)),
  content_markdown = IF(content_markdown LIKE '%labex-tutorial-seed:v2%', content_markdown, VALUES(content_markdown));

-- 11. 终端与预览
INSERT INTO t_tutorial_document (slug, title, summary, category, content_markdown, sort_order, status, published_at)
VALUES ('terminal-preview', '终端与预览', '运行开发命令、确认环境版本并通过预览访问项目服务。', '基础操作', '<!-- labex-tutorial-seed:v3 -->

# 终端与预览

## 终端

工作区底部是项目内终端，工作目录就是当前项目根目录。你可以自己敲命令，也可以让 Agent 代为执行。

### 先确认环境

拿到陌生项目先跑一遍环境检查：

```bash
node --version        # 前端项目
python --version      # Python 项目
java -version         # Java 项目
mvn -version          # Maven 构建
```

### 常用命令速查

| 场景 | 命令 |
| --- | --- |
| 安装依赖 | `npm install` / `pip install -r requirements.txt` |
| 启动开发服务器 | `npm run dev` / `python manage.py runserver` |
| 运行测试 | `npm test` / `mvn test` |
| 查看目录 | `ls`（Windows 用 `dir`） |

> 高风险命令（删除、全局安装、修改系统配置）会被权限策略拦截，需要你确认后才会执行。

## 预览

启动开发服务器后，终端里显示的 `localhost:xxxx` 是运行环境内部的地址，直接点可能打不开。正确做法：

1. 让开发服务器保持运行；
2. 使用工作区提供的**预览入口**打开页面；
3. 预览会自动转发到服务的实际端口。

### 端口说明

- 服务监听端口以框架输出为准（Vite 默认 5173、Vue CLI 默认 8080、Spring Boot 默认 8080）；
- 端口被占用时换一个端口重启即可；
- 不要把数据库、Redis 等内部服务端口暴露到公网。

## 终端排障

- 命令卡住不动：按 `Ctrl+C` 终止后检查命令是否缺少参数；
- 提示命令不存在：依赖没装或不在 PATH 里，先安装依赖；
- 权限报错：确认当前目录正确，必要时联系管理员检查环境配置。',
 60, 1, NOW())
ON DUPLICATE KEY UPDATE
  title = IF(content_markdown LIKE '%labex-tutorial-seed:v2%', title, VALUES(title)),
  summary = IF(content_markdown LIKE '%labex-tutorial-seed:v2%', summary, VALUES(summary)),
  category = IF(content_markdown LIKE '%labex-tutorial-seed:v2%', category, VALUES(category)),
  sort_order = IF(content_markdown LIKE '%labex-tutorial-seed:v2%', sort_order, VALUES(sort_order)),
  content_markdown = IF(content_markdown LIKE '%labex-tutorial-seed:v2%', content_markdown, VALUES(content_markdown));

-- 12. Token 用量与统计
INSERT INTO t_tutorial_document (slug, title, summary, category, content_markdown, sort_order, status, published_at)
VALUES ('token-usage', 'Token 用量与统计', '看懂 token 计费构成，掌握控制消耗的实用技巧。', '基础操作', '<!-- labex-tutorial-seed:v3 -->

# Token 用量与统计

Agent 的每次模型调用都会消耗 token。了解计费构成，才能把额度花在刀刃上。

## 什么是 token

token 是模型处理文本的基本单位。中文大约一个字对应一到两个 token，一段 500 字的说明约消耗 700~1000 token。

## 消耗来自哪里

| 构成 | 说明 | 占比特点 |
| --- | --- | --- |
| 输入 | 系统提示 + 对话历史 + 工具返回的文件内容 | 大头，随对话变长持续增长 |
| 输出 | Agent 的回复和生成的代码 | 与任务复杂度相关 |
| 工具结果 | 读到的文件、命令输出都会计入输入 | 读大文件时明显上涨 |

## 在哪里查看用量

每次任务结束后，消息流和统计面板会展示本次消耗；按会话、按天的汇总可以在用量面板查看，包含请求数与 token 明细。

## 省 token 的七个技巧

1. **一个话题一个会话**，做完就新开，别让历史无限膨胀；
2. **指定文件路径**再让 Agent 读，「看一下 src/api/user.js」远好于「看看这个项目」；
3. **大文件只读片段**，让 Agent 用搜索定位而不是通读全文；
4. **简单问题用轻量对话**，不必每个疑问都启动完整任务；
5. **复用结论**：Agent 总结过的架构信息，后续直接引用，避免重复分析；
6. **及时打断跑偏的任务**，发现方向不对立刻停止比等它跑完省得多；
7. **上下文接近上限时主动新开会话**，并把关键结论粘贴过去。',
 62, 1, NOW())
ON DUPLICATE KEY UPDATE
  title = IF(content_markdown LIKE '%labex-tutorial-seed:v2%', title, VALUES(title)),
  summary = IF(content_markdown LIKE '%labex-tutorial-seed:v2%', summary, VALUES(summary)),
  category = IF(content_markdown LIKE '%labex-tutorial-seed:v2%', category, VALUES(category)),
  sort_order = IF(content_markdown LIKE '%labex-tutorial-seed:v2%', sort_order, VALUES(sort_order)),
  content_markdown = IF(content_markdown LIKE '%labex-tutorial-seed:v2%', content_markdown, VALUES(content_markdown));

-- 13. 接入 MCP 服务
INSERT INTO t_tutorial_document (slug, title, summary, category, content_markdown, sort_order, status, published_at)
VALUES ('mcp-servers', '接入 MCP 服务', '通过 MCP 协议给 Agent 扩展数据库、浏览器等外部工具。', '进阶配置', '<!-- labex-tutorial-seed:v3 -->

# 接入 MCP 服务

MCP（Model Context Protocol）是一个开放协议，让 Agent 能够调用外部工具服务——查数据库、操作浏览器、访问内部 API 等。

## 添加一个 MCP 服务

1. 打开「模型设置」中的 MCP 配置区；
2. 点击新增，填写：
   - **名称**：自定义标识，例如「订单数据库」；
   - **连接方式**：标准输入输出（本地进程）或 URL（远程服务）；
   - **启动命令或地址**：来自 MCP 服务的部署说明；
3. 保存后平台会尝试连接并列出该服务提供的工具。

## 工具如何被使用

连接成功后，MCP 工具会自动注册进 Agent 的工具列表。之后在对话中正常描述需求即可：

```text
用订单数据库工具查询最近 10 条订单的字段结构。
```

Agent 会自行判断何时调用哪个工具，调用过程同样显示在消息流中。

## 状态与排障

| 现象 | 可能原因 | 处理 |
| --- | --- | --- |
| 连接失败 | 命令不存在或地址不可达 | 本地服务确认已安装可执行文件；远程服务确认网络可达 |
| 工具列表为空 | 服务启动了但未注册工具 | 检查 MCP 服务的配置文件与日志 |
| 调用超时 | 外部服务响应慢 | 增加超时时间或优化该服务本身 |

## 安全提醒

- 只接入可信来源的 MCP 服务，工具会在你的工作区环境里执行；
- 涉及写操作的 MCP 工具同样受权限审批约束；
- 不再使用的服务建议及时停用。',
 65, 1, NOW())
ON DUPLICATE KEY UPDATE
  title = IF(content_markdown LIKE '%labex-tutorial-seed:v2%', title, VALUES(title)),
  summary = IF(content_markdown LIKE '%labex-tutorial-seed:v2%', summary, VALUES(summary)),
  category = IF(content_markdown LIKE '%labex-tutorial-seed:v2%', category, VALUES(category)),
  sort_order = IF(content_markdown LIKE '%labex-tutorial-seed:v2%', sort_order, VALUES(sort_order)),
  content_markdown = IF(content_markdown LIKE '%labex-tutorial-seed:v2%', content_markdown, VALUES(content_markdown));

-- 14. 使用技能（Skills）
INSERT INTO t_tutorial_document (slug, title, summary, category, content_markdown, sort_order, status, published_at)
VALUES ('skills', '使用技能（Skills）', '用技能封装团队经验，让 Agent 按标准流程干活。', '进阶配置', '<!-- labex-tutorial-seed:v3 -->

# 使用技能（Skills）

技能是一份结构化的说明书，教 Agent 按 固定套路 完成某类任务。比如「代码评审技能」「发布检查技能」。

## 技能长什么样

一个技能就是一个文件夹，核心是 `SKILL.md` 文件：

```text
my-skill/
  SKILL.md          # 说明文档（必需）
  scripts/          # 配套脚本（可选）
```

`SKILL.md` 开头用 YAML 描述名称和触发场景，正文写执行步骤：

```markdown
---
name: code-review
description: 当用户要求代码评审时使用
---

# 代码评审流程

1. 先用 git diff 列出改动文件
2. 按安全、性能、可读性三个维度逐项检查
3. 输出结构化评审报告
```

## 怎么触发

- 把技能文件夹放进项目的约定目录，平台会自动发现；
- 对话中直接说「帮我做一次代码评审」，Agent 匹配到技能描述就会加载并遵循其中的流程；
- 也可以显式要求：「使用 code-review 技能处理这次改动」。

## 写好技能的建议

| 要点 | 说明 |
| --- | --- |
| description 写清触发时机 | Agent 靠它判断什么时候用这个技能 |
| 步骤具体可执行 | 「检查安全性」不如「检查 SQL 是否拼接用户输入」 |
| 善用配套脚本 | 固定命令写成脚本，减少 Agent 自由发挥空间 |
| 保持单一职责 | 一个技能解决一类问题，不要大而全 |

## 与普通提示的区别

提示词是一次性的，技能是可复用、可分享、可持续改进的团队资产。重复执行超过三次的流程，就值得沉淀成技能。',
 66, 1, NOW())
ON DUPLICATE KEY UPDATE
  title = IF(content_markdown LIKE '%labex-tutorial-seed:v2%', title, VALUES(title)),
  summary = IF(content_markdown LIKE '%labex-tutorial-seed:v2%', summary, VALUES(summary)),
  category = IF(content_markdown LIKE '%labex-tutorial-seed:v2%', category, VALUES(category)),
  sort_order = IF(content_markdown LIKE '%labex-tutorial-seed:v2%', sort_order, VALUES(sort_order)),
  content_markdown = IF(content_markdown LIKE '%labex-tutorial-seed:v2%', content_markdown, VALUES(content_markdown));

-- 15. 联网搜索与图片理解
INSERT INTO t_tutorial_document (slug, title, summary, category, content_markdown, sort_order, status, published_at)
VALUES ('web-and-image', '联网搜索与图片理解', '让 Agent 搜索资料、抓取网页、看懂截图和设计稿。', '进阶配置', '<!-- labex-tutorial-seed:v3 -->

# 联网搜索与图片理解

## 联网搜索

Agent 可以实时搜索公开网页来回答时效性问题：

```text
搜一下 Vite 6 的主要变化，评估我们项目升级的风险。
```

适合场景：查最新版本说明、找报错解决方案、对比技术方案。

注意：搜索结果会占用上下文，问题越具体，检索越高效。

## 网页内容抓取

给出具体链接可以让 Agent 直接读取页面正文：

```text
读取 https://example.com/docs/migration 的内容，总结迁移步骤。
```

- 只能访问 http/https 公开页面，需要登录的内网系统无法抓取；
- 页面过长时 Agent 会截取关键部分，可指定「重点看配置章节」。

## 图片理解

在输入框直接粘贴或拖拽图片（支持 jpg/png/gif/webp），配合文字提问：

```text
（附上设计稿截图）按这张图还原登录页样式，使用项目里已有的组件。
```

### 常见用法

| 场景 | 提问方式 |
| --- | --- |
| 还原设计稿 | 附图 + 「按图实现，保持现有技术栈」 |
| 报错截图 | 附图 + 「这个报错是什么原因」 |
| UI 走查 | 附图 + 「对比这张图找出样式差异」 |

## 使用限制

- 单条消息最多附带 4 张图片，单张不超过 20MB；
- 图片理解依赖平台配置的视觉模型服务，不可用时会有明确提示；
- 涉及隐私的截图（含密钥、个人信息）请先打码再上传。',
 67, 1, NOW())
ON DUPLICATE KEY UPDATE
  title = IF(content_markdown LIKE '%labex-tutorial-seed:v2%', title, VALUES(title)),
  summary = IF(content_markdown LIKE '%labex-tutorial-seed:v2%', summary, VALUES(summary)),
  category = IF(content_markdown LIKE '%labex-tutorial-seed:v2%', category, VALUES(category)),
  sort_order = IF(content_markdown LIKE '%labex-tutorial-seed:v2%', sort_order, VALUES(sort_order)),
  content_markdown = IF(content_markdown LIKE '%labex-tutorial-seed:v2%', content_markdown, VALUES(content_markdown));

-- 16. 权限与安全
INSERT INTO t_tutorial_document (slug, title, summary, category, content_markdown, sort_order, status, published_at)
VALUES ('safety-basics', '权限与安全', '理解审批机制、工作区边界与危险操作的识别方法。', '安全注意事项', '<!-- labex-tutorial-seed:v3 -->

# 权限与安全

## 两道防线

Agent 的每个敏感操作都要过两道关：

1. **权限规则**：平台预置的策略自动放行低风险操作（读文件、跑测试），拦截高风险类别；
2. **人工审批**：被拦截的操作会弹出确认卡片，等你决定。

## 哪些操作需要审批

| 操作类型 | 示例 | 风险点 |
| --- | --- | --- |
| 删除文件 | `rm`、清空目录 | 不可逆丢失 |
| 安装依赖 | `npm install`、`pip install` | 引入不可信代码 |
| 网络访问 | `curl`、下载脚本 | 数据外发风险 |
| 工作区外访问 | 读写上级目录、系统路径 | 越界破坏 |
| 服务变更 | 启动常驻服务、改系统配置 | 影响整体环境 |

## 审批时看什么

拿到确认卡片，花十秒核对三件事：

1. **命令内容**：是否与你交代的任务相关？
2. **作用范围**：工作目录、目标路径是否越界？
3. **连锁影响**：这条命令会不会连带删除或覆盖别的东西？

看不懂的命令不要盲目批准——先复制到对话里问 Agent「这条命令会做什么」。

## 工作区边界

Agent 的活动范围被限制在当前项目目录内。要求它访问工作区之外的路径会被默认拒绝，这是刻意的保护设计，不要想办法绕过。

## 密钥与敏感信息

- API Key、数据库密码交给平台的加密存储，不要写进代码或对话；
- 发现对话中出现了真实密钥，立即吊销并新开会话；
- 上传项目前清理 `.env`、私钥证书等敏感文件。

## 出事怎么办

1. 点击停止按钮中断任务；
2. 打开改动面板，拒绝或撤销可疑改动；
3. 在对话里描述异常现象，让 Agent 协助排查；
4. 必要时下载当前项目备份，联系管理员检查环境。',
 70, 1, NOW())
ON DUPLICATE KEY UPDATE
  title = IF(content_markdown LIKE '%labex-tutorial-seed:v2%', title, VALUES(title)),
  summary = IF(content_markdown LIKE '%labex-tutorial-seed:v2%', summary, VALUES(summary)),
  category = IF(content_markdown LIKE '%labex-tutorial-seed:v2%', category, VALUES(category)),
  sort_order = IF(content_markdown LIKE '%labex-tutorial-seed:v2%', sort_order, VALUES(sort_order)),
  content_markdown = IF(content_markdown LIKE '%labex-tutorial-seed:v2%', content_markdown, VALUES(content_markdown));

-- 17. 常见问题
INSERT INTO t_tutorial_document (slug, title, summary, category, content_markdown, sort_order, status, published_at)
VALUES ('common-issues', '常见问题', '按症状排查：模型报错、页面异常、终端失败与恢复方法。', '故障排查', '<!-- labex-tutorial-seed:v3 -->

# 常见问题

## 模型请求失败

**现象：对话报错、无响应或提示未授权。**

| 报错 | 原因 | 解决 |
| --- | --- | --- |
| 401 未授权 | Key 错误/过期 | 重贴完整密钥 |
| 404 模型不存在 | 模型 ID 写错 | 从控制台复制准确 ID |
| 超时 | 接口地址错误或网络不通 | 核对根地址；检查服务商状态页 |
| 429 限流 | 额度用尽 | 充值或稍后重试 |

排查顺序：先点「测试连接」，失败再看上表；测试通过但对话仍失败，把报错原文发给 Agent 分析。

## 对话中途断开 / 页面刷新

**现象：浏览器刷新、断网后任务好像没了。**

不用担心：任务状态和消息都持久化在服务端。重新进入工作区后：

1. 会话历史完整保留；
2. 执行中的任务会从断点继续推进；
3. 已产生的改动仍在「改动」面板等待处理。

## 文件加载慢或打不开

1. 刷新页面重试；
2. 确认文件还存在（可能已被重命名）；
3. 大文件首次加载较慢属正常，避免反复开关；
4. 持续打不开时查看浏览器控制台网络请求是否有红色失败项，连同现象反馈给管理员。

## 终端命令失败

1. 先跑 `node --version` 等版本命令确认环境存在；
2. 「command not found」= 依赖没装，先安装；
3. 权限类报错确认当前目录是否正确；
4. 环境缺失联系管理员，不要让 Agent 擅自改装系统组件。

## 改动应用后出问题

1. 立即使用「撤销」回滚该批改动；
2. 在对话里指出具体问题：「撤销了刚才的改动，xxx 函数不应该删，重新实现」；
3. 复杂情况让 Agent 先分析 diff 再动手修。

## 预览页面打不开

1. 确认开发服务器还在运行（看终端）；
2. 用工作区的预览入口而不是手敲 localhost；
3. 检查服务实际监听端口是否与预览一致；
4. 服务崩溃就重启：`npm run dev`。',
 80, 1, NOW())
ON DUPLICATE KEY UPDATE
  title = IF(content_markdown LIKE '%labex-tutorial-seed:v2%', title, VALUES(title)),
  summary = IF(content_markdown LIKE '%labex-tutorial-seed:v2%', summary, VALUES(summary)),
  category = IF(content_markdown LIKE '%labex-tutorial-seed:v2%', category, VALUES(category)),
  sort_order = IF(content_markdown LIKE '%labex-tutorial-seed:v2%', sort_order, VALUES(sort_order)),
  content_markdown = IF(content_markdown LIKE '%labex-tutorial-seed:v2%', content_markdown, VALUES(content_markdown));

-- 停用历史遗留 slug 的旧教程（幂等）
UPDATE t_tutorial_document
SET status = 0, update_time = CURRENT_TIMESTAMP
WHERE (slug = 'deployment-safety' AND title = '部署与公网访问')
   OR (slug = 'project-workspace' AND title = '项目与工作区')
   OR (slug = 'agent-chat' AND title = '如何使用 Agent')
   OR (slug = 'terminal-and-preview' AND title = '终端与开发服务器')
   OR (slug = 'model-configuration' AND title = '模型配置')
   OR (slug = 'permissions-and-safety' AND title = '权限与安全')
   OR (slug = 'troubleshooting' AND title = '常见问题');
