<template>
  <Teleport to="body">
    <Transition name="context-dialog">
      <div v-if="open" class="context-dialog-backdrop" @click.self="$emit('close')">
        <section class="context-dialog" role="dialog" aria-modal="true" aria-label="LLM 上下文使用情况">
          <header class="dialog-header">
            <div>
              <h2>LLM 上下文</h2>
              <p>{{ modelLabel }}</p>
            </div>
            <button type="button" aria-label="关闭上下文窗口" @click="$emit('close')">&times;</button>
          </header>

          <div class="tabs" role="tablist" aria-label="上下文信息视图">
            <button type="button" role="tab" :aria-selected="activeTab === 'usage'" :class="{ active: activeTab === 'usage' }" @click="activeTab = 'usage'">Token 用量</button>
            <button type="button" role="tab" :aria-selected="activeTab === 'actual'" :class="{ active: activeTab === 'actual' }" @click="selectPreviewTab('actual')">实际发送上下文</button>
            <button type="button" role="tab" :aria-selected="activeTab === 'prediction'" :class="{ active: activeTab === 'prediction' }" @click="selectPreviewTab('prediction')">下一次请求预测</button>
          </div>

          <section v-if="activeTab === 'usage'" class="dialog-body">
            <template v-if="hasSnapshot">
              <div class="dialog-summary">
                <strong>{{ format(budget.usedTokens) }}</strong>
                <span v-if="status.contextWindowTokens"> / {{ format(status.contextWindowTokens) }} tokens（总窗口 {{ status.usagePercent }}%）</span>
              </div>
              <div class="budget-overview" aria-label="上下文预算分类">
                <div><span>静态上下文</span><strong>{{ format(budget.staticTokens) }}</strong><small>无法通过压缩历史减少</small></div>
                <div><span>可压缩上下文</span><strong>{{ format(budget.reducibleTokens) }}</strong><small>项目上下文、消息与工具结果</small></div>
                <div><span>预留输出</span><strong>{{ format(budget.reservedOutputTokens) }}</strong><small>为本轮模型输出保留</small></div>
                <div><span>输入容量</span><strong>{{ format(budget.inputCapacityTokens) }}</strong><small>总窗口扣除预留输出</small></div>
              </div>
              <div class="effective-limit" :class="{ exceeded: budget.softLimitTokens > 0 && budget.usedTokens >= budget.softLimitTokens }" aria-label="有效自动压缩预算">
                <div>
                  <span>有效自动压缩线</span>
                  <strong>{{ format(budget.softLimitTokens) }} tokens</strong>
                  <small>{{ effectiveLimitHint }}</small>
                </div>
                <div>
                  <span>当前占有效压缩线</span>
                  <strong>{{ formatPercent(budget.usedAgainstSoftLimitPercent) }}</strong>
                  <small>{{ distanceHint }}</small>
                </div>
                <div>
                  <span>本轮上下文处理</span>
                  <strong>{{ trimStateLabel }}</strong>
                  <small>真正压缩以时间线 COMPACTION_COMPLETED 事件为准</small>
                </div>
              </div>
              <div class="summary-bar"><i v-for="row in rows" :key="row.key" :style="{ width: row.width + '%', background: row.color }" /></div>
              <div class="table-head"><span>组成</span><span>Tokens</span><span>占总窗口</span></div>
              <div class="rows">
                <div v-for="row in rows" :key="row.key" class="row">
                  <span class="category"><i :style="{ background: row.color }" />{{ row.label }}</span>
                  <span>{{ format(row.tokens) }}</span>
                  <span>{{ ratio(row.tokens) }}</span>
                </div>
              </div>
            </template>
            <div v-else class="empty"><strong>暂无上下文快照</strong><p>向 Agent 发送一条消息后，系统会在实际调用模型前记录用量。</p></div>
          </section>

          <section v-else class="dialog-body preview-body">
            <p class="preview-note">{{ previewLabel }}。内容会进行敏感信息脱敏；API Key、Bearer Token 等不会展示，过长分段会截断。</p>
            <div v-if="activeTab === 'prediction'" class="prediction-actions">
              <span>{{ predictionDraftHint }}</span>
              <button type="button" :disabled="predictionLoading" @click="$emit('load-next-preview')">{{ predictionLoading ? '正在生成...' : (prediction ? '重新生成' : '生成预测快照') }}</button>
            </div>
            <div v-if="previewSections.length" class="preview-list">
              <details v-for="section in previewSections" :key="section.key" class="preview-section">
                <summary>
                  <span>{{ sectionLabel(section.key) }}</span>
                  <small>{{ format(section.estimatedTokens) }} tokens{{ section.truncated ? ' · 已截断' : '' }}</small>
                </summary>
                <pre>{{ section.content }}</pre>
              </details>
            </div>
            <div v-else class="empty">
              <strong>{{ activeTab === 'prediction' ? '暂无下一次请求预测' : '暂无实际请求快照' }}</strong>
              <p>{{ activeTab === 'prediction' ? '生成预测不会调用 LLM 或写入会话。' : '首次 Agent 请求开始后，这里会显示当次发送给 LLM 的脱敏组装上下文。' }}</p>
            </div>
          </section>

          <footer>{{ footerText }}</footer>
        </section>
      </div>
    </Transition>
  </Teleport>
