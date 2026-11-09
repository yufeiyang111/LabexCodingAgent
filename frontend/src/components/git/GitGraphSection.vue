<template>
  <div class="gg">
    <p v-if="!commits.length" class="gg-hint">
      {{ loading ? '正在读取历史…' : '还没有提交' }}
    </p>

    <ul v-else class="gg-list">
      <li v-for="(commit, index) in commits" :key="commit.sha" class="gg-item">
        <div class="gg-row" :class="{ active: activeSha === commit.sha }">
          <span class="gg-node" :class="{ head: index === 0 }" aria-hidden="true"></span>
          <button class="gg-message" :title="commit.message" @click="openCommit(commit.sha)">
            {{ commit.message }}
          </button>
          <span v-if="index === 0" class="gg-branch">{{ branchName }}</span>
        </div>

        <div v-if="activeSha === commit.sha" class="gg-detail">
          <div class="gg-meta">
            <span class="gg-sha">{{ commit.shortSha }}</span>
            <span>{{ commit.authorName }}</span>
            <span>{{ formatDateTime(commit.committedAt) }}</span>
          </div>
          <ul v-if="detail" class="gg-files">
            <li v-for="file in detail.files" :key="file.path">
              <button
                class="gg-file"
                :class="{ active: activePath === file.path }"
                :title="file.path"
                @click="openFileDiff(file.path)"
              >
                <span class="gg-status" :class="'gg-status-' + statusClass(file.status)">{{ statusLabel(file.status) }}</span>
                <span class="gg-file-name">{{ file.path }}</span>
                <span v-if="file.binary" class="gg-stat">二进制</span>
                <span v-else class="gg-stat">
                  <span v-if="file.additions" class="gg-add">+{{ file.additions }}</span>
                  <span v-if="file.deletions" class="gg-del">-{{ file.deletions }}</span>
                </span>
              </button>
              <DiffLines v-if="activePath === file.path" :diff="fileDiff" empty-label="正在读取 diff…" />
            </li>
          </ul>
          <div class="gg-actions">
            <button class="gg-revert" :disabled="reverting" @click="revertTo(commit.sha, commit.message)">
              {{ reverting ? '回滚中…' : '回滚到此提交' }}
            </button>
          </div>
        </div>
      </li>
    </ul>

    <button v-if="nextBefore" class="gg-more" :disabled="loading" @click="loadMore">
      {{ loading ? '加载中…' : '加载更多' }}
    </button>
  </div>
</template>

<script setup>
// 提交图表：提交列表 + 单条展开的文件清单与 diff + 回滚。
// 回滚由后端新增一条 Revert 提交，历史不会被改写（reset --hard 会让后续提交消失）。
import { onMounted, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'

import { projectApi } from '@/api'
import DiffLines from '@/components/git/DiffLines.vue'
import { statusClass, statusLabel } from '@/composables/git/sourceControlModel'

const props = defineProps({
  projectId: { type: [Number, String], default: null },
  refreshKey: { type: [Number, String], default: 0 },
  branchName: { type: String, default: 'master' }
})

const emit = defineEmits(['reverted'])

const commits = ref([])
const nextBefore = ref(null)
const loading = ref(false)
const reverting = ref(false)
const activeSha = ref(null)
const detail = ref(null)
const activePath = ref(null)
const fileDiff = ref('')

async function reload() {
  if (!props.projectId) return
  loading.value = true
  try {
    const result = await projectApi.gitCommits(props.projectId, { limit: 20 })
    if (result.code === 0) {
      commits.value = result.data.commits || []
      nextBefore.value = result.data.nextBefore || null
      if (activeSha.value && !commits.value.some(commit => commit.sha === activeSha.value)) {
        activeSha.value = null
        detail.value = null
        fileDiff.value = ''
        activePath.value = null
      }
    }
  } catch (error) {
    commits.value = []
  } finally {
    loading.value = false
  }
}

async function loadMore() {
  if (!nextBefore.value || loading.value) return
  loading.value = true
  try {
    const result = await projectApi.gitCommits(props.projectId, { limit: 20, before: nextBefore.value })
    if (result.code === 0) {
      commits.value = [...commits.value, ...(result.data.commits || [])]
      nextBefore.value = result.data.nextBefore || null
    }
  } catch (error) {
    // 分页失败保留已加载的部分，不打断浏览
  } finally {
    loading.value = false
  }
}

async function openCommit(sha) {
  if (activeSha.value === sha) {
    activeSha.value = null
    detail.value = null
    activePath.value = null
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
  } catch (error) {
    detail.value = null
  }
}

async function openFileDiff(path) {
  if (!activeSha.value) return
  if (activePath.value === path) {
    activePath.value = null
    fileDiff.value = ''
    return
  }
  activePath.value = path
  fileDiff.value = ''
  try {
    const result = await projectApi.gitCommitDiff(props.projectId, activeSha.value, path)
    if (result.code === 0) fileDiff.value = result.data.diff || ''
  } catch (error) {
    fileDiff.value = ''
  }
}

async function revertTo(sha, message) {
  if (reverting.value) return
  try {
    await ElMessageBox.confirm(
      `把工作区恢复到 ${sha.slice(0, 7)}「${message}」。\n历史不会被删除，会新增一条 Revert 提交。\n工作区有未提交改动时会拒绝执行。`,
      '回滚到此提交',
      { confirmButtonText: '回滚', cancelButtonText: '取消', type: 'warning' }
    )
  } catch (cancelled) {
    return
  }
  reverting.value = true
  try {
    const result = await projectApi.gitRevert(props.projectId, sha)
    if (result.code !== 0) {
      ElMessage.error(result.message || '回滚失败')
      return
    }
    ElMessage.success(`已生成回滚提交 ${result.data.shortSha}`)
    emit('reverted', result.data)
    activeSha.value = null
    detail.value = null
    fileDiff.value = ''
    await reload()
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

<style scoped lang="scss" src="@/styles/git-graph-section.scss"></style>
