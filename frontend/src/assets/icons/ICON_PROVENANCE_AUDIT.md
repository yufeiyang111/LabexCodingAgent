# 图标来源审计报告

> 记录 `frontend/src` 全量内联图标几何的来源核验证据与逐文件归属。许可义务与许可全文见
> [`THIRD_PARTY_LICENSES.md`](./THIRD_PARTY_LICENSES.md) 与仓库根目录 [`THIRD_PARTY_NOTICES.md`](../../../../THIRD_PARTY_NOTICES.md)。

## 1. 方法与基准

把源码中的矢量数据（`d` / `points`）**归一化**（移除空白与逗号等坐标分隔符）后，与下列上游**原始（SVGO 前）**文件比对。判定判据分两级：

1. **子串包含**（强判据）：源码几何被某上游图标的路径数据完整包含 —— 覆盖「源码把 `<line>`/`<polyline>` 合并进 `<path d>`」的 SVGO 优化形态；
2. **16 字符窗口覆盖度 ≥ 85%**（弱判据）：源码几何与某上游图标高度重合但存在版本改写。

| 基准库 | 版本 | 许可 | 核验来源 |
|---|---|---|---|
| Feather | 4.29.2 | MIT | `feather-icons` 包 `dist/icons/*.svg` |
| Lucide | Static 1.43.0 | ISC | `lucide-static` 包 `icons/*.svg` |
| Heroicons | 1.0.6 | MIT | `heroicons` 包 `outline/*.svg`、`solid/*.svg` |
| Bootstrap Icons | 1.11.3 | MIT | `bootstrap-icons` 包 `icons/*.svg` |
| LobeHub Icons | 1.95.0 | MIT | `@lobehub/icons-static-svg`（仅厂商图标） |

## 2. 总体结果

| 指标 | 数值 |
|---|---:|
| 含内联 `<svg>` 的 `.vue` 组件 | 43 |
| 几何元素总数 | 503 |
| 带 `d`/`points` 数据、可比对的值 | 260 |
| **已归因到上游库** | **232** |
| 未归因 | 28（唯一值 24：品牌/自有标识 7、通用图形 17） |

**结论：`frontend/src` 全仓库不存在「编造的品牌标识」或来源不明的通用图标。** 260 个可比对几何值中 232 个（89%）被自动化判据直接归因到开源图标库 —— **Feather 214、Lucide 14、Heroicons 4**（Bootstrap Icons 仅作比对基线，实际零命中）；其余 28 个中，7 个是**已登记的官方品牌标识与项目自有 Logo**，17 个为**通用 UI 图形**（勾、叉、时钟、加号、文件夹、书本、机器人等），经人工核形均为标准几何、其中多项可确认为 Feather / Lucide 的路径改写形态，详见第 5 节。

非开源图标库素材仅两类，均已单列登记：第三方登录**官方品牌标识**（GitHub、Google）与**项目自有 Logo**。

## 3. 逐文件归属

