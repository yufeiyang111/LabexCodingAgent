import { ref, watch } from 'vue'

const DEFAULT_LANGUAGE_MAP = {
  js: 'javascript', jsx: 'javascript', ts: 'typescript', tsx: 'typescript', vue: 'html',
  py: 'python', java: 'java', c: 'c', cpp: 'cpp', html: 'html', css: 'css', scss: 'scss',
  json: 'json', xml: 'xml', yml: 'yaml', yaml: 'yaml', md: 'markdown', sql: 'sql', sh: 'shell',
  bat: 'shell', ps1: 'powershell', go: 'go', rs: 'rust', php: 'php'
}

function notifier() {
  return { warning() {}, success() {}, error() {} }
}

function errorMessage(error, fallback) {
  return error?.response?.data?.message || error?.message || fallback
}

export function useWorkspaceFiles(options) {
  const projectId = options.projectId
  const api = options.api
  const notify = { ...notifier(), ...(options.notify || {}) }
  const confirmAction = options.confirmAction || (async () => true)
  const nextTick = options.nextTick || (async () => {})
  const languageMap = options.languageMap || DEFAULT_LANGUAGE_MAP

  const fileTree = ref([])
  const treeError = ref('')
  const treeNextOffset = ref(null)
  const treeLoadingMore = ref(false)
  const treeLoading = ref(false)
  const treeRefreshKey = ref(0)
  const activePath = ref('')
  const fileContent = ref('')
  const fileContentDirty = ref(false)
  const activeFileReadOnly = ref(false)
  const savingFile = ref(false)
  const editorReady = ref(true)
  const openFiles = ref([])
  const activeTabIndex = ref(-1)
  const detectedLang = ref('plaintext')

  const showNewModal = ref(false)
  const newModalType = ref('file')
  const newItemParent = ref('')
  const newItemName = ref('')
  const showRenameModal = ref(false)
  const renameItemValue = ref('')
  const renamingItemPath = ref('')

  let rootGeneration = 0
  let openGeneration = 0
  let suppressDirtyTracking = false

  function languageForPath(path) {
    const extension = String(path || '').split('.').pop()?.toLowerCase()
    return languageMap[extension] || 'plaintext'
  }

  function treeLoadErrorMessage(error) {
    if (error?.response?.status === 404) return '文件分页接口暂不可用，请重启后端服务后重试'
    return error?.message || '文件列表加载失败，请重试'
  }

  async function loadRoot() {
    if (!projectId.value) return false
    const generation = ++rootGeneration
    treeLoading.value = true
    treeError.value = ''
    treeNextOffset.value = null
    try {
      const response = await api.getTreePage(projectId.value, '', 0)
      if (generation !== rootGeneration) return false
      fileTree.value = response.data?.entries || []
      treeNextOffset.value = response.data?.nextOffset ?? null
      treeRefreshKey.value++
      return true
    } catch (error) {
      if (generation === rootGeneration) {
        fileTree.value = []
        treeError.value = treeLoadErrorMessage(error)
      }
      return false
    } finally {
      if (generation === rootGeneration) treeLoading.value = false
    }
  }

  async function loadMoreRoot() {
    if (!projectId.value || treeNextOffset.value === null || treeLoadingMore.value) return false
    const requestedOffset = treeNextOffset.value
    treeLoadingMore.value = true
    try {
      const response = await api.getTreePage(projectId.value, '', requestedOffset)
      if (treeNextOffset.value !== requestedOffset) return false
      fileTree.value = [...fileTree.value, ...(response.data?.entries || [])]
      treeNextOffset.value = response.data?.nextOffset ?? null
      return true
    } catch (error) {
      treeError.value = treeLoadErrorMessage(error)
      return false
    } finally {
      treeLoadingMore.value = false
    }
  }

  function handleTreeScroll(event) {
    const target = event.currentTarget
    if (target.scrollHeight - target.scrollTop - target.clientHeight < 80) void loadMoreRoot()
  }

  async function loadChildren(directoryPath, offset = 0) {
    if (!projectId.value) return { entries: [], nextOffset: null }
    try {
      const response = await api.getTreePage(projectId.value, directoryPath, offset)
      return response.data || { entries: [], nextOffset: null }
    } catch {
      return { entries: [], nextOffset: null }
    }
  }

  function applyActiveFile(file, index) {
    suppressDirtyTracking = true
    activeTabIndex.value = index
    activePath.value = file.path
    activeFileReadOnly.value = Boolean(file.readOnly)
    fileContent.value = file.content
    fileContentDirty.value = Boolean(file.dirty)
    detectedLang.value = file.lang || languageForPath(file.path)
    editorReady.value = true
    queueMicrotask(() => { suppressDirtyTracking = false })
  }

  async function openFile(rawPath, options = {}) {
    if (!projectId.value || !rawPath) return false
    const path = String(rawPath).replace(/^(\.\/|\/)/, '').replace(/\\/g, '/')
    const existingIndex = openFiles.value.findIndex(file => file.path === path)
    if (existingIndex >= 0) {
      openGeneration++
      applyActiveFile(openFiles.value[existingIndex], existingIndex)
      return true
    }

    const generation = ++openGeneration
    let file
    try {
      const response = await api.readFile(projectId.value, path, options)
      file = response.data || {}
    } catch {
      return false
    }
    if (generation !== openGeneration) return false

    const content = file.content || ''
    const opened = {
      path,
      name: path.split('/').pop() || path,
      content,
      lang: languageForPath(path),
      dirty: false,
      readOnly: Boolean(file.readOnly)
    }
    openFiles.value.push(opened)
    applyActiveFile(opened, openFiles.value.length - 1)
    if (file.truncated) notify.warning(`文件过大，仅加载前 ${Math.round(content.length / 1024)} KB 内容`)
    editorReady.value = false
    await nextTick()
    editorReady.value = true
    return true
  }

  function switchTab(index) {
    if (index < 0 || index >= openFiles.value.length) return false
    if (activeTabIndex.value >= 0 && activeTabIndex.value < openFiles.value.length) {
      openFiles.value[activeTabIndex.value].content = fileContent.value
      openFiles.value[activeTabIndex.value].dirty = fileContentDirty.value
    }
    openGeneration++
    applyActiveFile(openFiles.value[index], index)
    return true
  }

  async function closeFile(index) {
    if (index < 0 || index >= openFiles.value.length) return false
    const target = openFiles.value[index]
    if (target.dirty) {
      try {
        await confirmAction(`文件 "${target.name}" 有未保存修改，仍要关闭吗？`, '未保存修改', {
          confirmButtonText: '仍要关闭', cancelButtonText: '取消', type: 'warning'
        })
      } catch {
        return false
      }
    }
    const wasActive = index === activeTabIndex.value
    openFiles.value.splice(index, 1)
    if (wasActive) {
      if (openFiles.value.length) switchTab(Math.min(index, openFiles.value.length - 1))
      else clearEditor()
    } else if (index < activeTabIndex.value) {
      activeTabIndex.value--
    }
    return true
  }

  function clearEditor() {
    suppressDirtyTracking = true
    activeTabIndex.value = -1
    activePath.value = ''
    fileContent.value = ''
    fileContentDirty.value = false
    activeFileReadOnly.value = false
    editorReady.value = false
    queueMicrotask(() => { suppressDirtyTracking = false })
  }

  watch(fileContent, (value, previous) => {
    if (suppressDirtyTracking || previous === undefined || value === previous) return
    fileContentDirty.value = true
    const active = openFiles.value[activeTabIndex.value]
    if (active) {
      active.content = value
      active.dirty = true
    }
  })

  async function saveFile() {
    if (!projectId.value || !activePath.value || savingFile.value || activeFileReadOnly.value) return false
    savingFile.value = true
    try {
      await api.saveFile(projectId.value, activePath.value, fileContent.value)
      fileContentDirty.value = false
      const active = openFiles.value[activeTabIndex.value]
      if (active) active.dirty = false
      notify.success('已保存')
      return true
    } catch (error) {
      notify.error('保存失败: ' + errorMessage(error, '未知错误'))
      return false
    } finally {
      savingFile.value = false
    }
  }

  function showNewFileModal(type) {
    newModalType.value = type
    newItemParent.value = ''
    newItemName.value = ''
    showNewModal.value = true
  }

  function handleNewItem(parentPath, type) {
    newModalType.value = type
    newItemParent.value = parentPath
    newItemName.value = ''
    showNewModal.value = true
  }

  async function confirmNewItem() {
    const name = newItemName.value.trim()
    if (!name) { notify.warning('请输入名称'); return false }
    try {
      await api.createItem(projectId.value, newItemParent.value, name, newModalType.value)
      notify.success(newModalType.value === 'directory' ? '文件夹创建成功' : '文件创建成功')
      showNewModal.value = false
      await loadRoot()
      return true
    } catch (error) {
      notify.error('创建失败: ' + errorMessage(error, '未知错误'))
      return false
    }
  }

  function handleRename(path, currentName) {
    renamingItemPath.value = path
    renameItemValue.value = currentName
    showRenameModal.value = true
  }

  function renamedPath(path, name) {
    const slash = path.lastIndexOf('/')
    return slash < 0 ? name : `${path.slice(0, slash)}/${name}`
  }

  async function confirmRename() {
    const name = renameItemValue.value.trim()
    if (!name) { notify.warning('请输入新名称'); return false }
    const oldPath = renamingItemPath.value
    const nextPath = renamedPath(oldPath, name)
    try {
      await api.renameItem(projectId.value, oldPath, name)
      openFiles.value.forEach(file => {
        if (file.path === oldPath || file.path.startsWith(oldPath + '/')) {
          file.path = nextPath + file.path.slice(oldPath.length)
          file.name = file.path.split('/').pop() || file.path
        }
      })
      if (activePath.value === oldPath || activePath.value.startsWith(oldPath + '/')) {
        activePath.value = nextPath + activePath.value.slice(oldPath.length)
      }
      notify.success('重命名成功')
      showRenameModal.value = false
      await loadRoot()
      return true
    } catch (error) {
      notify.error('重命名失败: ' + errorMessage(error, '未知错误'))
      return false
    }
  }

  async function handleDelete(path) {
    const itemName = path.split('/').pop()
    try {
      await confirmAction(`确定要删除 "${itemName}" 吗？删除后无法恢复。`, '删除确认', {
        confirmButtonText: '确认删除', cancelButtonText: '取消', type: 'warning'
      })
      await api.deleteItem(projectId.value, path, { silent: true })
      const activeWasDeleted = activePath.value === path || activePath.value.startsWith(path + '/')
      openFiles.value = openFiles.value.filter(file => file.path !== path && !file.path.startsWith(path + '/'))
      if (activeWasDeleted) {
        if (openFiles.value.length) switchTab(Math.min(activeTabIndex.value, openFiles.value.length - 1))
        else clearEditor()
      } else {
        activeTabIndex.value = openFiles.value.findIndex(file => file.path === activePath.value)
      }
      notify.success('已删除')
      await loadRoot()
      return true
    } catch (error) {
      if (error !== 'cancel' && error !== 'close') notify.error('删除失败: ' + errorMessage(error, '未知错误'))
      return false
    }
  }

  return {
    fileTree, treeError, treeNextOffset, treeLoadingMore, treeLoading, treeRefreshKey,
    activePath, fileContent, fileContentDirty, activeFileReadOnly, savingFile, editorReady,
    openFiles, activeTabIndex, detectedLang,
    showNewModal, newModalType, newItemParent, newItemName,
    showRenameModal, renameItemValue, renamingItemPath,
    loadRoot, loadMoreRoot, handleTreeScroll, loadChildren, openFile, switchTab, closeFile,
    saveFile, showNewFileModal, handleNewItem, confirmNewItem, handleRename, confirmRename, handleDelete,
    treeLoadErrorMessage
  }
}
