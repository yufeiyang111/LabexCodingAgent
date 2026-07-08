<template>
  <div class="ftn">
    <div v-if="node.type === 'directory'" class="ftn-row dir" :class="{ selected: isDirSelected }" @click.stop="toggle" @contextmenu.prevent="openContextMenu($event, node)" tabindex="-1">
      <span class="ftn-arrow" :class="{ open: expanded }">
        <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5"><polyline points="9 18 15 12 9 6"/></svg>
      </span>
      <span class="ftn-folder-icon" :class="{ open: expanded }">
        <svg v-if="expanded" width="14" height="14" viewBox="0 0 24 24" fill="#6366f1" stroke="#6366f1" stroke-width="1"><path d="M22 19a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h5l2 3h9a2 2 0 0 1 2 2z"/></svg>
        <svg v-else width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="#6b7280" stroke-width="2"><path d="M22 19a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h5l2 3h9a2 2 0 0 1 2 2z"/></svg>
      </span>
      <span class="ftn-name">{{ node.name }}</span>
      <span v-if="node.fileCount" class="ftn-count">{{ node.fileCount }}</span>
      <button v-if="showActions" class="ftn-action-btn" title="新建" @click.stop="$emit('newItem', node.path, 'directory')">
        <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><line x1="12" y1="5" x2="12" y2="19"/><line x1="5" y1="12" x2="19" y2="12"/></svg>
      </button>
    </div>
    <Transition name="ftn-slide">
      <div v-if="expanded" class="ftn-children">
        <div v-if="loading" class="ftn-loading"><span class="ftn-spinner"></span><span>加载中...</span></div>
        <TransitionGroup v-else name="ftn-list" tag="div">
          <FileTreeNode v-for="child in children" :key="child.path" :node="child" :depth="depth + 1" :selected-path="selectedPath" :load-children="loadChildren" :show-actions="showActions" @select="(p) => emit('select', p)" @newItem="(p, t) => emit('newItem', p, t)" @rename="(p, n) => emit('rename', p, n)" @delete="(p) => emit('delete', p)"/>
        </TransitionGroup>
      </div>
    </Transition>
    <div v-if="node.type === 'file'" class="ftn-row file" :class="{ selected: node.path === selectedPath }" @click.stop="emit('select', node.path)" @contextmenu.prevent="openContextMenu($event, node)" tabindex="-1">
      <span class="ftn-file-icon"><svg width="14" height="14" viewBox="0 0 24 24" fill="none" :stroke="node.path === selectedPath ? '#6366f1' : '#9ca3af'" stroke-width="2"><path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z"/><polyline points="14 2 14 8 20 8"/></svg></span>
      <span class="ftn-name">{{ node.name }}</span>
    </div>
    <Teleport to="body">
      <Transition name="ftn-menu">
        <div v-if="contextMenu.visible" class="ftn-context-menu" :style="{ top: contextMenu.y + 'px', left: contextMenu.x + 'px' }" @click.stop @click="contextMenu.visible = false">
          <button class="ftn-menu-item" @click="emit('rename', contextMenu.node.path, contextMenu.node.name)">
            <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M11 4H4a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h14a2 2 0 0 0 2-2v-7"/><path d="M18.5 2.5a2.121 2.121 0 0 1 3 3L12 15l-4 1 1-4 9.5-9.5z"/></svg>
            <span>重命名</span>
          </button>
          <button class="ftn-menu-item ftn-menu-danger" @click="emit('delete', contextMenu.node.path)">
            <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><polyline points="3 6 5 6 21 6"/><path d="M19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6m3 0V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2"/></svg>
            <span>删除</span>
          </button>
          <div v-if="contextMenu.node.type === 'directory'" class="ftn-menu-divider"></div>
          <button v-if="contextMenu.node.type === 'directory'" class="ftn-menu-item" @click="emit('newItem', contextMenu.node.path, 'file')">
            <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z"/><polyline points="14 2 14 8 20 8"/><line x1="12" y1="18" x2="12" y2="12"/><line x1="9" y1="15" x2="15" y2="15"/></svg>
            <span>新建文件</span>
          </button>
          <button v-if="contextMenu.node.type === 'directory'" class="ftn-menu-item" @click="emit('newItem', contextMenu.node.path, 'directory')">
            <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M22 19a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h5l2 3h9a2 2 0 0 1 2 2z"/><line x1="12" y1="11" x2="12" y2="17"/><line x1="9" y1="14" x2="15" y2="14"/></svg>
            <span>新建文件夹</span>
          </button>
        </div>
      </Transition>
    </Teleport>
  </div>
</template>
<script setup>
import { ref, computed, watch, onMounted, onBeforeUnmount } from 'vue'
const props = defineProps({ node: { type: Object, required: true }, depth: { type: Number, default: 0 }, selectedPath: { type: String, default: '' }, loadChildren: { type: Function, default: null }, showActions: { type: Boolean, default: false } })
const emit = defineEmits(['select', 'newItem', 'rename', 'delete'])
const expanded = ref(false)
const children = ref([])
const loading = ref(false)
const isDirSelected = computed(() => {
  if (!props.selectedPath) return false
  const sel = props.selectedPath.trim()
  if (sel === '' || sel === '/') return false
  const nodePath = props.node.path.replace(/\/+$/, '')
  const selClean = sel.replace(/\/+$/, '')
  return selClean === nodePath || selClean.startsWith(nodePath + '/')
})
const contextMenu = ref({ visible: false, x: 0, y: 0, node: null })