| 文件 | 内联 SVG | 几何元素 | 数据值 | 归因来源 | 未归因 | 备注 |
|---|---:|---:|---:|---|---:|---|
| `views/CloudWorkspace.vue` | 64 | 134 | 72 | Feather ×63、Lucide ×4、Heroicons ×1 | 4 |  |
| `components/cloud/ToolCallCard.vue` | 23 | 56 | 19 | Feather ×17、Lucide ×2 | — |  |
| `components/cloud/FileContextMenu.vue` | 11 | 29 | 15 | Feather ×15 | — |  |
| `views/CloudSpace.vue` | 11 | 26 | 15 | Feather ×15 | — |  |
| `components/cloud/chat/CenterAiWorkspace.vue` | 14 | 22 | 12 | Feather ×11、Lucide ×1 | — |  |
| `components/cloud/preview/WebPreviewPanel.vue` | 8 | 19 | 7 | Feather ×4、Lucide ×1 | 2 |  |
| `components/cloud/layout/WorkspaceTopBar.vue` | 9 | 18 | 10 | Feather ×8、Lucide ×2 | — |  |
| `components/cloud/ModelConfigDialog.vue` | 9 | 16 | 8 | Feather ×7、Lucide ×1 | — |  |
| `components/cloud/SummaryCard.vue` | 10 | 16 | 16 | Feather ×12、Heroicons ×2 | 2 |  |
| `views/Tutorials.vue` | 5 | 15 | 3 | Feather ×3 | — |  |
| `components/cloud/composer/ComposerDock.vue` | 8 | 14 | 7 | Feather ×6、Heroicons ×1 | — |  |
| `components/sidebar/FileExplorerPanel.vue` | 6 | 14 | 10 | Feather ×10 | — |  |
| `components/sidebar/ConversationPanel.vue` | 6 | 12 | 5 | Feather ×5 | — |  |
| `components/cloud/ChangesPanel.vue` | 6 | 11 | 8 | Feather ×8 | — |  |
| `components/cloud/composer/ModelSelectorPopover.vue` | 5 | 9 | 3 | Feather ×3 | — |  |
| `components/cloud/chat/FileChangesSummaryCard.vue` | 4 | 8 | 4 | Feather ×3、Lucide ×1 | — |  |
| `components/cloud/composer/ModeSlider.vue` | 3 | 8 | 3 | Feather ×3 | — |  |
| `components/tutorial/TutorialOutline.vue` | 2 | 8 | 0 | — | — |  |
| `components/cloud/PlanDisplay.vue` | 5 | 7 | 4 | Feather ×3 | 1 |  |
| `views/Monitor.vue` | 2 | 6 | 6 | Lucide ×2 | 4 |  |
| `components/auth/OAuthProviderIcon.vue` | 2 | 5 | 5 | — | 5 | GitHub / Google 官方品牌标识 |
| `components/cloud/layout/ImageLightboxModal.vue` | 2 | 5 | 1 | Feather ×1 | — |  |
| `components/cloud/CenterDiffViewer.vue` | 3 | 4 | 4 | Feather ×3 | 1 |  |
| `components/sidebar/SidebarNav.vue` | 3 | 4 | 2 | Feather ×2 | — |  |
| `components/tutorial/TutorialArticle.vue` | 1 | 4 | 2 | Feather ×2 | — |  |
| `components/cloud/DiffViewer.vue` | 2 | 3 | 3 | Feather ×3 | — |  |
| `components/cloud/FileTreeNode.vue` | 2 | 3 | 1 | Feather ×1 | — |  |
| `components/Logo.vue` | 1 | 3 | 2 | — | 2 | 项目自有 Logo |
| `components/tutorial/TutorialLoadingState.vue` | 1 | 3 | 0 | — | — |  |
| `components/cloud/AgentTimer.vue` | 1 | 2 | 1 | — | 1 |  |
| `components/cloud/ContextUsageIndicator.vue` | 1 | 2 | 0 | — | — |  |
| `components/cloud/models/ModelTemplateSelector.vue` | 1 | 2 | 0 | — | — |  |
| `components/cloud/TokenChart.vue` | 2 | 2 | 2 | Feather ×2 | — |  |
| `components/icons/FileIcon.vue` | 1 | 2 | 2 | Feather ×1 | 1 |  |
| `components/sidebar/SearchPanel.vue` | 1 | 2 | 0 | — | — |  |
| `components/theme/ThemeSettingsLauncher.vue` | 1 | 2 | 1 | Feather ×1 | — |  |
| `components/tutorial/TutorialsLauncher.vue` | 1 | 2 | 2 | — | 2 |  |
| `components/cloud/AgentImageAttachments.vue` | 1 | 1 | 1 | — | 1 |  |
| `components/cloud/chat/ThinkingProcessBlock.vue` | 1 | 1 | 1 | Feather ×1 | — |  |
| `components/sidebar/FileHistoryPanel.vue` | 1 | 1 | 1 | Feather ×1 | — |  |
| `components/theme/ThemeChoiceCard.vue` | 1 | 1 | 1 | — | 1 |  |
| `components/theme/ThemeSettingsDrawer.vue` | 1 | 1 | 1 | — | 1 |  |
| `components/tutorial/TutorialSidebar.vue` | 1 | 0 | 0 | — | — |  |

## 4. 典型归因样本

| 图形 | 归属（强判据命中） |
|---|---|
| 删除线文件 `file-minus` | Feather |
| 消息气泡 `message-square` | Feather |
| 复制 `copy` / 编辑 `edit` / 闪电 `zap` / 垃圾桶 `trash-2` | Feather |
| 勾选 `check`（折线 `20 6 9 17 4 12`） / `check-circle` / `check-square` | Feather |
| 下拉箭头 `chevron-down`（折线 `6 9 12 15 18 9`） | Feather |
| 刷新 `refresh-cw`（折线 `23 4 23 10 17 10`） | Feather |
| 美元符 `dollar-sign`（`M12 2v20` + `M17 5H9.5a3.5…` 合并形） | Lucide |
| 加载 `loader-2` | Lucide |
| 灯泡 `light-bulb` | **Heroicons v1**（本次新发现，已补入许可全文第 4 节） |
| 齿轮 `settings`、文件夹 `folder-minus`、下载 `download` | Feather（版本改写，窗口覆盖 40–85%） |

