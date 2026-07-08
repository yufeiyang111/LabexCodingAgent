<template>
  <div class="cs-shell">
    <div class="cs-left">
      <div class="cs-panel-header">
        <h2>项目列表</h2>
        <div class="cs-header-actions">
          <button class="cs-btn cs-btn-primary" @click="showCreate = true">
            <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><line x1="12" y1="5" x2="12" y2="19"/><line x1="5" y1="12" x2="19" y2="12"/></svg>
            <span>新建</span>
          </button>
          <button class="cs-btn cs-btn-outline" @click="triggerUpload">
            <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"/><polyline points="17 8 12 3 7 8"/><line x1="12" y1="3" x2="12" y2="15"/></svg>
            <span>上传</span>
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
    </div>
    <div class="cs-right">
      <div v-if="!selectedProject" class="cs-right-empty">
        <svg width="48" height="48" viewBox="0 0 24 24" fill="none" stroke="#d1d5db" stroke-width="1"><rect x="2" y="3" width="20" height="14" rx="2" ry="2"/><line x1="8" y1="21" x2="16" y2="21"/><line x1="12" y1="17" x2="12" y2="21"/></svg>
        <p>请选择一个项目</p>
      </div>
      <div v-else class="cs-right-panel">
        <div class="cs-panel-header cs-right-header">
          <div class="cs-right-title">
            <h3>{{ selectedProject.projectName }}</h3>
            <span class="cs-right-meta">{{ selectedProject.fileCount || 0 }} 个文件</span>
          </div>
          <button class="cs-btn cs-btn-primary" @click="enterWorkspace(selectedProject)">
            <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><polyline points="15 3 21 3 21 9"/><polyline points="9 21 3 21 3 15"/><line x1="21" y1="3" x2="14" y2="10"/><line x1="3" y1="21" x2="10" y2="14"/></svg>
            <span>打开工作空间</span>
          </button>
        </div>
        <div class="cs-tree-panel" v-loading="treeLoading">
          <TransitionGroup name="ftn-list" tag="div">
            <FileTreeNode v-for="child in fileTree" :key="child.path" :node="child" :selected-path="selectedPath" :load-children="loadTreeChildren" @select="onFileSelect"/>
          </TransitionGroup>
          <div v-if="!treeLoading && fileTree.length === 0" class="cs-empty" style="padding:24px">
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
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { projectApi } from '@/api'
import FileTreeNode from '@/components/cloud/FileTreeNode.vue'

const router = useRouter()
const projects = ref([])
const selectedId = ref(null)
const selectedProject = ref(null)
const fileTree = ref([])
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
  treeLoading.value = true
  try {
    const r = await projectApi.getTree(proj.projectId, '')
    fileTree.value = r.data || []
  } catch (e) {
    fileTree.value = []
  } finally {
    treeLoading.value = false
  }
}

async function loadTreeChildren(dirPath) {
  if (!selectedProject.value) return []
  try {
    const r = await projectApi.getTree(selectedProject.value.projectId, dirPath)
    return r.data || []
  } catch (e) {
    return []
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

async function exportProject(proj) {
  try {
    const r = await projectApi.exportProject(proj.projectId)
    const blob = r.data instanceof Blob ? r.data : new Blob([r.data], { type: 'application/zip' })
    const url = window.URL.createObjectURL(blob)
    const a = document.createElement('a')
    a.href = url
    a.download = (proj.projectName || 'project') + '.zip'
    document.body.appendChild(a)
    a.click()
    document.body.removeChild(a)
    window.URL.revokeObjectURL(url)
    ElMessage.success('导出成功')
  } catch (e) {
    ElMessage.error('导出失败: ' + (e?.response?.data?.message || e?.message || '未知错误'))
  }
}

onMounted(() => {
  loadProjects()
})
</script>

<style scoped>
.cs-shell { display: flex; height: calc(100vh - 120px); background: #fff; border-radius: 12px; overflow: hidden; box-shadow: 0 1px 3px rgba(0,0,0,0.04); }
.cs-left { width: 300px; border-right: 1px solid #f0f0f0; display: flex; flex-direction: column; background: #fafbfc; flex-shrink: 0; }
.cs-right { flex: 1; display: flex; flex-direction: column; background: #fff; min-width: 0; }
.cs-panel-header { display: flex; align-items: center; justify-content: space-between; padding: 14px 16px; border-bottom: 1px solid #f0f0f0; }
.cs-panel-header h2 { font-size: 14px; font-weight: 600; color: #111827; margin: 0; }
.cs-right-header { background: #fafbfc; }
.cs-right-title { display: flex; align-items: center; gap: 10px; }
.cs-right-title h3 { font-size: 14px; font-weight: 600; color: #111827; margin: 0; }
.cs-right-meta { font-size: 12px; color: #9ca3af; }
.cs-header-actions { display: flex; gap: 6px; }
.cs-btn { display: inline-flex; align-items: center; gap: 5px; border: none; border-radius: 8px; cursor: pointer; font-size: 12px; font-weight: 500; padding: 7px 14px; transition: all 0.2s cubic-bezier(0.25, 0.1, 0.25, 1); font-family: inherit; line-height: 1; }
.cs-btn svg { flex-shrink: 0; }
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
.cs-item { display: flex; align-items: center; gap: 10px; padding: 10px 12px; border-radius: 8px; cursor: pointer; transition: all 0.15s ease; }
.cs-item:hover { background: #f3f4f6; }
.cs-item.active { background: #eef2ff; }
.cs-item.active .cs-item-name { color: #4338ca; }
.cs-item-icon { color: #6b7280; flex-shrink: 0; display: flex; transition: color 0.15s; }
.cs-item.active .cs-item-icon { color: #6366f1; }
.cs-item-body { flex: 1; min-width: 0; }
.cs-item-name { font-size: 13px; font-weight: 500; color: #111827; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.cs-item-meta { font-size: 11px; color: #9ca3af; margin-top: 2px; }
.cs-item-actions { display: flex; gap: 2px; opacity: 0; transition: opacity 0.15s; }
.cs-item:hover .cs-item-actions { opacity: 1; }
.proj-list-enter-active, .proj-list-leave-active { transition: all 0.3s ease; }
.proj-list-enter-from { opacity: 0; transform: translateX(-16px); }
.proj-list-leave-to { opacity: 0; transform: translateX(-8px); }
.proj-list-move { transition: transform 0.3s ease; }
.cs-right-empty { flex: 1; display: flex; flex-direction: column; align-items: center; justify-content: center; color: #9ca3af; gap: 10px; font-size: 13px; }
.cs-right-panel { flex: 1; display: flex; flex-direction: column; }
.cs-tree-panel { flex: 1; overflow-y: auto; padding: 8px; }
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
</style>