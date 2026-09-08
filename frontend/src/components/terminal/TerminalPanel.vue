<template>
  <div class="terminal-panel" :class="{ 'terminal-dark': isDark }">
    <!-- 顶部工具栏 -->
    <div class="terminal-toolbar">
      <div class="terminal-tabs">
        <div
          v-for="term in terminals"
          :key="term.id"
          class="terminal-tab"
          :class="{ active: term.id === activeTerminalId }"
          @click="switchTerminal(term.id)"
          @contextmenu.prevent="showTabMenu($event, term)"
        >
          <el-icon class="tab-icon"><Monitor /></el-icon>
          <span class="tab-name">{{ term.name }}</span>
          <span
            class="tab-close"
            @click.stop="closeTerminal(term.id)"
            title="关闭终端"
          >
            <el-icon><Close /></el-icon>
          </span>
        </div>
      </div>
      <div class="terminal-actions">
        <el-tooltip content="新建终端" placement="bottom">
          <button class="action-btn" @click="createNewTerminal">
            <el-icon><Plus /></el-icon>
          </button>
        </el-tooltip>
        <el-tooltip content="分割终端" placement="bottom">
          <button class="action-btn" @click="splitTerminal">
            <el-icon><CopyDocument /></el-icon>
          </button>
        </el-tooltip>
        <el-tooltip content="清屏" placement="bottom">
          <button class="action-btn" @click="clearActiveTerminal">
            <el-icon><Delete /></el-icon>
          </button>
        </el-tooltip>
        <el-tooltip content="搜索" placement="bottom">
          <button class="action-btn" @click="toggleSearch">
            <el-icon><Search /></el-icon>
          </button>
        </el-tooltip>
        <el-tooltip content="终止进程" placement="bottom">
          <button class="action-btn danger" @click="killActiveTerminal">
            <el-icon><VideoPause /></el-icon>
          </button>
        </el-tooltip>
        <el-tooltip :content="isDark ? '切换亮色主题' : '切换暗色主题'" placement="bottom">
          <button class="action-btn" @click="toggleTheme">
            <el-icon><Sunny v-if="isDark" /><Moon v-else /></el-icon>
          </button>
        </el-tooltip>
      </div>
    </div>

    <!-- 搜索栏 -->
    <div v-if="showSearchBar" class="terminal-search">
      <el-input
        v-model="searchText"
        size="small"
        placeholder="搜索终端输出..."
        @keyup.enter="searchNext"
        @keyup.escape="toggleSearch"
        clearable
      >
        <template #prefix>
          <el-icon><Search /></el-icon>
        </template>
      </el-input>
      <button class="search-btn" @click="searchPrev">
        <el-icon><ArrowUp /></el-icon>
      </button>
      <button class="search-btn" @click="searchNext">
        <el-icon><ArrowDown /></el-icon>
      </button>
    </div>

    <div v-if="hasManagedTerminal" class="terminal-policy-notice" role="status">
      受管终端正在运行：命令会通过权限策略和 REST 会话执行。
    </div>

    <!-- 终端容器 -->
    <div class="terminal-container" ref="terminalContainerRef">
      <div
        v-for="host in terminalHosts"
        :key="host.id"
        class="terminal-instance"
        :class="{ visible: host.terminalId === activeTerminalId }"
        :ref="el => setTermRef(host.id, el)"
      />
      <!-- 空状态 -->
      <div v-if="terminals.length === 0" class="terminal-empty">
        <div class="empty-icon">>_</div>
        <div class="empty-text">暂无终端</div>
        <el-button type="primary" @click="createNewTerminal">
          <el-icon><Plus /></el-icon>
          新建终端
        </el-button>
      </div>
    </div>

  </div>
</template>

<script setup>
import { ref, watch, onMounted, onUnmounted, nextTick } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  Plus, Close, Monitor, CopyDocument, Delete,
  Search, ArrowUp, ArrowDown, VideoPause, Sunny, Moon
} from '@element-plus/icons-vue'
import {
  useTerminalManager,
  useTerminalWebSocket,
  VSCODE_DARK_THEME,
  VSCODE_LIGHT_THEME
} from '@/composables/useTerminal'
import { projectApi } from '@/api'
import { normalizeManagedTerminalResult } from '@/composables/terminalProtocol'

