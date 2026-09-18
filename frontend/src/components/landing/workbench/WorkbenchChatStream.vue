<template>
  <!-- 对话流：未提问时展示欢迎态与快捷提问卡；提问后展示用户消息、工具卡、思考态与 AI 回复
       根节点为片段，由 AiPanel 的 .ide__aibody 统一包裹，避免双层 padding -->
  <div v-if="askText" class="ide__chat">
        <!-- 用户提问（逐字打出） -->
        <div class="ide__msg-user">{{ askText }}</div>

        <!-- 工具调用卡：按演示时间线依次出现 -->
        <div v-for="step in steps" :key="step.id" class="ide__tc">
          <div class="ide__tc-head">
            <span class="ide__tc-dot"></span>
            <span class="ide__tc-name">{{ step.name }}</span>
            <span class="ide__tc-sub">{{ step.sub }}</span>
            <span class="ide__tc-state">{{ step.state }}</span>
          </div>
        </div>

        <!-- 思考态：工具卡尚未出完 / 回复未到达时显示打字指示 -->
        <div v-if="!reply" class="ide__msg-ai">
          <span class="ide__typing"><i></i><i></i><i></i></span>
        </div>

        <!-- AI 回复：回复文本中的 <b> 标记必须避免 v-html，这里拆成片段渲染 -->
        <div v-if="reply" class="ide__msg-ai">
          <template v-for="(seg, i) in replySegments" :key="i">
            <b v-if="seg.bold">{{ seg.text }}</b>
            <template v-else>{{ seg.text }}</template>
          </template>
        </div>
      </div>

    <!-- 欢迎态：首次进入、尚未开始对话 -->
    <div v-else class="ide__welcome">
      <span class="ide__welcome-icon">
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7">
          <path d="M12 3l1.9 4.6L18.5 9l-4.6 1.4L12 15l-1.9-4.6L5.5 9l4.6-1.4z" />
          <path d="M18 15l.9 2.1L21 18l-2.1.9L18 21l-.9-2.1L15 18l2.1-.9z" />
        </svg>
      </span>
      <h4>我是你的开发助手</h4>
      <p>描述需求，我会读写文件、运行命令并给出可运行的改动。</p>
      <div class="ide__quick">
        <span
          v-for="q in quickScenes"
          :key="q.key"
          role="button"
          tabindex="0"
          @click="$emit('quick', q.key)"
          @keydown.enter="$emit('quick', q.key)"
        >
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7">
            <path d="M5 12h14M12 5v14" />
          </svg>
          {{ q.label }}
        </span>
      </div>
    </div>
</template>

<script setup>
import { computed } from 'vue'

const props = defineProps({
  // 当前场景的提问文本（逐字打出后的完整串）
  askText: { type: String, default: '' },
  // 已出现的工具卡列表
  steps: { type: Array, default: () => [] },
  // AI 最终回复（可能含 <b> 标记）
  reply: { type: String, default: '' },
  // 演示阶段：idle | typing | thinking | tools | done
  phase: { type: String, default: 'idle' },
  // 快捷提问卡：来自 WORKBENCH_SCENES 的提问文案
  quickScenes: { type: Array, default: () => [] },
})

defineEmits(['quick'])

/**
 * 把回复里的 <b>…</b> 拆成「普通 / 加粗」片段。
 * 之所以不用 v-html：回复字符串来自演示数据且含 HTML 标签，直接插入会有 XSS 风险，
 * 这里用正则切片后分别渲染，既不丢失加粗语义也绝不执行 HTML。
 */
const replySegments = computed(() => {
  const text = props.reply
  if (!text) return []
  const out = []
  const re = /<b>(.*?)<\/b>/g
  let last = 0
  let m
  while ((m = re.exec(text)) !== null) {
    if (m.index > last) out.push({ text: text.slice(last, m.index), bold: false })
    out.push({ text: m[1], bold: true })
    last = m.index + m[0].length
  }
  if (last < text.length) out.push({ text: text.slice(last), bold: false })
  return out
})
</script>

<style scoped>
/* 快捷卡在设计稿中是 <span>，这里补上可点击光标（不新增布局类） */
.ide__quick span {
  cursor: pointer;
}
</style>
