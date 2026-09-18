<template>
  <!-- AI 面板：顶栏 + 标签 + 主体（对话或面板）+ 输入区 + 状态栏 -->
  <aside class="ide__ai">
    <div class="ide__aitb">
      <span class="ide__brand">
        <!-- 复用统一品牌标记组件，避免工作台演示里留一份独立的内联副本 -->
        <BrandMark />
        <b>LabexAgent</b>
      </span>
      <span class="ide__session">
        <span>新会话</span>
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
          <path d="M6 9l6 6 6-6" />
        </svg>
      </span>
      <span class="ide__aitb-acts">
        <i title="折叠侧边栏" @click="$emit('toggle-sidebar')">
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
            <path d="M3 12h18M9 4v16" />
          </svg>
        </i>
        <i title="在中部放大 LabexAgent" @click="$emit('toggle-ai-max')">
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
            <path d="M15 3h6v6M9 21H3v-6M21 3l-7 7M3 21l7-7" />
          </svg>
        </i>
        <i title="切换暗色主题">
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
            <path d="M21 12.79A9 9 0 1 1 11.21 3a7 7 0 0 0 9.79 9.79z" />
          </svg>
        </i>
        <i title="清空会话" @click="$emit('clear')">
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
            <path d="M3 6h18M8 6V4h8v2M6 6l1 14h10l1-14" />
          </svg>
        </i>
      </span>
    </div>

    <div class="ide__aitabs">
      <button
        v-for="p in PANELS"
        :key="p.key"
        type="button"
        class="ide__aitab"
        :class="{ 'is-on': panel === p.key }"
        @click="$emit('update:panel', p.key)"
      >
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
          <path :d="p.icon" />
        </svg>
        {{ p.label }}
      </button>
    </div>

    <!-- 主体：对话标签渲染对话流，其余标签渲染对应面板 -->
    <div class="ide__aibody">
      <WorkbenchChatStream
        v-if="panel === 'chat'"
        :ask-text="askText"
        :steps="steps"
        :reply="reply"
        :phase="phase"
        :quick-scenes="quickScenes"
        @quick="$emit('quick', $event)"
      />
      <PanelUsage v-else-if="panel === 'usage'" :label="usageLabel" />
      <PanelReview v-else-if="panel === 'review'" />
      <PanelExtensions v-else-if="panel === 'extensions'" />
      <PanelTerminal v-else-if="panel === 'terminal'" />
    </div>

    <WorkbenchComposer
      :mode="mode"
      :mode-index="modeIndex"
      :modes="modes"
      @mode="$emit('mode', $event)"
      @send="$emit('send')"
    />

    <div class="ide__statusbar">
      <span class="ide__badge"><span class="ide__badgename">{{ modelName }}</span></span>
      <span class="ide__online"><i></i>在线</span>
      <span class="ide__tok">{{ statusTok }}</span>
    </div>
  </aside>
</template>

<script setup>
import { WORKBENCH_PANELS } from '@/data/landing/workbenchContent.js'
import BrandMark from '@/components/landing/BrandMark.vue'
import WorkbenchChatStream from './WorkbenchChatStream.vue'
import WorkbenchComposer from './WorkbenchComposer.vue'
import PanelUsage from './panels/PanelUsage.vue'
import PanelReview from './panels/PanelReview.vue'
import PanelExtensions from './panels/PanelExtensions.vue'
import PanelTerminal from './panels/PanelTerminal.vue'

defineProps({
  // 当前激活的标签：chat / usage / review / extensions / terminal
  panel: { type: String, default: 'chat' },
  askText: { type: String, default: '' },
  steps: { type: Array, default: () => [] },
  reply: { type: String, default: '' },
  phase: { type: String, default: 'idle' },
  mode: { type: String, default: 'build' },
  modeIndex: { type: Number, default: 0 },
  modes: { type: Array, default: () => [] },
  quickScenes: { type: Array, default: () => [] },
  // 用量面板标题：当前场景 + 文件
  usageLabel: { type: String, default: '' },
})

defineEmits(['update:panel', 'mode', 'quick', 'send', 'toggle-ai-max', 'clear', 'toggle-sidebar'])

// 标签图标路径与数据中的顺序一一对应
const PANEL_ICONS = {
  chat: 'M21 15a2 2 0 0 1-2 2H7l-4 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2z',
  usage: 'M12 2v20M17 5H9.5a3.5 3.5 0 0 0 0 7h5a3.5 3.5 0 0 1 0 7H6',
  review: 'M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8zM14 2v6h6',
  extensions: 'M12 3v18M3 12h18',
  terminal: 'M4 17l6-6-6-6M12 19h8',
}
const PANELS = WORKBENCH_PANELS.map((p) => ({ ...p, icon: PANEL_ICONS[p.key] }))

// 状态栏的模型名与费用为演示文案
const modelName = 'MiniMax-M2.7-highspeed'
const statusTok = '$ 0'
</script>
