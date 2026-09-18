<template>
  <!-- 侧边栏：子标签（文件/会话/搜索）+ 资源管理器标题行 + 文件树 -->
  <aside class="ide__sidebar">
    <div class="ide__sn">
      <button
        v-for="t in TABS"
        :key="t"
        type="button"
        class="ide__snbtn"
        :class="{ 'is-on': tab === t }"
        @click="tab = t"
      >{{ t }}</button>
    </div>

    <div class="ide__sidehead">
      <span class="ide__sidetitle">
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5">
          <path d="M6 9l6 6 6-6" />
        </svg>
        {{ tab }}
      </span>
      <!-- 资源管理器操作按钮仅在「文件」标签下有意义 -->
      <span v-if="tab === '文件'" class="ide__sideacts">
        <i title="新建文件">
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
            <path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z" />
            <path d="M14 2v6h6M12 12v6M9 15h6" />
          </svg>
        </i>
        <i title="新建文件夹">
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
            <path d="M22 19a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h5l2 3h9a2 2 0 0 1 2 2z" />
            <path d="M12 11v6M9 14h6" />
          </svg>
        </i>
        <i title="刷新">
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
            <path d="M21 12a9 9 0 1 1-3-6.7" />
            <path d="M21 3v6h-6" />
          </svg>
        </i>
        <i title="全部折叠">
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
            <path d="M4 9h16M4 15h16" />
            <path d="M9 4l3 2 3-2M9 20l3-2 3 2" />
          </svg>
        </i>
        <i title="收起资源管理器" @click="$emit('collapse')">
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
            <path d="M15 6l-6 6 6 6" />
            <path d="M19 4v16" />
          </svg>
        </i>
      </span>
    </div>

    <!-- 文件标签：递归渲染文件树 -->
    <div v-if="tab === '文件'" class="ide__tree">
      <WorkbenchTreeNode
        v-for="node in tree"
        :key="node.name"
        :node="node"
        :expanded="expanded"
        :active-file="activeFile"
        @open="$emit('open', $event)"
        @toggle="onToggle"
      />
    </div>

    <!-- 会话标签：演示无历史数据，展示空态提示 -->
    <div v-else-if="tab === '会话'" class="ide__anyhint">暂无历史会话</div>

    <!-- 搜索标签：搜索框 + 空态提示 -->
    <div v-else class="ide__search">
      <div class="ide__searchbox">
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7">
          <circle cx="11" cy="11" r="7" />
          <path d="M20 20l-3.5-3.5" />
        </svg>
        <input type="text" placeholder="在项目中搜索..." />
      </div>
      <div class="ide__anyhint">输入关键词以检索文件与符号</div>
    </div>
  </aside>
</template>

<script setup>
import { reactive, ref } from 'vue'
import { WORKBENCH_TREE } from '@/data/landing/workbenchContent.js'
import WorkbenchTreeNode from './WorkbenchTreeNode.vue'

defineProps({
  // 当前已打开的文件名，用于树中高亮
  activeFile: { type: String, default: '' },
})

defineEmits(['open', 'collapse'])

const TABS = ['文件', '会话', '搜索']
const tree = WORKBENCH_TREE

// 子标签默认停留在「文件」
const tab = ref('文件')

// 展开态以「目录名 → 是否展开」映射维护，初始化时展开 design 中 open:true 的目录
const expanded = reactive({})
;(function initExpanded(nodes) {
  nodes.forEach((n) => {
    if (n.type === 'dir') {
      expanded[n.name] = !!n.open
      if (n.children) initExpanded(n.children)
    }
  })
})(tree)

function onToggle(name) {
  expanded[name] = !expanded[name]
}
</script>