const props = defineProps({
  projectId: { type: [String, Number], required: true },
  projectPath: { type: String, default: '' },
  isDark: { type: Boolean, default: true },
  visible: { type: Boolean, default: false }
})

const emit = defineEmits(['terminal-created', 'terminal-closed', 'toggle-theme', 'command-finished'])

const {
  terminals,
  activeTerminalId,
  createTerminal,
  getTerminal,
  getActiveTerminal,
  setActiveTerminal,
  removeTerminal,
  fitTerminal,
  fitAllTerminals,
  setTerminalTheme,
  searchInTerminal,
  clearTerminal
} = useTerminalManager()

const terminalContainerRef = ref(null)
const termRefs = ref({})
const terminalHosts = ref([])
const showSearchBar = ref(false)
const searchText = ref('')
const currentCwd = ref('')
// Tracks whether any terminal is using managed REST mode (for the policy notice bar)
const hasManagedTerminal = ref(false)
let terminalHostCounter = 0

// Per-terminal WebSocket connections, keyed by terminal id
const wsConnections = new Map()

function setTermRef(id, el) {
  if (el) {
    termRefs.value[id] = el
  } else {
    delete termRefs.value[id]
  }
}

function switchTerminal(id) {
  setActiveTerminalId(id)
  nextTick(() => {
    fitTerminal(id)
    const termData = getTerminal(id)
    if (termData) {
      termData.terminal.focus()
    }
  })
}

function setActiveTerminalId(id) {
  setActiveTerminal(id)
}

// ─── Managed REST terminal helpers (fallback) ───

function renderManagedOutput(termData, output) {
  termData.terminal.reset()
  termData.terminal.write(output || '')
  termData.terminal.scrollToBottom()
}

async function resolveManagedApproval(result, termData) {
  let approved = false
  try {
    await ElMessageBox.confirm(
      `The command requires approval:\n${result.displayCommand || ''}`,
      'Command approval',
      { confirmButtonText: 'Approve and run', cancelButtonText: 'Reject', type: 'warning' }
    )
    approved = true
  } catch {
    // A cancelled dialog is an explicit rejection, not an application error.
  }

  await projectApi.terminalDecideApproval(props.projectId, result.approvalId, approved ? 'approve' : 'reject')
  if (!approved) {
    termData.terminal.write('\r\n[Command rejected]\r\n')
    return
  }
  const executed = await projectApi.terminalExecuteApproval(props.projectId, result.approvalId)
  renderManagedOutput(termData, executed.data?.output)
  renderManagedExecution(termData, executed.data)
}

function renderManagedExecution(termData, result = {}) {
  termData.lastExecution = normalizeManagedTerminalResult(result)
}

async function runManagedCommand(command, termData) {
  try {
    const response = await projectApi.terminalRunSession(props.projectId, termData.managedSessionId, command, {
      path: termData.managedPath,
      timeoutSeconds: termData.timeoutSeconds
    })
    const result = response.data || {}
    if (result.refused) {
      termData.terminal.write(`\r\n[Command blocked: ${result.reasonCode || 'policy'}]\r\n`)
      return
    }
    if (result.approvalRequired) {
      await resolveManagedApproval(result, termData)
      return
    }
    renderManagedOutput(termData, result.output)
    renderManagedExecution(termData, result)
  } finally {
    // 命令结束（含审批执行完成）后通知工作区刷新文件树，避免手动刷新。
    emit('command-finished')
  }
}

// ─── Terminal creation: WebSocket PTY first, REST fallback ───

async function createNewTerminal() {
  const hostId = `terminal-host-${++terminalHostCounter}`
  terminalHosts.value.push({ id: hostId, terminalId: null })
  await nextTick()

  const container = termRefs.value[hostId]
  if (!container) {
    terminalHosts.value = terminalHosts.value.filter(host => host.id !== hostId)
    ElMessage.error('Terminal container initialization failed')
    return
  }

  // Try WebSocket PTY mode first
  const ptySuccess = await tryCreatePtyTerminal(hostId, container)
  if (ptySuccess) return

  // Fallback to managed REST terminal
  await createManagedTerminal(hostId, container)
}

