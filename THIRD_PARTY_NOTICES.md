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

## 其他说明

- 仓库内所有代码注释、变量与文档均按项目自身规范编写，不包含从第三方源码直接粘贴的段落（除上述明确标注外）。
- 若发现疑似遗漏的第三方代码引用，请在本文件补充来源、版本、许可与引用方式后再提交。
