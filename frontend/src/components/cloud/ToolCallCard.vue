<template>
  <div class="tc-card" :class="['tc-' + call.status, { 'is-expanded': expanded }]" :data-tool-call-id="call.toolCallId || ''">
    <!-- 卡片头部 (1:1 复刻用户设计图一) -->
    <div class="tc-header" @click="expanded = !expanded">
      <div class="tc-header-left">
        <!-- 专用语义化图标 -->
        <!-- 专用语义化图标 (全量纯净 SVG 图标，绝不使用 Emoji) -->
        <span v-if="isTaskTool" class="tc-type-icon subagent" title="子代理">
          <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2"><circle cx="18" cy="5" r="3"/><circle cx="6" cy="12" r="3"/><circle cx="18" cy="19" r="3"/><line x1="8.59" y1="13.51" x2="15.42" y2="17.49"/><line x1="15.41" y1="6.51" x2="8.59" y2="10.49"/></svg>
        </span>
        <span v-else-if="isStartPreviewTool" class="tc-type-icon preview" title="Web 预览服务">
          <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2"><rect x="2" y="3" width="20" height="14" rx="2" ry="2"/><line x1="8" y1="21" x2="16" y2="21"/><line x1="12" y1="17" x2="12" y2="21"/></svg>
        </span>
        <span v-else-if="isSearchTool" class="tc-type-icon search" title="搜索工具">
          <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5"><circle cx="11" cy="11" r="8"/><line x1="21" y1="21" x2="16.65" y2="16.65"/></svg>
        </span>
        <span v-else-if="isEditTool" class="tc-type-icon symbol" title="代码编辑">&lt;&gt;</span>
        <span v-else-if="isShellTool" class="tc-type-icon shell" title="终端命令">&gt;_</span>
        <span v-else-if="isReadTool" class="tc-type-icon read" title="文件读取">
          <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z"/><polyline points="14 2 14 8 20 8"/></svg>
        </span>
        <span v-else class="tc-type-icon default">
          <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><polygon points="13 2 3 14 12 14 11 22 21 10 12 10 13 2"/></svg>
        </span>

        <!-- 语义化标题结构 -->
        <div class="tc-title-tokens">
          <template v-if="isTaskTool && subagentData">
            <strong class="tc-tool-name is-subagent" :class="{ 'is-running': call.status === 'running' }">{{ subagentData.name }}:</strong>
            <span class="tc-subagent-type-pill">{{ subagentData.subagentType }}</span>
            <span class="tc-tool-arg-main">{{ subagentData.description }}</span>
          </template>
          <template v-else>
            <strong class="tc-tool-name" :class="{ 'is-running': call.status === 'running' }">{{ call.name || 'tool' }}:</strong>
            <span class="tc-tool-arg-main">{{ toolMainArg }}</span>
            <span v-if="toolSubInfo" class="tc-tool-sub-info">({{ toolSubInfo }})</span>
          </template>
        </div>
      </div>

      <div class="tc-header-right">
        <!-- 运行中/已完成均可一键打开子代理独立会话（拖出详情查看完整工作轨迹） -->
        <button
          v-if="isTaskTool && (call.subagentId || resolvedSubagentId)"
          type="button"
          class="tc-subagent-header-open"
          @click.stop="emit('open-subagent', { subagentId: call.subagentId || resolvedSubagentId, name: subagentData?.name })"
          title="打开子代理独立会话，查看实时工作详情"
        >
          <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><circle cx="18" cy="5" r="3"/><circle cx="6" cy="12" r="3"/><circle cx="18" cy="19" r="3"/><line x1="8.59" y1="13.51" x2="15.42" y2="17.49"/><line x1="15.41" y1="6.51" x2="8.59" y2="10.49"/></svg>
          <span>会话详情</span>
        </button>
        <!-- 右侧状态标签 (图一风格: 边框胶囊，含耗时与结果) -->
        <div class="tc-status-pill-badge" :class="call.status">
          <svg v-if="call.status === 'completed'" class="badge-icon-check" width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="#10b981" stroke-width="2.8"><polyline points="20 6 9 17 4 12"/></svg>
          <svg v-else-if="call.status === 'running'" class="badge-icon-spin" width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="#3b82f6" stroke-width="2.2"><path d="M21 12a9 9 0 1 1-6.219-8.56"/></svg>
          <svg v-else-if="call.status === 'error'" class="badge-icon-err" width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="#ef4444" stroke-width="2.5"><line x1="18" y1="6" x2="6" y2="18"/><line x1="6" y1="6" x2="18" y2="18"/></svg>
          <span class="badge-text">{{ formattedBadgeStatusText }}</span>
        </div>
      </div>
    </div>

    <!-- 展开详情 (流畅过渡折叠) -->
    <Transition name="tc-slide">
      <div v-if="expanded" class="tc-body">
        <!-- 专有：子代理任务专属可视化面板 -->
        <div v-if="isTaskTool && subagentData" class="tc-subagent-card-body">
          <!-- 任务目标栏 (极简现代扁平条) -->
          <div class="tc-subagent-target-bar">
            <div class="tc-subagent-target-header">
              <div class="tc-subagent-target-label">
                <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2"><circle cx="12" cy="12" r="10"/><circle cx="12" cy="12" r="6"/><circle cx="12" cy="12" r="2"/></svg>
                <span>探索目标</span>
              </div>
              <span class="tc-subagent-mode-badge" :class="subagentData.subagentType">
                {{ subagentData.subagentType === 'general' ? '读写执行' : '只读调研' }}
              </span>
            </div>
            <div class="tc-subagent-target-text">{{ subagentData.prompt || subagentData.description }}</div>
          </div>

          <!-- 正在运行中动画 + 实时当前工具 -->
          <div v-if="call.status === 'running'" class="tc-subagent-running-pulse">
            <svg class="badge-icon-spin" width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="#3b82f6" stroke-width="2.2"><path d="M21 12a9 9 0 1 1-6.219-8.56"/></svg>
            <span>{{ call.subagentCurrentTool || '子代理正在独立调研中，请稍候...' }}</span>
          </div>

          <!-- 工作过程（如果有工具调用轨迹，默认可折叠） -->
          <div v-if="subagentTrace.length > 0" class="tc-subagent-trace-section">
            <div class="tc-subagent-trace-header" @click="traceExpanded = !traceExpanded">
              <div class="tc-subagent-trace-title">
                <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><polyline points="4 17 10 11 4 5"/><line x1="12" y1="19" x2="20" y2="19"/></svg>
                <span>执行过程轨迹 ({{ visibleSubagentTrace.length }} 步)</span>
              </div>
              <button type="button" class="tc-subagent-trace-toggle">
                <span>{{ traceExpanded ? '收起' : '查看' }}</span>
                <svg width="10" height="10" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2" :style="{ transform: traceExpanded ? 'rotate(180deg)' : 'none', transition: 'transform .18s' }"><polyline points="6 9 12 15 18 9"/></svg>
              </button>
            </div>
            <div v-if="traceExpanded" class="tc-subagent-trace-list">
              <div v-for="entry in visibleSubagentTrace" :key="String(entry.sequence)" class="tc-subagent-trace-entry">
                <span class="tc-subagent-trace-kind">{{ subagentEventLabel(entry.type) }}</span>
                <code>{{ entry.payload }}</code>
              </div>
            </div>
          </div>

          <!-- 真正的系统级底层异常（仅当没有产出成果报告且确实存在故障时展示） -->
          <div v-if="subagentSystemError" class="tc-subagent-system-error">
            <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="#ef4444" stroke-width="2.2"><circle cx="12" cy="12" r="10"/><line x1="12" y1="8" x2="12" y2="12"/><line x1="12" y1="16" x2="12.01" y2="16"/></svg>
            <div class="tc-subagent-error-msg">{{ subagentSystemError }}</div>
          </div>

          <!-- 核心：成果报告卡片 (全量对齐主界面 Markdown 呈现) -->
          <div v-if="subagentReportContent" class="tc-subagent-deliverable-card">
            <!-- 顶栏 -->
            <div class="tc-subagent-deliverable-header">
              <div class="tc-subagent-deliverable-title">
                <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z"/><polyline points="14 2 14 8 20 8"/><line x1="16" y1="13" x2="8" y2="13"/><line x1="16" y1="17" x2="8" y2="17"/></svg>
                <span>成果报告与结论</span>
                <span class="tc-subagent-deliverable-pill">{{ subagentData.subagentType }}</span>
              </div>
              <div class="tc-subagent-deliverable-tools">
                <button
                  type="button"
                  class="tc-subagent-tool-btn"
                  @click.stop="copySubagentReport"
                  title="复制完整 Markdown 报告"
                >
                  <svg width="11" height="11" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><rect x="9" y="9" width="13" height="13" rx="2" ry="2"/><path d="M5 15H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v1"/></svg>
                  <span>复制报告</span>
                </button>
                <button
                  v-if="call.subagentId || resolvedSubagentId"
                  type="button"
                  class="tc-subagent-tool-btn"
                  @click.stop="emit('open-subagent', { subagentId: call.subagentId || resolvedSubagentId, name: subagentData?.name })"
                  title="在新标签页打开该子代理的独立会话"
                >
                  <svg width="11" height="11" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><circle cx="18" cy="5" r="3"/><circle cx="6" cy="12" r="3"/><circle cx="18" cy="19" r="3"/><line x1="8.59" y1="13.51" x2="15.42" y2="17.49"/><line x1="15.41" y1="6.51" x2="8.59" y2="10.49"/></svg>
                  <span>会话详情</span>
                </button>
              </div>
            </div>

            <!-- 正文 Markdown 视窗 -->
            <div
              class="tc-subagent-markdown-body markdown-rendered"
              :class="{ 'is-clamped': !reportExpanded && shouldClampReport }"
              v-html="renderSubagentReport(subagentReportContent)"
              @click="e => emit('markdown-click', e)"
            ></div>

            <!-- 超长折叠渐变条 -->
            <div
              v-if="shouldClampReport"
              class="tc-subagent-expand-bar"
              :class="{ 'is-expanded': reportExpanded }"
              @click.stop="reportExpanded = !reportExpanded"
            >
              <button type="button" class="tc-subagent-toggle-report-btn">
                <span>{{ reportExpanded ? '收起成果报告' : '展开完整成果报告' }}</span>
                <svg width="11" height="11" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2" :style="{ transform: reportExpanded ? 'rotate(180deg)' : 'none', transition: 'transform .18s' }"><polyline points="6 9 12 15 18 9"/></svg>
              </button>
            </div>
          </div>

          <!-- 关联文件 (支持一键打开) -->
          <div v-if="subagentData.relevantFiles && subagentData.relevantFiles.length > 0" class="tc-subagent-files-section">
            <div class="tc-subagent-files-title">
              <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z"/><polyline points="14 2 14 8 20 8"/></svg>
              <span>关联文件 (点击在工作区打开)</span>
            </div>
            <div class="tc-subagent-files-chips">
              <button
                v-for="(fpath, fIdx) in subagentData.relevantFiles"
                :key="fIdx"
                type="button"
                class="tc-subagent-file-chip"
                @click.stop="emitOpenFile(fpath)"
                :title="'打开 ' + fpath"
              >
                <svg width="11" height="11" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M13 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V9z"/><polyline points="13 2 13 9 20 9"/></svg>
                <span class="tc-subagent-file-name">{{ fpath }}</span>
              </button>
            </div>
          </div>
        </div>

        <!-- 专有：Web 实时预览启动快捷动作 -->
        <div v-if="isStartPreviewTool && previewUrlFromOutput" class="tc-preview-action-box">
          <div class="tc-preview-meta">
            <span class="tc-preview-dot"></span>
            <span class="tc-preview-url-text">{{ previewUrlFromOutput }}</span>
          </div>
          <div class="tc-preview-btns">
            <button type="button" class="tc-btn-open-preview" @click.stop="emitOpenPreview(previewUrlFromOutput)">
              <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><rect x="2" y="3" width="20" height="14" rx="2" ry="2"/><line x1="8" y1="21" x2="16" y2="21"/><line x1="12" y1="17" x2="12" y2="21"/></svg>
              <span>在右侧视窗打开预览</span>
            </button>
            <a :href="previewUrlFromOutput" target="_blank" class="tc-btn-ext-preview" @click.stop title="在新标签页打开">
              <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M18 13v6a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2h6"/><polyline points="15 3 21 3 21 9"/><line x1="10" y1="14" x2="21" y2="3"/></svg>
            </a>
          </div>
        </div>

        <!-- Edit File Special Diff View -->
        <div v-if="isEditTool && editDiffLines.length > 0" class="tc-edit-diff-box">
          <div
            v-for="(dl, dIdx) in editDiffLines"
            :key="dIdx"
            class="tc-diff-line"
            :class="'is-' + dl.type"
          >
            <span class="tc-diff-lineno">{{ dl.line }}</span>
            <span class="tc-diff-marker">{{ dl.type === 'add' ? '+' : '-' }}</span>
            <span class="tc-diff-code">{{ dl.text }}</span>
          </div>
        </div>

        <!-- Args Section (非 Subagent 工具展示普通参数) -->
        <div v-if="call.args && (!isEditTool || editDiffLines.length === 0) && !isTaskTool" class="tc-section">
          <div class="tc-section-header">
            <span class="tc-section-label">调用参数</span>
          </div>
          <div class="tc-args">
            <template v-if="isEditTool">
              <div class="tc-arg-row" v-if="argsObj.path || argsObj.file_path">
                <span class="tc-arg-key">文件</span>
                <span class="tc-arg-val tc-arg-file">{{ argsObj.path || argsObj.file_path }}</span>
              </div>
              <div class="tc-arg-row" v-if="argsObj.old_string || argsObj.old_text">
                <span class="tc-arg-key">查找</span>
                <pre class="tc-arg-code">{{ truncate(argsObj.old_string || argsObj.old_text, 200) }}</pre>
              </div>
              <div class="tc-arg-row" v-if="argsObj.new_string || argsObj.new_text">
                <span class="tc-arg-key">替换</span>
                <pre class="tc-arg-code">{{ truncate(argsObj.new_string || argsObj.new_text, 200) }}</pre>
              </div>
            </template>
            <template v-else-if="isShellTool">
              <div class="tc-arg-row">
                <span class="tc-arg-key">命令</span>
                <code class="tc-arg-cmd">{{ argsObj.command || argsObj.cmd }}</code>
              </div>
              <div class="tc-arg-row" v-if="argsObj.description">
                <span class="tc-arg-key">说明</span>
                <span class="tc-arg-val">{{ argsObj.description }}</span>
              </div>
            </template>
            <template v-else-if="isReadTool">
              <div class="tc-arg-row">
                <span class="tc-arg-key">文件</span>
                <span class="tc-arg-val tc-arg-file">{{ argsObj.path || argsObj.file_path }}</span>
              </div>
            </template>
            <template v-else-if="isSearchTool">
              <div class="tc-arg-row">
                <span class="tc-arg-key">模式</span>
                <code class="tc-arg-cmd">{{ argsObj.pattern || argsObj.query }}</code>
              </div>
              <div class="tc-arg-row" v-if="argsObj.path || argsObj.directory">
                <span class="tc-arg-key">目录</span>
                <span class="tc-arg-val tc-arg-file">{{ argsObj.path || argsObj.directory }}</span>
              </div>
            </template>
            <template v-else>
              <pre class="tc-args-json">{{ formatJson(call.args) }}</pre>
            </template>
          </div>
        </div>

        <div v-if="call.projection?.truncated" class="tc-projection-note">
          完整结果已保留；模型上下文使用 {{ call.projection.modelProjectionChars }} / {{ call.projection.resultChars }} 字符的受限投影。
        </div>

        <!-- Result Section (非 Subagent 工具展示普通结果) -->
        <div v-if="call.result && (!isEditTool || editDiffLines.length === 0) && !isTaskTool" class="tc-section">
          <div class="tc-section-header">
            <span class="tc-section-label">执行输出</span>
            <button
              type="button"
              class="tc-btn-copy-result"
              @click.stop="copyOutput(call.result)"
              title="复制输出"
            >
              <svg width="11" height="11" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><rect x="9" y="9" width="13" height="13" rx="2" ry="2"/><path d="M5 15H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v1"/></svg>
              <span>复制</span>
            </button>
          </div>
          <div v-if="call.outputTruncated" class="tc-projection-note">
            输出过长，历史页已截断展示前 {{ truncate(call.result, 1000).length }} 字符（完整 {{ call.outputLength || call.result.length }} 字符已持久化在数据库）。
          </div>
          <div v-if="hasDiff" class="tc-diff-wrap">
            <DiffViewer :diff="extractDiff(call.result)" />
          </div>
          <!-- IDE 风格多色彩高亮代码块 (复刻截图图四) -->
          <div v-else-if="formattedCodeLines.length > 0" class="tc-code-ide-block">
            <div class="tc-code-ide-lines">
              <div v-for="(item, idx) in formattedCodeLines" :key="idx" class="tc-code-ide-row">
                <span class="tc-code-ide-ln">{{ item.lineNum }}</span>
                <span class="tc-code-ide-code hljs" v-html="item.html || '&nbsp;'"></span>
              </div>
            </div>
          </div>
          <div v-else class="tc-result-text">
            <pre>{{ truncate(call.result, 1000) }}</pre>
          </div>
        </div>

        <div v-if="isQuestionAsk" class="tc-question">
          <div class="tc-question-kicker">等待用户输入</div>
          <div class="tc-question-title">{{ questionRequest.summary || 'Agent 需要你的回答' }}</div>
          <div class="tc-question-text">{{ questionRequest.question }}</div>
          <div v-if="!questionRequestReady" class="tc-question-sync" role="status">正在同步可恢复提问请求，请稍候...</div>
          <div v-if="questionOptions.length" class="tc-question-options">
            <button
              v-for="option in questionOptions"
              :key="option"
              type="button"
              class="tc-option-btn"
              @click.stop="setQuestionAnswer(option)"
            >{{ option }}</button>
          </div>
          <textarea
            v-model="answerDraft"
            class="tc-textarea"
            rows="3"
            placeholder="输入你的回答，Agent 会基于这个回答继续执行"
            @click.stop
          />
          <div class="tc-approval-actions">
            <button type="button" class="tc-approval-btn primary" :disabled="!questionRequestReady" @click.stop="emitQuestion('answer')">{{ questionRequestReady ? '发送回答' : '同步请求中...' }}</button>
            <button type="button" class="tc-approval-btn danger" :disabled="!questionRequestReady" @click.stop="emitQuestion('cancel')">取消这次提问</button>
          </div>
        </div>

        <div v-if="isCommandApproval" class="tc-approval">
          <div class="tc-approval-title">此命令需要一次性批准</div>
          <div v-if="commandApproval.displayCommand" class="tc-approval-command">
            <span>命令</span>
            <code>{{ commandApproval.displayCommand }}</code>
          </div>
          <div class="tc-approval-meta">
            <span v-if="commandApproval.riskLevel">风险：{{ commandApproval.riskLevel }}</span>
            <span v-if="commandApproval.expiresTime">到期：{{ commandApproval.expiresTime }}</span>
          </div>
          <div class="tc-approval-actions">
            <button type="button" class="tc-approval-btn primary" :disabled="commandSubmitting" @click.stop="emitCommandApproval('approve')">{{ commandSubmitting ? '提交中...' : '批准一次' }}</button>
            <button type="button" class="tc-approval-btn danger" :disabled="commandSubmitting" @click.stop="emitCommandApproval('reject')">拒绝</button>
          </div>
        </div>

        <div v-if="isNetworkAsk" class="tc-approval tc-network-approval">
          <div class="tc-approval-title">Agent 请求使用网络</div>
          <div class="tc-network-reason">{{ networkRequest.summary || 'Agent 请求访问外部依赖仓库' }}</div>
          <div v-if="networkRequest.retryable" class="tc-approval-meta tc-network-retry-note">
            <span>这是离线失败后的单次重试，批准后只会重试当前命令一次</span>
          </div>
          <div v-if="networkDomains.length" class="tc-approval-meta">
            <span>允许的目标：{{ networkDomains.join(', ') }}</span>
          </div>
          <div class="tc-approval-meta"><span>仅对当前命令生效，不会保存为永久权限</span></div>
          <div class="tc-approval-actions">
            <button type="button" class="tc-approval-btn primary" @click.stop="emitPermission('once')">允许一次</button>
            <button type="button" class="tc-approval-btn secondary" @click.stop="emitPermission('always')">总是允许</button>
            <button type="button" class="tc-approval-btn danger" @click.stop="emitPermission('reject')">拒绝</button>
          </div>
        </div>

        <div v-if="isPermissionAsk" class="tc-approval">
          <div class="tc-approval-title">{{ call.permissionRequest.reason || 'Agent 请求操作权限' }}</div>
          <div class="tc-approval-actions">
            <button type="button" class="tc-approval-btn primary" @click.stop="emitPermission('once')">允许一次</button>
            <button type="button" class="tc-approval-btn secondary" @click.stop="emitPermission('always')">总是允许</button>
            <button type="button" class="tc-approval-btn danger" @click.stop="emitPermission('reject')">拒绝</button>
          </div>
        </div>
      </div>
    </Transition>
  </div>