async function tryCreatePtyTerminal(hostId, container) {
  return new Promise((resolve) => {
    const termWs = useTerminalWebSocket(props.projectId)
    let termData = null
    let resolved = false

    function finish(success) {
      if (resolved) return
      resolved = true
      if (!success) {
        termWs.disconnect()
        if (termData) {
          removeTerminal(termData.id)
          termData = null
        }
      }
      resolve(success)
    }

    termWs.connect({
      onConnect() {
        // WebSocket connected — request PTY session from backend
        termWs.createTerminalSession(
          currentCwd.value,
          120,
          30
        )
      },

      onCreated(sid) {
        // Backend PTY session confirmed: create the xterm instance now
        termData = createTerminal(container, {
          name: `Terminal ${terminals.value.length + 1}`,
          theme: props.isDark ? VSCODE_DARK_THEME : VSCODE_LIGHT_THEME,
          onRawInput: (data) => termWs.sendInput(data)
        })

        termData.managedSessionId = sid
        const host = terminalHosts.value.find(item => item.id === hostId)
        if (host) host.terminalId = termData.id

        wsConnections.set(termData.id, termWs)

        // Sync resize events to PTY
        const resizeDisposable = termData.terminal.onResize(({ cols, rows }) => {
          termWs.resize(cols, rows)
        })
        termData.disposables.push(resizeDisposable)

        nextTick(() => {
          fitTerminal(termData.id)
          termData.terminal.focus()
        })

        emit('terminal-created', termData.id)
        finish(true)
      },

      onOutput(data) {
        if (termData) {
          termData.terminal.write(data)
        }
      },

      onExit(code) {
        if (termData) {
          termData.terminal.write(`\r\n\x1b[33m[Process exited with code ${code}]\x1b[0m\r\n`)
        }
      },

      onError(msg) {
        console.warn('[Terminal] WebSocket error:', msg)
        finish(false)
      },

      onDisabled() {
        finish(false)
      },

      onDisconnect() {
        if (termData) {
          termData.terminal.write('\r\n\x1b[31m[Terminal disconnected]\x1b[0m\r\n')
        } else {
          finish(false)
        }
      },

      onReconnectFailed() {
        if (termData) {
          termData.terminal.write('\r\n\x1b[31m[Reconnection failed. Please create a new terminal.]\x1b[0m\r\n')
        }
      }
    })

    // Timeout: if PTY session isn't established within 3 seconds, fall back to REST
    setTimeout(() => {
      if (!resolved) {
        finish(false)
      }
    }, 3000)
  })
}

async function createManagedTerminal(hostId, container) {
  hasManagedTerminal.value = true
  let session
  try {
    const response = await projectApi.terminalCreateSession(props.projectId)
    session = response.data
  } catch (error) {
    ElMessage.error(error?.message || 'Terminal session initialization failed')
    terminalHosts.value = terminalHosts.value.filter(host => host.id !== hostId)
    return
  }

  const termData = createTerminal(container, {
    projectId: props.projectId,
    managedSessionId: session.sessionId,
    managedPath: currentCwd.value,
    runManagedCommand,
    name: session.name || `Terminal ${terminals.value.length + 1}`,
    theme: props.isDark ? VSCODE_DARK_THEME : VSCODE_LIGHT_THEME
  })

  const host = terminalHosts.value.find(item => item.id === hostId)
  if (host) host.terminalId = termData.id
  renderManagedOutput(termData, session.output)
  renderManagedExecution(termData, session)
  await nextTick()
  fitTerminal(termData.id)
  termData.terminal.focus()

  emit('terminal-created', termData.id)
}

function splitTerminal() {
  createNewTerminal()
}

/** 资源管理器右键"在集成终端中打开"入口：先切换 cwd 再新建终端。 */
async function openTerminalAtPath(cwdPath) {
  if (typeof cwdPath === 'string' && cwdPath.trim()) {
    currentCwd.value = cwdPath.trim().replace(/\\/g, '/').replace(/^\/+/, '')
  }
  await createNewTerminal()
}

