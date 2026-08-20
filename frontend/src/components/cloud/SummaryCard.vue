<template>
  <div v-if="content" class="summary-card">
    <div class="summary-header" @click="open = !open">
      <div class="summary-header-left">
        <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="#10b981" stroke-width="2">
          <path d="M22 11.08V12a10 10 0 1 1-5.93-9.14"/><path d="M22 4L12 14.01l-3-3"/>
        </svg>
        <span class="summary-title">任务完成总结</span>
        <span v-if="hasContent" class="summary-badge">{{ stats.words }} 字</span>
      </div>
      <div class="summary-header-right">
        <div class="summary-stats" @click.stop>
          <span v-if="stats.sections > 0" class="stat-chip" :title="`${stats.sections} 个章节`">
            <svg width="11" height="11" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M4 6h16M4 12h16M4 18h10"/></svg>
            {{ stats.sections }}
          </span>
          <span v-if="fileList.length > 0" class="stat-chip" :title="`${fileList.length} 个文件`">
            <svg width="11" height="11" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z"/><polyline points="14 2 14 8 20 8"/></svg>
            {{ fileList.length }}
          </span>
          <span v-if="suggestions.length > 0" class="stat-chip" :title="`${suggestions.length} 条建议`">
            <svg width="11" height="11" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M9 11l3 3L22 4"/><path d="M21 12v7a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h11"/></svg>
            {{ suggestions.length }}
          </span>
        </div>
        <svg width="10" height="10" viewBox="0 0 24 24" fill="none" stroke="#9ca3af" stroke-width="2"
          :style="{ transform: open ? 'rotate(180deg)' : '', cursor: 'pointer' }" @click.stop="open = !open">
          <polyline points="6 9 12 15 18 9"/>
        </svg>
      </div>
    </div>
    <Transition name="tc-slide">
      <div v-if="open" class="summary-body">
        <div ref="contentRef" class="summary-content" v-html="renderedHtml" @click="handleContentClick"></div>
        <div v-if="fileList.length > 0" class="summary-files">
          <div class="files-title">
            <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="#10b981" stroke-width="2">
              <path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z"/><polyline points="14 2 14 8 20 8"/>
            </svg>
            <span>涉及文件</span>
            <span class="files-count">{{ fileList.length }}</span>
          </div>
          <div class="files-list">
            <span v-for="f in fileList" :key="f" class="file-chip" :title="`打开 ${f}`" @click="$emit('open-file', f)">
              <svg width="11" height="11" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z"/><polyline points="14 2 14 8 20 8"/></svg>
              <span>{{ f.split('/').pop() }}</span>
            </span>
          </div>
        </div>
        <div v-if="suggestions.length > 0" class="summary-suggestions">
          <div class="suggestions-title">
            <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="#f59e0b" stroke-width="2">
              <path d="M9.663 17h4.673M12 3v1m6.364 1.636l-.707.707M21 12h-1M4 12H3m3.343-5.657l-.707-.707m2.828 9.9a5 5 0 1 1 7.072 0l-.548.547A3.374 3.374 0 0 0 14 18.469V19a2 2 0 1 1-4 0v-.531c0-.895-.356-1.754-.988-2.386l-.548-.547z"/>
            </svg>
            <span>拓展建议</span>
            <span class="suggestions-count">{{ suggestions.length }}</span>
          </div>
          <div class="suggestions-grid">
            <div v-for="(s, idx) in suggestions" :key="idx" class="suggestion-card" @click="$emit('apply-suggestion', s)">
              <span class="suggestion-index">{{ idx + 1 }}</span>
              <span class="suggestion-text">{{ s }}</span>
              <svg class="suggestion-arrow" width="11" height="11" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><polyline points="9 18 15 12 9 6"/></svg>
            </div>
          </div>
        </div>
      </div>
    </Transition>
  </div>
</template>

<script setup>
import { ref, computed, watch, nextTick } from 'vue'
import { enhanceFileLinks, FILE_LINK_EXTS } from '@/utils/fileLinks'

const props = defineProps({ content: String })
const emit = defineEmits(['apply-suggestion', 'open-file'])
const open = ref(true)

const fileIconSvg = '<svg viewBox="0 0 24 24" width="11" height="11" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z"/><polyline points="14 2 14 8 20 8"/></svg>'

