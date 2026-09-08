<template>
  <div class="ftn">
    <div v-if="node.type === 'directory'" class="ftn-row dir" :class="{ selected: isDirSelected }" @click.stop="toggle" @contextmenu.prevent="openContextMenu($event, node)" tabindex="-1">
      <span class="ftn-arrow" :class="{ open: expanded }">
        <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5"><polyline points="9 18 15 12 9 6"/></svg>
      </span>
      <span class="ftn-folder-icon" :class="{ open: expanded }">
        <FileIcon kind="folder" :size="14" :open="expanded" :selected="isDirSelected" />
      </span>
      <span class="ftn-name">{{ displayName }}</span>
      <span v-if="node.fileCount" class="ftn-count">{{ node.fileCount }}</span>
      <button v-if="showActions" class="ftn-action-btn" title="新建" @click.stop="$emit('newItem', node.path, 'directory')">
        <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><line x1="12" y1="5" x2="12" y2="19"/><line x1="5" y1="12" x2="19" y2="12"/></svg>
      </button>
    </div>
    <Transition name="ftn-slide">
      <div v-if="expanded" class="ftn-children">
        <div v-if="loading" class="ftn-loading"><span class="ftn-spinner"></span><span>加载中...</span></div>
        <template v-else>
          <TransitionGroup name="ftn-list" tag="div">
            <FileTreeNode
              v-for="child in children"
              :key="child.path"
              :node="child"
              :depth="depth + 1"
              :selected-path="selectedPath"
              :load-children="loadChildren"
              :show-actions="showActions"
              :refresh-key="refreshKey"
              :collapse-key="collapseKey"
              :has-clipboard="hasClipboard"
              @select="(p) => emit('select', p)"
              @newItem="(p, t) => emit('newItem', p, t)"
              @rename="(p, n) => emit('rename', p, n)"
              @delete="(p) => emit('delete', p)"
              @menu-action="(payload) => emit('menu-action', payload)"
            />
          </TransitionGroup>
          <button v-if="nextOffset !== null" class="ftn-load-more" type="button" @click.stop="loadMore">加载更多</button>
        </template>
      </div>
    </Transition>
    <div v-if="node.type === 'file'" class="ftn-row file" :class="{ selected: node.path === selectedPath }" @click.stop="emit('select', node.path)" @contextmenu.prevent="openContextMenu($event, node)" tabindex="-1">
      <span class="ftn-file-icon"><FileIcon :name="node.name" :size="14" :selected="node.path === selectedPath" /></span>
      <span class="ftn-name">{{ node.name }}</span>
    </div>
    <FileContextMenu
      :visible="contextMenu.visible"
      :x="contextMenu.x"
      :y="contextMenu.y"
      :node="contextMenu.node"
      :has-clipboard="hasClipboard"
      @action="onMenuAction"
      @close="closeContextMenu"
    />
  </div>
</template>
<script setup>
import { ref, computed, watch } from 'vue'
import FileIcon from '../icons/FileIcon.vue'
import FileContextMenu from '../cloud/FileContextMenu.vue'

/**
 * 树节点只负责渲染、懒加载与右键入口；菜单项与动作定义集中在 FileContextMenu，
 * 增删改走既有事件（newItem/rename/delete），其余动作统一经 menu-action 上抛，
 * 由 CloudWorkspace 集中分发到对应 composable。
 */
const props = defineProps({
  node: { type: Object, required: true },
  depth: { type: Number, default: 0 },
  selectedPath: { type: String, default: '' },
  loadChildren: { type: Function, default: null },
  showActions: { type: Boolean, default: false },
  refreshKey: { type: [Number, String], default: 0 },
  collapseKey: { type: Number, default: 0 },
  hasClipboard: { type: Boolean, default: false }
})
const emit = defineEmits(['select', 'newItem', 'rename', 'delete', 'menu-action'])
const expanded = ref(false)
const children = ref([])
const loading = ref(false)
const loaded = ref(false)
const nextOffset = ref(null)
const compactPath = ref(null)
const isDirSelected = computed(() => {
  if (!props.selectedPath) return false
  const sel = props.selectedPath.trim()
  if (sel === '' || sel === '/') return false
  const nodePath = props.node.path.replace(/\/+$/, '')
  const selClean = sel.replace(/\/+$/, '')
  return selClean === nodePath || selClean.startsWith(nodePath + '/')
})
const contextMenu = ref({ visible: false, x: 0, y: 0, node: null })