async function closeTerminal(id) {
  const termData = getTerminal(id)

  // Clean up WebSocket connection if PTY mode
  const termWs = wsConnections.get(id)
  if (termWs) {
    termWs.closeTerminal()
    termWs.disconnect()
    wsConnections.delete(id)
  }

  removeTerminal(id)
  terminalHosts.value = terminalHosts.value.filter(host => host.terminalId !== id)

  if (termData?.managedSessionId && !termData.isPtyMode) {
    try {
      await projectApi.terminalDeleteSession(props.projectId, termData.managedSessionId)
    } catch {
      // The local xterm has already been disposed; backend cleanup can be retried on the next session.
    }
  }

  // Update managed terminal flag
  hasManagedTerminal.value = terminals.value.some(t => !t.isPtyMode)

  if (terminals.value.length > 0 && activeTerminalId.value) {
    nextTick(() => {
      fitTerminal(activeTerminalId.value)
      const active = getActiveTerminal()
      active?.terminal?.focus()
    })
  }

  emit('terminal-closed', id)
}

function clearActiveTerminal() {
  if (activeTerminalId.value) {
    clearTerminal(activeTerminalId.value)
  }
}

async function killActiveTerminal() {
  const active = getActiveTerminal()
  if (!active) return

  // PTY mode: close the PTY session via WebSocket
  const termWs = wsConnections.get(active.id)
  if (termWs) {
    termWs.closeTerminal()
    return
  }

  // Managed REST mode: stop the running command
  if (!active.managedSessionId) return
  try {
    const response = await projectApi.terminalStopSession(props.projectId, active.managedSessionId)
    const result = response?.data || {}
    renderManagedOutput(active, result.session?.output ?? result.output)
    renderManagedExecution(active, result.session || result)
  } catch (error) {
    ElMessage.error(error?.message || 'Failed to stop terminal command')
  }
}

function toggleSearch() {
  showSearchBar.value = !showSearchBar.value
}

function toggleTheme() {
  emit('toggle-theme')
}

function searchNext() {
  if (activeTerminalId.value && searchText.value) {
    searchInTerminal(activeTerminalId.value, searchText.value)
  }
}

function searchPrev() {
  // TODO: 实现向上搜索
  searchNext()
}

function showTabMenu(event, term) {
  // TODO: 实现右键菜单
}

// 响应窗口大小变化
let resizeObserver = null
onMounted(() => {
  resizeObserver = new ResizeObserver(() => {
    fitAllTerminals()
  })
  if (terminalContainerRef.value) {
    resizeObserver.observe(terminalContainerRef.value)
  }

  // 按需创建第一个终端（若面板处于可见状态）
  nextTick(() => {
    if (props.visible && terminals.value.length === 0) {
      createNewTerminal()
    }
  })
})

watch(() => props.visible, (val) => {
  if (val) {
    nextTick(() => {
      if (terminals.value.length === 0) {
        createNewTerminal()
      } else {
        fitAllTerminals()
      }
    })
  }
})

onUnmounted(() => {
  if (resizeObserver) {
    resizeObserver.disconnect()
  }
  // Clean up all WebSocket connections
  for (const [id, termWs] of wsConnections) {
    termWs.closeTerminal()
    termWs.disconnect()
  }
  wsConnections.clear()
})

watch(() => props.isDark, isDark => {
  setTerminalTheme(isDark ? VSCODE_DARK_THEME : VSCODE_LIGHT_THEME)
}, { immediate: true })

// 暴露方法给父组件
defineExpose({
  createNewTerminal,
  openTerminalAtPath,
  closeTerminal,
  clearTerminal,
  writeToTerminal: (id, data) => {
    const termData = getTerminal(id)
    if (termData) termData.terminal.write(data)
  },
  fitAllTerminals
})
</script>

<style scoped>
.terminal-panel {
  --terminal-bg: #1e1e1e;
  --terminal-toolbar-bg: #252526;
  --terminal-border: #3c3c3c;
  --terminal-text: #d4d4d4;
  --terminal-muted: #969696;
  --terminal-hover: #3c3c3c;
  --terminal-active: #1e1e1e;
  --terminal-scrollbar: #5a5a5a;
  display: flex;
  flex-direction: column;
  height: 100%;
  min-height: 0;
  overflow: hidden;
  background: var(--terminal-bg);
  color: var(--terminal-text);
  font-family: 'JetBrains Mono', 'Cascadia Code', 'Fira Code', 'Consolas', monospace;
}
.terminal-panel:not(.terminal-dark) {
  --terminal-bg: #ffffff;
  --terminal-toolbar-bg: #f6f8fa;
  --terminal-border: #d0d7de;
  --terminal-text: #24292f;
  --terminal-muted: #57606a;
  --terminal-hover: #eaeef2;
  --terminal-active: #ffffff;
  --terminal-scrollbar: #8c959f;
}

