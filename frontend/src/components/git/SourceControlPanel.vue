<template>
  <div class="git-panel">
    <div class="git-header">
      <div class="git-title">
        <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="#6366f1" stroke-width="2">
          <circle cx="18" cy="6" r="3" /><circle cx="6" cy="18" r="3" />
          <path d="M18 9v3a3 3 0 0 1-3 3H9" /><path d="M6 15V9" />
        </svg>
        <span>源代码管理</span>
      </div>
      <div class="git-header-actions">
        <span v-if="stagedCount" class="git-count">{{ stagedCount }} 已暂存</span>
        <span v-if="unstagedCount" class="git-count git-count-muted">{{ unstagedCount }} 未暂存</span>
        <button class="git-icon-btn" :disabled="busy || loading" :title="busy ? '刷新中' : '刷新'" @click="refresh">
          <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
            <polyline points="23 4 23 10 17 10" /><path d="M20.49 15a9 9 0 1 1-2.12-9.36L23 10" />
          </svg>
        </button>
        <button class="git-icon-btn" title="clone 到本地" @click="openCloneDialog">
          <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
            <path d="M10 13a5 5 0 0 0 7.54.54l3-3a5 5 0 0 0-7.07-7.07l-1.72 1.71" />
            <path d="M14 11a5 5 0 0 0-7.54-.54l-3 3a5 5 0 0 0 7.07 7.07l1.71-1.71" />
          </svg>
        </button>
      </div>
    </div>

    <div v-if="errorMessage" class="git-error">{{ errorMessage }}</div>

    <div v-if="loading && !entries.length" class="git-empty">正在读取工作区状态…</div>

    <div v-else-if="!entries.length" class="git-empty">
      <svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="#555" stroke-width="1.5">
        <polyline points="20 6 9 17 4 12" />
      </svg>
      <span>没有未提交的改动</span>
      <span v-if="!initialized" class="git-empty-hint">勾选文件并提交，建立第一条历史</span>
    </div>

    <div v-else class="git-body">
      <div class="git-list">
        <label v-for="entry in entries" :key="entry.path" class="sc-row" :class="{ selected: selected.has(entry.path) }">
          <input
            type="checkbox"
            :checked="selected.has(entry.path)"
            :disabled="busy"
            @change="toggle(entry.path)"
          />
          <span class="git-status" :class="'git-status-' + statusClass(entry.status)">{{ statusLabel(entry.status) }}</span>
          <button class="sc-path" :title="entry.path" @click.stop.prevent="toggleDetail(entry.path)">
            {{ entry.oldPath ? entry.oldPath + ' → ' : '' }}{{ entry.path }}
          </button>
          <span v-if="agentPaths.has(entry.path)" class="sc-agent-badge" title="本次对话里 Agent 也改过这个文件">Agent</span>
        </label>
      </div>

      <div v-if="detailPath" class="git-detail">
        <div class="git-detail-header">
          <span class="git-detail-path">{{ detailPath }}</span>
          <button class="git-icon-btn" title="收起" @click="detailPath = null">
            <svg width="11" height="11" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
              <line x1="18" y1="6" x2="6" y2="18" /><line x1="6" y1="6" x2="18" y2="18" />
            </svg>
          </button>
        </div>
        <DiffLines :diff="detailDiff" empty-label="正在读取 diff…" />
      </div>
    </div>

    <div class="sc-commit">
      <textarea
        v-model="message"
        class="sc-message"
        rows="3"
        :disabled="busy"
        placeholder="提交信息（第一行是标题）"
        @keydown.ctrl.enter="submitCommit"
      ></textarea>
      <div class="sc-commit-row">
        <label class="sc-select-all">
          <input
            type="checkbox"
            :checked="allSelected"
            :disabled="busy || !entries.length"
            @change="toggleAll"
          />
          全选
        </label>
        <button class="sc-commit-btn" :disabled="busy || !canCommit" @click="submitCommit">
          {{ busy ? '提交中…' : commitLabel }}
        </button>
      </div>
    </div>

    <div v-if="cloneUrl" class="sc-clone">
      <div class="sc-clone-title">clone 地址（一小时过期）</div>
      <div class="sc-clone-row">
        <input class="sc-clone-input" :value="cloneCommand" readonly @focus="$event.target.select()" />
        <button class="git-icon-btn" title="复制" @click="copyClone">
          <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
            <rect x="9" y="9" width="13" height="13" rx="2" /><path d="M5 15H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v1" />
          </svg>
        </button>
      </div>
    </div>
  </div>
</template>