const displayName = computed(() => compactPath.value || props.node.name)

watch(() => props.node.path, (newPath, oldPath) => {
  if (newPath !== oldPath) {
    children.value = []
    loaded.value = false
    nextOffset.value = null
    expanded.value = false
  }
})

watch(() => props.refreshKey, () => {
  if (props.node.type !== 'directory') return
  if (expanded.value) {
    reloadChildren()
  } else {
    children.value = []
    loaded.value = false
    nextOffset.value = null
  }
})

watch(() => props.collapseKey, () => {
  if (props.node.type !== 'directory') return
  expanded.value = false
  children.value = []
  loaded.value = false
  nextOffset.value = null
})

async function loadPage(offset = 0, append = false) {
  if (!props.loadChildren || props.node.type !== 'directory' || loading.value) return
  loading.value = true
  try {
    const result = await props.loadChildren(props.node.path, offset)
    const page = Array.isArray(result) ? { entries: result, nextOffset: null } : (result || {})
    const entries = page.entries || []
    children.value = append ? [...children.value, ...entries] : entries
    nextOffset.value = page.nextOffset ?? null
    loaded.value = true
    if (!append && entries.length === 1 && entries[0].type === 'directory') {
      compactPath.value = props.node.name + '\\' + entries[0].name
    } else {
      compactPath.value = null
    }
  } finally {
    loading.value = false
  }
}

async function reloadChildren() {
  nextOffset.value = null
  loaded.value = false
  await loadPage(0)
}

async function loadMore() {
  if (nextOffset.value === null) return
  await loadPage(nextOffset.value, true)
}

async function toggle() {
  expanded.value = !expanded.value
  if (expanded.value && !loaded.value) {
    await reloadChildren()
  }
}
function openContextMenu(e, node) {
  contextMenu.value = { visible: true, x: e.clientX, y: e.clientY, node }
}
function closeContextMenu() { contextMenu.value.visible = false }

