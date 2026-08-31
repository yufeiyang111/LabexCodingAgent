import { normalizeWorkspacePath } from '../utils/pathUtils.js'
import { diffStatistics } from './useChangeSetState.js'

/**
 * Merges sessionChanges, messages fileChanges, toolCalls, and completionEvidence
 * into a single unified array of changes with accurate additions, deletions, and patches.
 */
export function resolveEffectiveChanges(sessionChangesList = [], messagesList = []) {
  const map = new Map() // normPath -> change object

  // 1. Session changes (active in-memory changes)
  for (const c of sessionChangesList || []) {
    const raw = c.file || c.relativePath || c.path || ''
    const file = normalizeWorkspacePath(raw)
    if (!file) continue
    const stats = (c.additions || c.deletions)
      ? { additions: c.additions || 0, deletions: c.deletions || 0 }
      : diffStatistics(c.patch || c.diff || '')
    map.set(file, {
      ...c,
      file,
      relativePath: file,
      path: file,
      status: c.status || 'modified',
      patch: c.patch || c.diff || '',
      diff: c.diff || c.patch || '',
      additions: stats.additions,
      deletions: stats.deletions
    })
  }

  // 2. Message fileChanges (persisted/historical turn changes)
  for (const m of messagesList || []) {
    if (m?.fileChanges && Array.isArray(m.fileChanges)) {
      for (const fc of m.fileChanges) {
        const raw = fc.file || fc.filePath || fc.path || fc.relativePath || ''
        const file = normalizeWorkspacePath(raw)
        if (!file) continue
        const patch = fc.patch || fc.diff || ''
        const stats = (fc.additions || fc.deletions)
          ? { additions: fc.additions || 0, deletions: fc.deletions || 0 }
          : diffStatistics(patch)
        const existing = map.get(file)
        if (!existing) {
          map.set(file, {
            ...fc,
            file,
            relativePath: file,
            path: file,
            status: fc.status || fc.type || 'modified',
            patch,
            diff: patch,
            additions: stats.additions,
            deletions: stats.deletions
          })
        } else {
          if (!existing.patch && patch) {
            existing.patch = patch
            existing.diff = patch
          }
          if (!existing.additions && !existing.deletions && (stats.additions || stats.deletions)) {
            existing.additions = stats.additions
            existing.deletions = stats.deletions
          }
        }
      }
    }

    // 3. Tool calls (edit_file, write_file, apply_patch)
    if (m?.toolCalls && Array.isArray(m.toolCalls)) {
      for (const tc of m.toolCalls) {
        const toolName = String(tc?.name || tc?.tool || '').toLowerCase()
        if (['edit_file', 'write_file', 'apply_patch'].includes(toolName)) {
          let args = tc?.args ?? tc?.arguments
          if (typeof args === 'string') {
            try { args = JSON.parse(args) } catch {}
          }
          const raw = args?.path || args?.file_path || args?.filename || args?.file || ''
          const file = normalizeWorkspacePath(raw)
          if (!file) continue
          // 1. Look for genuine diff/patch on the tool call or arguments
          let patch = tc?.diff || tc?.patch || args?.patch || args?.diff || ''

          // 2. Synthesize diff from tool args if available and no patch exists
          const content = args?.content ?? args?.contents
          if (!patch && toolName === 'write_file' && typeof content === 'string') {
            const lines = content.split('\n')
            patch = `@@ -0,0 +1,${lines.length} @@\n` + lines.map(l => `+${l}`).join('\n')
          } else if (!patch && toolName === 'edit_file' && typeof args?.old_string === 'string' && typeof args?.new_string === 'string') {
            const oldLines = args.old_string.split('\n').map(l => `-${l}`)
            const newLines = args.new_string.split('\n').map(l => `+${l}`)
            patch = `@@ -1,${oldLines.length} +1,${newLines.length} @@\n` + [...oldLines, ...newLines].join('\n')
          }

          // 3. Fallback: only if tc.result looks like an actual unified diff (starts with @@ or diff or ---)
          if (!patch && typeof tc.result === 'string' && (tc.result.startsWith('@@') || tc.result.startsWith('diff --git') || tc.result.startsWith('--- '))) {
            patch = tc.result
          }

          let additions = 0
          let deletions = 0
          if (patch) {
            const stats = diffStatistics(patch)
            additions = stats.additions
            deletions = stats.deletions
          }

          const existing = map.get(file)
          if (!existing) {
            map.set(file, {
              file,
              relativePath: file,
              path: file,
              patch,
              diff: patch,
              status: toolName === 'write_file' ? 'added' : 'modified',
              additions,
              deletions
            })
          } else {
            if (!existing.patch && patch) {
              existing.patch = patch
              existing.diff = patch
            }
            if (!existing.additions && !existing.deletions && (additions || deletions)) {
              existing.additions = additions
              existing.deletions = deletions
            }
          }
        }
      }
    }

    // 4. Completion Evidence changedFiles (fallback when only file list is available)
    const ev = m?.completionEvidence || m?.completionBlockedEvidence
    if (ev?.changedFiles && Array.isArray(ev.changedFiles)) {
      for (const f of ev.changedFiles) {
        const file = normalizeWorkspacePath(f)
        if (!file) continue
        if (!map.has(file)) {
          map.set(file, {
            file,
            relativePath: file,
            path: file,
            status: 'modified',
            patch: '',
            diff: '',
            additions: 0,
            deletions: 0
          })
        }
      }
    }
  }

  return Array.from(map.values())
}

/**
 * Resolves changes produced specifically in a single message turn.
 */
export function resolveMessageChanges(msg) {
  if (!msg) return []
  return resolveEffectiveChanges([], [msg])
}

/**
 * Calculates total additions and deletions for a single message turn.
 */
export function resolveMessageStats(msg) {
  const changes = resolveMessageChanges(msg)
  let additions = 0
  let deletions = 0
  for (const c of changes) {
    additions += (c.additions || 0)
    deletions += (c.deletions || 0)
  }
  return { additions, deletions, count: changes.length }
}
