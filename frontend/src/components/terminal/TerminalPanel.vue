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

    <!-- 终端容器 -->
    <div class="terminal-container" ref="terminalContainerRef">
      <div
        v-for="term in terminals"
        :key="term.id"
        class="terminal-instance"
        :class="{ visible: term.id === activeTerminalId }"
        :ref="el => setTermRef(term.id, el)"
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

    <!-- 状态栏 -->
    <div class="terminal-statusbar">
      <span class="status-item">
        <el-icon><Connection /></el-icon>
        {{ isConnected ? '已连接' : '未连接' }}
      </span>
      <span v-if="activeTerminal" class="status-item">
        {{ activeTerminal.name }}
      </span>
      <span v-if="currentCwd" class="status-item cwd">
        {{ currentCwd }}
      </span>
      <span class="status-spacer" />
      <span class="status-item">
        {{ terminals.length }} 个终端
      </span>
    </div>
  </div>
</template>

<script setup>
import { ref, watch, onMounted, onUnmounted, nextTick, computed } from 'vue'
import { ElMessage } from 'element-plus'
import {
  Plus, Close, Monitor, CopyDocument, Delete,
  Search, ArrowUp, ArrowDown, Connection, VideoPause
} from '@element-plus/icons-vue'
import { useTerminalManager } from '@/composables/useTerminal'

const props = defineProps({
  projectId: { type: [String, Number], required: true },
  projectPath: { type: String, default: '' },
  isDark: { type: Boolean, default: true }
})

const emit = defineEmits(['terminal-created', 'terminal-closed'])

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
  searchInTerminal,
  clearTerminal
} = useTerminalManager()

const terminalContainerRef = ref(null)
const termRefs = ref({})
const showSearchBar = ref(false)
const searchText = ref('')
const isConnected = computed(() => {
  const active = getActiveTerminal()
  return active?.wsConn?.connected?.value || false
})
const activeTerminal = computed(() => getActiveTerminal())
const currentCwd = ref(props.projectPath)

function setTermRef(id, el) {
  if (el) {
    termRefs.value[id] = el
  }
}

function switchTerminal(id) {
  setActiveTerminalId(id)
  nextTick(() => {
    fitTerminal(id)
    // 确保终端获得焦点
    const termData = getTerminal(id)
    if (termData) {
      termData.terminal.focus()
    }
  })
}

function setActiveTerminalId(id) {
  activeTerminalId.value = id
}

function createNewTerminal() {
  const container = termRefs.value[`new-${Date.now()}`] || null

  // 创建占位容器
  const div = document.createElement('div')
  div.className = 'terminal-instance visible'
  if (terminalContainerRef.value) {
    terminalContainerRef.value.appendChild(div)
  }

  const termData = createTerminal(div, {
    cwd: currentCwd.value,
    name: `Terminal ${terminals.value.length + 1}`
  })

  // 存储引用
  nextTick(() => {
    termRefs.value[termData.id] = div
    // 确保终端获得焦点
    setTimeout(() => {
      termData.terminal.focus()
    }, 200)
  })

  emit('terminal-created', termData.id)
  ElMessage.success('终端已创建')
}

function splitTerminal() {
  // 简单实现：创建新终端
  createNewTerminal()
}

function closeTerminal(id) {
  removeTerminal(id)
  // 移除 DOM 元素
  const el = termRefs.value[id]
  if (el && el.parentNode) {
    el.parentNode.removeChild(el)
  }
  delete termRefs.value[id]
  emit('terminal-closed', id)
}

function clearActiveTerminal() {
  if (activeTerminalId.value) {
    clearTerminal(activeTerminalId.value)
  }
}

function killActiveTerminal() {
  const active = getActiveTerminal()
  if (active) {
    active.wsConn.closeTerminal()
    active.terminal.write('\r\n\x1b[90m[Terminated]\x1b[0m\r\n')
  }
}

function toggleSearch() {
  showSearchBar.value = !showSearchBar.value
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

  // 自动创建第一个终端
  nextTick(() => {
    createNewTerminal()
  })
})

onUnmounted(() => {
  if (resizeObserver) {
    resizeObserver.disconnect()
  }
})

// 暴露方法给父组件
defineExpose({
  createNewTerminal,
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
  display: flex;
  flex-direction: column;
  height: 100%;
  background: #1e1e1e;
  color: #d4d4d4;
  font-family: 'JetBrains Mono', 'Cascadia Code', 'Fira Code', 'Consolas', monospace;
}

.terminal-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  height: 35px;
  background: #252526;
  border-bottom: 1px solid #3c3c3c;
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
  background: #5a5a5a;
  border-radius: 2px;
}

.terminal-tab {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 4px 12px;
  background: transparent;
  border: none;
  color: #969696;
  cursor: pointer;
  font-size: 12px;
  white-space: nowrap;
  border-radius: 4px 4px 0 0;
  transition: all 0.15s;
  position: relative;
}

.terminal-tab:hover {
  background: #2d2d2d;
  color: #cccccc;
}

.terminal-tab.active {
  background: #1e1e1e;
  color: #ffffff;
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
  background: #5a5a5a;
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
  color: #969696;
  cursor: pointer;
  border-radius: 4px;
  transition: all 0.15s;
}

.action-btn:hover {
  background: #3c3c3c;
  color: #cccccc;
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
  background: #252526;
  border-bottom: 1px solid #3c3c3c;
}

.search-btn {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 24px;
  height: 24px;
  background: transparent;
  border: none;
  color: #969696;
  cursor: pointer;
  border-radius: 3px;
}

.search-btn:hover {
  background: #3c3c3c;
  color: #cccccc;
}

.terminal-container {
  flex: 1;
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
  background: #5a5a5a;
  border-radius: 4px;
}

.terminal-instance :deep(.xterm-viewport::-webkit-scrollbar-thumb:hover) {
  background: #7a7a7a;
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
  color: #3c3c3c;
  font-family: monospace;
}

.empty-text {
  color: #666;
  font-size: 14px;
}

.terminal-statusbar {
  display: flex;
  align-items: center;
  gap: 12px;
  height: 22px;
  background: #007acc;
  color: #ffffff;
  padding: 0 8px;
  font-size: 11px;
  flex-shrink: 0;
}

.status-item {
  display: flex;
  align-items: center;
  gap: 4px;
  white-space: nowrap;
}

.status-item.cwd {
  opacity: 0.8;
  max-width: 300px;
  overflow: hidden;
  text-overflow: ellipsis;
}

.status-spacer {
  flex: 1;
}

/* 暗色主题覆盖 */
.terminal-dark .terminal-toolbar {
  background: #1e1e1e;
}

.terminal-dark .terminal-search {
  background: #1e1e1e;
}
</style>