</template>

<script setup>
import { ref, computed, watch } from 'vue'
import { ElMessage } from 'element-plus'
import hljs from 'highlight.js/lib/common'
import DiffViewer from './DiffViewer.vue'
import { renderMessageMarkdown } from '@/utils/agentMarkdownRenderer'

const props = defineProps({
  call: {
    type: Object,
    required: true
  }
})

const subagentTrace = computed(() => Array.isArray(props.call.subagentTrace) ? props.call.subagentTrace : [])
// 抽屉里只保留最近 10 条轨迹，完整轨迹在子代理会话标签中查看。
const visibleSubagentTrace = computed(() => subagentTrace.value.slice(-10))
const SUBAGENT_PREVIEW_LIMIT = 500

function truncatePreview(text, limit = SUBAGENT_PREVIEW_LIMIT) {
  const raw = String(text || '')
  return raw.length <= limit ? raw : raw.slice(0, limit) + '…'
}

const subagentErrorPreview = computed(() => truncatePreview(props.call.subagentError, 600))
const findingsPreview = computed(() => truncatePreview(subagentData.value?.findings, SUBAGENT_PREVIEW_LIMIT))
const nextStepsPreview = computed(() => truncatePreview(subagentData.value?.nextSteps, 300))
const subagentLiveOutputPreview = computed(() => truncatePreview(props.call.subagentLiveOutput, 300))

