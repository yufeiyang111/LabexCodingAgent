<template>
  <!-- 单个文件树节点：目录可展开/折叠，文件可点击打开（事件冒泡给父级） -->
  <div>
    <button
      type="button"
      class="ide__node"
      :class="nodeClass"
      @click="onClick"
    >
      <!-- 目录显示展开箭头（打开时由 CSS 旋转），文件保留占位以保持对齐 -->
      <span v-if="node.type === 'dir'" class="ide__caret">
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
          <path d="M9 6l6 6-6 6" />
        </svg>
      </span>
      <span v-else class="ide__caret"></span>

      <span v-if="node.type === 'dir'" class="ide__folder">
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5">
          <path d="M22 19a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h5l2 3h9a2 2 0 0 1 2 2z" />
        </svg>
      </span>
      <span v-else-if="ext" class="ide__ext">{{ ext }}</span>

      <span class="ide__nodename">{{ node.name }}</span>
    </button>

    <!-- 子节点：仅当目录且处于展开状态时渲染 -->
    <div
      v-if="node.type === 'dir' && expanded[node.name]"
      class="ide__children"
      :hidden="!expanded[node.name]"
    >
      <WorkbenchTreeNode
        v-for="child in node.children"
        :key="child.name"
        :node="child"
        :expanded="expanded"
        :active-file="activeFile"
        @open="$emit('open', $event)"
        @toggle="$emit('toggle', $event)"
      />
    </div>
  </div>
</template>

<script setup>
import { computed } from 'vue'

const props = defineProps({
  node: { type: Object, required: true },
  // 以「目录名 → 是否展开」的映射驱动整棵树的展开态
  expanded: { type: Object, default: () => ({}) },
  // 当前已打开的文件名，用于高亮
  activeFile: { type: String, default: '' },
})

const emit = defineEmits(['open', 'toggle'])

const ext = computed(() => {
  // 以「.」开头的隐藏文件（如 .labex-agentignore）不显示扩展名徽章
  if (props.node.name.startsWith('.')) return ''
  const m = props.node.name.match(/\.([a-z0-9]+)$/i)
  return m ? m[1] : ''
})

const nodeClass = computed(() => {
  if (props.node.type === 'dir') {
    return ['ide__node--dir', { 'is-open': props.expanded[props.node.name] }]
  }
  return ['is-file', { 'is-active': props.node.name === props.activeFile }]
})

function onClick() {
  if (props.node.type === 'dir') emit('toggle', props.node.name)
  else emit('open', props.node.name)
}
</script>
