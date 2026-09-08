<template>
  <div class="cs-shell" :class="{ 'is-resizing': isResizingSidebar, 'is-mobile': isMobile, 'mobile-show-detail': isMobile && mobileActiveView === 'detail' }">
    <div
      class="cs-left"
      :class="{ 'is-resizing': isResizingSidebar }"
      :style="{ '--cs-sidebar-w': `${sidebarWidth}px` }"
    >
      <div class="cs-panel-header">
        <h2>项目列表</h2>
        <div class="cs-header-actions">
          <button class="cs-btn cs-btn-primary" title="新建项目" @click="showCreate = true">
            <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><line x1="12" y1="5" x2="12" y2="19"/><line x1="5" y1="12" x2="19" y2="12"/></svg>
            <span class="cs-btn-text">新建</span>
          </button>
          <button class="cs-btn cs-btn-outline" title="上传项目" @click="triggerUpload">
            <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"/><polyline points="17 8 12 3 7 8"/><line x1="12" y1="3" x2="12" y2="15"/></svg>
            <span class="cs-btn-text">上传</span>
          </button>
          <input ref="uploadInput" type="file" accept=".zip" hidden @change="handleUpload" />
        </div>
      </div>
      <div class="cs-list" v-loading="listLoading">
        <div v-if="!listLoading && projects.length === 0" class="cs-empty">
          <svg width="36" height="36" viewBox="0 0 24 24" fill="none" stroke="#d1d5db" stroke-width="1.5"><path d="M22 19a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h5l2 3h9a2 2 0 0 1 2 2z"/></svg>
          <p>暂无项目</p>
          <p class="cs-empty-hint">点击上方按钮新建或上传项目</p>
        </div>
        <TransitionGroup name="proj-list" tag="div">
          <div v-for="proj in projects" :key="proj.projectId" class="cs-item" :class="{ active: selectedId === proj.projectId }" @click="selectProject(proj)">
            <div class="cs-item-icon">
              <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5"><path d="M22 19a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h5l2 3h9a2 2 0 0 1 2 2z"/></svg>
            </div>
            <div class="cs-item-body">
              <div class="cs-item-name">{{ proj.projectName }}</div>
              <div class="cs-item-meta">{{ proj.fileCount || 0 }} 个文件</div>
            </div>
            <div class="cs-item-actions">
              <button class="cs-btn cs-btn-ghost cs-btn-sm" title="编辑名称" @click.stop="startRename(proj)">
                <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M11 4H4a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h14a2 2 0 0 0 2-2v-7"/><path d="M18.5 2.5a2.121 2.121 0 0 1 3 3L12 15l-4 1 1-4 9.5-9.5z"/></svg>
              </button>
              <button class="cs-btn cs-btn-ghost cs-btn-sm" title="进入工作空间" @click.stop="enterWorkspace(proj)">
                <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><polyline points="15 3 21 3 21 9"/><polyline points="9 21 3 21 3 15"/><line x1="21" y1="3" x2="14" y2="10"/><line x1="3" y1="21" x2="10" y2="14"/></svg>
              </button>
              <button class="cs-btn cs-btn-ghost cs-btn-sm" title="导出项目" @click.stop="exportProject(proj)">
                <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"/><polyline points="7 10 12 15 17 10"/><line x1="12" y1="15" x2="12" y2="3"/></svg>
              </button>
              <button class="cs-btn cs-btn-ghost cs-btn-sm cs-btn-danger" title="删除项目" @click.stop="deleteProject(proj)">
                <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><polyline points="3 6 5 6 21 6"/><path d="M19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6m3 0V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2"/></svg>
              </button>
            </div>
          </div>
        </TransitionGroup>
      </div>
      <UserPanel />
    </div>
    <div class="cs-resize-handle" title="拖拽调整侧边栏宽度" @pointerdown="startSidebarResize"></div>
    <div class="cs-right">
      <div v-if="!selectedProject" class="cs-right-empty">
        <svg width="48" height="48" viewBox="0 0 24 24" fill="none" stroke="#d1d5db" stroke-width="1"><rect x="2" y="3" width="20" height="14" rx="2" ry="2"/><line x1="8" y1="21" x2="16" y2="21"/><line x1="12" y1="17" x2="12" y2="21"/></svg>
        <p>请选择一个项目</p>
      </div>
      <div v-else class="cs-right-panel">
        <div class="cs-panel-header cs-right-header">
          <div class="cs-right-title">
            <button
              v-if="isMobile"
              class="cs-btn cs-btn-ghost cs-btn-sm cs-mobile-back"
              title="返回项目列表"
              @click="mobileActiveView = 'list'"
            >
              <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><polyline points="15 18 9 12 15 6"/></svg>
              <span>列表</span>
            </button>
            <h3>{{ selectedProject.projectName }}</h3>
            <span class="cs-right-meta">{{ selectedProject.fileCount || 0 }} 个文件</span>
          </div>
          <button class="cs-btn cs-btn-primary" @click="enterWorkspace(selectedProject)">
            <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><polyline points="15 3 21 3 21 9"/><polyline points="9 21 3 21 3 15"/><line x1="21" y1="3" x2="14" y2="10"/><line x1="3" y1="21" x2="10" y2="14"/></svg>
            <span class="cs-open-ws-text">打开工作空间</span>
          </button>
        </div>
        <div class="cs-tree-panel" v-loading="treeLoading" @scroll="handleTreeScroll">
          <div v-if="treeError" class="cs-tree-error" role="alert">
            <span>{{ treeError }}</span>
            <button type="button" @click="selectProject(selectedProject)">重试</button>
          </div>
          <TransitionGroup name="ftn-list" tag="div">
            <FileTreeNode v-for="child in fileTree" :key="child.path" :node="child" :selected-path="selectedPath" :load-children="loadTreeChildren" :context-menu-enabled="false" @select="onFileSelect"/>
          </TransitionGroup>
          <button v-if="treeNextOffset !== null" class="cs-tree-load-more" type="button" @click="loadMoreTree">加载更多文件</button>
          <div v-if="!treeLoading && !treeError && fileTree.length === 0" class="cs-empty" style="padding:24px">
            <p>项目为空</p>
          </div>
        </div>
      </div>
    </div>
    <Teleport to="body">
      <Transition name="modal">
        <div v-if="showCreate" class="cs-overlay" @click.self="showCreate = false">
          <div class="cs-modal">
            <h3>新建项目</h3>
            <input v-model="newProjectName" class="cs-input" placeholder="请输入项目名称" @keyup.enter="createProject" />
            <div class="cs-template-section">
              <p class="cs-template-label">选择模板（可选）</p>
              <div class="cs-template-list">
                <button v-for="tpl in templates" :key="tpl.key" class="cs-template-item" :class="{ active: selectedTemplate === tpl.key }" @click="selectedTemplate = selectedTemplate === tpl.key ? null : tpl.key">
                  <span class="cs-template-icon">{{ tpl.icon }}</span>
                  <span class="cs-template-name">{{ tpl.name }}</span>
                </button>
              </div>
            </div>
            <div class="cs-modal-actions">
              <button class="cs-btn cs-btn-outline" @click="showCreate = false">取消</button>
              <button class="cs-btn cs-btn-primary" @click="createProject">创建</button>
            </div>
          </div>
        </div>
      </Transition>
    </Teleport>
    <Teleport to="body">
      <Transition name="modal">
        <div v-if="showRename" class="cs-overlay" @click.self="showRename = false">
          <div class="cs-modal">
            <h3>编辑项目名称</h3>
            <input v-model="renameValue" class="cs-input" placeholder="请输入新的项目名称" @keyup.enter="confirmRename" />
            <div class="cs-modal-actions">
              <button class="cs-btn cs-btn-outline" @click="showRename = false">取消</button>
              <button class="cs-btn cs-btn-primary" @click="confirmRename">确认</button>
            </div>
          </div>
        </div>
      </Transition>
    </Teleport>
    <ExportProgressDialog
      :visible="projectExport.phase.value !== 'idle'"
      :phase="projectExport.phase.value"
      :progress-percent="projectExport.progressPercent.value"
      :packed-bytes="projectExport.packedBytes.value"
      v-model:include-all="exportIncludeAll"
      @start="projectExport.confirmStart"
      @cancel="projectExport.cancel"
    />
  </div>