</template>

<script setup>
import { computed, ref } from 'vue'
import { normalizeContextBudget } from '@/composables/contextBudgetView'

const props = defineProps({
  open: Boolean,
  status: { type: Object, default: null },
  prediction: { type: Object, default: null },
  predictionLoading: Boolean
})
const emit = defineEmits(['close', 'load-next-preview'])

const activeTab = ref('usage')
const categories = [
  { key: 'systemPrompt', label: '系统提示', color: '#5d86e8' },
  { key: 'toolDefinitions', label: '工具定义', color: '#68b9ee' },
  { key: 'projectContext', label: '项目上下文', color: '#72c7b5' },
  { key: 'workspaceMemory', label: '工作区记忆', color: '#8bd98a' },
  { key: 'conversationMemory', label: '持久化会话记忆', color: '#6fc0a8' },
  { key: 'runRecoveryContext', label: '运行恢复上下文', color: '#d6c16c' },
  { key: 'compactionSummary', label: '压缩摘要', color: '#e58a6b' },
  { key: 'compactedContext', label: '旧版恢复上下文', color: '#bc7d6b' },
  { key: 'skillsAndInstructions', label: 'Skills 与指令', color: '#e6ad70' },
  { key: 'fixedInstructions', label: '固定运行指令', color: '#d5b66f' },
  { key: 'imageInputs', label: '图片输入', color: '#a07ad8' },
  { key: 'conversationMessages', label: '会话消息', color: '#b079d3' },
  { key: 'toolResults', label: '工具结果', color: '#ce85c7' },
  { key: 'messageProtocol', label: '协议元数据', color: '#8791a2' }
]
const sectionNames = Object.fromEntries(categories.map(item => [item.key, item.label]))
const trimStateNames = {
  NONE: '本轮未执行压缩',
  MODEL_CHECKPOINT: '本轮已生成模型压缩摘要',
  DETERMINISTIC_CHECKPOINT: '本轮已生成确定性压缩摘要',
  NEXT_REQUEST_ESTIMATE: '下一次请求预测'
}