/** 新建/重命名/删除仍走既有模态框链路；其余动作原样上抛。 */
function onMenuAction({ action, node }) {
  if (!node) return
  if (action === 'new-file') return emit('newItem', node.path, 'file')
  if (action === 'new-folder') return emit('newItem', node.path, 'directory')
  if (action === 'rename') return emit('rename', node.path, node.name)
  if (action === 'delete') return emit('delete', node.path)
  emit('menu-action', { action, node })
}
</script>
<style scoped>
.ftn { user-select: none; font-size: 13px; }
.ftn-row { display: flex; align-items: center; gap: 6px; padding: 4px 8px; border-radius: 8px; cursor: pointer; color: var(--theme-text, #3f3f46); transition: all 0.12s ease; margin: 2px 0; outline: none; border: 1px solid transparent; }
.ftn-row:hover { background: var(--bg-hover, #f4f4f5); color: var(--text-primary, #18181b); }
.ftn-row:focus, .ftn-row:focus-visible, .ftn-row:active { outline: none !important; }
.ftn-row.selected { background: var(--theme-accent-soft, #f4f4f5); border-color: var(--border-light, #e4e4e7); color: var(--theme-text, #09090b); font-weight: 600; box-shadow: 0 1px 2px rgba(0,0,0,0.03); }
.ftn-row.selected .ftn-name { color: var(--theme-text, #09090b); font-weight: 600; }
.ftn-row.dir.selected { background: var(--theme-accent-soft, #f4f4f5); border-color: var(--border-light, #e4e4e7); }
.ftn-arrow { width: 14px; height: 14px; display: flex; align-items: center; justify-content: center; flex-shrink: 0; color: var(--theme-text-muted, #71717a); transition: transform 0.2s cubic-bezier(0.34, 1.56, 0.64, 1); }
.ftn-arrow.open { transform: rotate(90deg); color: var(--theme-text, #18181b); }
.ftn-folder-icon { flex-shrink: 0; display: flex; align-items: center; transition: all 0.15s ease; color: #d97706; }
.ftn-file-icon { flex-shrink: 0; display: flex; align-items: center; }
.ftn-name { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; flex: 1; font-size: 13px; font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif; letter-spacing: -0.01em; color: var(--theme-text, #18181b); }
.ftn-count { font-size: 10px; color: var(--theme-text-muted, #a1a1aa); background: var(--theme-surface-muted, #f4f4f5); padding: 1px 6px; border-radius: 8px; flex-shrink: 0; }
.ftn-children { padding-left: 6px; margin-left: 8px; border-left: 1px solid var(--theme-border, #e4e4e7); }
.ftn-loading { display: flex; align-items: center; gap: 8px; padding: 6px 8px; color: var(--theme-text-muted, #71717a); font-size: 12px; }
.ftn-load-more { width: calc(100% - 8px); margin: 5px 4px; padding: 5px 8px; border: 1px solid var(--theme-border, #e4e4e7); border-radius: 6px; background: var(--theme-surface, #fff); color: var(--theme-text, #18181b); font: inherit; font-size: 12px; cursor: pointer; }
.ftn-load-more:hover { background: var(--theme-surface-muted, #f4f4f5); border-color: var(--border-medium, #d4d4d8); }
.ftn-spinner { width: 14px; height: 14px; border: 2px solid var(--theme-border, #e5e7eb); border-top-color: var(--theme-accent, #6366f1); border-radius: 50%; animation: ftn-spin 0.6s linear infinite; }
@keyframes ftn-spin { to { transform: rotate(360deg); } }
.ftn-action-btn { display: none; align-items: center; justify-content: center; width: 20px; height: 20px; border: none; background: transparent; color: var(--theme-text-muted, #9ca3af); cursor: pointer; border-radius: 4px; padding: 0; flex-shrink: 0; }
.ftn-row:hover .ftn-action-btn { display: flex; }
.ftn-action-btn:hover { background: rgba(0, 0, 0, 0.06); color: var(--theme-text, #4b5563); }
.ftn-slide-enter-active { transition: all 0.25s cubic-bezier(0.4, 0, 0.2, 1); overflow: hidden; }
.ftn-slide-leave-active { transition: all 0.15s cubic-bezier(0.4, 0, 0.2, 1); overflow: hidden; }
.ftn-slide-enter-from, .ftn-slide-leave-to { opacity: 0; max-height: 0; transform: translateY(-4px); }
.ftn-slide-enter-to, .ftn-slide-leave-from { opacity: 1; max-height: 2000px; transform: translateY(0); }
.ftn-list-enter-active { transition: all 0.2s ease; }
.ftn-list-leave-active { transition: all 0.15s ease; }
.ftn-list-enter-from { opacity: 0; transform: translateX(-8px); }
.ftn-list-leave-to { opacity: 0; transform: translateX(-4px); }
.ftn-list-move { transition: transform 0.2s ease; }

:global([data-theme="dark"]) .ftn-row,
:global(html[data-theme="dark"] .ftn-row) { color: #bac2de; }
:global([data-theme="dark"]) .ftn-name { color: #c0caf5; }
:global(html[data-theme="dark"] .ftn-name) { color: #edf1fb; }
:global([data-theme="dark"]) .ftn-folder-icon svg,
:global([data-theme="dark"]) .ftn-file-icon svg { stroke: #a9b1d6; }
:global(html[data-theme="dark"] .ftn-arrow) { color: #778195; }
:global(html[data-theme="dark"] .ftn-arrow.open) { color: #cdd6f4; }
:global(html[data-theme="dark"] .ftn-folder-icon) { color: #e5a93c; }
:global(html[data-theme="dark"] .ftn-row:hover) { background: rgba(255, 255, 255, 0.06); color: #ffffff; }
:global(html[data-theme="dark"] .ftn-row:hover .ftn-name) { color: #ffffff; }
:global(html[data-theme="dark"] .ftn-row.selected),
:global(html[data-theme="dark"] .ftn-row.dir.selected) { background: rgba(99, 102, 241, 0.25); border-color: rgba(99, 102, 241, 0.45); color: #ffffff; }
:global(html[data-theme="dark"] .ftn-row.selected .ftn-name) { color: #ffffff; font-weight: 600; }
:global(html[data-theme="dark"] .ftn-count) { background: #202432; color: #8c96a8; }
:global(html[data-theme="dark"] .ftn-children) { border-left-color: rgba(255, 255, 255, 0.08); }
:global(html[data-theme="dark"] .ftn-loading) { color: #8c96a8; }
:global(html[data-theme="dark"] .ftn-load-more) { background: #181b24; border-color: #272a37; color: #818cf8; }
:global(html[data-theme="dark"] .ftn-load-more:hover) { background: #202432; color: #a5b4fc; }
:global(html[data-theme="dark"] .ftn-action-btn:hover) { background: #2e3448; color: #ffffff; }
</style>
