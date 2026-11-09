<template>
  <div class="gg">
    <p v-if="!commits.length" class="gg-hint">
      {{ loading ? '正在读取历史…' : '还没有提交' }}
    </p>

    <template v-else>
      <div v-for="group in groups" :key="group.label" class="gg-group">
        <div class="gg-day">{{ group.label }}</div>
        <ul class="gg-list">
          <li v-for="commit in group.commits" :key="commit.sha" class="gg-item">
            <div class="gg-row" :class="{ active: activeSha === commit.sha, head: commit.sha === headSha }">
              <span class="gg-node" :class="{ head: commit.sha === headSha }" aria-hidden="true"></span>
              <button
                class="gg-message"
                :title="`${commit.message}\n${commit.authorName} · ${formatFullTime(commit.committedAt)}`"
                :aria-expanded="activeSha === commit.sha"
                @click="openCommit(commit.sha)"
              >
                {{ commit.message }}
              </button>
              <span v-if="commit.sha === headSha" class="gg-branch">{{ branchName }}</span>
              <span class="gg-time" :title="formatFullTime(commit.committedAt)">{{ formatCommitTime(commit.committedAt) }}</span>
            </div>

            <Transition name="gg-expand">
              <div v-if="activeSha === commit.sha" class="gg-detail">
                <div class="gg-meta">
                  <span class="gg-sha" :title="commit.sha">{{ commit.shortSha }}</span>
                  <span class="gg-author">
                    <IconUser />
                    {{ commit.authorName }}
                  </span>
                  <span>{{ formatFullTime(commit.committedAt) }}</span>
                </div>
                <ul v-if="detail" class="gg-files">
                  <li v-for="file in detail.files" :key="file.path">
                    <button
                      class="gg-file"
                      :class="{ active: activePath === file.path }"
                      :title="file.path"
                      @click="emit('open-diff', { path: file.path, source: 'commit', sha: commit.sha })"
                    >
                      <FileIcon class="gg-file-icon" :name="fileName(file.path)" :size="12" />
                      <span class="gg-file-name">{{ fileName(file.path) }}</span>
                      <span v-if="fileDir(file.path)" class="gg-file-dir">{{ fileDir(file.path) }}</span>
                      <span class="gg-stat" :class="'gg-stat-' + statusClass(file.status)">{{ statusLabel(file.status) }}</span>
                      <span v-if="file.binary" class="gg-num gg-num-binary">二进制</span>
                      <span v-else class="gg-num">
                        <span v-if="file.additions" class="gg-add">+{{ file.additions }}</span>
                        <span v-if="file.deletions" class="gg-del">-{{ file.deletions }}</span>
                      </span>
                    </button>
                  </li>
                </ul>
                <div v-else class="gg-loading">正在读取文件清单…</div>
                <div class="gg-actions">
                  <button class="gg-revert" :disabled="reverting" @click="revertTo(commit.sha, commit.message)">
                    <IconUndo />
                    {{ reverting ? '回滚中…' : '回滚到此提交' }}
                  </button>
                </div>
              </div>
            </Transition>
          </li>
        </ul>
      </div>
    </template>

    <button v-if="nextBefore" class="gg-more" :disabled="loading" @click="loadMore">
      {{ loading ? '加载中…' : '加载更多' }}
    </button>
  </div>
</template>

<script setup>
// 提交图表：提交列表 + 单条展开的文件清单，点文件把 diff 交给中栏（emit('open-diff')）。
// 回滚由后端新增一条 Revert 提交，历史不会被改写（reset --hard 会让后续提交消失）。
//
// 列表按天分组：一天里提十几条时，连续的同一天时间戳会把「哪天做的」淹没在相对时间里，
// 分组标题承担日期、行内只留相对时间，扫读成本更低。
import { computed, onMounted, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import IconUndo from '~icons/lucide/undo-2'
import IconUser from '~icons/lucide/user'

import { projectApi } from '@/api'
import FileIcon from '@/components/icons/FileIcon.vue'
import { formatFullTime } from '@/constants/conversationList'
import { formatCommitTime, groupCommitsByDay, splitGitPath, statusClass, statusLabel } from '@/composables/git/sourceControlModel'

const props = defineProps({
  projectId: { type: [Number, String], default: null },
  refreshKey: { type: [Number, String], default: 0 },
  branchName: { type: String, default: 'master' },
  /** 当前正在中栏展示的 diff 路径，用于高亮对应行。 */
  activePath: { type: String, default: '' }
})

const emit = defineEmits(['reverted', 'open-diff'])

const commits = ref([])
const nextBefore = ref(null)
const loading = ref(false)
const reverting = ref(false)
const activeSha = ref(null)
const detail = ref(null)

const groups = computed(() => groupCommitsByDay(commits.value))
const headSha = computed(() => commits.value[0]?.sha || null)

function fileName(path) {
  return splitGitPath(path).name
}
function fileDir(path) {
  return splitGitPath(path).dir
}

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
    return
  }
  activeSha.value = sha
  detail.value = null
  try {
    const result = await projectApi.gitCommitDetail(props.projectId, sha)
    if (result.code === 0) detail.value = result.data
  } catch (error) {
    detail.value = null
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
    await reload()
  } catch (error) {
    ElMessage.error(error?.response?.data?.message || error?.message || '回滚失败')
  } finally {
    reverting.value = false
  }
}

watch(() => props.projectId, reload)
watch(() => props.refreshKey, reload)
onMounted(reload)

defineExpose({ reload })
</script>

<style scoped lang="scss" src="@/styles/git-graph-section.scss"></style>