const budget = computed(() => normalizeContextBudget(props.status || {}))
const hasSnapshot = computed(() => Boolean(props.status?.usedTokens > 0 || Object.keys(props.status?.categories || {}).length))
const displayedPreview = computed(() => activeTab.value === 'prediction' ? props.prediction : props.status)
const modelLabel = computed(() => displayedPreview.value?.model ? `当前模型：${displayedPreview.value.model}` : '等待首次模型请求')
const rows = computed(() => categories.map(item => {
  const tokens = Number(props.status?.categories?.[item.key] || 0)
  return { ...item, tokens, width: props.status?.contextWindowTokens ? Math.min(100, tokens * 100 / props.status.contextWindowTokens) : 0 }
}).filter(item => item.tokens > 0))
const effectiveLimitHint = computed(() => {
  if (!budget.value.softLimitTokens) return '当前快照没有可用的有效预算数据'
  if (budget.value.softLimitSource !== 'backend') return '旧快照未记录有效线，暂按输入容量估算'
  return `占总窗口 ${formatPercent(budget.value.softLimitOfWindowPercent)}；由输入容量、阈值和安全缓冲共同约束`
})
const distanceHint = computed(() => {
  if (!budget.value.softLimitTokens) return '等待后端生成预算快照'
  if (budget.value.usedTokens > budget.value.softLimitTokens) {
    return `已超过有效线 ${format(budget.value.usedTokens - budget.value.softLimitTokens)} tokens`
  }
  if (budget.value.usedTokens === budget.value.softLimitTokens) return '已到达有效触发线'
  return `距触发还剩 ${format(budget.value.distanceToSoftLimitTokens)} tokens`
})
const trimStateLabel = computed(() => trimStateNames[String(props.status?.trimState || 'NONE')] || `上下文处理：${props.status?.trimState}`)
const previewSections = computed(() => Array.isArray(displayedPreview.value?.previewSections) ? displayedPreview.value.previewSections : [])
const previewMetadata = computed(() => displayedPreview.value?.previewMetadata || {})
const previewLabel = computed(() => {
  if (activeTab.value === 'prediction') return '以下是基于当前会话状态、模型、模式和打开文件组装的下一次请求估算，它尚未发送给 LLM'
  return displayedPreview.value?.previewSource === 'LAST_ACTUAL_REQUEST'
    ? '以下是最近一次实际调用 LLM 前的组装快照'
    : '尚未取得实际请求快照'
})
const predictionDraftHint = computed(() => previewMetadata.value.nextUserMessageIncluded
  ? '已将输入框中的提问纳入预测。'
  : '当前没有输入提问，预测只包含已知的基础上下文。')
const footerText = computed(() => {
  if (activeTab.value === 'prediction') {
    return '这是 NEXT_REQUEST_ESTIMATE：不会调用模型、不写入会话、不会冒充为已发送内容；Token 计数为估算值。'
  }
  if (activeTab.value === 'usage') {
    return '展示的是最近一次实际请求前的预算快照；自动压缩以有效压缩线为准，真实压缩以持久化时间线事件为准。'
  }
  return `展示的是${displayedPreview.value?.previewSource === 'LAST_ACTUAL_REQUEST' ? '最近一次实际请求前' : '可用的'}上下文快照；Token 计数为估算值。`
})
const format = value => value >= 1_000_000 ? `${(value / 1_000_000).toFixed(1)}M` : value >= 1_000 ? `${(value / 1_000).toFixed(1)}K` : String(value || 0)
const formatPercent = value => `${Number(value || 0).toFixed(1)}%`
const ratio = tokens => props.status?.contextWindowTokens ? `${(tokens * 100 / props.status.contextWindowTokens).toFixed(1)}%` : '—'
const sectionLabel = key => sectionNames[key] || key

function selectPreviewTab(tab) {
  activeTab.value = tab
  if (tab === 'prediction' && !props.prediction && !props.predictionLoading) emit('load-next-preview')
}
</script>