<script setup>
// 唯一职责：展示工作区相对 HEAD 的未提交改动，让用户勾选并提交。
// 这里不涉及 Agent 概念——Agent 改动的 diff / undo 由 ChangesPanel 负责。
import { computed, onMounted, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'

import { projectApi } from '@/api'
import DiffLines from '@/components/git/DiffLines.vue'
import { buildCloneCommand, statusClass, statusLabel } from '@/composables/git/sourceControlModel'

const props = defineProps({
  projectId: { type: [Number, String], default: null },
  // 当前对话里 Agent 改过的路径，只用于角标提示，不影响 git 行为
  agentPaths: { type: Array, default: () => [] },
  refreshKey: { type: [Number, String], default: 0 }
})

const emit = defineEmits(['committed'])

const entries = ref([])
const initialized = ref(false)
const unstagedCount = ref(0)
const stagedCount = ref(0)
const loading = ref(false)
const busy = ref(false)
const errorMessage = ref('')
const selected = ref(new Set())
const message = ref('')
const detailPath = ref(null)
const detailDiff = ref('')
const cloneUrl = ref(null)

const agentPaths = computed(() => new Set(props.agentPaths || []))
const allSelected = computed(() => entries.value.length > 0 && selected.value.size === entries.value.length)
const canCommit = computed(() => selected.value.size > 0 && message.value.trim().length > 0)
const commitLabel = computed(() => (initialized.value ? `提交 ${selected.value.size} 个文件` : '创建首次提交'))
const cloneCommand = computed(() => buildCloneCommand(cloneUrl.value))

async function refresh() {
  if (!props.projectId) return
  loading.value = true
  errorMessage.value = ''
  try {
    const result = await projectApi.gitStatus(props.projectId)
    if (result.code !== 0) {
      errorMessage.value = result.message || '读取工作区状态失败'
      return
    }
    entries.value = result.data.entries || []
    initialized.value = Boolean(result.data.initialized)
    unstagedCount.value = result.data.unstagedCount || 0
    stagedCount.value = result.data.stagedCount || 0
    // 刷新后保留仍然存在的勾选，避免用户刚勾好的文件被一次刷新清空
    const present = new Set(entries.value.map(entry => entry.path))
    selected.value = new Set([...selected.value].filter(path => present.has(path)))
  } catch (error) {
    errorMessage.value = error?.response?.data?.message || error?.message || '读取工作区状态失败'
  } finally {
    loading.value = false
  }
}

function toggle(path) {
  const next = new Set(selected.value)
  if (next.has(path)) next.delete(path)
  else next.add(path)
  selected.value = next
}

function toggleAll() {
  selected.value = allSelected.value ? new Set() : new Set(entries.value.map(entry => entry.path))
}

async function toggleDetail(path) {
  if (detailPath.value === path) {
    detailPath.value = null
    return
  }
  detailPath.value = path
  detailDiff.value = ''
  try {
    const result = await projectApi.gitWorkingDiff(props.projectId, path)
    if (result.code === 0) detailDiff.value = result.data.diff || ''
  } catch (error) {
    detailDiff.value = ''
  }
}

async function submitCommit() {
  if (!canCommit.value || busy.value) return
  busy.value = true
  errorMessage.value = ''
  try {
    const stageResult = await projectApi.gitStage(props.projectId, [...selected.value])
    if (stageResult.code !== 0) {
      errorMessage.value = stageResult.message || '暂存失败'
      return
    }
    const commitResult = await projectApi.gitCommit(props.projectId, message.value.trim())
    if (commitResult.code !== 0) {
      errorMessage.value = commitResult.message || '提交失败'
      return
    }
    ElMessage.success(`已提交 ${commitResult.data.fileCount} 个文件`)
    message.value = ''
    selected.value = new Set()
    detailPath.value = null
    emit('committed', commitResult.data)
    await refresh()
  } catch (error) {
    errorMessage.value = error?.response?.data?.message || error?.message || '提交失败'
  } finally {
    busy.value = false
  }
}

async function openCloneDialog() {
  try {
    const result = await projectApi.gitCloneUrl(props.projectId)
    if (result.code !== 0) {
      ElMessage.error(result.message || '获取 clone 地址失败')
      return
    }
    cloneUrl.value = result.data
  } catch (error) {
    ElMessage.error(error?.response?.data?.message || error?.message || '获取 clone 地址失败')
  }
}

async function copyClone() {
  try {
    await navigator.clipboard.writeText(cloneCommand.value)
    ElMessage.success('已复制')
  } catch (error) {
    ElMessage.warning('复制失败，请手动选择文本')
  }
}

watch(() => props.projectId, refresh)
watch(() => props.refreshKey, refresh)
onMounted(refresh)

defineExpose({ refresh })
</script>

<style scoped lang="scss" src="@/styles/source-control-panel.scss"></style>
