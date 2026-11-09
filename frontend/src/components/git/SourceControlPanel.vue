<template>
  <div class="sc-panel">
    <header class="sc-head">
      <span class="sc-head-title">源代码管理</span>
      <div class="sc-head-actions">
        <button class="sc-icon-btn" :disabled="busy || loading" :title="loading ? '刷新中' : '刷新'" @click="refresh">
          <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
            <polyline points="23 4 23 10 17 10" /><path d="M20.49 15a9 9 0 1 1-2.12-9.36L23 10" />
          </svg>
        </button>
        <button class="sc-icon-btn" title="clone 到本地" @click="openCloneDialog">
          <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
            <path d="M10 13a5 5 0 0 0 7.54.54l3-3a5 5 0 0 0-7.07-7.07l-1.72 1.71" />
            <path d="M14 11a5 5 0 0 0-7.54-.54l-3 3a5 5 0 0 0 7.07 7.07l1.71-1.71" />
          </svg>
        </button>
      </div>
    </header>

    <div v-if="errorMessage" class="sc-error">{{ errorMessage }}</div>

    <!-- 提交信息在上、主按钮紧随其下：与 VSCode 的源代码管理一致，先写信息再一键提交 -->
    <div class="sc-commit-box">
      <textarea
        v-model="message"
        class="sc-message"
        rows="2"
        :disabled="busy"
        :placeholder="commitPlaceholder"
        @keydown.ctrl.enter="submitCommit"
      ></textarea>
      <div class="sc-commit-actions">
        <button class="sc-primary" :disabled="busy || !commitReady" @click="submitCommit">
          <svg width="11" height="11" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="3">
            <polyline points="20 6 9 17 4 12" />
          </svg>
          <span>{{ busy ? '提交中…' : commitLabel }}</span>
        </button>
        <button class="sc-primary-caret" :disabled="busy || !entries.length" title="全选 / 取消全选" @click="toggleAll">
          <svg width="11" height="11" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.4">
            <polyline :points="allSelected ? '18 15 12 9 6 15' : '6 9 12 15 18 9'" />
          </svg>
        </button>
      </div>
    </div>

    <div class="sc-scroll">
      <section class="sc-section">
        <button class="sc-section-head" @click="changesOpen = !changesOpen">
          <svg class="sc-chevron" :class="{ collapsed: !changesOpen }" width="10" height="10" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5">
            <polyline points="6 9 12 15 18 9" />
          </svg>
          <span>更改</span>
          <span v-if="entries.length" class="sc-badge">{{ entries.length }}</span>
        </button>

        <p v-if="!entries.length" class="sc-hint">
          {{ initialized ? '没有未提交的改动' : '勾选文件并提交，建立第一条历史' }}
        </p>

        <ul v-else-if="changesOpen" class="sc-list">
          <li v-for="entry in entries" :key="entry.path">
            <div class="sc-row" :class="{ selected: selectedSet.has(entry.path), active: activePath === entry.path }">
              <input
                type="checkbox"
                class="sc-check"
                :checked="selectedSet.has(entry.path)"
                :disabled="busy"
                :aria-label="entry.path"
                @change="toggle(entry.path)"
              />
              <span class="sc-status" :class="'sc-status-' + statusClass(entry.status)">{{ statusLabel(entry.status) }}</span>
              <button class="sc-file" :title="entry.path" @click="emit('open-diff', { path: entry.path, source: 'working' })">
                <span class="sc-file-name">{{ entry.name }}</span>
                <span v-if="entry.dir" class="sc-file-dir">{{ entry.dir }}</span>
              </button>
              <span v-if="agentPaths.has(entry.path)" class="sc-agent-badge" title="本次对话里 Agent 也改过这个文件">Agent</span>
            </div>
          </li>
        </ul>
      </section>

      <section class="sc-section">
        <button class="sc-section-head" @click="graphOpen = !graphOpen">
          <svg class="sc-chevron" :class="{ collapsed: !graphOpen }" width="10" height="10" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5">
            <polyline points="6 9 12 15 18 9" />
          </svg>
          <span>图表</span>
          <span v-if="commits.length" class="sc-badge sc-badge-muted">{{ commits.length }}</span>
        </button>
        <GitGraphSection
          v-if="graphOpen"
          :project-id="projectId"
          :refresh-key="refreshKey + graphRefreshKey"
          :active-path="activePath"
          @open-diff="emit('open-diff', $event)"
          @reverted="handleReverted"
        />
      </section>
    </div>

    <div v-if="cloneUrl" class="sc-clone">
      <div class="sc-clone-title">clone 地址（一小时过期）</div>
      <div class="sc-clone-row">
        <input class="sc-clone-input" :value="cloneCommand" readonly @focus="$event.target.select()" />
        <button class="sc-icon-btn" title="复制" @click="copyClone">
          <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
            <rect x="9" y="9" width="13" height="13" rx="2" /><path d="M5 15H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v1" />
          </svg>
        </button>
      </div>
    </div>
  </div>
