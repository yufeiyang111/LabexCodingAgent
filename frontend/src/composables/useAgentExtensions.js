import { ref } from 'vue'

const DEFAULT_MAX_SKILL_FILES = 50
const DEFAULT_MAX_SKILL_FILE_BYTES = 1024 * 1024
const DEFAULT_MAX_CONTENT_CHARS = 15000

function fallbackNotifier() {
  return { info() {}, success() {}, warning() {}, error() {} }
}

function errorMessage(error, fallback) {
  return error?.response?.data?.message || error?.message || fallback
}

function skillNameFromPath(filePath) {
  return String(filePath || '').replaceAll('\\', '/').split('/').pop()?.replace(/\.md$/i, '') || 'unnamed-skill'
}

function skillKeyFromName(name) {
  const key = String(name || '').trim().toLowerCase().replace(/[^a-z0-9-]+/g, '-').replace(/^-+|-+$/g, '')
  return key || 'unnamed-skill'
}

export function parseSkillMarkdown(content, filePath, options = {}) {
  if (typeof content !== 'string' || !content.trim()) return null
  const maxContentChars = Number.isFinite(options.maxContentChars) ? Math.max(1, options.maxContentChars) : DEFAULT_MAX_CONTENT_CHARS
  const startsWithFrontmatter = /^---\s*(?:\r?\n)/.test(content)
  const match = content.match(/^---\s*\r?\n([\s\S]*?)\r?\n---\s*\r?\n([\s\S]*)$/)
  if (startsWithFrontmatter && !match) return null

  const fallbackName = skillNameFromPath(filePath)
  let title = fallbackName
  let description = ''
  let body = content
  if (match) {
    const frontmatter = match[1]
    body = match[2]
    const nameMatch = frontmatter.match(/^\s*name\s*:\s*(.+?)\s*$/m)
    const descriptionMatch = frontmatter.match(/^\s*description\s*:\s*(.+?)\s*$/m)
    title = (nameMatch?.[1] || fallbackName).replace(/^['"]|['"]$/g, '').trim() || fallbackName
    description = (descriptionMatch?.[1] || '').replace(/^['"]|['"]$/g, '').trim()
  }

  let normalizedBody = body.trim()
  if (normalizedBody.length > maxContentChars) {
    normalizedBody = normalizedBody.slice(0, maxContentChars) + '\n\n[内容已截断]'
  }
  return {
    title,
    skillKey: skillKeyFromName(title),
    description,
    content: normalizedBody,
    isEnabled: 1
  }
}

export function eligibleSkillFiles(files, options = {}) {
  const maxFiles = Number.isFinite(options.maxFiles) ? Math.max(1, options.maxFiles) : DEFAULT_MAX_SKILL_FILES
  const maxFileBytes = Number.isFinite(options.maxFileBytes) ? Math.max(1, options.maxFileBytes) : DEFAULT_MAX_SKILL_FILE_BYTES
  return Array.from(files || [])
    .filter(file => file?.name === 'SKILL.md' && Number(file.size || 0) <= maxFileBytes)
    .slice(0, maxFiles)
}

export function useAgentExtensions(options) {
  const api = options.api || {}
  const notify = { ...fallbackNotifier(), ...(options.notify || {}) }
  const confirmAction = options.confirmAction || (async () => true)
  const maxSkillFiles = options.maxSkillFiles || DEFAULT_MAX_SKILL_FILES
  const maxSkillFileBytes = options.maxSkillFileBytes || DEFAULT_MAX_SKILL_FILE_BYTES
  const maxContentChars = options.maxContentChars || DEFAULT_MAX_CONTENT_CHARS

  const extensionLoading = ref(false)
  const agentSkills = ref([])
  const mcpServers = ref([])
  const editingSkillId = ref(null)
  const editingMcpId = ref(null)
  const skillForm = ref(emptySkillForm())
  const mcpForm = ref(emptyMcpForm())
  let loadGeneration = 0

  function emptySkillForm() {
    return { title: '', skillKey: '', description: '', content: '', isEnabled: true }
  }

  function emptyMcpForm() {
    return { serverName: '', serverKey: '', endpoint: '', authHeader: '', toolsJson: '', isEnabled: true, transport: 'http' }
  }

  async function loadAgentExtensions() {
    const generation = ++loadGeneration
    extensionLoading.value = true
    try {
      const [skillsResponse, mcpResponse] = await Promise.all([api.listSkills(), api.listMcpServers()])
      if (generation !== loadGeneration) return false
      agentSkills.value = skillsResponse?.data || []
      mcpServers.value = mcpResponse?.data || []
      return true
    } catch (error) {
      if (generation === loadGeneration) notify.warning('扩展配置加载失败')
      return false
    } finally {
      if (generation === loadGeneration) extensionLoading.value = false
    }
  }

  function resetSkillForm() {
    editingSkillId.value = null
    skillForm.value = emptySkillForm()
  }

  function editSkill(skill) {
    editingSkillId.value = skill.skillId
    skillForm.value = {
      title: skill.title || '',
      skillKey: skill.skillKey || '',
      description: skill.description || '',
      content: skill.content || '',
      isEnabled: skill.isEnabled === 1
    }
  }

  async function saveSkill() {
    if (!skillForm.value.title.trim() || !skillForm.value.content.trim()) {
      notify.warning('请填写 Skill 标题和内容')
      return false
    }
    const payload = { ...skillForm.value, isEnabled: skillForm.value.isEnabled ? 1 : 0 }
    try {
      if (editingSkillId.value) await api.updateSkill(editingSkillId.value, payload)
      else await api.createSkill(payload)
      notify.success('Skill 已保存')
      resetSkillForm()
      await loadAgentExtensions()
      return true
    } catch (error) {
      notify.error(errorMessage(error, 'Skill 保存失败'))
      return false
    }
  }

  async function toggleSkill(skill) {
    try {
      await api.updateSkill(skill.skillId, { ...skill, isEnabled: skill.isEnabled === 1 ? 0 : 1 })
      await loadAgentExtensions()
      return true
    } catch {
      notify.error('状态更新失败')
      return false
    }
  }

  async function deleteSkill(skill) {
    try {
      await confirmAction(`删除 Skill "${skill.title}"？`, '确认删除', { confirmButtonText: '删除', cancelButtonText: '取消', type: 'warning' })
      await api.deleteSkill(skill.skillId)
      if (editingSkillId.value === skill.skillId) resetSkillForm()
      await loadAgentExtensions()
      return true
    } catch (error) {
      if (error !== 'cancel') notify.error('删除失败')
      return false
    }
  }

  function resetMcpForm() {
    editingMcpId.value = null
    mcpForm.value = emptyMcpForm()
  }

  function editMcp(server) {
    editingMcpId.value = server.serverId
    mcpForm.value = {
      serverName: server.serverName || '',
      serverKey: server.serverKey || '',
      endpoint: server.endpoint || '',
      authHeader: '',
      toolsJson: server.toolsJson || '',
      isEnabled: server.isEnabled === 1,
      transport: server.transport || 'http'
    }
  }

  async function saveMcp() {
    if (!mcpForm.value.serverName.trim() || !mcpForm.value.endpoint.trim()) {
      notify.warning('请填写 MCP 名称和 Endpoint')
      return false
    }
    const payload = { ...mcpForm.value, isEnabled: mcpForm.value.isEnabled ? 1 : 0 }
    try {
      if (editingMcpId.value) await api.updateMcpServer(editingMcpId.value, payload)
      else await api.createMcpServer(payload)
      notify.success('MCP 配置已保存')
      resetMcpForm()
      await loadAgentExtensions()
      return true
    } catch (error) {
      notify.error(errorMessage(error, 'MCP 保存失败'))
      return false
    }
  }

  async function testMcpConnection(server) {
    if (typeof api.testMcpServer !== 'function') {
      notify.warning('当前后端未提供 MCP 连接测试接口，不能伪造连接成功。')
      return { success: false, reason: 'unsupported' }
    }
    try {
      notify.info('正在测试连接...')
      const response = await api.testMcpServer(server.serverId)
      const result = response?.data || {}
      if (result.success === false) throw new Error(result.message || '连接测试失败')
      notify.success('连接成功')
      return { success: true, result }
    } catch (error) {
      notify.error('连接失败: ' + errorMessage(error, '未知错误'))
      return { success: false, reason: 'failed' }
    }
  }

  async function toggleMcp(server) {
    try {
      await api.updateMcpServer(server.serverId, { ...server, isEnabled: server.isEnabled === 1 ? 0 : 1 })
      await loadAgentExtensions()
      return true
    } catch {
      notify.error('状态更新失败')
      return false
    }
  }

  async function deleteMcp(server) {
    try {
      await confirmAction(`删除 MCP "${server.serverName}"？`, '确认删除', { confirmButtonText: '删除', cancelButtonText: '取消', type: 'warning' })
      await api.deleteMcpServer(server.serverId)
      if (editingMcpId.value === server.serverId) resetMcpForm()
      await loadAgentExtensions()
      return true
    } catch (error) {
      if (error !== 'cancel') notify.error('删除失败')
      return false
    }
  }

  async function persistImportedSkill(parsed) {
    const existing = agentSkills.value.find(skill => skill.skillKey === parsed.skillKey)
    if (existing) {
      await api.updateSkill(existing.skillId, parsed)
      return 'updated'
    }
    try {
      await api.createSkill(parsed)
      return 'imported'
    } catch (error) {
      const message = errorMessage(error, '')
      if (!/duplicate/i.test(message)) throw error
      const fresh = await api.listSkills()
      const duplicate = (fresh?.data || []).find(skill => skill.skillKey === parsed.skillKey)
      if (!duplicate) return 'skipped'
      await api.updateSkill(duplicate.skillId, parsed)
      return 'updated'
    }
  }

  async function handleSkillFolderUpload(event) {
    const allFiles = Array.from(event?.target?.files || [])
    const skillFiles = eligibleSkillFiles(allFiles, { maxFiles: maxSkillFiles, maxFileBytes: maxSkillFileBytes })
    if (skillFiles.length === 0) {
      notify.warning('未找到符合大小限制的 SKILL.md 文件')
      return { imported: 0, updated: 0, skipped: 0, failed: 0 }
    }
    notify.info(`找到 ${skillFiles.length} 个 SKILL.md 文件，正在导入...`)
    const counts = { imported: 0, updated: 0, skipped: 0, failed: 0 }
    for (const file of skillFiles) {
      try {
        const parsed = parseSkillMarkdown(await file.text(), file.webkitRelativePath || file.name, { maxContentChars })
        if (!parsed) { counts.skipped++; continue }
        const folderPath = String(file.webkitRelativePath || '').split('/').slice(0, -1).join('/')
        const relatedFiles = allFiles.filter(candidate =>
          folderPath && String(candidate.webkitRelativePath || '').startsWith(folderPath + '/') &&
          candidate.name !== 'SKILL.md' && !candidate.name.startsWith('.')
        ).slice(0, 100)
        if (relatedFiles.length) {
          const suffix = '\n\n## Related Files\n- ' + relatedFiles.map(candidate => candidate.webkitRelativePath).join('\n- ')
          parsed.content = (parsed.content + suffix).slice(0, maxContentChars)
        }
        const outcome = await persistImportedSkill(parsed)
        counts[outcome]++
      } catch (error) {
        counts.failed++
        console.error('Skill import failed:', file.name, error)
      }
    }
    const labels = []
    if (counts.imported) labels.push(`${counts.imported} 个新增`)
    if (counts.updated) labels.push(`${counts.updated} 个更新`)
    if (counts.skipped) labels.push(`${counts.skipped} 个跳过`)
    if (counts.failed) labels.push(`${counts.failed} 个失败`)
    notify.success(`Skill 导入完成: ${labels.join(', ') || '无变更'}`)
    await loadAgentExtensions()
    if (event?.target) event.target.value = ''
    return counts
  }

  async function handleSkillFileUpload(event) {
    const file = event?.target?.files?.[0]
    if (!file) return false
    try {
      if (file.name !== 'SKILL.md' || Number(file.size || 0) > maxSkillFileBytes) {
        notify.warning('请选择大小合规的 SKILL.md 文件')
        return false
      }
      const parsed = parseSkillMarkdown(await file.text(), file.name, { maxContentChars })
      if (!parsed) {
        notify.warning('无法解析 SKILL.md 文件')
        return false
      }
      const outcome = await persistImportedSkill(parsed)
      notify.success(outcome === 'updated' ? 'Skill 已更新' : 'Skill 已导入')
      await loadAgentExtensions()
      return true
    } catch (error) {
      notify.error('导入失败: ' + errorMessage(error, '未知错误'))
      return false
    } finally {
      if (event?.target) event.target.value = ''
    }
  }

  return {
    extensionLoading,
    agentSkills,
    mcpServers,
    editingSkillId,
    editingMcpId,
    skillForm,
    mcpForm,
    loadAgentExtensions,
    resetSkillForm,
    editSkill,
    saveSkill,
    toggleSkill,
    deleteSkill,
    resetMcpForm,
    editMcp,
    saveMcp,
    testMcpConnection,
    toggleMcp,
    deleteMcp,
    handleSkillFolderUpload,
    handleSkillFileUpload
  }
}