// 结构化 Markdown 渲染（与主会话同一份渲染管线：表格/代码高亮/callout 全支持），
// 不再展示 markdown 源文本。
function renderSubagentReport(text) {
  if (!text) return ''
  return renderMessageMarkdown({ role: 'assistant', content: String(text), isStreaming: false })
}

function subagentEventLabel(type) {
  switch (String(type || '').toUpperCase()) {
    case 'TOOL_CALL': return '工具调用'
    case 'TOOL_RESULT': return '工具结果'
    case 'DELTA': return '可见结论'
    case 'ERROR': return '失败'
    case 'START': return '开始'
    default: return '进度'
  }
}

const emit = defineEmits(['permission', 'command-approval', 'question', 'open-file', 'open-preview', 'open-subagent', 'markdown-click'])

const expanded = ref(false)
const traceExpanded = ref(false)
const reportExpanded = ref(false)
const answerDraft = ref('')
const commandSubmitting = ref(false)

function isReportLike(text) {
  if (!text) return false
  const s = String(text).trim()
  if (s.includes('##') || s.includes('|---|') || s.includes('```')) return true
  if (s.length > 80 && (s.includes('\n-') || s.includes('\n1.') || s.includes('\n*'))) return true
  return false
}

function copySubagentReport() {
  const text = subagentReportContent.value
  if (!text) return
  navigator.clipboard?.writeText(text)
  ElMessage.success('成果报告已复制到剪贴板')
}

const isEditTool = computed(() => ['edit_file', 'write_file', 'apply_patch'].includes(props.call.name))
const isShellTool = computed(() => ['shell', 'bash', 'run_command', 'execute_code', 'run_tests'].includes(props.call.name))
const isReadTool = computed(() => ['read_file', 'retrieve_context'].includes(props.call.name))
const isSearchTool = computed(() => ['grep_search', 'list_files', 'glob', 'grep', 'search_code', 'lsp_symbols'].includes(props.call.name))
const isTaskTool = computed(() => ['task', 'subagent'].includes(props.call.name))
const isStartPreviewTool = computed(() => ['start_preview'].includes(props.call.name))