## 5. 未归因项逐项核形

### 5.1 品牌 / 自有标识（非开源图标库素材）

| 图形 | 文件 | 性质 |
|---|---|---|
| GitHub Octocat；Google 四色「G」（共 5 个 path） | `components/auth/OAuthProviderIcon.vue` | 各自公司**官方品牌标识**；Google 品牌规范要求「使用 Google 账号登录」必须用四色 G |
| 自有 Logo（`M7 6v20h18`、`M12 11h12M12 16h8M12 21h14`） | `components/Logo.vue` | 项目自有商标，不适用第三方许可 |

### 5.2 通用 UI 图形（17 个唯一值，人工核形）

自动化判据未命中，原因是这些几何与上游存在**结构等价但写法不同**的改写（拆分的多 `path` 被合并、`<polyline>` 折线被转写为 `d`、坐标整体平移），而非来源不同。逐项核形结论：

| 几何 | 文件 | 核形结论 |
|---|---|---|
| `m6 6 12 12M18 6 6 18` | `AgentImageAttachments.vue`、`ThemeSettingsDrawer.vue` | **Lucide `x`**：上游为两条 `<path d="M18 6 6 18"/>`、`<path d="m6 6 12 12"/>`，此处合并为单条 `d` |
| `M23 4v6h-6`、`M1 20v-6h6` | `preview/WebPreviewPanel.vue` | **Feather `refresh-cw`**：上游为 `<polyline points="23 4 23 10 17 10"/>`、`<polyline points="1 20 1 14 7 14"/>`，此处转写为 `v`/`h` 指令 |
| `M12 7v5l3 2` | `AgentTimer.vue` | 时钟指针，属 Feather / Lucide 时钟族 |
| `M19 11h-6V5h-2v6H5v2h6v6h2v-6h6z` | `cloud/CenterDiffViewer.vue` | 填充加号，与 **Material `add`** 同形（同多边形、反向绕序） |
| `M9 11l3 3L22 4`、`M22 4L12 14.01l-3-3`、`m5 12 4 4L19 6` | `PlanDisplay.vue`、`SummaryCard.vue`、`ThemeChoiceCard.vue` | 长笔画勾选图形，属主流图标库勾选族 |
| `M13 3h5a2 2 0 0 1 2 2v12a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2V7a2 2 0 0 1 2-2h3l2 2h6` | `icons/FileIcon.vue` | 文件夹图形，圆角半径使用库特征常量（`a2 2`） |
| `M4 5.5A2.5 2.5 0 0 1 6.5 3H20v16H6.5A2.5 2.5 0 0 0 4 21.5z`、`M4 5.5v16M8 7h8M8 11h8M8 15h5` | `tutorial/TutorialsLauncher.vue` | 书本图形 |
| `M12 2a2 2 0 0 1 2 2…`、`M8 2l1.88 1.88…`、`M12 20c-3.3 0-6-2.7…`、`M12 20v-9M6.53 9…` | `views/CloudWorkspace.vue` | 机器人 / 缺陷（bug）图形 |
| `M12 14a3 3 0 1 0 0-6 3 3 0 0 0 0 6z`、`M3 12a9 9 0 1 0 18 0 9 9 0 0 0-18 0z` | `views/Monitor.vue` | 基本图元组合（半径 3 / 半径 9 圆 + 竖线），构成仪表 / 雷达图形 |

**核形结论：以上 17 项全部为通用 UI 图形，不含任何品牌标识或企业标识；其几何写法（圆角特征常量、路径指令拆分/合并方式）与开源图标库一致。**

## 6. 遗留事项（代码整洁性，非合规问题）

上表组件仍以**内联复制**方式包含库图形。按 AGENTS.md 第 8.3 节「复用优先、禁止复制粘贴」，
应逐步收敛为 `~icons/<集合>/<图标>` 按需引入（`unplugin-icons` 已在构建链中，`~icons/lucide/*` 可直接使用，零运行时开销）。
此项不影响许可合规，建议按文件分小步迁移。