<style scoped>
.context-dialog-backdrop { position: fixed; z-index: 4000; inset: 0; display: grid; place-items: center; padding: 20px; background: #080a0db8; backdrop-filter: blur(3px); }
.context-dialog { width: min(760px, 100%); max-height: min(820px, calc(100vh - 40px)); display: flex; flex-direction: column; overflow: hidden; border: 1px solid #3c4658; border-radius: 13px; background: #202328; color: #edf0f4; box-shadow: 0 30px 85px #000a; }
.dialog-header { display: flex; justify-content: space-between; padding: 22px 25px 14px; }.dialog-header h2 { margin: 0 0 8px; font-size: 21px; }.dialog-header p { margin: 0; color: #acb4c0; font-size: 14px; }.dialog-header button { border: 0; background: transparent; color: #9ca5b0; font-size: 28px; line-height: 20px; cursor: pointer; }
.tabs { display: flex; gap: 7px; padding: 0 25px 14px; border-bottom: 1px solid #383f49; overflow-x: auto; }.tabs button { border: 1px solid #454d5a; border-radius: 7px; background: #292e36; color: #aeb7c3; padding: 7px 11px; cursor: pointer; white-space: nowrap; }.tabs button.active { border-color: #6d8ded; background: #2e3d65; color: #eef3ff; }
.dialog-body { overflow: auto; padding: 18px 25px; }.budget-overview { display:grid; grid-template-columns:repeat(2,minmax(0,1fr)); gap:9px; margin:14px 0 9px; }.budget-overview div,.effective-limit > div { padding:11px 12px; border:1px solid #3f4855; border-radius:9px; background:#292e36; }.budget-overview span,.budget-overview small,.effective-limit span,.effective-limit small { display:block; color:#9fa9b6; font-size:11px; line-height:1.45; }.budget-overview strong,.effective-limit strong { display:block; margin:4px 0; color:#eef2f8; font-size:16px; font-variant-numeric:tabular-nums; }.effective-limit { display:grid; grid-template-columns:repeat(3,minmax(0,1fr)); gap:9px; margin-bottom:14px; }.effective-limit > div:first-child { border-color:#5976b8; background:#263148; }.effective-limit.exceeded > div:nth-child(2) { border-color:#b86c59; background:#472d29; }.dialog-summary strong { font-size: 23px; }.dialog-summary span { color: #a5adba; }.summary-bar { display: flex; height: 13px; margin-top: 14px; overflow: hidden; border-radius: 7px; background: #555c6c; }.summary-bar i { height: 100%; }
.table-head, .row { display: grid; grid-template-columns: 1fr 100px 60px; align-items: center; }.table-head { margin-top: 16px; padding: 12px 0; border-block: 1px solid #383f49; color: #8993a0; font-size: 11px; font-weight: 650; }.table-head span:not(:first-child), .row span:not(:first-child) { text-align: right; }.rows { padding: 6px 0; }.row { height: 39px; color: #c0c7d2; font-size: 14px; }.category { display: flex; align-items: center; gap: 10px; color: #c7cdd6; }.category i { width: 13px; height: 13px; border-radius: 3px; }
.preview-note { margin: 0 0 14px; color: #b5bfcc; font-size: 13px; line-height: 1.55; }.prediction-actions { display: flex; align-items: center; justify-content: space-between; gap: 12px; margin: 0 0 14px; padding: 10px; border: 1px solid #5e6f93; border-radius: 8px; background: #263148; color: #cbd7ef; font-size: 12px; line-height: 1.5; }.prediction-actions button { flex: 0 0 auto; border: 1px solid #7d9ee8; border-radius: 6px; background: #36518d; color: #f2f6ff; padding: 6px 9px; cursor: pointer; }.prediction-actions button:disabled { opacity: .65; cursor: wait; }
.preview-list { display: grid; gap: 9px; }.preview-section { border: 1px solid #39414d; border-radius: 8px; background: #181b20; }.preview-section summary { display: flex; justify-content: space-between; gap: 16px; padding: 10px 12px; cursor: pointer; color: #d8e0e9; }.preview-section summary small { color: #9ba6b5; white-space: nowrap; }.preview-section pre { max-height: 280px; overflow: auto; margin: 0; padding: 0 12px 12px; white-space: pre-wrap; word-break: break-word; color: #b9c3cf; font: 12px/1.55 ui-monospace, SFMono-Regular, Menlo, monospace; }
.context-dialog footer { padding: 14px 25px; border-top: 1px solid #383f49; color: #9fa8b4; font-size: 12px; }.empty { padding: 30px 0; color: #cbd1da; }.empty p { color: #9fa8b4; font-size: 13px; line-height: 1.6; }.context-dialog-enter-active, .context-dialog-leave-active { transition: opacity .2s ease; }.context-dialog-enter-active .context-dialog, .context-dialog-leave-active .context-dialog { transition: transform .2s ease; }.context-dialog-enter-from, .context-dialog-leave-to { opacity: 0; }.context-dialog-enter-from .context-dialog, .context-dialog-leave-to .context-dialog { transform: translateY(10px) scale(.98); }
@media (max-width: 700px) { .effective-limit { grid-template-columns:1fr; } }
@media (max-width: 560px) { .budget-overview { grid-template-columns:1fr; } .prediction-actions { align-items: flex-start; flex-direction: column; }.prediction-actions button { width: 100%; }.table-head, .row { grid-template-columns: 1fr 82px 64px; } }
</style>