const suggestions = computed(() => {
  if (!props.content) return []
  const match = props.content.match(/(?:后续建议|拓展建议|建议|Suggestions?)\s*\n([\s\S]*?)(?:\n##|\n---|$)/i)
  if (!match) return []
  return match[1].split('\n')
    .map(l => l.replace(/^[-*]\s*/, '').trim())
    .filter(l => l.length > 2)
})

// Header stats — pure string parses so they're cheap to recompute.
const stats = computed(() => {
  const text = props.content || ''
  const stripped = text.replace(/```[\s\S]*?```/g, '').replace(/[#*`>|\-]/g, '')
  const cjk = (stripped.match(/[一-龥]/g) || []).length
  const words = stripped.trim().length
  return {
    words: cjk > 0 ? cjk : Math.max(1, Math.round(words / 4)),
    sections: (text.match(/^##\s+/gm) || []).length
  }
})

const hasContent = computed(() => Boolean(props.content))

// Unique, ordered file paths referenced anywhere in the content.
const fileList = computed(() => {
  const text = props.content || ''
  const re = /((?:[A-Za-z][\w.-]*\/)+[\w.-]+\.[A-Za-z0-9]{1,10})/g
  const seen = new Set()
  const out = []
  let m
  while ((m = re.exec(text)) !== null) {
    const ext = m[1].split('.').pop().toLowerCase()
    if (!FILE_LINK_EXTS.has(ext)) continue
    if (seen.has(m[1])) continue
    seen.add(m[1])
    out.push(m[1])
  }
  return out
})

function baseMarkdownHtml(text) {
  if (!text) return ''
  let html = text
  html = html.replace(/[\u{1F600}-\u{1F64F}\u{1F300}-\u{1F5FF}\u{1F680}-\u{1F6FF}\u{1F1E0}-\u{1F1FF}\u{2600}-\u{26FF}\u{2700}-\u{27BF}\u{FE00}-\u{FE0F}\u{1F900}-\u{1F9FF}\u{1FA00}-\u{1FA6F}\u{1FA70}-\u{1FAFF}\u{2702}-\u{27B0}\u{200D}\u{20E3}\u{E0020}-\u{E007F}]/gu, '')
  html = html.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')

  html = html.replace(/```(\w*)\n([\s\S]*?)```/g, (_, lang, code) => {
    const langLabel = lang ? `<span class="code-lang">${lang}</span>` : ''
    return `<div class="s-code-block">${langLabel}<pre><code>${code.trim()}</code></pre></div>`
  })
  html = html.replace(/`([^`\n]+)`/g, '<code class="s-inline-code">$1</code>')
  html = html.replace(/\*\*(.+?)\*\*/g, '<strong>$1</strong>')
  html = html.replace(/(?<!\*)\*(?!\*)(.+?)(?<!\*)\*(?!\*)/g, '<em>$1</em>')
  html = html.replace(/~~(.+?)~~/g, '<del>$1</del>')
  html = html.replace(/^#### (.+)$/gm, '<h5>$1</h5>')
  html = html.replace(/^### (.+)$/gm, '<h4>$1</h4>')
  html = html.replace(/^## (.+)$/gm, '<h3>$1</h3>')
  html = html.replace(/^# (.+)$/gm, '<h2>$1</h2>')
  html = html.replace(/^&gt; (.+)$/gm, '<blockquote>$1</blockquote>')
  html = html.replace(/^(\|.+\|)\n(\|[-: |]+\|)\n((?:\|.+\|\n?)*)/gm, (_, header, sep, body) => {
    const ths = header.split('|').filter(c => c.trim()).map(c => `<th>${c.trim()}</th>`).join('')
    const rows = body.trim().split('\n').map(row => {
      const tds = row.split('|').filter(c => c.trim()).map(c => `<td>${c.trim()}</td>`).join('')
      return `<tr>${tds}</tr>`
    }).join('')
    return `<div class="s-table-wrap"><div class="s-table-scroll"><table class="s-table"><thead><tr>${ths}</tr></thead><tbody>${rows}</tbody></table></div></div>`
  })
  html = html.replace(/^[-*+] (.+)$/gm, '<li class="s-ul">$1</li>')
  html = html.replace(/^\d+\. (.+)$/gm, '<li class="s-ol">$1</li>')
  html = html.replace(/((?:<li class="s-ul">.*<\/li>\n?)+)/gs, '<ul class="s-list">$1</ul>')
  html = html.replace(/((?:<li class="s-ol">.*<\/li>\n?)+)/gs, '<ol class="s-list">$1</ol>')
  html = html.replace(/^---+$/gm, '<hr class="s-hr">')
  html = html.replace(/(^|[\s(])(https?:\/\/[^\s<>\)]+)/g, (m, prefix, url) => {
    return `${prefix}<a href="${url}" target="_blank" rel="noopener noreferrer" class="s-link">${url}</a>`
  })
  html = html.replace(/\n/g, '<br>')
  html = html.replace(/<br><br>/g, '<br>')
  html = html.replace(/<br>(<\/?(?:ul|ol|li|h[2-5]|blockquote|table|div|hr))/g, '$1')
  html = html.replace(/(<\/(?:ul|ol|li|h[2-5]|blockquote|table|div)>)<br>/g, '$1')
  return html
}

const renderedHtml = computed(() => baseMarkdownHtml(props.content))

const contentRef = ref(null)

// Wrap file paths in <span class="s-file-link">...</span> after the HTML
// is mounted. v-html doesn't run after hooks on children, so we wire this
// up via a watch on renderedHtml + nextTick.
watch(renderedHtml, () => {
  nextTick(() => {
    if (contentRef.value) {
      enhanceFileLinks(contentRef.value, { iconHtml: fileIconSvg, linkClass: 's-file-link' })
    }
  })
}, { immediate: true })

function handleContentClick(event) {
  const link = event.target?.closest?.('.s-file-link')
  if (link && link.dataset.path) {
    emit('open-file', link.dataset.path)
  }
}
</script>

<style scoped>
.summary-card {
  margin: 12px 0;
  border: 1px solid #d1fae5;
  border-radius: 12px;
  overflow: hidden;
  background: #ffffff;
  box-shadow: 0 1px 4px rgba(16, 185, 129, 0.06);
  transition: all 0.2s cubic-bezier(0.16, 1, 0.3, 1);
}
.summary-card:hover {
  border-color: #a7f3d0;
  box-shadow: 0 6px 20px -2px rgba(16, 185, 129, 0.14), 0 2px 6px -1px rgba(0, 0, 0, 0.04);
  transform: translateY(-1px);
}
.summary-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  padding: 12px 18px;
  cursor: pointer;
  font-size: 13px;
  font-weight: 700;
  color: #065f46;
  user-select: none;
  background: linear-gradient(135deg, #ecfdf5 0%, #f0fdf4 100%);
  border-bottom: 1px solid #d1fae5;
}
.summary-header-left,
.summary-header-right {
  display: flex;
  align-items: center;
  gap: 8px;
}
.summary-header-left { flex: 1; min-width: 0; }
.summary-title { flex: 1; }
.summary-badge {
  display: inline-flex;
  align-items: center;
  padding: 1px 8px;
  font-size: 10.5px;
  font-weight: 600;
  color: #047857;
  background: #fff;
  border: 1px solid #a7f3d0;
  border-radius: 999px;
}
.summary-stats {
  display: inline-flex;
  align-items: center;
  gap: 4px;
}
.stat-chip {
  display: inline-flex;
  align-items: center;
  gap: 3px;
  padding: 2px 7px;
  font-size: 10.5px;
  font-weight: 600;
  color: #065f46;
  background: #fff;
  border: 1px solid #a7f3d0;
  border-radius: 999px;
}
.stat-chip svg { color: #10b981; }

.summary-body { padding: 20px 24px 24px; }
.summary-content {
  font-size: 13px;
  line-height: 1.85;
  color: #1f2937;
  word-break: break-word;
  overflow-wrap: anywhere;
}

/* ===== Headings ===== */
.summary-content :deep(h2) {
  font-size: 17px;
  font-weight: 800;
  color: #065f46;
  margin: 20px 0 10px;
  padding-bottom: 6px;
  border-bottom: 2px solid #d1fae5;
  letter-spacing: -0.01em;
}
.summary-content :deep(h3) {
  font-size: 15px;
  font-weight: 700;
  color: #047857;
  margin: 16px 0 8px;
  padding-left: 10px;
  border-left: 3px solid #10b981;
}
.summary-content :deep(h4) {
  font-size: 13.5px;
  font-weight: 700;
  color: #059669;
  margin: 12px 0 6px;
}
.summary-content :deep(h5) {
  font-size: 13px;
  font-weight: 700;
  color: #065f46;
  margin: 10px 0 4px;
}
.summary-content :deep(p) { margin: 8px 0; }

/* ===== Lists ===== */
.summary-content :deep(.s-list) {
  margin: 8px 0;
  padding-left: 24px;
  list-style: none;
}
.summary-content :deep(.s-ul)::before {
  content: '';
  display: inline-block;
  width: 6px;
  height: 6px;
  background: #10b981;
  border-radius: 50%;
  margin-right: 10px;
  vertical-align: middle;
}
.summary-content :deep(.s-ol) { counter-increment: s-counter; }
.summary-content :deep(ol.s-list) { counter-reset: s-counter; }
.summary-content :deep(.s-ol)::before {
  content: counter(s-counter) '.';
  color: #10b981;
  font-weight: 700;
  margin-right: 8px;
  font-size: 12px;
}
.summary-content :deep(.s-list li) {
  margin: 5px 0;
  line-height: 1.8;
  color: #374151;
}

/* ===== Code ===== */
.summary-content :deep(.s-inline-code) {
  background: #ecfdf5;
  padding: 2px 7px;
  border-radius: 4px;
  font-size: 12px;
  font-family: 'JetBrains Mono', monospace;
  color: #065f46;
  border: 1px solid #bbf7d0;
  font-weight: 500;
}
.summary-content :deep(.s-code-block) {
  position: relative;
  margin: 12px 0;
  border-radius: 10px;
  overflow: hidden;
  border: 1px solid #d1d5db;
}
.summary-content :deep(.s-code-block pre) {
  background: #1e293b;
  color: #e2e8f0;
  padding: 16px 18px;
  margin: 0;
  font-size: 12px;
  font-family: 'JetBrains Mono', monospace;
  line-height: 1.6;
  overflow-x: auto;
}
.summary-content :deep(.s-code-block code) {
  background: transparent;
  padding: 0;
  color: inherit;
  border: none;
  font-size: inherit;
}
.summary-content :deep(.code-lang) {
  position: absolute;
  top: 0;
  right: 0;
  padding: 2px 10px;
  font-size: 10px;
  color: #94a3b8;
  background: #334155;
  border-radius: 0 10px 0 8px;
  text-transform: uppercase;
  letter-spacing: 0.5px;
  font-family: 'JetBrains Mono', monospace;
}

/* ===== Table (clean row separators, no vertical lines, no zebra) ===== */
.summary-content :deep(.s-table-wrap) {
  margin: 14px 0;
  border-radius: 8px;
  border: 1px solid #d1d5db;
  overflow: hidden;
  background: #fff;
}
.summary-content :deep(.s-table-scroll) {
  overflow-x: auto;
  -webkit-mask-image: linear-gradient(90deg, transparent 0, #000 16px, #000 calc(100% - 16px), transparent 100%);
  mask-image: linear-gradient(90deg, transparent 0, #000 16px, #000 calc(100% - 16px), transparent 100%);
}
.summary-content :deep(.s-table) {
  width: 100%;
  min-width: 300px;
  border-collapse: separate;
  border-spacing: 0;
  font-size: 13px;
  line-height: 1.55;
}
.summary-content :deep(.s-table th),
.summary-content :deep(.s-table td) {
  padding: 14px 18px;
  border-bottom: 1px solid #e5e7eb;
  text-align: left;
  vertical-align: top;
}
.summary-content :deep(.s-table tbody tr td:first-child) {
  font-weight: 600;
  color: #065f46;
}
.summary-content :deep(.s-table thead th) {
  background: #f9fafb;
  font-weight: 700;
  color: #065f46;
  white-space: nowrap;
  border-bottom-color: #d1d5db;
}
.summary-content :deep(.s-table td) {
  color: #374151;
  word-break: break-word;
}
.summary-content :deep(.s-table tbody tr:last-child td) {
  border-bottom: 0;
}
.summary-content :deep(.s-table tbody tr) {
  transition: background 0.12s;
}
.summary-content :deep(.s-table tbody tr:hover td) {
  background: #ecfdf5;
}

/* ===== Links ===== */
.summary-content :deep(.s-link) {
  color: #059669;
  text-decoration: none;
  border-bottom: 1px dashed #6ee7b7;
  font-weight: 500;
  transition: all 0.15s;
}
.summary-content :deep(.s-link:hover) {
  color: #047857;
  border-bottom-color: #047857;
  border-bottom-style: solid;
}

/* ===== File-link chip ===== */
.summary-content :deep(.s-file-link) {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 1px 7px 1px 5px;
  margin: 0 1px;
  color: #047857;
  background: #ecfdf5;
  border: 1px solid #a7f3d0;
  border-radius: 5px;
  font-family: 'JetBrains Mono', 'Fira Code', Consolas, monospace;
  font-size: 0.9em;
  font-weight: 500;
  cursor: pointer;
  white-space: nowrap;
  max-width: 100%;
  overflow: hidden;
  text-overflow: ellipsis;
  transition: all 0.15s;
  vertical-align: baseline;
}
.summary-content :deep(.s-file-link:hover) {
  background: #d1fae5;
  border-color: #6ee7b7;
}
.summary-content :deep(.s-file-link .s-file-link-icon) { flex-shrink: 0; opacity: 0.85; }
.summary-content :deep(.s-file-link .s-file-link-text) {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

/* ===== Horizontal Rule ===== */
.summary-content :deep(.s-hr) {
  border: none;
  border-top: 2px solid #d1fae5;
  margin: 16px 0;
}

/* ===== Blockquote ===== */
.summary-content :deep(blockquote) {
  margin: 10px 0;
  padding: 10px 16px;
  border-left: 4px solid #10b981;
  background: #ecfdf5;
  border-radius: 0 8px 8px 0;
  color: #374151;
  font-style: italic;
  line-height: 1.7;
}

/* ===== Bold & Emphasis ===== */
.summary-content :deep(strong) {
  color: #065f46;
  font-weight: 700;
}
.summary-content :deep(em) { color: #374151; font-style: italic; }
.summary-content :deep(del) { color: #9ca3af; text-decoration: line-through; }

/* ===== Files list (new section) ===== */
.summary-files {
  margin-top: 16px;
  padding: 12px 14px;
  background: #fff;
  border: 1px solid #d1fae5;
  border-radius: 10px;
}
.files-title {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 12px;
  font-weight: 700;
  color: #065f46;
  margin-bottom: 8px;
}
.files-title svg { color: #10b981; }
.files-count {
  margin-left: 4px;
  padding: 0 6px;
  font-size: 10.5px;
  color: #047857;
  background: #ecfdf5;
  border-radius: 999px;
  border: 1px solid #a7f3d0;
}
.files-list {
  display: flex;
  flex-wrap: wrap;
  gap: 5px;
}
.file-chip {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 3px 9px;
  font-size: 11.5px;
  color: #047857;
  background: #ecfdf5;
  border: 1px solid #a7f3d0;
  border-radius: 999px;
  cursor: pointer;
  transition: all 0.15s;
}
.file-chip:hover {
  background: #d1fae5;
  border-color: #6ee7b7;
}
.file-chip svg { color: #10b981; flex-shrink: 0; }

/* ===== Suggestions (cards grid) ===== */
.summary-suggestions {
  margin-top: 16px;
  padding-top: 14px;
  border-top: 1px solid #d1fae5;
}
.suggestions-title {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 12px;
  font-weight: 700;
  color: #92400e;
  margin-bottom: 8px;
}
.suggestions-count {
  margin-left: 4px;
  padding: 0 6px;
  font-size: 10.5px;
  color: #b45309;
  background: #fffbeb;
  border: 1px solid #fde68a;
  border-radius: 999px;
}
.suggestions-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(220px, 1fr));
  gap: 6px;
}
.suggestion-card {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 9px 12px;
  font-size: 12.5px;
  color: #1e40af;
  background: #eff6ff;
  border: 1px solid #bfdbfe;
  border-radius: 8px;
  cursor: pointer;
  transition: all 0.15s;
}
.suggestion-card:hover {
  background: #dbeafe;
  border-color: #93c5fd;
  transform: translateY(-1px);
  box-shadow: 0 2px 6px rgba(37, 99, 235, 0.08);
}
.suggestion-index {
  flex-shrink: 0;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 18px;
  height: 18px;
  font-size: 10.5px;
  font-weight: 700;
  color: #1e40af;
  background: #fff;
  border: 1px solid #93c5fd;
  border-radius: 50%;
}
.suggestion-text {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.suggestion-arrow {
  flex-shrink: 0;
  color: #60a5fa;
  opacity: 0;
  transition: opacity 0.15s;
}
.suggestion-card:hover .suggestion-arrow { opacity: 1; }
</style>