// Reactively clear cached children when tree is rebuilt
watch(() => props.node, () => {
  children.value = []
  expanded.value = false
}, { deep: false })

async function toggle() { expanded.value = !expanded.value; if (expanded.value && props.loadChildren && children.value.length === 0) { loading.value = true; try { const result = await props.loadChildren(props.node.path); children.value = result || [] } finally { loading.value = false } } }
function openContextMenu(e, node) {
  contextMenu.value = { visible: true, x: e.clientX, y: e.clientY, node }
}
function closeContextMenu() { contextMenu.value.visible = false }
onMounted(() => { document.addEventListener('click', closeContextMenu) })
onBeforeUnmount(() => { document.removeEventListener('click', closeContextMenu) })
</script>
<style scoped>
.ftn { user-select: none; font-size: 13px; }
.ftn-row { display: flex; align-items: center; gap: 5px; padding: 4px 8px; border-radius: 6px; cursor: pointer; color: #374151; transition: background 0.12s ease; margin: 1px 0; outline: none; border: 2px solid transparent; }
.ftn-row:hover { background: #f3f4f6; }
.ftn-row:focus, .ftn-row:focus-visible, .ftn-row:active { outline: none !important; background: transparent; }
.ftn-row.selected { background: #eef2ff; border-color: #c7d2fe; }
.ftn-row.selected .ftn-name { color: #4338ca; font-weight: 500; }
.ftn-row.dir.selected { background: #eef2ff; border-color: #c7d2fe; }
.ftn-arrow { width: 16px; height: 16px; display: flex; align-items: center; justify-content: center; flex-shrink: 0; color: #9ca3af; transition: transform 0.2s cubic-bezier(0.34, 1.56, 0.64, 1); }
.ftn-arrow.open { transform: rotate(90deg); color: #6366f1; }
.ftn-folder-icon { flex-shrink: 0; display: flex; align-items: center; transition: all 0.15s ease; }
.ftn-file-icon { flex-shrink: 0; display: flex; align-items: center; }
.ftn-name { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; flex: 1; font-size: 13px; }
.ftn-count { font-size: 10px; color: #9ca3af; background: #f3f4f6; padding: 1px 6px; border-radius: 8px; flex-shrink: 0; }
.ftn-children { padding-left: 16px; border-left: 1px solid #e5e7eb; margin-left: 7px; }
.ftn-loading { display: flex; align-items: center; gap: 8px; padding: 6px 8px; color: #9ca3af; font-size: 12px; }
.ftn-spinner { width: 14px; height: 14px; border: 2px solid #e5e7eb; border-top-color: #6366f1; border-radius: 50%; animation: ftn-spin 0.6s linear infinite; }
@keyframes ftn-spin { to { transform: rotate(360deg); } }
.ftn-action-btn { display: none; align-items: center; justify-content: center; width: 20px; height: 20px; border: none; background: transparent; color: #9ca3af; cursor: pointer; border-radius: 4px; padding: 0; flex-shrink: 0; }
.ftn-row:hover .ftn-action-btn { display: flex; }
.ftn-action-btn:hover { background: #e5e7eb; color: #4b5563; }
.ftn-slide-enter-active { transition: all 0.25s cubic-bezier(0.4, 0, 0.2, 1); overflow: hidden; }
.ftn-slide-leave-active { transition: all 0.15s cubic-bezier(0.4, 0, 0.2, 1); overflow: hidden; }
.ftn-slide-enter-from, .ftn-slide-leave-to { opacity: 0; max-height: 0; transform: translateY(-4px); }
.ftn-slide-enter-to, .ftn-slide-leave-from { opacity: 1; max-height: 2000px; transform: translateY(0); }
.ftn-list-enter-active { transition: all 0.2s ease; }
.ftn-list-leave-active { transition: all 0.15s ease; }
.ftn-list-enter-from { opacity: 0; transform: translateX(-8px); }
.ftn-list-leave-to { opacity: 0; transform: translateX(-4px); }
.ftn-list-move { transition: transform 0.2s ease; }
.ftn-context-menu { position: fixed; background: #fff; border: 1px solid #e5e7eb; border-radius: 8px; box-shadow: 0 4px 16px rgba(0,0,0,0.12); padding: 4px; z-index: 9999; min-width: 160px; }
.ftn-menu-item { display: flex; align-items: center; gap: 8px; width: 100%; padding: 7px 10px; border: none; background: none; border-radius: 5px; cursor: pointer; font-size: 12px; color: #374151; font-family: inherit; text-align: left; transition: background 0.1s; }
.ftn-menu-item:hover { background: #f3f4f6; }
.ftn-menu-danger { color: #ef4444; }
.ftn-menu-danger:hover { background: #fef2f2; }
.ftn-menu-divider { height: 1px; background: #f0f0f0; margin: 3px 6px; }
.ftn-menu-enter-active { transition: all 0.12s ease; }
.ftn-menu-leave-active { transition: all 0.08s ease; }
.ftn-menu-enter-from, .ftn-menu-leave-to { opacity: 0; transform: scale(0.95); }
</style>
