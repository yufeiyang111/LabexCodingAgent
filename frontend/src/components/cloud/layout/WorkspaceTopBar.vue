<template>
  <header class="ws-topbar">
    <div class="topbar-left">
      <button class="topbar-btn" @click="emit('go-back')" title="返回项目列表">
        <span class="icon">
          <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><polyline points="15 18 9 12 15 6"/></svg>
        </span>
        <span>返回</span>
      </button>
      <div class="breadcrumb-pill">
        <span class="icon">
          <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M22 19a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h5l2 3h9a2 2 0 0 1 2 2z"/></svg>
        </span>
        <span>{{ projectName || '工作空间' }}</span>
        <template v-if="activePath">
          <span class="breadcrumb-slash">/</span>
          <span class="active-file-tag">{{ fileName }}</span>
        </template>
      </div>
    </div>

    <!-- VS Code 风格布局三大开关 -->
    <div class="topbar-center">
      <div class="layout-toggle-group" role="group" aria-label="工作区布局开关">
        <button
          class="layout-toggle-btn"
          :class="{ active: explorerVisible }"
          @click="emit('toggle-explorer')"
          title="开关资源管理器 (Ctrl+B)"
        >
          <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><rect x="3" y="3" width="18" height="18" rx="2"/><path d="M9 3v18"/></svg>
        </button>
        <button
          class="layout-toggle-btn"
          :class="{ active: terminalVisible }"
          @click="emit('toggle-terminal')"
          title="开关底部终端 (Ctrl+`)"
        >
          <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><rect x="3" y="3" width="18" height="18" rx="2"/><path d="M3 15h18"/></svg>
        </button>
        <button
          class="layout-toggle-btn"
          :class="{ active: previewVisible }"
          @click="emit('toggle-preview')"
          title="开关 Web 实时预览视窗"
        >
          <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><rect x="2" y="3" width="20" height="14" rx="2" ry="2"/><line x1="8" y1="21" x2="16" y2="21"/><line x1="12" y1="17" x2="12" y2="21"/></svg>
        </button>
        <button
          class="layout-toggle-btn"
          :class="{ active: aiPanelVisible }"
          @click="emit('toggle-ai-panel')"
          title="开关 AI 助手面板 (Ctrl+Shift+L)"
        >
          <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><rect x="3" y="3" width="18" height="18" rx="2"/><path d="M15 3v18"/></svg>
        </button>
      </div>
    </div>

    <div class="topbar-right">
      <slot name="theme-button">
        <button class="topbar-btn ws-btn ws-btn-outline ws-btn-sm ws-theme-settings-btn" @click="emit('open-theme-settings')" title="主题设置">
          <span class="icon">
            <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><circle cx="12" cy="12" r="3"/><path d="M19.4 15a1.65 1.65 0 0 0 .33 1.82l.06.06a2 2 0 0 1 0 2.83 2 2 0 0 1-2.83 0l-.06-.06a1.65 1.65 0 0 0-1.82-.33 1.65 1.65 0 0 0-1 1.51V21a2 2 0 0 1-2 2 2 2 0 0 1-2-2v-.09A1.65 1.65 0 0 0 9 19.4a1.65 1.65 0 0 0-1.82.33l-.06.06a2 2 0 0 1-2.83 0 2 2 0 0 1 0-2.83l.06-.06A1.65 1.65 0 0 0 4.68 15a1.65 1.65 0 0 0-1.51-1H3a2 2 0 0 1-2-2 2 2 0 0 1 2-2h.09A1.65 1.65 0 0 0 4.6 9a1.65 1.65 0 0 0-.33-1.82l-.06-.06a2 2 0 0 1 0-2.83 2 2 0 0 1 2.83 0l.06.06A1.65 1.65 0 0 0 9 4.68a1.65 1.65 0 0 0 1-1.51V3a2 2 0 0 1 2-2 2 2 0 0 1 2 2v.09a1.65 1.65 0 0 0 1 1.51 1.65 1.65 0 0 0 1.82-.33l.06-.06a2 2 0 0 1 2.83 0 2 2 0 0 1 0 2.83l-.06.06A1.65 1.65 0 0 0 19.4 9a1.65 1.65 0 0 0 1.51 1H21a2 2 0 0 1 2 2 2 2 0 0 1-2 2h-.09a1.65 1.65 0 0 0-1.51 1z"/></svg>
          </span>
          <span>主题</span>
        </button>
      </slot>

      <button class="topbar-btn" @click="emit('open-tutorials')" title="使用教程">
        <span class="icon">
          <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M2 3h6a4 4 0 0 1 4 4v14a3 3 0 0 0-3-3H2z"/><path d="M22 3h-6a4 4 0 0 0-4 4v14a3 3 0 0 1 3-3h7z"/></svg>
        </span>
        <span>教程</span>
      </button>

      <button class="topbar-btn" @click="emit('export-project')" title="导出项目压缩包">
        <span class="icon">
          <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"/><polyline points="7 10 12 15 17 10"/><line x1="12" y1="15" x2="12" y2="3"/></svg>
        </span>
        <span>导出</span>
      </button>

      <span v-if="fileContentDirty" class="unsaved-badge">未保存</span>

      <button
        v-if="activePath"
        class="topbar-btn solid-black"
        :disabled="savingFile"
        @click="emit('save-file')"
      >
        <span>{{ savingFile ? '保存中...' : '保存' }}</span>
      </button>
    </div>
  </header>