.terminal-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  height: 35px;
  background: var(--terminal-toolbar-bg);
  border-bottom: 1px solid var(--terminal-border);
  padding: 0 8px;
  flex-shrink: 0;
}

.terminal-tabs {
  display: flex;
  align-items: center;
  gap: 2px;
  overflow-x: auto;
  flex: 1;
}

.terminal-tabs::-webkit-scrollbar {
  height: 3px;
}

.terminal-tabs::-webkit-scrollbar-thumb {
  background: var(--terminal-scrollbar);
  border-radius: 2px;
}

.terminal-tab {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 4px 12px;
  background: transparent;
  border: none;
  color: var(--terminal-muted);
  cursor: pointer;
  font-size: 12px;
  white-space: nowrap;
  border-radius: 4px 4px 0 0;
  transition: all 0.15s;
  position: relative;
}

.terminal-tab:hover {
  background: var(--terminal-hover);
  color: var(--terminal-text);
}

.terminal-tab.active {
  background: var(--terminal-active);
  color: var(--terminal-text);
}

.terminal-tab.active::after {
  content: '';
  position: absolute;
  bottom: 0;
  left: 0;
  right: 0;
  height: 2px;
  background: #007acc;
}

.tab-icon {
  font-size: 14px;
}

.tab-name {
  max-width: 120px;
  overflow: hidden;
  text-overflow: ellipsis;
}

.tab-close {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 16px;
  height: 16px;
  border-radius: 3px;
  opacity: 0;
  transition: all 0.15s;
}

.terminal-tab:hover .tab-close {
  opacity: 0.7;
}

.tab-close:hover {
  background: var(--terminal-scrollbar);
  opacity: 1 !important;
}

.terminal-actions {
  display: flex;
  align-items: center;
  gap: 2px;
}

.action-btn {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 28px;
  height: 28px;
  background: transparent;
  border: none;
  color: var(--terminal-muted);
  cursor: pointer;
  border-radius: 4px;
  transition: all 0.15s;
}

.action-btn:hover {
  background: var(--terminal-hover);
  color: var(--terminal-text);
}

.action-btn.danger:hover {
  background: #5a1d1d;
  color: #f14c4c;
}

.terminal-search {
  display: flex;
  align-items: center;
  gap: 4px;
  padding: 4px 8px;
  background: var(--terminal-toolbar-bg);
  border-bottom: 1px solid var(--terminal-border);
}

.search-btn {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 24px;
  height: 24px;
  background: transparent;
  border: none;
  color: var(--terminal-muted);
  cursor: pointer;
  border-radius: 3px;
}

.search-btn:hover {
  background: var(--terminal-hover);
  color: var(--terminal-text);
}

.terminal-policy-notice {
  padding: 6px 10px;
  background: #3d3215;
  color: #f5d06f;
  border-bottom: 1px solid #725c23;
  font-size: 12px;
}

.terminal-container {
  flex: 1;
  min-height: 0;
  position: relative;
  overflow: hidden;
}

.terminal-instance {
  position: absolute;
  top: 0;
  left: 0;
  right: 0;
  bottom: 0;
  display: none;
  padding: 4px;
}

.terminal-instance.visible {
  display: block;
}

.terminal-instance :deep(.xterm) {
  height: 100%;
}

.terminal-instance :deep(.xterm-viewport) {
  overflow-y: auto !important;
}

.terminal-instance :deep(.xterm-viewport::-webkit-scrollbar) {
  width: 8px;
}

.terminal-instance :deep(.xterm-viewport::-webkit-scrollbar-thumb) {
  background: var(--terminal-scrollbar);
  border-radius: 4px;
}

.terminal-instance :deep(.xterm-viewport::-webkit-scrollbar-thumb:hover) {
  background: var(--terminal-muted);
}

.terminal-empty {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  height: 100%;
  gap: 16px;
}

.empty-icon {
  font-size: 48px;
  color: var(--terminal-border);
  font-family: monospace;
}

.empty-text {
  color: var(--terminal-muted);
  font-size: 14px;
}

</style>