</template>

<script setup>
import { ref, computed, onMounted, defineAsyncComponent } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { projectApi } from '@/api'
import FileTreeNode from '@/components/cloud/FileTreeNode.vue'
import UserPanel from '@/components/cloud/UserPanel.vue'
import ExportProgressDialog from '@/components/cloud/ExportProgressDialog.vue'
import { useProjectExport } from '@/composables/useProjectExport'
import { useResponsive } from '@/composables/useResponsive'

const route = useRoute()
const router = useRouter()
const { isMobile } = useResponsive()
const mobileActiveView = ref('list')
const projects = ref([])
const selectedId = ref(null)
const selectedProject = ref(null)
const fileTree = ref([])
const treeError = ref('')
const treeNextOffset = ref(null)
const treeLoadingMore = ref(false)
const selectedPath = ref('')
const listLoading = ref(false)
const treeLoading = ref(false)
const uploadInput = ref(null)
const showCreate = ref(false)
const newProjectName = ref('')
const selectedTemplate = ref(null)
const showRename = ref(false)
const renameValue = ref('')
const renamingProject = ref(null)

// ─── 侧边栏拖拽拉伸与持久化 ───
const DEFAULT_CS_SIDEBAR_WIDTH = 300
const MIN_CS_SIDEBAR_WIDTH = 220
const MAX_CS_SIDEBAR_WIDTH = 640

