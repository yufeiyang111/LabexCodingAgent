<template>
  <div class="git-panel">
    <div class="git-header">
      <div class="git-title">
        <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="#6366f1" stroke-width="2">
          <circle cx="12" cy="12" r="9" /><polyline points="12 7 12 12 15 14" />
        </svg>
        <span>提交历史</span>
      </div>
      <button class="git-icon-btn" :disabled="loading" title="刷新" @click="reload">
        <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
          <polyline points="23 4 23 10 17 10" /><path d="M20.49 15a9 9 0 1 1-2.12-9.36L23 10" />
        </svg>
      </button>
    </div>

    <div v-if="errorMessage" class="git-error">{{ errorMessage }}</div>

    <div v-if="loading && !commits.length" class="git-empty">正在读取历史…</div>

    <div v-else-if="!commits.length" class="git-empty">
      <span>还没有提交</span>
      <span class="git-empty-hint">在「源代码管理」里勾选文件并提交</span>
    </div>

    <div v-else class="git-body">
      <div class="git-list">
        <template v-for="group in groups" :key="group.label">
          <div class="gh-group">{{ group.label }}</div>
          <button
            v-for="commit in group.commits"
            :key="commit.sha"
            class="gh-row"
            :class="{ active: activeSha === commit.sha }"
            :title="commit.message"
            @click="openCommit(commit.sha)"
          >
            <span class="gh-graph" aria-hidden="true"></span>
            <span class="gh-sha">{{ commit.shortSha }}</span>
            <span class="gh-message">{{ commit.message }}</span>
            <span class="gh-time">{{ formatCommitTime(commit.committedAt) }}</span>
          </button>
        </template>
        <button v-if="nextBefore" class="gh-more" :disabled="loading" @click="loadMore">
          {{ loading ? '加载中…' : '加载更多' }}
        </button>
      </div>

      <div v-if="detail" class="git-detail">
        <div class="gh-detail-head">
          <div class="gh-detail-title">{{ detail.commit.message }}</div>
          <div class="gh-detail-meta">
            <span class="gh-detail-sha">{{ detail.commit.shortSha }}</span>
            <span>{{ detail.commit.authorName }}</span>
            <span>{{ formatDateTime(detail.commit.committedAt) }}</span>
          </div>
          <div class="gh-detail-actions">
            <button class="gh-btn" :disabled="reverting" @click="revertTo(detail.commit)">
              {{ reverting ? '回滚中…' : '回滚到此提交' }}
            </button>
          </div>
        </div>
        <div class="gh-files">
          <button
            v-for="file in detail.files"
            :key="file.path"
            class="gh-file"
            :class="{ active: activePath === file.path }"
            :title="file.path"
            @click="openFileDiff(file.path)"
          >
            <span class="git-status" :class="'git-status-' + statusClass(file.status)">{{ statusLabel(file.status) }}</span>
            <span class="gh-file-path">{{ file.oldPath ? file.oldPath + ' → ' : '' }}{{ file.path }}</span>
            <span v-if="file.binary" class="gh-file-stat">二进制</span>
            <span v-else class="gh-file-stat">
              <span v-if="file.additions" class="gh-add">+{{ file.additions }}</span>
              <span v-if="file.deletions" class="gh-del">-{{ file.deletions }}</span>
            </span>
          </button>
        </div>
        <DiffLines :diff="fileDiff" empty-label="选择一个文件查看 diff" />
      </div>
    </div>
  </div>
</template>

