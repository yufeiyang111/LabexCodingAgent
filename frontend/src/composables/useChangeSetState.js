function diffStatistics(patch) {
  let additions = 0
  let deletions = 0
  for (const line of patch.split('\n')) {
    if (line.startsWith('+') && !line.startsWith('+++')) additions++
    if (line.startsWith('-') && !line.startsWith('---')) deletions++
  }
  return { additions, deletions }
}

function reconstructBeforePatch(patch) {
  const lines = []
  for (const line of patch.split('\n')) {
    if (line.startsWith('@@') || line.startsWith('---') || line.startsWith('+++') || line.startsWith('diff ')) continue
    if (line.startsWith('+')) continue
    if (line.startsWith('-') || line.startsWith(' ')) lines.push(line.slice(1))
  }
  return lines.join('\n')
}

export function useChangeSetState({ projectId, api, sessionChanges, onFileReverted }) {
  function trackFileChange(data, toolCall) {
    const file = data.file || data.path || toolCall?.args?.path || toolCall?.args?.file_path || ''
    if (!file) return

    const patch = data.diff || ''
    const statistics = diffStatistics(patch)
    const existing = sessionChanges.value.find(change => change.file === file)
    if (existing) {
      existing.patch = patch
      existing.additions = statistics.additions
      existing.deletions = statistics.deletions
      existing.status = data.success !== false ? 'modified' : existing.status
      return
    }

    sessionChanges.value.push({
      file,
      patch,
      ...statistics,
      status: toolCall?.name === 'write_file' ? 'added' : 'modified'
    })
  }

  async function revertChange(change) {
    if (!change?.file) return { success: false, reason: 'file_missing' }

    await api.readFile(projectId.value, change.file)
    const content = reconstructBeforePatch(change.patch || '')
    await api.saveFile(projectId.value, change.file, content)
    sessionChanges.value = sessionChanges.value.filter(item => item.file !== change.file)
    onFileReverted?.({ file: change.file, content })
    return { success: true, file: change.file, content }
  }

  function removeChange(change) {
    const file = change?.relativePath || change?.file
    if (!file) return false
    sessionChanges.value = sessionChanges.value.filter(item => item.file !== file)
    return true
  }

  return {
    trackFileChange,
    revertChange,
    removeChange
  }
}