function resolveInitialSidebarWidth() {
  if (typeof window === 'undefined') return DEFAULT_CS_SIDEBAR_WIDTH
  try {
    const saved = localStorage.getItem('labex_projects_sidebar_width')
    if (saved) {
      const num = parseInt(saved, 10)
      if (num >= MIN_CS_SIDEBAR_WIDTH && num <= MAX_CS_SIDEBAR_WIDTH) return num
    }
  } catch {}
  return DEFAULT_CS_SIDEBAR_WIDTH
}

const sidebarWidth = ref(resolveInitialSidebarWidth())
const isResizingSidebar = ref(false)

function startSidebarResize(e) {
  e.preventDefault?.()
  const handle = e.currentTarget
  const sidebar = handle.previousElementSibling?.classList.contains('cs-left')
    ? handle.previousElementSibling
    : document.querySelector('.cs-left')
  if (!sidebar) return
  const startX = e.clientX
  const startWidth = sidebarWidth.value
  let latestX = startX
  let frame = null
  handle.setPointerCapture?.(e.pointerId)
  document.body.classList.add('is-resizing-sidebar')
  sidebar.classList.add('is-resizing')
  handle.classList.add('is-active')
  isResizingSidebar.value = true
  let latestApplied = startWidth

  const apply = () => {
    frame = null
    const width = Math.round(Math.max(MIN_CS_SIDEBAR_WIDTH, Math.min(startWidth + latestX - startX, MAX_CS_SIDEBAR_WIDTH)))
    sidebar.style.setProperty('--cs-sidebar-w', `${width}px`)
    latestApplied = width
  }

  const move = event => {
    latestX = event.clientX
    if (frame == null) frame = requestAnimationFrame(apply)
  }

  const finish = event => {
    if (frame != null) {
      cancelAnimationFrame(frame)
      frame = null
    }
    apply()
    document.body.classList.remove('is-resizing-sidebar')
    sidebar.classList.remove('is-resizing')
    handle.classList.remove('is-active')
    isResizingSidebar.value = false
    sidebarWidth.value = latestApplied
    try {
      localStorage.setItem('labex_projects_sidebar_width', String(latestApplied))
    } catch {}
    try {
      if (event?.pointerId != null) {
        handle.releasePointerCapture?.(event.pointerId)
      }
    } catch {}
    handle.removeEventListener('pointermove', move)
    handle.removeEventListener('pointerup', finish)
    handle.removeEventListener('pointercancel', finish)
    window.removeEventListener('pointermove', move)
    window.removeEventListener('pointerup', finish)
    window.removeEventListener('pointercancel', finish)
  }

  handle.addEventListener('pointermove', move)
  handle.addEventListener('pointerup', finish)
  handle.addEventListener('pointercancel', finish)
  window.addEventListener('pointermove', move, { passive: true })
  window.addEventListener('pointerup', finish)
  window.addEventListener('pointercancel', finish)
}

