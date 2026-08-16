<template>
  <div class="ws-sidebar-panel" :class="{ 'is-tree-collapsed': isTreeCollapsed }">
    <div class="ws-sidebar-header" @click="toggleTreeCollapse">
      <div class="header-title-group">
        <span class="chevron-icon" :class="{ rotated: isTreeCollapsed }">
          <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5"><polyline points="6 9 12 15 18 9"/></svg>
        </span>
        <span class="header-title-text">文件资源管理器</span>
      </div>
      <div class="ws-sidebar-actions" @click.stop>
        <button class="ws-btn ws-btn-ghost ws-btn-sm" @click="$emit('create-file')" title="新建文件">
          <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z"/><polyline points="14 2 14 8 20 8"/><line x1="12" y1="18" x2="12" y2="12"/><line x1="9" y1="15" x2="15" y2="15"/></svg>
        </button>
        <button class="ws-btn ws-btn-ghost ws-btn-sm" @click="$emit('create-dir')" title="新建文件夹">
          <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M22 19a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h5l2 3h9a2 2 0 0 1 2 2z"/><line x1="12" y1="11" x2="12" y2="17"/><line x1="9" y1="14" x2="15" y2="14"/></svg>
        </button>
        <button class="ws-btn ws-btn-ghost ws-btn-sm" @click="$emit('refresh')" title="刷新">
          <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><polyline points="23 4 23 10 17 10"/><path d="M20.49 15a9 9 0 1 1-2.12-9.36L23 10"/></svg>
        </button>
      </div>
    </div>

    <Transition name="panel-collapse">
      <div v-show="!isTreeCollapsed" class="ws-tree-container" v-loading="treeLoading" @scroll="$emit('scroll')">
        <div class="ws-tree">
          <div v-if="treeError" class="ws-tree-error" role="alert">
            <span>{{ treeError }}</span>
            <button type="button" @click="$emit('refresh')">重试</button>
          </div>
          <FileTreeNode
            v-for="child in fileTree"
            :key="child.path"
            :node="child"
            :selected-path="activePath"
            :load-children="loadChildren"
            :show-actions="true"
            :refresh-key="refreshKey"
            @select="p => $emit('select', p)"
            @new-item="(p, t) => $emit('new-item', p, t)"
            @rename="(p, n) => $emit('rename', p, n)"
            @delete="p => $emit('delete', p)"
          />
          <button v-if="treeNextOffset !== null" class="ws-tree-load-more" type="button" @click="$emit('load-more')">加载更多文件</button>
          <div v-if="!treeLoading && !treeError && fileTree.length === 0" class="ws-tree-empty">暂无文件</div>
        </div>
      </div>
    </Transition>

    <Teleport to="body">
      <Transition name="modal">
        <div v-if="showNewModal" class="ws-overlay" @click.self="$emit('close-new')">
          <div class="ws-dialog">
            <h3>{{ newModalType === 'directory' ? '新建文件夹' : '新建文件' }}</h3>
            <input v-model="newItemName" class="ws-dialog-input" :placeholder="newModalType === 'directory' ? '请输入文件夹名称' : '请输入文件名称（含扩展名）'" @keyup.enter="$emit('confirm-new')" />
            <div class="ws-dialog-actions">
              <button class="ws-btn ws-btn-outline" @click="$emit('close-new')">取消</button>
              <button class="ws-btn ws-btn-primary" @click="$emit('confirm-new')">创建</button>
            </div>
          </div>
        </div>
      </Transition>
    </Teleport>
    <Teleport to="body">
      <Transition name="modal">
        <div v-if="showRenameModal" class="ws-overlay" @click.self="$emit('close-rename')">
          <div class="ws-dialog">
            <h3>重命名</h3>
            <input v-model="renameItemValue" class="ws-dialog-input" placeholder="请输入新名称" @keyup.enter="$emit('confirm-rename')" />
            <div class="ws-dialog-actions">
              <button class="ws-btn ws-btn-outline" @click="$emit('close-rename')">取消</button>
              <button class="ws-btn ws-btn-primary" @click="$emit('confirm-rename')">确认</button>
            </div>
          </div>
        </div>
      </Transition>
    </Teleport>
  </div>
</template>

<script setup>
import { ref } from 'vue'
import FileTreeNode from '@/components/cloud/FileTreeNode.vue'

defineProps({
  fileTree: { type: Array, default: () => [] },
  treeError: { type: String, default: '' },
  treeLoading: { type: Boolean, default: false },
  treeNextOffset: { type: [Number, null], default: null },
  activePath: { type: String, default: '' },
  loadChildren: { type: Function, default: null },
  refreshKey: { type: [Number, String], default: 0 },
  showNewModal: { type: Boolean, default: false },
  newModalType: { type: String, default: 'file' },
  showRenameModal: { type: Boolean, default: false }
})

const isTreeCollapsed = ref(false)
function toggleTreeCollapse() {
  isTreeCollapsed.value = !isTreeCollapsed.value
}

const newItemName = defineModel('newItemName', { type: String, default: '' })
const renameItemValue = defineModel('renameValue', { type: String, default: '' })
defineEmits([
  'select',
  'new-item',
  'rename',
  'delete',
  'refresh',
  'load-more',
  'scroll',
  'create-file',
  'create-dir',
  'close-new',
  'close-rename',
  'confirm-new',
  'confirm-rename'
])
</script>

<style scoped>
.header-title-group {
  display: flex;
  align-items: center;
  gap: 6px;
  cursor: pointer;
  user-select: none;
}

.chevron-icon {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  color: #71717a;
  transition: transform 0.2s cubic-bezier(0.16, 1, 0.3, 1);
}

.chevron-icon.rotated {
  transform: rotate(-90deg);
}

.header-title-text {
  font-weight: 600;
  font-size: 12px;
  color: #09090b;
}

.ws-tree-container {
  flex: 1;
  overflow-y: auto;
  min-height: 0;
}

.panel-collapse-enter-active,
.panel-collapse-leave-active {
  transition: opacity 0.18s ease, max-height 0.22s cubic-bezier(0.16, 1, 0.3, 1);
  overflow: hidden;
}

.panel-collapse-enter-from,
.panel-collapse-leave-to {
  max-height: 0;
  opacity: 0;
}

.panel-collapse-enter-to,
.panel-collapse-leave-from {
  max-height: 100vh;
  opacity: 1;
}
</style>