const isPermissionAsk = computed(() => props.call.status === 'waiting_approval' && !!props.call.permissionRequest)
const isNetworkAsk = computed(() => props.call.status === 'waiting_approval' && !!props.call.networkRequest)
const networkRequest = computed(() => props.call.networkRequest || {})
const networkDomains = computed(() => Array.isArray(networkRequest.value.domains) ? networkRequest.value.domains.filter(Boolean) : [])
const isCommandApproval = computed(() => props.call.status === 'waiting_approval' && !!props.call.commandApproval && !props.call.networkRequest && !props.call.permissionRequest)
const commandApproval = computed(() => props.call.commandApproval || {})
const isQuestionAsk = computed(() => props.call.status === 'waiting_user' && !!props.call.questionRequest)
const questionRequest = computed(() => props.call.questionRequest || {})
const questionRequestReady = computed(() => Boolean(questionRequest.value.requestId || questionRequest.value.interactionId))
const questionOptions = computed(() => Array.isArray(questionRequest.value.options) ? questionRequest.value.options.filter(Boolean) : [])

const argsObj = computed(() => {
  if (typeof props.call.args === 'string') {
    try { return JSON.parse(props.call.args) } catch { return {} }
  }
  return props.call.args || {}
})

// 子代理数据结构化解析
const subagentData = computed(() => {
  if (!isTaskTool.value) return null
  const args = argsObj.value
  const name = args.name || args.agent_name || args.role || ''
  const subagentType = args.subagent_type || args.type || 'general'
  const prompt = args.prompt || args.task || args.question || args.description || ''
  const description = args.description || name || prompt || ''

  let summary = description
  const relevantFiles = []
  let nextSteps = ''
  const rawResult = props.call.result || ''
  const persistedSummary = props.call.subagentSummary || ''
  const rawError = props.call.subagentError || ''

  let body = ''
  if (rawResult && typeof rawResult === 'string') {
    const summaryMatch = rawResult.match(/<summary>([\s\S]*?)<\/summary>/i)
    if (summaryMatch) summary = summaryMatch[1].trim()

    const taskResultMatch = rawResult.match(/<task_result>([\s\S]*?)<\/task_result>/i)
    const taskErrorMatch = rawResult.match(/<task_error>([\s\S]*?)<\/task_error>/i)
    if (taskResultMatch && taskResultMatch[1].trim()) {
      body = taskResultMatch[1].trim()
    } else if (taskErrorMatch && taskErrorMatch[1].trim() && isReportLike(taskErrorMatch[1])) {
      body = taskErrorMatch[1].trim()
    } else if (!rawResult.trim().startsWith('<task') && isReportLike(rawResult)) {
      body = rawResult.trim()
    }
  }

  if (!body && persistedSummary && isReportLike(persistedSummary)) {
    body = persistedSummary.trim()
  }
  if (!body && rawError && isReportLike(rawError)) {
    body = rawError.trim()
  }
  if (!body && persistedSummary) {
    body = persistedSummary.trim()
  }

  const nameMatch = rawResult && typeof rawResult === 'string' ? rawResult.match(/<name>([\s\S]*?)<\/name>/i) : null
  const effectiveName = name || (nameMatch ? nameMatch[1].trim() : '')

  if (body) {
    const filesMatch = body.match(/##\s*(?:Relevant Files|关联文件|涉及文件)([\s\S]*?)(?=##|$)/i)
    if (filesMatch) {
      const filesText = filesMatch[1].trim()
      const lines = filesText.split('\n')
      for (const line of lines) {
        const cleaned = line.replace(/^[-*•\d.)\s]+/, '').trim().replace(/[`]/g, '')
        if (cleaned && !cleaned.startsWith('#') && (cleaned.includes('/') || cleaned.includes('.'))) {
          relevantFiles.push(cleaned)
        }
      }
    }

    const nextStepsMatch = body.match(/##\s*(?:Suggested Next Steps|Next Steps|后续建议|建议下一步)([\s\S]*?)(?=##|$)/i)
    if (nextStepsMatch) nextSteps = nextStepsMatch[1].trim()
  }

  return {
    name: effectiveName || description || '代码调研子代理',
    subagentType,
    prompt,
    description: summary,
    fullReport: body,
    relevantFiles,
    nextSteps,
    isBackground: Boolean(args.background)
  }
})

// 判定子代理是否有实质性的成果报告
const subagentReportContent = computed(() => {
  if (!isTaskTool.value) return ''
  return subagentData.value?.fullReport || ''
})

// 纯系统底层报错（当且仅当该错误不是报告正文时展示）
const subagentSystemError = computed(() => {
  const err = props.call.subagentError || ''
  if (!err) return ''
  if (isReportLike(err)) return ''
  if (subagentReportContent.value && subagentReportContent.value.includes(err.trim())) return ''
  return err.trim()
})

const shouldClampReport = computed(() => {
  return (subagentReportContent.value || '').length > 900
})

const resolvedSubagentId = computed(() => {
  if (props.call.subagentId) return props.call.subagentId
  const res = props.call.result
  if (typeof res === 'string') {
    const match = res.match(/<task\b[^>]*\bid=["']([^"']+)["']/i)
    if (match && match[1]) return match[1]
  }
  return ''
})

// Web 实时预览 URL 提取
const previewUrlFromOutput = computed(() => {
  if (!isStartPreviewTool.value || !props.call.result) return ''
  const text = String(props.call.result)
  const match = text.match(/preview_url=(https?:\/\/[^\s\n]+)/i)
  if (match) return match[1].trim()
  const directMatch = text.match(/(https?:\/\/(?:localhost|127\.0\.0\.1|[\w.-]+):\d+[^\s\n]*)/i)
  if (directMatch) return directMatch[1].trim()
  return ''
})

function emitOpenFile(path) {
  if (path) emit('open-file', path)
}

function emitOpenPreview(url) {
  if (url) emit('open-preview', url)
}

const editStats = computed(() => {
  if (!isEditTool.value) return { added: 0, deleted: 0 }
  let added = 0
  let deleted = 0
  const oldStr = argsObj.value.old_string || argsObj.value.old_text || ''
  const newStr = argsObj.value.new_string || argsObj.value.new_text || ''
  if (oldStr) deleted = oldStr.split('\n').length
  if (newStr) added = newStr.split('\n').length
  if (props.call.result && typeof props.call.result === 'string') {
    const addMatches = props.call.result.match(/^\+[^+]/gm)
    const delMatches = props.call.result.match(/^-[^-]/gm)
    if (addMatches) added = Math.max(added, addMatches.length)
    if (delMatches) deleted = Math.max(deleted, delMatches.length)
  }
  return { added, deleted }
})

// 主参数提取 (图一精炼显示)
const toolMainArg = computed(() => {
  if (isTaskTool.value && subagentData.value) {
    return subagentData.value.name
  }
  if (isSearchTool.value) {
    const p = argsObj.value.pattern || argsObj.value.query || argsObj.value.path || ''
    return p ? `"${p}"` : ''
  }
  if (isEditTool.value || isReadTool.value) {
    return argsObj.value.path || argsObj.value.file_path || argsObj.value.filename || ''
  }
  if (isShellTool.value) {
    return argsObj.value.command || argsObj.value.cmd || ''
  }
  return argsObj.value.query || argsObj.value.name || ''
})

// 次要/缩略信息
const toolSubInfo = computed(() => {
  if (isEditTool.value) {
    if (editStats.value.added > 0 || editStats.value.deleted > 0) {
      return `+${editStats.value.added} -${editStats.value.deleted} 行`
    }
  }
  if (isSearchTool.value && props.call.result) {
    if (typeof props.call.result === 'string') {
      const matchLines = props.call.result.split('\n').filter(l => l.trim().length > 0).length
      if (matchLines > 0) return `找到 ${matchLines} 处匹配`
    }
  }
  return ''
})

// 右侧状态胶囊文本 (图一精炼显示: "0.08s 成功" / "0.45s 退出码 0")
const formattedBadgeStatusText = computed(() => {
  const elapsed = Number(props.call.execution?.elapsedMs || 0)
  const timeStr = elapsed > 0 ? `${(elapsed / 1000).toFixed(2)}s ` : ''
  if (props.call.status === 'completed') {
    if (isShellTool.value) {
      return `${timeStr}退出码 0`
    }
    return `${timeStr}成功`
  }
  if (props.call.status === 'running') return '执行中...'
  if (props.call.status === 'error') return `${timeStr}失败`
  if (props.call.status === 'waiting_approval') return '等待审批'
  if (props.call.status === 'waiting_user') return '等待输入'
  return props.call.status
})

const editDiffLines = computed(() => {
  if (!isEditTool.value) return []
  const oldStr = argsObj.value.old_string || argsObj.value.old_text || ''
  const newStr = argsObj.value.new_string || argsObj.value.new_text || ''
  const lines = []
  let lineNum = parseInt(argsObj.value.start_line || argsObj.value.line || '12', 10)
  if (isNaN(lineNum) || lineNum <= 0) lineNum = 12
  if (oldStr) {
    oldStr.split('\n').forEach(l => {
      lines.push({ type: 'del', line: lineNum, text: l })
    })
  }
  if (newStr) {
    newStr.split('\n').forEach((l, idx) => {
      lines.push({ type: 'add', line: lineNum + idx, text: l })
    })
  }
  return lines
})

const detectedCodeLang = computed(() => {
  const file = argsObj.value.path || argsObj.value.file_path || argsObj.value.filename || ''
  if (!file) return ''
  const ext = file.split('.').pop()?.toLowerCase()
  const map = {
    py: 'python',
    js: 'javascript',
    jsx: 'javascript',
    ts: 'typescript',
    tsx: 'typescript',
    vue: 'html',
    html: 'html',
    css: 'css',
    scss: 'scss',
    java: 'java',
    json: 'json',
    sh: 'bash',
    bash: 'bash',
    sql: 'sql',
    md: 'markdown',
    xml: 'xml',
    yml: 'yaml',
    yaml: 'yaml'
  }
  return map[ext] || ''
})

function escapeHtml(str) {
  return String(str || '').replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
}

const formattedCodeLines = computed(() => {
  if (!expanded.value || !props.call.result || typeof props.call.result !== 'string') return []
  const text = truncate(props.call.result, 3000)
  const lines = text.split('\n')
  const lang = detectedCodeLang.value

  return lines.map((line, idx) => {
    const match = line.match(/^(\s*\d+)([:|])\s?(.*)$/)
    let lineNum = ''
    let codeContent = line
    if (match) {
      lineNum = match[1].trim()
      codeContent = match[3]
    }

    let highlightedHtml = ''
    try {
      if (lang && hljs.getLanguage(lang)) {
        highlightedHtml = hljs.highlight(codeContent, { language: lang }).value
      } else {
        const auto = hljs.highlightAuto(codeContent)
        highlightedHtml = auto.value || escapeHtml(codeContent)
      }
    } catch {
      highlightedHtml = escapeHtml(codeContent)
    }

    return {
      lineNum: lineNum || String(idx + 1),
      raw: line,
      html: highlightedHtml
    }
  })
})

const hasDiff = computed(() => {
  if (!props.call.result || typeof props.call.result !== 'string') return false
  return props.call.result.includes('--- ') && props.call.result.includes('+++ ')
})

function extractDiff(text) {
  if (!text) return ''
  const lines = text.split('\n')
  const diffStart = lines.findIndex(l => l.startsWith('--- ') || l.startsWith('diff --git'))
  return diffStart >= 0 ? lines.slice(diffStart).join('\n') : text
}

function truncate(text, maxLen = 300) {
  if (!text) return ''
  return text.length > maxLen ? text.slice(0, maxLen) + '...' : text
}

function formatJson(val) {
  if (!val) return ''
  if (typeof val === 'string') {
    try { return JSON.stringify(JSON.parse(val), null, 2) } catch { return val }
  }
  return JSON.stringify(val, null, 2)
}

function copyOutput(text) {
  navigator.clipboard?.writeText(text || '')
  ElMessage.success('输出已复制到剪贴板')
}

function emitPermission(action) {
  emit('permission', {
    call: props.call,
    toolCallId: props.call.toolCallId,
    action,
    permissionRequest: props.call.permissionRequest,
    networkRequest: props.call.networkRequest
  })
}

function emitCommandApproval(action) {
  commandSubmitting.value = true
  emit('command-approval', {
    call: props.call,
    toolCallId: props.call.toolCallId,
    action,
    commandApproval: props.call.commandApproval
  })
  setTimeout(() => { commandSubmitting.value = false }, 1000)
}

function emitQuestion(action) {
  if (!questionRequestReady.value) return
  emit('question', {
    call: props.call,
    toolCallId: props.call.toolCallId,
    requestId: questionRequest.value.requestId || questionRequest.value.interactionId,
    action,
    answer: action === 'answer' ? answerDraft.value : null
  })
}

function setQuestionAnswer(option) {
  answerDraft.value = option
}
</script>

<style scoped>
.tc-card {
  background: #ffffff;
  border: 1px solid #e4e4e7;
  border-radius: 8px;
  margin: 6px 0;
  overflow: hidden;
  box-shadow: 0 1px 3px rgba(0, 0, 0, 0.03);
  transition: all 0.2s cubic-bezier(0.16, 1, 0.3, 1);
  user-select: none;
}

.tc-card:hover {
  border-color: #d4d4d8;
  box-shadow: 0 4px 16px -2px rgba(0, 0, 0, 0.08), 0 2px 6px -1px rgba(0, 0, 0, 0.04);
  transform: translateY(-1px);
}

.tc-card.is-expanded {
  border-color: #d4d4d8;
  box-shadow: 0 4px 14px rgba(0, 0, 0, 0.05);
}

/* 头部 (Figure 1) */
.tc-header {
  padding: 8px 12px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  cursor: pointer;
  background: #ffffff;
  min-height: 38px;
}

.tc-header-left {
  display: flex;
  align-items: center;
  gap: 8px;
  overflow: hidden;
  flex: 1;
}

.tc-type-icon {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  font-family: 'JetBrains Mono', monospace;
  font-size: 11px;
  font-weight: 700;
  color: #71717a;
  flex-shrink: 0;
}

.tc-type-icon.symbol,
.tc-type-icon.shell {
  font-size: 11.5px;
  color: #52525b;
}

.tc-title-tokens {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 12.5px;
  color: #09090b;
  overflow: hidden;
  white-space: nowrap;
}

.tc-tool-name {
  font-family: 'JetBrains Mono', monospace;
  font-weight: 700;
  color: #09090b;
  flex-shrink: 0;
  transition: all 0.2s ease;
}

/* 工具正在调用时的光影流动动画 (Flowing Shimmer Animation) */
.tc-tool-name.is-running,
.tc-card.tc-running .tc-tool-name {
  background: linear-gradient(
    90deg,
    #09090b 0%,
    #2563eb 25%,
    #60a5fa 50%,
    #2563eb 75%,
    #09090b 100%
  );
  background-size: 200% 100%;
  color: transparent !important;
  -webkit-background-clip: text;
  background-clip: text;
  animation: toolNameFlowLight 2s linear infinite;
  display: inline-block;
  text-shadow: 0 0 1px rgba(37, 99, 235, 0.15);
}

.tc-card.dark .tc-tool-name.is-running,
.tc-card.is-dark .tc-tool-name.is-running,
.tc-card.dark.tc-running .tc-tool-name,
:deep(.dark) .tc-running .tc-tool-name {
  background: linear-gradient(
    90deg,
    #e4e4e7 0%,
    #60a5fa 25%,
    #93c5fd 50%,
    #60a5fa 75%,
    #e4e4e7 100%
  );
  background-size: 200% 100%;
  color: transparent !important;
  -webkit-background-clip: text;
  background-clip: text;
  animation: toolNameFlowLight 2s linear infinite;
  text-shadow: 0 0 2px rgba(147, 197, 253, 0.35);
}

@keyframes toolNameFlowLight {
  0% {
    background-position: 100% 50%;
  }
  100% {
    background-position: -100% 50%;
  }
}

.tc-tool-arg-main {
  font-family: 'JetBrains Mono', monospace;
  color: #3f3f46;
  overflow: hidden;
  text-overflow: ellipsis;
  max-width: 420px;
}

.tc-tool-sub-info {
  font-size: 12px;
  color: #71717a;
  flex-shrink: 0;
}

/* 右侧状态胶囊 (Figure 1) */
.tc-header-right {
  display: flex;
  align-items: center;
  flex-shrink: 0;
}

.tc-status-pill-badge {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 2px 8px;
  background: #ffffff;
  border: 1px solid #e4e4e7;
  border-radius: 6px;
  font-size: 11.5px;
  font-family: 'JetBrains Mono', 'Inter', -apple-system, sans-serif;
  font-weight: 600;
  color: #3f3f46;
}

.tc-status-pill-badge .badge-text {
  font-weight: 600;
  letter-spacing: 0.2px;
}

.tc-status-pill-badge.completed {
  color: #18181b;
}

.tc-status-pill-badge.error {
  color: #ef4444;
  border-color: #fecaca;
  background: #fef2f2;
}

.tc-status-pill-badge.running {
  color: #3b82f6;
  border-color: #bfdbfe;
  background: #eff6ff;
  box-shadow: 0 0 8px rgba(59, 130, 246, 0.15);
}

.badge-icon-spin {
  animation: spin 1s linear infinite;
}

/* 详情展开区 */
.tc-body {
  border-top: 1px solid #f4f4f5;
  background: #fafafa;
  padding: 10px 12px;
  font-size: 12px;
}

.tc-section {
  margin-bottom: 10px;
}

.tc-section:last-child {
  margin-bottom: 0;
}

.tc-section-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 6px;
}

.tc-section-label {
  font-size: 11px;
  font-weight: 600;
  color: #71717a;
}

.tc-btn-copy-result {
  background: transparent;
  border: none;
  color: #a1a1aa;
  cursor: pointer;
  display: inline-flex;
  align-items: center;
  gap: 4px;
  font-size: 11px;
  padding: 2px 6px;
  border-radius: 4px;
  transition: all 0.12s;
}

.tc-btn-copy-result:hover {
  color: #09090b;
  background: #f4f4f5;
}

.tc-args {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.tc-arg-row {
  display: flex;
  align-items: flex-start;
  gap: 8px;
  font-size: 12px;
}

.tc-arg-key {
  color: #71717a;
  font-weight: 500;
  width: 42px;
  flex-shrink: 0;
}

.tc-arg-val {
  color: #09090b;
  word-break: break-all;
}

.tc-arg-file {
  font-family: 'JetBrains Mono', monospace;
  background: #f4f4f5;
  padding: 1px 5px;
  border-radius: 4px;
  font-size: 11.5px;
}

.tc-arg-cmd {
  font-family: 'JetBrains Mono', monospace;
  background: #f4f4f5;
  color: #09090b;
  border: 1px solid #e4e4e7;
  padding: 3px 8px;
  border-radius: 5px;
  font-size: 11.5px;
  word-break: break-all;
}

.tc-arg-code {
  margin: 0;
  font-family: 'JetBrains Mono', monospace;
  background: #f4f4f5;
  padding: 4px 8px;
  border-radius: 5px;
  font-size: 11.5px;
  color: #3f3f46;
  white-space: pre-wrap;
  word-break: break-all;
}

.tc-args-json {
  margin: 0;
  font-family: 'JetBrains Mono', monospace;
  background: #f4f4f5;
  padding: 6px 8px;
  border-radius: 6px;
  font-size: 11.5px;
  color: #3f3f46;
  white-space: pre-wrap;
}

/* 终端与 IDE 代码视窗 (复刻截图图四) */
.tc-shell-output,
.tc-result-text {
  background: #0d1117;
  border-radius: 6px;
  padding: 8px 12px;
  overflow-x: auto;
}

.tc-code-ide-block {
  background: #0d1117;
  border: 1px solid #30363d;
  border-radius: 6px;
  overflow: hidden;
  font-family: 'JetBrains Mono', 'Fira Code', 'Consolas', monospace;
  font-size: 12px;
  line-height: 1.6;
}

.tc-code-ide-lines {
  overflow-x: auto;
  padding: 8px 0;
}

.tc-code-ide-row {
  display: flex;
  align-items: flex-start;
  padding: 0 10px;
  min-height: 20px;
}

.tc-code-ide-row:hover {
  background: rgba(255, 255, 255, 0.05);
}

.tc-code-ide-ln {
  width: 38px;
  min-width: 38px;
  text-align: right;
  padding-right: 12px;
  color: #6e7681;
  font-size: 11px;
  user-select: none;
  border-right: 1px solid #21262d;
  margin-right: 12px;
}

.tc-code-ide-code {
  flex: 1;
  white-space: pre;
  word-break: normal;
  color: #e6edf3;
  font-family: inherit;
  font-size: inherit;
  background: transparent !important;
  padding: 0 !important;
}

/* IDE 语法高亮颜色映射 */
:deep(.hljs-keyword), :deep(.hljs-selector-tag), :deep(.hljs-built_in) {
  color: #ff7b72 !important;
  font-weight: 600;
}

:deep(.hljs-string), :deep(.hljs-attr) {
  color: #a5d6ff !important;
}

:deep(.hljs-title), :deep(.hljs-title.function_), :deep(.hljs-section) {
  color: #d2a8ff !important;
  font-weight: 600;
}

:deep(.hljs-comment), :deep(.hljs-quote) {
  color: #8b949e !important;
  font-style: italic;
}

:deep(.hljs-number), :deep(.hljs-literal) {
  color: #79c0ff !important;
}

:deep(.hljs-type), :deep(.hljs-class) {
  color: #ffa657 !important;
}

:deep(.hljs-variable), :deep(.hljs-template-variable) {
  color: #ffa657 !important;
}

.tc-shell-output pre,
.tc-result-text pre {
  margin: 0;
  font-family: 'JetBrains Mono', monospace;
  font-size: 11.5px;
  line-height: 1.5;
  color: #f8fafc;
  white-space: pre-wrap;
  word-break: break-all;
}

.tc-projection-note {
  font-size: 11px;
  color: #71717a;
  background: #f4f4f5;
  padding: 4px 8px;
  border-radius: 4px;
  margin-bottom: 8px;
}

/* Edit Diff Box */
.tc-edit-diff-box {
  background: #ffffff;
  border: 1px solid #e4e4e7;
  border-radius: 6px;
  overflow: hidden;
  font-family: 'JetBrains Mono', monospace;
  font-size: 11.5px;
}

.tc-diff-line {
  display: flex;
  align-items: center;
  padding: 2px 8px;
  line-height: 1.4;
}

.tc-diff-line.is-add {
  background: #f0fdf4;
  color: #166534;
}

.tc-diff-line.is-del {
  background: #fef2f2;
  color: #991b1b;
}

.tc-diff-lineno {
  width: 28px;
  color: #a1a1aa;
  font-size: 10.5px;
  user-select: none;
}

.tc-diff-marker {
  width: 14px;
  font-weight: 700;
}

.tc-diff-code {
  white-space: pre-wrap;
  word-break: break-all;
}

/* 交互卡片 (提问、审批) */
.tc-question,
.tc-approval {
  background: #ffffff;
  border: 1px solid #e4e4e7;
  border-radius: 6px;
  padding: 10px;
  margin-top: 8px;
}

.tc-question-kicker {
  font-size: 10.5px;
  font-weight: 700;
  color: #8b5cf6;
  text-transform: uppercase;
  margin-bottom: 4px;
}

.tc-question-title,
.tc-approval-title {
  font-weight: 600;
  color: #09090b;
  margin-bottom: 6px;
}

.tc-question-text {
  color: #52525b;
  margin-bottom: 8px;
}

.tc-question-options {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  margin-bottom: 8px;
}

.tc-option-btn {
  padding: 4px 10px;
  background: #f4f4f5;
  border: 1px solid #e4e4e7;
  border-radius: 6px;
  font-size: 11.5px;
  cursor: pointer;
  transition: all 0.12s;
}

.tc-option-btn:hover {
  background: #e4e4e7;
  color: #09090b;
}

.tc-textarea {
  width: 100%;
  padding: 6px 8px;
  border: 1px solid #e4e4e7;
  border-radius: 6px;
  font-size: 12px;
  outline: none;
  resize: vertical;
  margin-bottom: 8px;
}

.tc-textarea:focus {
  border-color: #18181b;
}

.tc-approval-actions {
  display: flex;
  gap: 8px;
  margin-top: 8px;
}

.tc-approval-btn {
  padding: 4px 12px;
  border-radius: 6px;
  font-size: 12px;
  font-weight: 500;
  cursor: pointer;
  border: 1px solid transparent;
  transition: all 0.12s;
}

.tc-approval-btn.primary {
  background: #18181b;
  color: #ffffff;
}

.tc-approval-btn.primary:hover {
  background: #27272a;
}

.tc-approval-btn.secondary {
  background: #f4f4f5;
  border-color: #e4e4e7;
  color: #3f3f46;
}

.tc-approval-btn.secondary:hover {
  background: #e4e4e7;
}

.tc-approval-btn.danger {
  background: #fee2e2;
  color: #dc2626;
  border-color: #fca5a5;
}

.tc-approval-btn.danger:hover {
  background: #fecaca;
}

@keyframes spin {
  to { transform: rotate(360deg); }
}

/* Animations */
.tc-slide-enter-active,
.tc-slide-leave-active {
  transition: max-height 0.2s cubic-bezier(0.16, 1, 0.3, 1), opacity 0.15s ease;
  overflow: hidden;
}

.tc-slide-enter-from,
.tc-slide-leave-to {
  max-height: 0;
  opacity: 0;
}

.tc-slide-enter-to,
.tc-slide-leave-from {
  max-height: 1200px;
  opacity: 1;
}

/* 子代理专用高质感样式 */
.tc-type-icon.subagent {
  color: #7c3aed;
}

.tc-type-icon.preview {
  color: #0284c7;
}

.tc-tool-name.is-subagent {
  color: #6d28d9;
}

.tc-subagent-type-pill {
  display: inline-flex;
  align-items: center;
  font-size: 10px;
  font-weight: 600;
  text-transform: uppercase;
  letter-spacing: 0.5px;
  padding: 1px 6px;
  border-radius: 4px;
  background: #f5f3ff;
  color: #7c3aed;
  border: 1px solid #ddd6fe;
  flex-shrink: 0;
}

.tc-subagent-card-body {
  display: flex;
  flex-direction: column;
  gap: 10px;
  padding-top: 4px;
}

/* ==================== 子代理专属高质感设计规范 ==================== */
.tc-type-icon.subagent {
  color: #7c3aed;
}

.tc-tool-name.is-subagent {
  color: #6d28d9;
}

.tc-subagent-type-pill {
  display: inline-flex;
  align-items: center;
  font-size: 10px;
  font-weight: 700;
  text-transform: uppercase;
  letter-spacing: 0.5px;
  padding: 1px 6px;
  border-radius: 4px;
  background: #f5f3ff;
  color: #7c3aed;
  border: 1px solid #ddd6fe;
  flex-shrink: 0;
}

.tc-subagent-card-body {
  display: flex;
  flex-direction: column;
  gap: 12px;
  padding-top: 6px;
}

/* 头部常显的会话详情入口 */
.tc-subagent-header-open {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 3px 10px;
  margin-right: 8px;
  border-radius: 999px;
  border: 1px solid #c7d2fe;
  background: #eef2ff;
  color: #4338ca;
  font-size: 11px;
  font-weight: 500;
  cursor: pointer;
  white-space: nowrap;
  transition: all .18s cubic-bezier(.25,.1,.25,1);
}

.tc-subagent-header-open:hover {
  background: #e0e7ff;
  border-color: #a5b4fc;
}

/* 任务目标栏 */
.tc-subagent-target-bar {
  background: #f8fafc;
  border: 1px solid #e2e8f0;
  border-radius: 7px;
  padding: 9px 12px;
}

.tc-subagent-target-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 6px;
}

.tc-subagent-target-label {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 11px;
  font-weight: 600;
  color: #64748b;
}

.tc-subagent-target-label svg {
  color: #7c3aed;
}

.tc-subagent-mode-badge {
  font-size: 10px;
  font-weight: 600;
  padding: 1px 6px;
  border-radius: 4px;
  background: #f1f5f9;
  color: #475569;
  border: 1px solid #e2e8f0;
}

.tc-subagent-mode-badge.general {
  background: #eff6ff;
  color: #2563eb;
  border-color: #bfdbfe;
}

.tc-subagent-target-text {
  font-size: 12px;
  line-height: 1.55;
  color: #334155;
  word-break: break-word;
}

/* 运行中脉冲条 */
.tc-subagent-running-pulse {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 8px 12px;
  border-radius: 6px;
  background: #eff6ff;
  border: 1px solid #bfdbfe;
  color: #1d4ed8;
  font-size: 12px;
}

/* 过程轨迹折叠区 */
.tc-subagent-trace-section {
  background: #f8fafc;
  border: 1px solid #e2e8f0;
  border-radius: 6px;
  overflow: hidden;
}

.tc-subagent-trace-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 7px 12px;
  cursor: pointer;
  background: #f8fafc;
  user-select: none;
  transition: background 0.15s;
}

.tc-subagent-trace-header:hover {
  background: #f1f5f9;
}

.tc-subagent-trace-title {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 11px;
  font-weight: 600;
  color: #64748b;
}

.tc-subagent-trace-title svg {
  color: #7c3aed;
}

.tc-subagent-trace-toggle {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  border: none;
  background: transparent;
  color: #64748b;
  font-size: 11px;
  cursor: pointer;
  padding: 0;
}

.tc-subagent-trace-list {
  display: flex;
  flex-direction: column;
  gap: 5px;
  padding: 8px 12px;
  border-top: 1px solid #e2e8f0;
  max-height: 220px;
  overflow-y: auto;
  background: #ffffff;
}

.tc-subagent-trace-entry {
  display: grid;
  grid-template-columns: 60px minmax(0, 1fr);
  gap: 8px;
  align-items: start;
  font-size: 11px;
  line-height: 1.45;
}

.tc-subagent-trace-kind {
  color: #64748b;
  font-weight: 600;
}

.tc-subagent-trace-entry code {
  color: #334155;
  white-space: pre-wrap;
  word-break: break-all;
}

/* 系统底层真实报错 */
.tc-subagent-system-error {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 8px 12px;
  border-radius: 6px;
  background: #fef2f2;
  border: 1px solid #fecaca;
  color: #991b1b;
  font-size: 12px;
}

.tc-subagent-system-error svg {
  flex-shrink: 0;
}

.tc-subagent-error-msg {
  flex: 1;
  line-height: 1.45;
  word-break: break-word;
}

/* 核心成果交付卡片 (对齐主工作区 Markdown 美学) */
.tc-subagent-deliverable-card {
  position: relative;
  border: 1px solid #e2e8f0;
  border-radius: 8px;
  background: #ffffff;
  overflow: hidden;
  box-shadow: 0 1px 4px rgba(0, 0, 0, 0.04);
  transition: all 0.2s ease;
}

.tc-subagent-deliverable-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 8px 14px;
  background: #f8fafc;
  border-bottom: 1px solid #e2e8f0;
}

.tc-subagent-deliverable-title {
  display: flex;
  align-items: center;
  gap: 7px;
  font-size: 12px;
  font-weight: 600;
  color: #0f172a;
}

.tc-subagent-deliverable-title svg {
  color: #7c3aed;
}

.tc-subagent-deliverable-pill {
  font-size: 10px;
  font-weight: 700;
  text-transform: uppercase;
  padding: 1px 6px;
  border-radius: 4px;
  background: #ede9fe;
  color: #6d28d9;
  border: 1px solid #ddd6fe;
}

.tc-subagent-deliverable-tools {
  display: flex;
  align-items: center;
  gap: 6px;
}

.tc-subagent-tool-btn {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 3px 8px;
  border-radius: 5px;
  border: 1px solid #cbd5e1;
  background: #ffffff;
  color: #475569;
  font-size: 11px;
  font-weight: 500;
  cursor: pointer;
  transition: all 0.15s ease;
}

.tc-subagent-tool-btn:hover {
  color: #7c3aed;
  border-color: #7c3aed;
  background: #f5f3ff;
}

/* Markdown 正文视窗 */
.tc-subagent-markdown-body {
  padding: 14px 18px;
  font-size: 13px;
  line-height: 1.72;
  color: #1e293b;
  overflow-x: auto;
}

.tc-subagent-markdown-body.is-clamped {
  max-height: 480px;
  overflow: hidden;
}

/* 超长展开/收起渐变栏 */
.tc-subagent-expand-bar {
  cursor: pointer;
  transition: all 0.18s ease;
}

.tc-subagent-expand-bar:not(.is-expanded) {
  position: absolute;
  bottom: 0;
  left: 0;
  right: 0;
  height: 90px;
  background: linear-gradient(to bottom, rgba(255, 255, 255, 0) 0%, rgba(255, 255, 255, 0.95) 60%, #ffffff 100%);
  display: flex;
  align-items: flex-end;
  justify-content: center;
  padding-bottom: 12px;
}

.tc-subagent-expand-bar.is-expanded {
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 10px 0;
  border-top: 1px dashed #e2e8f0;
  background: #f8fafc;
}

.tc-subagent-toggle-report-btn {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  padding: 4px 14px;
  border-radius: 999px;
  background: #ffffff;
  border: 1px solid #7c3aed;
  color: #7c3aed;
  font-size: 11.5px;
  font-weight: 600;
  box-shadow: 0 2px 6px rgba(124, 58, 237, 0.12);
  cursor: pointer;
  transition: all 0.15s ease;
}

.tc-subagent-toggle-report-btn:hover {
  background: #7c3aed;
  color: #ffffff;
}

/* 关联文件区 */
.tc-subagent-files-section {
  background: #f8fafc;
  border: 1px solid #e2e8f0;
  border-radius: 7px;
  padding: 9px 12px;
}

.tc-subagent-files-title {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 11px;
  font-weight: 600;
  color: #64748b;
  margin-bottom: 8px;
}

.tc-subagent-files-title svg {
  color: #7c3aed;
}

.tc-subagent-files-chips {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}

.tc-subagent-file-chip {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  padding: 3px 8px;
  border-radius: 4px;
  background: #ffffff;
  border: 1px solid #e2e8f0;
  color: #0f172a;
  font-size: 11.5px;
  font-family: 'JetBrains Mono', monospace;
  cursor: pointer;
  transition: all 0.15s ease;
}

.tc-subagent-file-chip:hover {
  background: #f5f3ff;
  border-color: #7c3aed;
  color: #7c3aed;
}

.tc-subagent-file-chip svg {
  color: #64748b;
}

.tc-subagent-file-chip:hover svg {
  color: #7c3aed;
}

/* ==================== 暗色模式自适应 (Dark Theme) ==================== */
:global(html[data-theme='dark'] .tc-card),
:root[data-theme='dark'] .tc-card {
  background: #1e1e2e;
  border-color: #313244;
  box-shadow: 0 1px 3px rgba(0, 0, 0, 0.3);
}

:global(html[data-theme='dark'] .tc-card:hover),
:root[data-theme='dark'] .tc-card:hover {
  border-color: #45475a;
}

:global(html[data-theme='dark'] .tc-header),
:root[data-theme='dark'] .tc-header {
  background: #1e1e2e;
}

:global(html[data-theme='dark'] .tc-tool-name),
:root[data-theme='dark'] .tc-tool-name {
  color: #edf1fb;
}

:global(html[data-theme='dark'] .tc-tool-arg-main),
:root[data-theme='dark'] .tc-tool-arg-main {
  color: #bac2de;
}

:global(html[data-theme='dark'] .tc-tool-sub-info),
:root[data-theme='dark'] .tc-tool-sub-info {
  color: #778195;
}

:global(html[data-theme='dark'] .tc-status-pill-badge),
:root[data-theme='dark'] .tc-status-pill-badge {
  background: #181825;
  border-color: #313244;
  color: #bac2de;
}

:global(html[data-theme='dark'] .tc-status-pill-badge.completed),
:root[data-theme='dark'] .tc-status-pill-badge.completed {
  color: #a6e3a1;
}

:global(html[data-theme='dark'] .tc-status-pill-badge.error),
:root[data-theme='dark'] .tc-status-pill-badge.error {
  background: rgba(243, 139, 168, 0.15);
  border-color: rgba(243, 139, 168, 0.35);
  color: #f38ba8;
}

:global(html[data-theme='dark'] .tc-subagent-header-open),
:root[data-theme='dark'] .tc-subagent-header-open {
  background: rgba(99, 102, 241, 0.2);
  border-color: rgba(99, 102, 241, 0.4);
  color: #c4b5fd;
}

:global(html[data-theme='dark'] .tc-subagent-header-open:hover),
:root[data-theme='dark'] .tc-subagent-header-open:hover {
  background: rgba(99, 102, 241, 0.35);
  border-color: #818cf8;
  color: #ffffff;
}

:global(html[data-theme='dark'] .tc-body),
:root[data-theme='dark'] .tc-body {
  background: #181825;
  border-top-color: #313244;
}

:global(html[data-theme='dark'] .tc-section-label),
:root[data-theme='dark'] .tc-section-label {
  color: #a6adc8;
}

:global(html[data-theme='dark'] .tc-btn-copy-result),
:root[data-theme='dark'] .tc-btn-copy-result {
  color: #778195;
}

:global(html[data-theme='dark'] .tc-btn-copy-result:hover),
:root[data-theme='dark'] .tc-btn-copy-result:hover {
  background: #242538;
  color: #edf1fb;
}

:root[data-theme='dark'] .tc-subagent-target-bar,
:root[data-theme='dark'] .tc-subagent-trace-section,
:root[data-theme='dark'] .tc-subagent-files-section {
  background: #181825;
  border-color: #313244;
}

:root[data-theme='dark'] .tc-subagent-target-label,
:root[data-theme='dark'] .tc-subagent-trace-title,
:root[data-theme='dark'] .tc-subagent-files-title {
  color: #a6adc8;
}

:root[data-theme='dark'] .tc-subagent-target-text {
  color: #cdd6f4;
}

:root[data-theme='dark'] .tc-subagent-mode-badge {
  background: #1e1e2e;
  border-color: #313244;
  color: #a6adc8;
}

:root[data-theme='dark'] .tc-subagent-trace-header {
  background: #181825;
}

:root[data-theme='dark'] .tc-subagent-trace-header:hover {
  background: #1e1e2e;
}

:root[data-theme='dark'] .tc-subagent-trace-list {
  background: #1e1e2e;
  border-top-color: #313244;
}

:root[data-theme='dark'] .tc-subagent-trace-kind {
  color: #a6adc8;
}

:root[data-theme='dark'] .tc-subagent-trace-entry code {
  color: #bac2de;
}

:root[data-theme='dark'] .tc-subagent-system-error {
  background: rgba(239, 68, 68, 0.12);
  border-color: rgba(239, 68, 68, 0.3);
  color: #f87171;
}

:root[data-theme='dark'] .tc-subagent-deliverable-card {
  background: #1e1e2e;
  border-color: #313244;
  box-shadow: 0 2px 10px rgba(0, 0, 0, 0.3);
}

:root[data-theme='dark'] .tc-subagent-deliverable-header {
  background: #181825;
  border-bottom-color: #313244;
}

:root[data-theme='dark'] .tc-subagent-deliverable-title {
  color: #cdd6f4;
}

:root[data-theme='dark'] .tc-subagent-deliverable-pill {
  background: rgba(124, 58, 237, 0.2);
  color: #c4b5fd;
  border-color: rgba(124, 58, 237, 0.4);
}

:root[data-theme='dark'] .tc-subagent-tool-btn {
  background: #242538;
  border-color: #3b3d54;
  color: #a6adc8;
}

:root[data-theme='dark'] .tc-subagent-tool-btn:hover {
  background: #313244;
  color: #c4b5fd;
  border-color: #7c3aed;
}

:root[data-theme='dark'] .tc-subagent-markdown-body {
  color: #cdd6f4;
}

:root[data-theme='dark'] .tc-subagent-expand-bar:not(.is-expanded) {
  background: linear-gradient(to bottom, rgba(30, 30, 46, 0) 0%, rgba(30, 30, 46, 0.95) 60%, #1e1e2e 100%);
}

:root[data-theme='dark'] .tc-subagent-expand-bar.is-expanded {
  background: #181825;
  border-top-color: #313244;
}

:root[data-theme='dark'] .tc-subagent-toggle-report-btn {
  background: #1e1e2e;
  color: #c4b5fd;
  border-color: #7c3aed;
}

:root[data-theme='dark'] .tc-subagent-toggle-report-btn:hover {
  background: #7c3aed;
  color: #ffffff;
}

:root[data-theme='dark'] .tc-subagent-file-chip {
  background: #181825;
  border-color: #313244;
  color: #cdd6f4;
}

:root[data-theme='dark'] .tc-subagent-file-chip:hover {
  background: #242538;
  border-color: #7c3aed;
  color: #c4b5fd;
}
</style>