// 项目异步导出（列表页入口）：确认 → 后台打包 → 轮询进度 → 自动保存
const projectExport = useProjectExport({ projectId: ref(null), projectName: ref(''), api: projectApi, notify: ElMessage })
const exportIncludeAll = computed({
  get: () => projectExport.includeAll.value,
  set: value => { projectExport.includeAll.value = value }
})

const templates = [
  { key: 'vue', name: 'Vue', icon: 'V' },
  { key: 'react', name: 'React', icon: 'R' },
  { key: 'springboot', name: 'Spring Boot', icon: 'S' },
  { key: 'flask', name: 'Flask', icon: 'F' },
  { key: 'empty', name: '空项目', icon: 'E' },
]

async function loadProjects() {
  listLoading.value = true
  try {
    const r = await projectApi.list()
    projects.value = r.data || []
  } catch (e) {
    projects.value = []
  } finally {
    listLoading.value = false
  }
}

async function selectProject(proj) {
  selectedId.value = proj.projectId
  selectedProject.value = proj
  selectedPath.value = ''
  if (isMobile.value) {
    mobileActiveView.value = 'detail'
  }
  treeLoading.value = true
  treeError.value = ''
  treeNextOffset.value = null
  try {
    const r = await projectApi.getTreePage(proj.projectId, '', 0)
    fileTree.value = r.data?.entries || []
    treeNextOffset.value = r.data?.nextOffset ?? null
  } catch (e) {
    fileTree.value = []
    treeError.value = treeLoadErrorMessage(e)
  } finally {
    treeLoading.value = false
  }
}

function treeLoadErrorMessage(error) {
  if (error?.response?.status === 404) {
    return '文件分页接口暂不可用，请重启后端服务后重试'
  }
  return error?.message || '文件列表加载失败，请重试'
}

async function loadMoreTree() {
  if (!selectedProject.value || treeNextOffset.value === null || treeLoadingMore.value) return
  treeLoadingMore.value = true
  try {
    const r = await projectApi.getTreePage(selectedProject.value.projectId, '', treeNextOffset.value)
    fileTree.value = [...fileTree.value, ...(r.data?.entries || [])]
    treeNextOffset.value = r.data?.nextOffset ?? null
  } catch (e) {
    treeError.value = treeLoadErrorMessage(e)
  } finally {
    treeLoadingMore.value = false
  }
}

function handleTreeScroll(event) {
  const target = event.currentTarget
  if (target.scrollHeight - target.scrollTop - target.clientHeight < 80) {
    void loadMoreTree()
  }
}

async function loadTreeChildren(dirPath, offset = 0) {
  if (!selectedProject.value) return { entries: [], nextOffset: null }
  try {
    const r = await projectApi.getTreePage(selectedProject.value.projectId, dirPath, offset)
    return r.data || { entries: [], nextOffset: null }
  } catch (e) {
    return { entries: [], nextOffset: null }
  }
}

function onFileSelect(path) {
  selectedPath.value = path
}

function enterWorkspace(proj) {
  if (!proj || !proj.projectId) return
  router.push({ name: 'CloudWorkspace', params: { projectId: proj.projectId } })
}

async function createProject() {
  const name = newProjectName.value.trim()
  if (!name) {
    ElMessage.warning('请输入项目名称')
    return
  }
  try {
    if (selectedTemplate.value && selectedTemplate.value !== 'empty') {
      await projectApi.createWithTemplate(name, selectedTemplate.value)
    } else {
      await projectApi.createEmpty(name)
    }
    ElMessage.success('项目创建成功')
    showCreate.value = false
    newProjectName.value = ''
    selectedTemplate.value = null
    await loadProjects()
  } catch (e) {
    ElMessage.error('创建失败: ' + (e?.response?.data?.message || e?.message || '未知错误'))
  }
}

function triggerUpload() {
  uploadInput.value?.click()
}