</template>

<script setup>
// 复刻 VSCode 源代码管理：提交信息在上、主按钮在其下、「更改」列表可折叠、
// 下方内嵌提交图表。点文件行不在侧边栏就地展开，而是把 diff 交给中栏（见 emit('open-diff')）——
// 侧边栏只有 260px 宽，diff 在那儿既看不清也抢走了列表空间。
// Agent 改动不在这里——那是 ChangesPanel，两套系统只共享工作区。
import { computed, onMounted, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'

import { projectApi } from '@/api'
import GitGraphSection from '@/components/git/GitGraphSection.vue'
import {
  buildCloneCommand,
  canCommit as canCommitModel,
  defaultSelection,
  splitGitPath,
  statusClass,
  statusLabel
} from '@/composables/git/sourceControlModel'

const props = defineProps({
  projectId: { type: [Number, String], default: null },
  agentPaths: { type: Array, default: () => [] },
  refreshKey: { type: [Number, String], default: 0 },
  /** 当前正在中栏展示的 diff 路径，用于高亮对应行。 */
  activePath: { type: String, default: '' }
})

const emit = defineEmits(['committed', 'changed', 'open-diff'])

const entries = ref([])
const commits = ref([])
const initialized = ref(false)
const unstagedCount = ref(0)
const stagedCount = ref(0)
const loading = ref(false)
const busy = ref(false)
const errorMessage = ref('')
const selected = ref([])
const selectionTouched = ref(false)
const message = ref('')
const cloneUrl = ref(null)
const changesOpen = ref(true)
const graphOpen = ref(true)
const graphRefreshKey = ref(0)

const selectedSet = computed(() => new Set(selected.value))
const agentPaths = computed(() => new Set(props.agentPaths || []))
const allSelected = computed(() => entries.value.length > 0 && selected.value.length === entries.value.length)
const commitReady = computed(() => canCommitModel(message.value, selected.value.length))
const commitLabel = computed(() => {
  const count = selected.value.length
  if (!count) return '提交'
  return `提交 ${count} 个文件`
})
const commitPlaceholder = computed(() => '信息 (Ctrl+Enter 提交)')
const cloneCommand = computed(() => buildCloneCommand(cloneUrl.value))

function splitPath(entry) {
  return splitGitPath(entry && entry.path)
}

/** 把接口返回的条目补上「文件名 / 目录」两段，模板里就不再重复解析。 */
function decorate(list) {
  return (list || []).map(entry => {
    const parts = splitGitPath(entry.path)
    return { ...entry, name: parts.name, dir: parts.dir }
  })
}

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
    entries.value = decorate(result.data.entries)
    initialized.value = Boolean(result.data.initialized)
    unstagedCount.value = result.data.unstagedCount || 0
    stagedCount.value = result.data.stagedCount || 0
    // 默认全选（VSCode 的点一下即提交）；用户动过勾选后只保留仍然存在的路径
    const all = defaultSelection(entries.value)
    selected.value = selectionTouched.value ? all.filter(path => selected.value.includes(path)) : all
    emit('changed', entries.value.length)
  } catch (error) {
    errorMessage.value = error?.response?.data?.message || error?.message || '读取工作区状态失败'
  } finally {
    loading.value = false
  }
}

async function refreshCommits() {
  if (!props.projectId) return
  try {
    const result = await projectApi.gitCommits(props.projectId, { limit: 30 })
    if (result.code === 0) commits.value = result.data.commits || []
  } catch (error) {
    commits.value = []
  }
}

function toggle(path) {
  selectionTouched.value = true
  selected.value = selectedSet.value.has(path)
    ? selected.value.filter(item => item !== path)
    : [...selected.value, path]
}

function toggleAll() {
  selectionTouched.value = true
  selected.value = allSelected.value ? [] : defaultSelection(entries.value)
}

async function submitCommit() {
  if (!commitReady.value || busy.value) return
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
    selected.value = []
    selectionTouched.value = false
    emit('committed', commitResult.data)
    await refresh()
    await refreshCommits()
  } catch (error) {
    errorMessage.value = error?.response?.data?.message || error?.message || '提交失败'
  } finally {
    busy.value = false
  }
}

async function handleReverted() {
  emit('committed', null)
  await refresh()
  await refreshCommits()
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

watch(() => props.projectId, async () => {
  await refresh()
  await refreshCommits()
})
watch(() => props.refreshKey, async () => {
  graphRefreshKey.value += 1
  await refresh()
  await refreshCommits()
})

onMounted(async () => {
  await refresh()
  await refreshCommits()
})

defineExpose({ refresh })
</script>

<style scoped lang="scss" src="@/styles/source-control-panel.scss"></style>
