<template>
  <div ref="indicator" class="context-usage-indicator" @mouseenter="showPopover" @mouseleave="expanded = false" @focusin="showPopover" @focusout="expanded = false">
    <button class="context-ring" :class="tone" type="button" :aria-label="ariaLabel" @click="$emit('open')">
      <svg viewBox="0 0 24 24" aria-hidden="true">
        <circle class="track" cx="12" cy="12" r="9" />
        <circle class="progress" cx="12" cy="12" r="9" :style="progressStyle" />
      </svg>
    </button>
    <Transition name="context-popover">
      <section v-if="expanded" class="context-popover" :class="{ 'opens-left': opensLeft }" role="status">
        <template v-if="hasSnapshot">
          <header><strong>当前上下文</strong><span>字符估算</span></header>
          <div class="segment-bar"><i v-for="item in segments" :key="item.key" :style="{ width: item.width + '%', background: item.color }" /></div>
          <footer><span>{{ usedLabel }}<template v-if="windowLabel"> / {{ windowLabel }}</template> tokens</span><b>{{ percentLabel }}</b></footer>
        </template>
        <template v-else><strong>上下文尚未生成</strong><span>首次模型调用前会显示状态。</span></template>
      </section>
    </Transition>
  </div>
</template>

<script setup>
import { computed, ref } from 'vue'

const props = defineProps({ status: { type: Object, default: null } })
defineEmits(['open'])
const indicator = ref(null)
const expanded = ref(false)
const opensLeft = ref(false)
const showPopover = () => {
  const bounds = indicator.value?.getBoundingClientRect()
  opensLeft.value = Boolean(bounds && bounds.left + 286 > window.innerWidth - 12)
  expanded.value = true
}
const palette = { systemPrompt: '#5d86e8', toolDefinitions: '#68b9ee', workspaceMemory: '#8bd98a', skillsAndInstructions: '#e6ad70', conversationMessages: '#b079d3', toolResults: '#ce85c7' }
const hasSnapshot = computed(() => Boolean(props.status?.usedTokens > 0 || Object.keys(props.status?.categories || {}).length))
const percent = computed(() => Number.isFinite(props.status?.usagePercent) ? props.status.usagePercent : null)
const tone = computed(() => percent.value === null ? 'neutral' : percent.value >= 90 ? 'danger' : percent.value >= 70 ? 'warning' : 'normal')
const circumference = 56.55
const progressStyle = computed(() => ({ strokeDasharray: circumference, strokeDashoffset: circumference * (1 - Math.min(100, percent.value ?? 0) / 100) }))
const format = value => value >= 1_000_000 ? `${(value / 1_000_000).toFixed(1)}M` : value >= 1_000 ? `${(value / 1_000).toFixed(1)}K` : String(value || 0)
const usedLabel = computed(() => format(props.status?.usedTokens || 0))
const windowLabel = computed(() => props.status?.contextWindowTokens ? format(props.status.contextWindowTokens) : '')
const percentLabel = computed(() => percent.value === null ? '未配置窗口' : `${percent.value}%`)
const ariaLabel = computed(() => hasSnapshot.value ? `当前上下文 ${usedLabel.value}${windowLabel.value ? ` / ${windowLabel.value}` : ''} tokens，${percentLabel.value}` : '当前上下文尚未生成')
const segments = computed(() => Object.entries(props.status?.categories || {}).map(([key, value]) => ({ key, color: palette[key] || '#8791a2', width: props.status?.contextWindowTokens ? Math.min(100, value * 100 / props.status.contextWindowTokens) : 0 })).filter(item => item.width > 0))
</script>

<style scoped>
.context-usage-indicator { position: relative; display: inline-flex; align-items: center; }
.context-ring { width: 16px; height: 16px; padding: 0; border: 0; border-radius: 50%; background: transparent; cursor: pointer; transition: transform .16s ease, filter .16s ease; }
.context-ring:hover, .context-ring:focus-visible { transform: scale(1.14); outline: none; filter: brightness(1.15); }
.context-ring svg { display:block; width:16px; height:16px; transform:rotate(-90deg); }
circle { fill:none; stroke-width:3.25; }.track { stroke:#596170; }.progress { stroke:var(--context-tone, #5d86e8); stroke-linecap:round; filter:drop-shadow(0 0 2px color-mix(in srgb, var(--context-tone, #5d86e8) 55%, transparent)); transition:stroke-dashoffset .25s ease, stroke .25s ease; }
.normal { --context-tone:#5d86e8; }.warning { --context-tone:#e6ad70; }.danger { --context-tone:#f0784d; }.neutral { --context-tone:#86909f; }
.context-popover { position:absolute; z-index:30; bottom:27px; left:-5px; width:286px; padding:14px; border:1px solid #48515e; border-radius:11px; background:#292e36; color:#edf0f4; box-shadow:0 18px 38px #000b; font-size:12px; }.context-popover::after { content:''; position:absolute; bottom:-6px; left:12px; width:11px; height:11px; border-right:1px solid #48515e; border-bottom:1px solid #48515e; background:#292e36; transform:rotate(45deg); }.context-popover.opens-left { right:-5px; left:auto; }.context-popover.opens-left::after { right:12px; left:auto; }.context-popover header,.context-popover footer { display:flex; justify-content:space-between; align-items:center; }.context-popover header { margin-bottom:10px; }.context-popover header span,.context-popover > span { color:#9ea8b5; font-size:11px; }.context-popover footer { margin-top:8px; color:#aab2bd; font-size:11px; }.context-popover footer b { color:#eef1f5; }.segment-bar { display:flex; height:6px; overflow:hidden; border-radius:4px; background:#535b69; }.segment-bar i { display:block; height:100%; }.context-popover-enter-active,.context-popover-leave-active { transition:opacity .15s ease, transform .15s ease; }.context-popover-enter-from,.context-popover-leave-to { opacity:0; transform:translateY(4px); }
</style>