async function handleUpload(e) {
  const f = e.target.files[0]
  if (!f) return
  try {
    await projectApi.upload(f)
    ElMessage.success('上传成功')
    await loadProjects()
  } catch (e) {
    ElMessage.error('上传失败: ' + (e?.response?.data?.message || e?.message || '未知错误'))
  } finally {
    e.target.value = ''
  }
}

async function deleteProject(proj) {
  try {
    await ElMessageBox.confirm(
      '确定要删除项目 "' + proj.projectName + '" 吗？删除后无法恢复。',
      '删除确认',
      {
        confirmButtonText: '确认删除',
        cancelButtonText: '取消',
        type: 'warning'
      }
    )
    await projectApi.delete(proj.projectId)
    ElMessage.success('项目已删除')
    if (selectedId.value === proj.projectId) {
      selectedProject.value = null
      selectedId.value = null
      fileTree.value = []
      mobileActiveView.value = 'list'
    }
    await loadProjects()
  } catch (e) {
    if (e !== 'cancel' && e !== 'close') {
      ElMessage.error('删除失败: ' + (e?.response?.data?.message || e?.message || '未知错误'))
    }
  }
}

function startRename(proj) {
  renamingProject.value = proj
  renameValue.value = proj.projectName
  showRename.value = true
}

async function confirmRename() {
  const name = renameValue.value.trim()
  if (!name) {
    ElMessage.warning('请输入项目名称')
    return
  }
  if (!renamingProject.value) return
  try {
    await projectApi.renameProject(renamingProject.value.projectId, name)
    ElMessage.success('项目名称已更新')
    showRename.value = false
    if (selectedProject.value && selectedProject.value.projectId === renamingProject.value.projectId) {
      selectedProject.value.projectName = name
    }
    await loadProjects()
  } catch (e) {
    ElMessage.error('重命名失败: ' + (e?.response?.data?.message || e?.message || '未知错误'))
  }
}

/** 项目列表页导出入口：复用异步导出编排（确认 → 后台打包 → 轮询进度 → 自动保存）。 */
function exportProject(proj) {
  if (!proj?.projectId) return
  projectExport.begin({ projectId: proj.projectId, projectName: proj.projectName || '' })
}

onMounted(async () => {
  const boundProvider = String(route.query.oauth_bound || '')
  const oauthError = String(route.query.oauth_error || '')
  if (boundProvider) {
    ElMessage.success(`${boundProvider === 'github' ? 'GitHub' : 'Google'} 绑定成功`)
  } else if (oauthError) {
    ElMessage.error('第三方账号绑定未完成，请重试')
  }
  if (boundProvider || oauthError) {
    await router.replace({ path: route.path, query: {} })
  }
  loadProjects()
})
</script>