</template>

<script setup>
import { computed } from 'vue'

const props = defineProps({
  projectName: {
    type: String,
    default: '',
  },
  activePath: {
    type: String,
    default: '',
  },
  fileContentDirty: {
    type: Boolean,
    default: false,
  },
  savingFile: {
    type: Boolean,
    default: false,
  },
  explorerVisible: {
    type: Boolean,
    default: true,
  },
  terminalVisible: {
    type: Boolean,
    default: false,
  },
  previewVisible: {
    type: Boolean,
    default: false,
  },
  aiPanelVisible: {
    type: Boolean,
    default: true,
  },
})

const emit = defineEmits([
  'go-back',
  'toggle-explorer',
  'toggle-terminal',
  'toggle-preview',
  'toggle-ai-panel',
  'open-theme-settings',
  'open-tutorials',
  'export-project',
  'save-file',
])

const fileName = computed(() => {
  if (!props.activePath) return ''
  return props.activePath.split('/').pop() || props.activePath
})
</script>

<style scoped>
.ws-topbar {
  height: 44px;
  min-height: 44px;
  background: var(--bg-app, #ffffff);
  border-bottom: 1px solid var(--border-light, #e4e4e7);
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 12px;
  user-select: none;
  z-index: 50;
}

.topbar-left,
.topbar-center,
.topbar-right {
  display: flex;
  align-items: center;
  gap: 8px;
}

.topbar-btn {
  height: 28px;
  padding: 0 9px;
  display: inline-flex;
  align-items: center;
  gap: 6px;
  background: var(--bg-app, #ffffff);
  border: 1px solid var(--border-light, #e4e4e7);
  border-radius: var(--radius-md, 7px);
  color: var(--text-secondary, #3f3f46);
  font-size: 12px;
  font-weight: 500;
  cursor: pointer;
  box-shadow: 0 1px 2px rgba(0, 0, 0, 0.03);
  transition: all 0.15s ease;
}

.topbar-btn:hover {
  background: var(--bg-hover, #f4f4f5);
  border-color: var(--border-medium, #d4d4d8);
  color: var(--text-primary, #09090b);
  box-shadow: 0 1px 3px rgba(0, 0, 0, 0.05);
}

.topbar-btn.solid-black {
  background: var(--theme-accent, #09090b);
  border-color: var(--theme-accent, #09090b);
  color: #ffffff;
  font-weight: 600;
}

.topbar-btn.solid-black:hover {
  background: var(--theme-accent-strong, #27272a);
}

.topbar-btn:disabled {
  opacity: 0.6;
  cursor: not-allowed;
}

.breadcrumb-pill {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 3px 9px;
  background: var(--bg-card-subtle, #fafafa);
  border: 1px solid var(--border-light, #e4e4e7);
  border-radius: var(--radius-md, 7px);
  font-size: 12px;
  color: var(--text-secondary, #3f3f46);
}

.breadcrumb-slash {
  color: #a1a1aa;
}

.active-file-tag {
  color: var(--text-primary, #09090b);
  font-weight: 600;
}

.layout-toggle-group {
  display: flex;
  align-items: center;
  background: var(--bg-card-subtle, #fafafa);
  border: 1px solid var(--border-light, #e4e4e7);
  border-radius: var(--radius-md, 7px);
  padding: 2px;
  gap: 2px;
}

.layout-toggle-btn {
  width: 26px;
  height: 24px;
  border-radius: 4px;
  border: 1px solid transparent;
  background: transparent;
  color: #71717a;
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
  transition: all 0.15s ease;
}

.layout-toggle-btn:hover {
  background: var(--bg-hover, #f4f4f5);
  color: var(--text-primary, #09090b);
}

.layout-toggle-btn.active {
  background: var(--bg-app, #ffffff);
  border-color: var(--border-light, #e4e4e7);
  color: var(--text-primary, #09090b);
  box-shadow: 0 1px 2px rgba(0, 0, 0, 0.03);
}

.unsaved-badge {
  font-size: 11px;
  color: #ea580c;
  font-weight: 600;
  padding: 2px 6px;
  background: #fff7ed;
  border: 1px solid #ffedd5;
  border-radius: 4px;
}

.icon {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
}

/* 暗色主题深度增强 */
:global(html[data-theme="dark"] .ws-topbar) {
  background: #11131a;
  border-bottom-color: #272a37;
}

:global(html[data-theme="dark"] .topbar-btn) {
  background: #181b24;
  border-color: #2e3547;
  color: #cdd6f4;
}

:global(html[data-theme="dark"] .topbar-btn:hover) {
  background: #222736;
  border-color: #3e4760;
  color: #ffffff;
}

:global(html[data-theme="dark"] .breadcrumb-pill) {
  background: #181b24;
  border-color: #2e3547;
  color: #a6adc8;
}

:global(html[data-theme="dark"] .breadcrumb-slash) {
  color: #6c7086;
}

:global(html[data-theme="dark"] .active-file-tag) {
  color: #89b4fa;
}

:global(html[data-theme="dark"] .layout-toggle-group) {
  background: #181b24;
  border-color: #2e3547;
}

:global(html[data-theme="dark"] .layout-toggle-btn) {
  color: #8c96a8;
}

:global(html[data-theme="dark"] .layout-toggle-btn:hover) {
  background: rgba(255, 255, 255, 0.08);
  color: #ffffff;
}

:global(html[data-theme="dark"] .layout-toggle-btn.active) {
  background: #202638;
  border-color: #3b486d;
  color: #818cf8;
}

:global(html[data-theme="dark"] .unsaved-badge) {
  background: rgba(234, 88, 12, 0.15);
  border-color: rgba(234, 88, 12, 0.35);
  color: #fb923c;
}

@media (max-width: 768px) {
  .ws-topbar {
    padding: 0 8px;
    gap: 4px;
    height: 42px;
    min-height: 42px;
  }
  .topbar-center {
    display: none !important;
  }
  .topbar-left,
  .topbar-right {
    gap: 4px;
  }
  .breadcrumb-pill {
    max-width: 130px;
    padding: 2px 6px;
    font-size: 11px;
    overflow: hidden;
  }
  .breadcrumb-pill span:not(.icon):not(.active-file-tag) {
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }
  .topbar-btn {
    padding: 0 7px;
    height: 28px;
  }
  .topbar-btn:not(.solid-black) span:not(.icon) {
    display: none;
  }
  .topbar-btn.solid-black {
    padding: 0 9px;
    font-size: 11px;
  }
  .unsaved-badge {
    padding: 1px 4px;
    font-size: 10px;
  }
}
</style>
