# THIRD_PARTY_NOTICES

本文件记录 LabexAgent 对第三方软件的引用方式与许可义务。依赖包自身的许可由构建清单（`backend/pom.xml`、`frontend/package.json` 及其 lockfile）声明，不在此重复列出；这里只记录「参考实现 / 设计对齐」类引用。

## OpenCode（参考实现，design reference only）

- **项目：** [opencode](https://github.com/sst/opencode)（仓库目录 `D:\opencode\opencode-dev`，`packages/opencode` 版本 1.17.4）
- **许可：** MIT License（Copyright (c) 2025 opencode）
- **引用方式：** **design reference only——仅参考设计不变量，未复制任何 OpenCode 源码。** 因此无需在本仓库内附带 MIT 许可全文；如未来任何提交复制或翻译了 OpenCode 的实质代码，必须在提交中引入 MIT 版权与许可声明，并同步更新本文件。
- **参考文件：** `packages/opencode/src/session/prompt.ts`、`processor.ts`、`compaction.ts`（以及早期对照过的 `tool/shell.ts`、`shell/prompt.ts`）
- **对齐内容（迁移为 Java/Vue 的不变量）：**
  - 每轮 Provider 请求由持久化 Message/Part 投影重建，内存列表只是派生只读缓存；
  - assistant tool_calls 与 role=tool 的 `tool_call_id` 一一对应；
  - compaction 持久化 summary/head/tail/epoch/source boundary 与 token 预算；
  - 完整 Shell payload 契约（`command` / `workdir` / `timeout` / `description`，不做 argv 拆分）；
  - 命令审批由运行时持久化、审批后经专用执行器执行的一次性语义。
- **差异：** 平台与控制面边界保留 LabexAgent 既有实现（JWT、项目归属、沙箱、权限规则），未复刻 OpenCode 的代码结构。

## 图标资产（第三方资产，assets only）

**许可全文：** [`frontend/src/assets/icons/THIRD_PARTY_LICENSES.md`](frontend/src/assets/icons/THIRD_PARTY_LICENSES.md)（含 Feather MIT、Lucide ISC、LobeHub MIT、Heroicons MIT、Material Symbols Apache-2.0 原文与 Simple Icons 声明）。

### 来源清单

| 资产 | 上游来源 | 版本 | 许可 | 位置 |
|---|---|---|---|---|
| 大模型厂商图标 × 33 | `@lobehub/icons-static-svg` | 1.95.0 | MIT | `frontend/src/assets/icons/models/` |
| 技术栈模板图标 × 4 | Simple Icons | — | CC0-1.0 | `frontend/src/assets/icons/stacks/` |
| 「空项目」图标 × 1 | Material Symbols | — | Apache-2.0 | `frontend/src/assets/icons/stacks/empty.svg` |
| 运维通用图标 | Lucide（经 unplugin-icons 按需引入） | `@iconify-json/lucide` 1.2.130 | ISC | `frontend/src/components/ops/ui/OpsIcon.vue` |
| 文件类型图标 | vscode-icons（经 unplugin-icons 按需引入） | `@iconify-json/vscode-icons` 1.2.x | MIT | `frontend/src/components/icons/fileIcons.js` |
| 通用 UI 图标 | Element Plus Icons | 2.3.x | MIT | 经 `@element-plus/icons-vue` 按需引入 |
| 源码内联通用图标图形 | Feather（主）/ Lucide / Heroicons / Material Symbols | 4.29.2 族 / 0.3xx 族 / 1.0.6 / 24px 族 | MIT / ISC / MIT / Apache-2.0 | 43 个 `.vue` 组件内联，逐项归属见下方审计结论 |
| 项目自有品牌标识 | 本项目自绘 | — | 项目自有 | `frontend/src/components/Logo.vue`（非第三方素材） |

### 引入方式与合规边界

- **按需引入，不整包引库。** 所有 npm 图标集合均通过 `unplugin-icons`（`~icons/<集合>/<图标>`）或 `@element-plus/icons-vue` 逐图标引用：每个图标在构建期编译为单个内联 SVG 组件，只有被模板引用到的图标才进入产物。图标数据包（`@iconify-json/*`）仅参与构建，不产生运行时依赖。经实测，本轮图标整改前后生产产物体积逐字节一致。
- **Simple Icons 的 CC0 仅覆盖路径数据本身。** 技术栈名称与商标归其各自所有者（Vue.js 团队、Meta、Spring 项目、Pallets 等），此处仅用于标识对应模板类型，不构成商标授权或背书。Vue 官方美术资源另标注为 CC-BY-NC-SA-4.0，商业再分发需另行确认。
- **第三方登录标识不属于开源图标库素材。** GitHub Octocat 标记、Google「G」四色标记为各自公司官方品牌标识，权利与使用约束见 `frontend/src/components/auth/OAuthProviderIcon.vue` 内注释。其中 Google 品牌规范要求「使用 Google 账号登录」按钮必须使用四色 G，故不使用单色开源替代版本。

### 审计结论与已整改项

2026-09 对全仓库图标做了来源审计，方法为把源码中的矢量路径与上游**原始（SVGO 前）**文件比对，并做归一化（移除空白与逗号等坐标分隔符）以消除 SVGO 把 `a5 5 0 1 1 7.072 0` 压成 `a5 5 0 117.072 0`、把 `<line>`/`<polyline>` 合并进 `<path d>` 等改写差异。

**完整证据（方法、基准、逐文件归属表、未归因项逐项核形）：** [`frontend/src/assets/icons/ICON_PROVENANCE_AUDIT.md`](frontend/src/assets/icons/ICON_PROVENANCE_AUDIT.md)。

**基准库：** Feather v4.29.2、Lucide Static 1.43.0、Heroicons 1.0.6（outline + solid）、Bootstrap Icons 1.11.3、`@lobehub/icons-static-svg` 1.95.0 —— 合计 4,499 个上游图标参与比对。

**扫描范围：** `frontend/src` 下全部 43 个含内联 `<svg>` 的 `.vue` 组件，503 个几何元素，其中 260 个带 `d`/`points` 数据可比对。

**结论：**

- **不存在「编造的品牌标识」或来源不明的通用图标。** 260 个可比对值中 **232 个（89%）**被自动化判据直接归因到开源图标库 —— 其中 **Feather 214、Lucide 14、Heroicons 4**（另以 Bootstrap Icons 1.11.3 作为比对基线，实际零命中）。其余 28 个（唯一值 24）中，**7 个为已登记的官方品牌标识与项目自有 Logo**，**17 个为通用 UI 图形**（叉、时钟指针、加号、勾选、刷新拐角、文件夹、书本、机器人、仪表圆等），经人工核形均为标准几何，其中多项可确认为 Feather / Lucide 的路径改写形态（如刷新拐角 = Feather `refresh-cw` 折线转 `d`、叉 = Lucide `x` 双 path 合并）。典型归因：`file-minus`/`message-square`/`copy`/`edit`/`zap`/`trash-2`/`check`/`chevron-down`/`settings`/`download` = Feather；`dollar-sign`/`loader-2`/`maximize` = Lucide；`light-bulb` = Heroicons v1；填充加号与 Material `add` 同形（列入 17 项通用图形）。
- **Heroicons 归属为本次新增发现**：`components/cloud/composer/ComposerDock.vue`、`components/cloud/SummaryCard.vue` 中的灯泡图标与 `heroicons@1.0.6` 的 `outline/light-bulb.svg` 归一化后完全一致，已补入许可全文第 4 节。
- **非开源库素材仅两类，且均已登记**：① 第三方登录品牌标识（GitHub Octocat、Google 四色 G，官方规范，见 `OAuthProviderIcon.vue` 注释）；② 项目自有 logo（`Logo.vue`，本项目商标，不适用第三方许可）。
- `models/` 注册表 35 条中 33 条经比对**确认出自 LobeHub**；另有 2 条（`aimlapi`、`custom`）既非 LobeHub 也非任何上游，属**编造品牌标识与手绘占位图形**，且在全仓库无任何引用 —— **已删除**，改由 `ModelVendorIcon` 的字母兜底渲染。无官方图标的供应商一律走兜底，不得自行绘制近似标识。
- `ops/ui/OpsIcon.vue` 原有 49 个分支全部无署名；该组件**已整体改为 `~icons/lucide/*` 按需引入**，不再内联任何手抄路径。（该组件当前在全仓库无引用，属保留的通用图标基建。）
- **遗留的代码整洁性课题（非合规问题）：** 43 个组件仍以内联方式复制了库图形，应逐步收敛为 `~icons/<集合>/<图标>` 按需引入以消除复制粘贴，见 AGENTS.md 第 8.3 节。此项已具备零成本迁移条件（`unplugin-icons` 已在构建链中，`~icons/lucide/*` 已可直接使用）。
- 核验方法与结论已固化为回归测试，防止后续再次出现编造或来源不明图标：
  - `frontend/src/components/cloud/models/ModelVendorIconProvenance.test.mjs`（厂商图标白名单 + 不得重引入 `aimlapi`/`custom`）
  - `frontend/src/components/ops/ui/OpsIconProvenance.test.mjs`（不得内联图形 + `~icons/lucide/*` 可解析 + 历史图标名契约）
  - `frontend/src/components/cloud/projects/ProjectStackIcon.test.mjs`（技术栈图标来源）

## 其他说明


- 仓库内所有代码注释、变量与文档均按项目自身规范编写，不包含从第三方源码直接粘贴的段落（除上述明确标注外）。
- 若发现疑似遗漏的第三方代码引用，请在本文件补充来源、版本、许可与引用方式后再提交。
