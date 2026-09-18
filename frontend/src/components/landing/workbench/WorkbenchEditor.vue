<template>
  <!-- 编辑区：标签栏 + 代码区（token 渲染，禁止 v-html）+ 空态 -->
  <div class="ide__center">
    <div class="ide__tabs">
      <button
        v-for="t in openTabs"
        :key="t"
        type="button"
        class="ide__tab"
        :class="{ 'is-on': t === active }"
        @click="active = t"
      >
        <span v-if="extOf(t)" class="ide__ext">{{ extOf(t) }}</span>
        <svg v-else class="ide__tabfile" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.6">
          <path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z" />
          <path d="M14 2v6h6" />
        </svg>
        <span class="ide__tabname">{{ t }}</span>
        <span class="ide__tab-x" title="关闭" @click.stop="close(t)">×</span>
      </button>
    </div>

    <div class="ide__editor">
      <!-- 已打开且有内容：按行渲染 token；行号用 .ln i，新增行加 .is-add -->
      <div v-if="active && hasContent" class="ide__code"><span v-for="(line, i) in content" :key="i" class="ln" :class="{ 'is-add': addedSet.has(i) }"><i>{{ i + 1 }}</i><template v-for="(tok, j) in line" :key="j"><span :class="tok.k || undefined">{{ tok.t }}</span></template></span></div>

      <!-- 空态：未打开任何文件，或文件在设计稿中无可预览内容 -->
      <div v-else class="ide__empty">
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1">
          <path d="M13 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V9z" />
          <path d="M13 2v7h7" />
        </svg>
        <p>{{ active ? '该文件暂无可预览内容' : '选择文件开始编辑' }}</p>
        <p class="ide__empty-hint">从左侧文件树中选择一个文件，或将右侧 LabexAgent 拖入此处放大</p>
      </div>
    </div>
  </div>
</template>

<script setup>
import { computed, ref, watch } from 'vue'
import { WORKBENCH_FILES } from '@/data/landing/workbenchContent.js'

const props = defineProps({
  // 由父级（编排容器或文件树）传入「当前要打开的文件名」
  file: { type: String, default: '' },
})

// 演示中新增的行：与场景「+2 / +3」对应，仅用于高亮，不改动数据
const ADDED_LINES = {
  'config.py': [10],
  'app.py': [10, 11, 12],
}

const openTabs = ref([])
const active = ref('')

// 父级传入新文件名时，加入标签并切换为激活
watch(
  () => props.file,
  (name) => {
    if (!name) return
    if (!openTabs.value.includes(name)) openTabs.value.push(name)
    active.value = name
  },
  { immediate: true }
)

const hasContent = computed(() => !!props.file && Array.isArray(WORKBENCH_FILES[props.file]))
const content = computed(() => (hasContent.value ? WORKBENCH_FILES[props.file] : []))
const addedSet = computed(() => new Set(ADDED_LINES[active.value] || []))

function extOf(name) {
  if (!name || name.startsWith('.')) return ''
  const m = name.match(/\.([a-z0-9]+)$/i)
  return m ? m[1] : ''
}

function close(name) {
  const idx = openTabs.value.indexOf(name)
  if (idx === -1) return
  openTabs.value.splice(idx, 1)
  if (active.value === name) {
    active.value = openTabs.value[openTabs.value.length - 1] || ''
  }
}
</script>

<style scoped>
/* 标签关闭按钮为组件级补充：设计稿标签栏为空，这里补齐交互所需的 ×
   （尺寸/定位走微调，不新增布局类） */
.ide__tab {
  gap: 6px;
}
.ide__tab-x {
  margin-left: 2px;
  width: 16px;
  height: 16px;
  display: inline-grid;
  place-items: center;
  border-radius: 4px;
  color: #94a3b8;
  font-size: 14px;
  line-height: 1;
  cursor: pointer;
}
.ide__tab-x:hover {
  background: #eef2ff;
  color: #4f46e5;
}
/* 无扩展名文件（如 .labex-agentignore）在标签上用通用文档图标代替扩展名徽章 */
.ide__tabfile {
  flex: 0 0 auto;
  width: 14px;
  height: 14px;
  color: #6b7280;
}
</style>
