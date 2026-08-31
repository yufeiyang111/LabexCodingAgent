<template>
  <div
    v-if="visible"
    ref="menuRef"
    class="file-context-menu"
    :style="{ top: adjusted.y + 'px', left: adjusted.x + 'px' }"
    @click.stop
    @contextmenu.prevent
  >
    <div v-if="isDir" class="fcm-item" @click="handleAction('new-file')">
      <span class="fcm-icon">+</span> 新建文件
    </div>
    <div v-if="isDir" class="fcm-item" @click="handleAction('new-folder')">
      <span class="fcm-icon">📁</span> 新建文件夹
    </div>
    <div v-if="isDir" class="fcm-divider"></div>

    <div v-if="canPreview" class="fcm-item" @click="handleAction('preview-image')">
      <span class="fcm-icon">👁</span> 预览图片
    </div>
    <div v-if="!isDir" class="fcm-item" @click="handleAction('add-to-chat')">
      <span class="fcm-icon">💬</span> 添加到聊天
    </div>
    <div class="fcm-item" @click="handleAction('copy-path')">
      <span class="fcm-icon">📋</span> 复制路径
    </div>
    <div class="fcm-divider"></div>

    <div class="fcm-item" @click="handleAction('copy')">
      <span class="fcm-icon">📄</span> 复制
    </div>
    <div class="fcm-item" @click="handleAction('cut')">
      <span class="fcm-icon">✂</span> 剪切
    </div>
    <div v-if="isDir" class="fcm-item" :class="{ disabled: !hasClipboard }" @click="hasClipboard && handleAction('paste')">
      <span class="fcm-icon">📋</span> 粘贴
    </div>
    <div class="fcm-divider"></div>

    <div v-if="!isDir" class="fcm-item" @click="handleAction('download')">
      <span class="fcm-icon">⬇</span> 下载
    </div>
    <div class="fcm-item" @click="handleAction('rename')">
      <span class="fcm-icon">✏</span> 重命名
    </div>
    <div class="fcm-item danger" @click="handleAction('delete')">
      <span class="fcm-icon">🗑</span> 删除
    </div>
  </div>
</template>

<script setup>
import { ref, computed, watch, nextTick, onMounted, onBeforeUnmount } from 'vue'
import { isImagePath } from '../../constants/workspaceFiles.js'

const VIEWPORT_MARGIN = 8

const props = defineProps({
  visible: { type: Boolean, default: false },
  x: { type: Number, default: 0 },
  y: { type: Number, default: 0 },
  node: { type: Object, default: null },
  hasClipboard: { type: Boolean, default: false }
})

const emit = defineEmits(['action', 'close'])

const menuRef = ref(null)
const adjusted = ref({ x: props.x, y: props.y })

const isDir = computed(() => props.node?.type === 'directory')
const canPreview = computed(() => !isDir.value && isImagePath(props.node?.path))

function handleAction(action) {
  emit('action', { action, node: props.node })
  emit('close')
}

watch(
  [() => props.visible, () => props.x, () => props.y],
  async ([visible]) => {
    if (!visible) return
    adjusted.value = { x: props.x, y: props.y }
    await nextTick()
    const el = menuRef.value
    if (!el) return
    const menuHeight = el.offsetHeight
    const menuWidth = el.offsetWidth
    const viewHeight = window.innerHeight
    const viewWidth = window.innerWidth

    let nextY = props.y
    if (nextY + menuHeight > viewHeight - VIEWPORT_MARGIN) {
      const flipped = props.y - menuHeight
      nextY = Math.max(VIEWPORT_MARGIN, Math.min(flipped, viewHeight - menuHeight - VIEWPORT_MARGIN))
    }

    let nextX = props.x
    if (nextX + menuWidth > viewWidth - VIEWPORT_MARGIN) {
      const flipped = props.x - menuWidth
      nextX = Math.max(VIEWPORT_MARGIN, Math.min(flipped, viewWidth - menuWidth - VIEWPORT_MARGIN))
    }

    adjusted.value = { x: nextX, y: nextY }
  },
  { immediate: true }
)

function onGlobalClick(e) {
  if (props.visible && menuRef.value && !menuRef.value.contains(e.target)) {
    emit('close')
  }
}

onMounted(() => {
  window.addEventListener('click', onGlobalClick, true)
  window.addEventListener('contextmenu', onGlobalClick, true)
})

onBeforeUnmount(() => {
  window.removeEventListener('click', onGlobalClick, true)
  window.removeEventListener('contextmenu', onGlobalClick, true)
})
</script>

<style scoped>
.file-context-menu {
  position: fixed;
  z-index: 9999;
  background: #ffffff;
  border: 1px solid #e4e4e7;
  border-radius: 8px;
  box-shadow: 0 4px 16px rgba(0, 0, 0, 0.08);
  padding: 4px;
  min-width: 140px;
  font-size: 12.5px;
  color: #27272a;
}
.fcm-item {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 6px 10px;
  border-radius: 5px;
  cursor: pointer;
  transition: background 0.12s;
  user-select: none;
}
.fcm-item:hover {
  background: #f4f4f5;
}
.fcm-item.disabled {
  opacity: 0.4;
  cursor: not-allowed;
}
.fcm-item.danger {
  color: #ef4444;
}
.fcm-item.danger:hover {
  background: #fef2f2;
}
.fcm-divider {
  height: 1px;
  background: #f4f4f5;
  margin: 3px 0;
}
.fcm-icon {
  font-size: 13px;
  width: 14px;
  display: inline-flex;
  justify-content: center;
}
</style>