<script setup>
// 唯一职责：展示提交历史、单次提交的文件清单与 diff、回滚到某次提交。
// 回滚由后端生成一条新的 Revert 提交，历史不会被改写。
import { computed, onMounted, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'

import { projectApi } from '@/api'
import DiffLines from '@/components/git/DiffLines.vue'
import {
  formatCommitTime,
  groupCommitsByDay,
  statusClass,
  statusLabel
} from '@/composables/git/sourceControlModel'

const props = defineProps({
  projectId: { type: [Number, String], default: null },
  refreshKey: { type: [Number, String], default: 0 }
})

const emit = defineEmits(['reverted'])

const commits = ref([])
const nextBefore = ref(null)
const loading = ref(false)
const reverting = ref(false)
const errorMessage = ref('')
const activeSha = ref(null)
const detail = ref(null)
const activePath = ref(null)
const fileDiff = ref('')

const groups = computed(() => groupCommitsByDay(commits.value))

async function reload() {
  if (!props.projectId) return
  loading.value = true
  errorMessage.value = ''
  try {
    const result = await projectApi.gitCommits(props.projectId, {})
    if (result.code !== 0) {
      errorMessage.value = result.message || '读取提交历史失败'
      return
    }
    commits.value = result.data.commits || []
    nextBefore.value = result.data.nextBefore || null
    if (activeSha.value && !commits.value.some(commit => commit.sha === activeSha.value)) {
      activeSha.value = null
      detail.value = null
      fileDiff.value = ''
    }
  } catch (error) {
    errorMessage.value = error?.response?.data?.message || error?.message || '读取提交历史失败'
  } finally {
    loading.value = false
  }
}

async function loadMore() {
  if (!nextBefore.value || loading.value) return
  loading.value = true
  try {
    const result = await projectApi.gitCommits(props.projectId, { before: nextBefore.value })
    if (result.code === 0) {
      commits.value = [...commits.value, ...(result.data.commits || [])]
      nextBefore.value = result.data.nextBefore || null
    }
  } catch (error) {
    errorMessage.value = error?.response?.data?.message || error?.message || '加载更多失败'
  } finally {
    loading.value = false
  }
}

async function openCommit(sha) {
  if (activeSha.value === sha) {
    activeSha.value = null
    detail.value = null
    fileDiff.value = ''
    return
  }
  activeSha.value = sha
  detail.value = null
  activePath.value = null
  fileDiff.value = ''
  try {
    const result = await projectApi.gitCommitDetail(props.projectId, sha)
    if (result.code === 0) detail.value = result.data
    else errorMessage.value = result.message || '读取提交详情失败'
  } catch (error) {
    errorMessage.value = error?.response?.data?.message || error?.message || '读取提交详情失败'
  }
}

async function openFileDiff(path) {
  if (!activeSha.value) return
  activePath.value = path
  fileDiff.value = ''
  try {
    const result = await projectApi.gitCommitDiff(props.projectId, activeSha.value, path)
    if (result.code === 0) fileDiff.value = result.data.diff || ''
  } catch (error) {
    fileDiff.value = ''
  }
}

async function revertTo(commit) {
  if (reverting.value) return
  try {
    await ElMessageBox.confirm(
      `把工作区恢复到 ${commit.shortSha}「${commit.message}」。\n历史不会被删除，会新增一条 Revert 提交。\n工作区有未提交改动时会拒绝执行。`,
      '回滚到此提交',
      { confirmButtonText: '回滚', cancelButtonText: '取消', type: 'warning' }
    )
  } catch (cancelled) {
    return
  }
  reverting.value = true
  try {
    const result = await projectApi.gitRevert(props.projectId, commit.sha)
    if (result.code !== 0) {
      ElMessage.error(result.message || '回滚失败')
      return
    }
    ElMessage.success(`已生成回滚提交 ${result.data.shortSha}`)
    emit('reverted', result.data)
    await reload()
    await openCommit(result.data.sha)
  } catch (error) {
    ElMessage.error(error?.response?.data?.message || error?.message || '回滚失败')
  } finally {
    reverting.value = false
  }
}

function formatDateTime(value) {
  const date = value ? new Date(value) : null
  if (!date || Number.isNaN(date.getTime())) return ''
  return date.toLocaleString()
}

watch(() => props.projectId, reload)
watch(() => props.refreshKey, reload)
onMounted(reload)

defineExpose({ reload })
</script>

<style scoped lang="scss" src="@/styles/git-history-panel.scss"></style>
