<template>
  <div class="tc-card" :class="['tc-' + call.status, { 'is-expanded': expanded }]" :data-tool-call-id="call.toolCallId || ''">
    <!-- 卡片头部 (1:1 复刻用户设计图一) -->
    <div class="tc-header" @click="expanded = !expanded">
      <div class="tc-header-left">
        <!-- 专用语义化图标 -->
        <span v-if="isSearchTool" class="tc-type-icon search" title="搜索工具">
          <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5"><circle cx="11" cy="11" r="8"/><line x1="21" y1="21" x2="16.65" y2="16.65"/></svg>
        </span>
        <span v-else-if="isEditTool" class="tc-type-icon symbol" title="代码编辑">&lt;&gt;</span>
        <span v-else-if="isShellTool" class="tc-type-icon shell" title="终端命令">&gt;_</span>
        <span v-else-if="isReadTool" class="tc-type-icon read" title="文件读取">
          <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z"/><polyline points="14 2 14 8 20 8"/></svg>
        </span>
        <span v-else class="tc-type-icon default">⚡</span>

        <!-- 语义化标题结构 -->
        <div class="tc-title-tokens">
          <strong class="tc-tool-name" :class="{ 'is-running': call.status === 'running' }">{{ call.name || 'tool' }}:</strong>
          <span class="tc-tool-arg-main">{{ toolMainArg }}</span>
          <span v-if="toolSubInfo" class="tc-tool-sub-info">({{ toolSubInfo }})</span>
        </div>
      </div>

      <div class="tc-header-right">
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

        <!-- Args Section -->
        <div v-if="call.args && (!isEditTool || editDiffLines.length === 0)" class="tc-section">
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

        <!-- Result Section -->
        <div v-if="call.result && (!isEditTool || editDiffLines.length === 0)" class="tc-section">
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

const props = defineProps({
  call: {
    type: Object,
    required: true
  }
})

const emit = defineEmits(['permission', 'command-approval', 'question'])

const expanded = ref(false)
const answerDraft = ref('')
const commandSubmitting = ref(false)

const isEditTool = computed(() => ['edit_file', 'write_file', 'apply_patch'].includes(props.call.name))
const isShellTool = computed(() => ['shell', 'bash', 'run_command', 'execute_code', 'run_tests'].includes(props.call.name))
const isReadTool = computed(() => ['read_file', 'retrieve_context'].includes(props.call.name))
const isSearchTool = computed(() => ['grep_search', 'list_files', 'glob', 'grep', 'search_code', 'lsp_symbols'].includes(props.call.name))

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
  if (!props.call.result || typeof props.call.result !== 'string') return []
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
    toolCallId: props.call.toolCallId,
    action,
    permissionRequest: props.call.permissionRequest,
    networkRequest: props.call.networkRequest
  })
}

function emitCommandApproval(action) {
  commandSubmitting.value = true
  emit('command-approval', {
    toolCallId: props.call.toolCallId,
    action,
    commandApproval: props.call.commandApproval
  })
  setTimeout(() => { commandSubmitting.value = false }, 1000)
}

function emitQuestion(action) {
  if (!questionRequestReady.value) return
  emit('question', {
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
  transition: border-color 0.16s ease, box-shadow 0.16s ease;
  user-select: none;
}

.tc-card:hover {
  border-color: #d4d4d8;
}

.tc-card.is-expanded {
  border-color: #d4d4d8;
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.04);
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
  background: #18181b;
  color: #f8fafc;
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
  max-height: 600px;
  opacity: 1;
}
</style>
