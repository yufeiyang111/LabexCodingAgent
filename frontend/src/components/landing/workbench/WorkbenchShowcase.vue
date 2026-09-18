<template>
<!-- 演示编排容器：持有 useWorkbenchDemo，把状态按 props 分发给各子组件 -->
  <div class="demo demo--ide" id="hero-demo">
    <div class="ide" :class="{ 'ide--ai-max': aiMax }">
      <WorkbenchTopbar
        :sidebar-on="showSidebar"
        :ai-on="showAi"
        @toggle-sidebar="showSidebar = !showSidebar"
        @toggle-ai="showAi = !showAi"
      />

      <div class="ide__body">
        <WorkbenchRail
          :explorer-on="showSidebar"
          @toggle-explorer="showSidebar = !showSidebar"
        />

        <WorkbenchSidebar
          v-show="showSidebar"
          :active-file="editorFile"
          @open="openFile"
          @collapse="showSidebar = false"
        />

        <WorkbenchEditor :file="editorFile" />

        <WorkbenchAiPanel
          v-show="showAi"
          :panel="activePanel"
          :ask-text="askText"
          :steps="steps"
          :reply="reply"
          :phase="phase"
          :mode="sceneKey"
          :mode-index="modeIndex"
          :modes="WORKBENCH_MODES"
          :quick-scenes="quickScenes"
          :usage-label="usageLabel"
          @update:panel="activePanel = $event"
          @mode="playScene"
          @quick="playScene"
          @send="replay"
          @toggle-ai-max="aiMax = !aiMax"
          @clear="onClear"
          @toggle-sidebar="showSidebar = !showSidebar"
        />
      </div>
    </div>
  </div>

</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
// 引入落地页样式入口（含工作台所需的作用域 CSS 与 CSS 变量）
import {
WORKBENCH_SCENES,
WORKBENCH_SCENE_ORDER,
WORKBENCH_MODES,
} from '@/data/landing/workbenchContent.js'
import { useWorkbenchDemo } from '@/composables/landing/useWorkbenchDemo.js'
import WorkbenchTopbar from './WorkbenchTopbar.vue'
import WorkbenchRail from './WorkbenchRail.vue'
import WorkbenchSidebar from './WorkbenchSidebar.vue'
import WorkbenchEditor from './WorkbenchEditor.vue'
import WorkbenchAiPanel from './WorkbenchAiPanel.vue'

const {
sceneKey,
askText,
steps,
reply,
phase,
play,
replay,
reset,
} = useWorkbenchDemo({ scenes: WORKBENCH_SCENES, order: WORKBENCH_SCENE_ORDER })

// 布局显隐状态：与顶栏 / 活动栏 / AI 面板按钮联动
const showSidebar = ref(true)
const showAi = ref(true)
const aiMax = ref(false)

// AI 面板当前标签（对话 / 用量 / 审查 / 扩展 / 终端）
const activePanel = ref('chat')

// 编辑器当前打开的文件（由文件树或场景驱动）
const editorFile = ref('')

// 播放某个场景：推进提问→工具→回答时间线，并让编辑器打开该场景的对应文件
function playScene(key) {
play(key)
const file = WORKBENCH_SCENES[key]?.file
if (file) editorFile.value = file
}

function openFile(name) {
editorFile.value = name
}

function onClear() {
reset()
editorFile.value = ''
}

// 快捷提问卡：直接复用演示场景的提问文案，避免编造展示内容
const quickScenes = computed(() =>
WORKBENCH_SCENE_ORDER.map((k) => ({ key: k, label: WORKBENCH_SCENES[k].ask }))
)

// 模式胶囊序号：由当前场景推导，驱动滑块位置
const modeIndex = computed(() =>
Math.max(0, WORKBENCH_MODES.findIndex((m) => m.key === sceneKey.value))
)

const usageLabel = computed(() => `${sceneKey.value} · ${editorFile.value}`)

// 挂载后自动播放第一个场景
onMounted(() => playScene(WORKBENCH_SCENE_ORDER[0]))

// 重播按钮位于 .hero__media 内（.stage 之外），由上层通过 ref 调用已解构出的 replay

defineExpose({ replay })
</script>