<style scoped>
.cs-shell { display: flex; min-height: 100dvh; height: 100dvh; background: #fff; overflow: hidden; box-shadow: 0 1px 3px rgba(0,0,0,0.04); }
.cs-shell.is-resizing,
:global(body.is-resizing-sidebar) {
  user-select: none !important;
  cursor: col-resize !important;
}

.cs-left {
  width: var(--cs-sidebar-w, 300px);
  min-width: var(--cs-sidebar-w, 300px);
  flex: 0 0 var(--cs-sidebar-w, 300px);
  border-right: 1px solid #f0f0f0;
  display: flex;
  flex-direction: column;
  background: #ffffff;
  overflow: hidden;
  contain: layout paint;
  container-type: inline-size;
  container-name: cs-sidebar;
  transition: width 0.22s cubic-bezier(0.4, 0, 0.2, 1),
              min-width 0.22s cubic-bezier(0.4, 0, 0.2, 1),
              flex-basis 0.22s cubic-bezier(0.4, 0, 0.2, 1);
}

.cs-left.is-resizing,
.cs-shell.is-resizing .cs-left,
:global(body.is-resizing-sidebar .cs-left) {
  transition: none !important;
  will-change: width, min-width, flex-basis;
}

.cs-left.is-resizing *,
.cs-shell.is-resizing .cs-left * {
  transition: none !important;
}

.cs-shell.is-resizing .cs-right,
:global(body.is-resizing-sidebar .cs-right) {
  pointer-events: none !important;
  user-select: none !important;
}

.cs-resize-handle {
  width: 5px;
  cursor: col-resize;
  flex: 0 0 5px;
  background: transparent;
  touch-action: none;
  position: relative;
  z-index: 15;
}

.cs-resize-handle::before {
  content: '';
  position: absolute;
  top: 0;
  bottom: 0;
  left: -4px;
  right: -4px;
  cursor: col-resize;
}

.cs-resize-handle:hover,
.cs-resize-handle.is-active,
.cs-shell.is-resizing .cs-resize-handle {
  background: var(--theme-accent, #4f46e5);
}

:global(html[data-theme="dark"] .cs-resize-handle:hover),
:global(html[data-theme="dark"] .cs-resize-handle.is-active),
:global(html[data-theme="dark"] .cs-shell.is-resizing .cs-resize-handle) {
  background: var(--theme-accent, #818cf8);
}

.cs-right {
  flex: 1;
  display: flex;
  flex-direction: column;
  background: #fff;
  min-width: 0;
  contain: layout paint;
  overflow: hidden;
}
.cs-panel-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 14px 16px;
  border-bottom: 1px solid #f0f0f0;
  gap: 8px;
  min-width: 0;
}
.cs-panel-header h2 {
  font-size: 14px;
  font-weight: 600;
  color: #111827;
  margin: 0;
  white-space: nowrap;
  word-break: keep-all;
  flex-shrink: 0;
  min-width: 0;
}
.cs-right-header { background: #ffffff; }
.cs-right-title { display: flex; align-items: center; gap: 10px; }
.cs-right-title h3 { font-size: 14px; font-weight: 600; color: #111827; margin: 0; }
.cs-right-meta { font-size: 12px; color: #9ca3af; }
.cs-header-actions {
  display: flex;
  gap: 6px;
  align-items: center;
  flex-shrink: 0;
}
.cs-btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 4px;
  border: none;
  border-radius: 8px;
  cursor: pointer;
  font-size: 12px;
  font-weight: 500;
  padding: 6px 12px;
  white-space: nowrap;
  word-break: keep-all;
  flex-shrink: 0;
  transition: background-color 0.2s cubic-bezier(0.25, 0.1, 0.25, 1),
              border-color 0.2s cubic-bezier(0.25, 0.1, 0.25, 1),
              color 0.2s cubic-bezier(0.25, 0.1, 0.25, 1),
              box-shadow 0.2s cubic-bezier(0.25, 0.1, 0.25, 1);
  font-family: inherit;
  line-height: 1;
}
.cs-btn svg { flex-shrink: 0; }
.cs-btn span {
  white-space: nowrap;
  word-break: keep-all;
  line-height: 1;
}
.cs-btn-primary { background: #4f46e5; color: #fff; }
.cs-btn-primary:hover { background: #4338ca; box-shadow: 0 2px 8px rgba(79, 70, 229, 0.3); }
.cs-btn-outline { background: #fff; color: #374151; border: 1px solid #e5e7eb; }
.cs-btn-outline:hover { background: #f9fafb; border-color: #d1d5db; }
.cs-btn-ghost { background: transparent; color: #6b7280; padding: 4px 6px; }
.cs-btn-ghost:hover { background: #f3f4f6; color: #111827; }
.cs-btn-danger:hover { background: #fef2f2; color: #ef4444; }
.cs-btn-sm { padding: 4px 8px; font-size: 12px; }
.cs-list { flex: 1; overflow-y: auto; padding: 6px; }
.cs-empty { display: flex; flex-direction: column; align-items: center; gap: 6px; padding: 40px 16px; color: #9ca3af; font-size: 13px; }
.cs-empty-hint { font-size: 12px; color: #d1d5db; margin: 0; }
.cs-item {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 10px 12px;
  border-radius: 8px;
  cursor: pointer;
  min-width: 0;
  transition: background-color 0.15s ease, color 0.15s ease;
}
.cs-item:hover { background: #f3f4f6; }
.cs-item.active { background: #eef2ff; }
.cs-item.active .cs-item-name { color: #4338ca; }
.cs-item-icon { color: #6b7280; flex-shrink: 0; display: flex; transition: color 0.15s; }
.cs-item.active .cs-item-icon { color: #6366f1; }
.cs-item-body {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  display: flex;
  flex-direction: column;
}
.cs-item-name {
  font-size: 13px;
  font-weight: 500;
  color: #111827;
  white-space: nowrap;
  word-break: keep-all;
  overflow: hidden;
  text-overflow: ellipsis;
  line-height: 1.4;
}
.cs-item-meta {
  font-size: 11px;
  color: #9ca3af;
  margin-top: 2px;
  white-space: nowrap;
  word-break: keep-all;
  overflow: hidden;
  text-overflow: ellipsis;
  line-height: 1.4;
}
.cs-item-actions { display: flex; gap: 2px; opacity: 0; transition: opacity 0.15s; }
.cs-item:hover .cs-item-actions { opacity: 1; }

@container (max-width: 270px) {
  .cs-panel-header {
    padding: 12px 10px;
    gap: 4px;
  }
  .cs-header-actions {
    gap: 4px;
  }
  .cs-btn {
    padding: 6px 8px;
    gap: 3px;
  }
  .cs-item {
    padding: 8px 8px;
    gap: 8px;
  }
}

@container (max-width: 225px) {
  .cs-panel-header {
    padding: 10px 6px;
    gap: 4px;
  }
  .cs-btn {
    padding: 6px 6px;
  }
  .cs-btn-text {
    display: none;
  }
}
.proj-list-enter-active, .proj-list-leave-active { transition: all 0.3s ease; }
.proj-list-enter-from { opacity: 0; transform: translateX(-16px); }
.proj-list-leave-to { opacity: 0; transform: translateX(-8px); }
.proj-list-move { transition: transform 0.3s ease; }
.cs-right-empty { flex: 1; display: flex; flex-direction: column; align-items: center; justify-content: center; color: #9ca3af; gap: 10px; font-size: 13px; }
.cs-right-panel { flex: 1; display: flex; flex-direction: column; }
.cs-tree-panel { flex: 1; overflow-y: auto; padding: 8px; }
.cs-tree-error { margin: 8px; padding: 8px; border: 1px solid #fecaca; border-radius: 6px; background: #fef2f2; color: #b91c1c; font-size: 12px; }
.cs-tree-error button { margin-top: 6px; border: 0; border-radius: 4px; padding: 4px 7px; background: #fee2e2; color: inherit; font: inherit; cursor: pointer; }
.cs-tree-load-more { display: block; width: calc(100% - 8px); margin: 7px 4px; padding: 7px 8px; border: 1px solid #dbe1f0; border-radius: 6px; background: #fff; color: #4f46e5; font: inherit; font-size: 12px; cursor: pointer; }
.cs-tree-load-more:hover { background: #eef2ff; border-color: #c7d2fe; }
.ftn-list-enter-active { transition: all 0.2s ease; }
.ftn-list-leave-active { transition: all 0.15s ease; }
.ftn-list-enter-from { opacity: 0; transform: translateX(-8px); }
.ftn-list-leave-to { opacity: 0; transform: translateX(-4px); }
.cs-overlay { position: fixed; inset: 0; background: rgba(0,0,0,0.3); display: flex; align-items: center; justify-content: center; z-index: 2000; }
.cs-modal { background: #fff; border-radius: 14px; padding: 28px; width: 420px; box-shadow: 0 24px 60px rgba(0,0,0,0.15); }
.cs-modal h3 { font-size: 17px; font-weight: 600; color: #111827; margin: 0 0 20px; }
.cs-input { width: 100%; padding: 10px 14px; border: 1px solid #e5e7eb; border-radius: 8px; font-size: 14px; outline: none; box-sizing: border-box; font-family: inherit; transition: border-color 0.2s, box-shadow 0.2s; }
.cs-input:focus { border-color: #6366f1; box-shadow: 0 0 0 3px rgba(99, 102, 241, 0.1); }
.cs-template-section { margin-top: 16px; }
.cs-template-label { font-size: 13px; color: #6b7280; margin: 0 0 8px; }
.cs-template-list { display: flex; gap: 8px; flex-wrap: wrap; }
.cs-template-item { display: flex; flex-direction: column; align-items: center; gap: 4px; padding: 10px 16px; border: 1px solid #e5e7eb; border-radius: 8px; background: #fff; cursor: pointer; transition: all 0.2s; font-family: inherit; min-width: 64px; }
.cs-template-item:hover { border-color: #6366f1; background: #f5f5ff; }
.cs-template-item.active { border-color: #6366f1; background: #eef2ff; box-shadow: 0 0 0 2px rgba(99, 102, 241, 0.15); }
.cs-template-icon { font-size: 18px; font-weight: 700; color: #6366f1; }
.cs-template-name { font-size: 12px; color: #374151; }
.cs-modal-actions { display: flex; gap: 10px; justify-content: flex-end; margin-top: 22px; }
.modal-enter-active, .modal-leave-active { transition: all 0.25s ease; }
.modal-enter-from, .modal-leave-to { opacity: 0; }
.modal-enter-from .cs-modal, .modal-leave-to .cs-modal { transform: scale(0.95) translateY(8px); }

:global(html[data-theme="dark"] .cs-btn-primary) {
  background: var(--theme-accent);
  color: var(--theme-accent-contrast, #ffffff);
  box-shadow: 0 1px 4px rgba(0, 0, 0, 0.4);
}
:global(html[data-theme="dark"] .cs-btn-primary:hover) {
  background: var(--theme-accent-strong);
  color: var(--theme-accent-contrast, #ffffff);
}
:global(html[data-theme="dark"] .cs-btn-outline) {
  background: #1e2230;
  border-color: #384158;
  color: #edf1fb;
}
:global(html[data-theme="dark"] .cs-btn-outline:hover) {
  background: #252b3d;
  border-color: #4b587a;
  color: #ffffff;
}

:global(html[data-theme="dark"] .cs-left) {
  background: #181b24;
  border-right-color: #2e3547;
}

:global(html[data-theme="dark"] .cs-right) {
  background: #11131a;
}

:global(html[data-theme="dark"] .cs-right-header) {
  background: #181b24;
}

:global(html[data-theme="dark"] .cs-panel-header) {
  border-bottom-color: #2e3547;
}

:global(html[data-theme="dark"] .cs-panel-header h2) {
  color: #edf1fb;
}

:global(html[data-theme="dark"] .cs-right-title h3) {
  color: #edf1fb;
}

:global(html[data-theme="dark"] .cs-item-name) {
  color: #edf1fb;
}

:global(html[data-theme="dark"] .cs-item-meta) {
  color: #8c96a8;
}

:global(html[data-theme="dark"] .cs-item:hover) {
  background: rgba(255, 255, 255, 0.05);
}

:global(html[data-theme="dark"] .cs-item.active) {
  background: rgba(99, 102, 241, 0.22);
}

:global(html[data-theme="dark"] .cs-item.active .cs-item-name) {
  color: #c4b5fd;
}

@media (max-width: 768px) {
  .cs-shell {
    flex-direction: column;
    height: 100dvh;
    min-height: 100dvh;
  }
  .cs-resize-handle {
    display: none !important;
  }
  .cs-left {
    width: 100% !important;
    min-width: 100% !important;
    flex: 1 1 100%;
    border-right: none;
    height: 100%;
  }
  .cs-right {
    width: 100% !important;
    flex: 1 1 100%;
    height: 100%;
    display: none;
  }
  .cs-shell.mobile-show-detail .cs-left {
    display: none;
  }
  .cs-shell.mobile-show-detail .cs-right {
    display: flex;
  }
  .cs-mobile-back {
    margin-right: 6px;
    padding: 5px 8px;
    color: #4f46e5;
    font-weight: 600;
  }
  .cs-item-actions {
    opacity: 0.92 !important;
  }
  .cs-item-actions .cs-btn {
    padding: 6px 7px;
  }
  .cs-modal {
    width: min(420px, calc(100vw - 32px));
    padding: 20px 16px;
  }
  .cs-right-header {
    padding: 10px 12px;
  }
  .cs-open-ws-text {
    font-size: 11px;
  }
}

:global(html[data-theme="dark"] .cs-mobile-back) {
  color: #818cf8;
}
</style>
