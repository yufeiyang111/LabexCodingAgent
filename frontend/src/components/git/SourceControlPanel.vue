<template>
  <div class="sc-panel">
    <header class="sc-head">
      <span class="sc-head-title">源代码管理</span>
      <span class="sc-branch" :title="`当前分支：${BRANCH_NAME}`">
        <IconGitBranch />
        {{ BRANCH_NAME }}
      </span>
      <div class="sc-head-actions">
        <button
          class="sc-icon-btn"
          :class="{ 'is-busy': loading }"
          :disabled="busy || loading"
          :title="loading ? '刷新中' : '刷新'"
          aria-label="刷新"
          @click="refresh"
        >
          <IconRefreshCw />
        </button>
        <button class="sc-icon-btn" :class="{ 'is-on': cloneOpen }" title="clone 到本地" aria-label="clone 到本地" @click="toggleClone">
          <IconLink />
        </button>
      </div>
    </header>

    <div v-if="errorMessage" class="sc-error" role="alert">{{ errorMessage }}</div>

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
      <div class="sc-commit-actions" :class="{ 'is-ready': commitReady }">
        <button class="sc-primary" :disabled="busy || !commitReady" @click="submitCommit">
          <IconLoader v-if="busy" class="sc-spin" />
          <IconCheck v-else />
          <span>{{ busy ? '提交中…' : commitLabel }}</span>
        </button>
        <button
          class="sc-primary-caret"
          :disabled="busy || !entries.length"
          :title="allSelected ? '取消全选' : '全选'"
          :aria-label="allSelected ? '取消全选' : '全选'"
          @click="toggleAll"
        >
          <IconListChecks />
        </button>
      </div>
    </div>

    <div class="sc-scroll">
      <section class="sc-section">
        <button class="sc-section-head" :aria-expanded="changesOpen" @click="changesOpen = !changesOpen">
          <IconChevronDown class="sc-chevron" :class="{ collapsed: !changesOpen }" />
          <IconFileDiff class="sc-section-icon" />
          <span>更改</span>
          <span v-if="entries.length" class="sc-badge" :title="`未暂存 ${unstagedCount} 个 · 已暂存 ${stagedCount} 个`">{{ entries.length }}</span>
        </button>

        <div v-if="changesOpen && !entries.length" class="sc-empty">
          <IconCircleCheck class="sc-empty-icon" />
          <p class="sc-empty-title">{{ initialized ? '没有未提交的改动' : '还没有提交历史' }}</p>
          <p class="sc-empty-hint">
            {{ initialized ? '工作区与最近一次提交一致' : '勾选文件并提交，建立第一条历史' }}
          </p>
        </div>

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
              <button
                class="sc-file"
                :title="entry.path"
                @click="emit('open-diff', { path: entry.path, source: 'working' })"
              >
                <FileIcon class="sc-file-icon" :name="entry.name" :size="13" />
                <span class="sc-file-name">{{ entry.name }}</span>
                <span v-if="entry.dir" class="sc-file-dir">{{ entry.dir }}</span>
                <span v-if="agentPaths.has(entry.path)" class="sc-agent-badge" title="本次对话里 Agent 也改过这个文件">Agent</span>
                <span class="sc-status" :class="'sc-status-' + statusClass(entry.status)" :title="statusText(entry.status)">
                  {{ statusLabel(entry.status) }}
                </span>
                <IconChevronRight class="sc-open-hint" />
              </button>
            </div>
          </li>
        </ul>
      </section>

      <section class="sc-section">
        <button class="sc-section-head" :aria-expanded="graphOpen" @click="graphOpen = !graphOpen">
          <IconChevronDown class="sc-chevron" :class="{ collapsed: !graphOpen }" />
          <IconGitCommit class="sc-section-icon" />
          <span>图表</span>
          <span v-if="commits.length" class="sc-badge sc-badge-muted">{{ commits.length }}</span>
        </button>
        <GitGraphSection
          v-if="graphOpen"
          :project-id="projectId"
          :branch-name="BRANCH_NAME"
          :refresh-key="refreshKey + graphRefreshKey"
          :active-path="activePath"
          @open-diff="emit('open-diff', $event)"
          @reverted="handleReverted"
        />
      </section>
    </div>

    <div class="sc-clone" :class="{ 'is-open': cloneOpen }">
      <button class="sc-clone-head" :aria-expanded="cloneOpen" @click="toggleClone">
        <IconLink class="sc-clone-icon" />
        <span>clone 地址</span>
        <span class="sc-clone-expiry">一小时过期</span>
        <IconChevronDown class="sc-chevron" :class="{ collapsed: !cloneOpen }" />
      </button>
      <div v-if="cloneOpen" class="sc-clone-body">
        <div v-if="cloneLoading" class="sc-clone-loading">正在获取…</div>
        <template v-else-if="cloneUrl">
          <div class="sc-clone-row">
            <input class="sc-clone-input" :value="cloneCommand" readonly aria-label="clone 命令" @focus="$event.target.select()" />
            <button class="sc-copy-btn" :class="{ 'is-done': copied }" title="复制命令" @click="copyClone">
              <IconCheck v-if="copied" />
              <IconCopy v-else />
              <span>{{ copied ? '已复制' : '复制' }}</span>
            </button>
          </div>
        </template>
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
import IconCheck from '~icons/lucide/check'
import IconChevronDown from '~icons/lucide/chevron-down'
import IconChevronRight from '~icons/lucide/chevron-right'
import IconCircleCheck from '~icons/lucide/circle-check'
import IconCopy from '~icons/lucide/copy'
import IconFileDiff from '~icons/lucide/file-diff'
import IconGitBranch from '~icons/lucide/git-branch'
import IconGitCommit from '~icons/lucide/git-commit-horizontal'
import IconLink from '~icons/lucide/link'
import IconListChecks from '~icons/lucide/list-checks'
import IconLoader from '~icons/lucide/loader'
import IconRefreshCw from '~icons/lucide/refresh-cw'

import { projectApi } from '@/api'
import FileIcon from '@/components/icons/FileIcon.vue'
import GitGraphSection from '@/components/git/GitGraphSection.vue'
import {
  BRANCH_NAME,
  buildCloneCommand,
  canCommit as canCommitModel,
  defaultSelection,
  splitGitPath,
  statusClass,
  statusLabel,
  statusText
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
const cloneOpen = ref(false)
const cloneLoading = ref(false)
const copied = ref(false)
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

/** 展开 clone 区块时按需取地址：令牌一小时过期，提前拉没有意义。 */
async function toggleClone() {
  cloneOpen.value = !cloneOpen.value
  if (!cloneOpen.value || cloneUrl.value || cloneLoading.value) return
  cloneLoading.value = true
  try {
    const result = await projectApi.gitCloneUrl(props.projectId)
    if (result.code !== 0) {
      ElMessage.error(result.message || '获取 clone 地址失败')
      return
    }
    cloneUrl.value = result.data
  } catch (error) {
    ElMessage.error(error?.response?.data?.message || error?.message || '获取 clone 地址失败')
  } finally {
    cloneLoading.value = false
  }
}

async function copyClone() {
  try {
    await navigator.clipboard.writeText(cloneCommand.value)
    copied.value = true
    setTimeout(() => { copied.value = false }, 1600)
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
