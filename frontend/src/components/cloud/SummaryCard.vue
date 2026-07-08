<template>
  <div v-if="content" class="summary-card">
    <div class="summary-header" @click="open = !open">
      <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="#10b981" stroke-width="2">
        <path d="M22 11.08V12a10 10 0 1 1-5.93-9.14"/><path d="M22 4L12 14.01l-3-3"/>
      </svg>
      <span class="summary-title">任务完成总结</span>
      <svg width="10" height="10" viewBox="0 0 24 24" fill="none" stroke="#9ca3af" stroke-width="2"
        :style="{ transform: open ? 'rotate(180deg)' : '' }">
        <polyline points="6 9 12 15 18 9"/>
      </svg>
    </div>
    <Transition name="tc-slide">
      <div v-if="open" class="summary-body">
        <div class="summary-content" v-html="renderMarkdown(content)"></div>
        <div v-if="suggestions.length > 0" class="summary-suggestions">
          <div class="suggestions-title">
            <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="#f59e0b" stroke-width="2">
              <path d="M9.663 17h4.673M12 3v1m6.364 1.636l-.707.707M21 12h-1M4 12H3m3.343-5.657l-.707-.707m2.828 9.9a5 5 0 1 1 7.072 0l-.548.547A3.374 3.374 0 0 0 14 18.469V19a2 2 0 1 1-4 0v-.531c0-.895-.356-1.754-.988-2.386l-.548-.547z"/>
            </svg>
            <span>拓展建议</span>
          </div>
          <div v-for="(s, idx) in suggestions" :key="idx" class="suggestion-item" @click="$emit('apply-suggestion', s)">
            <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="#60a5fa" stroke-width="2">
              <line x1="5" y1="12" x2="19" y2="12"/><polyline points="12 5 19 12 12 19"/>
            </svg>
            <span>{{ s }}</span>
          </div>
        </div>
      </div>
    </Transition>
  </div>
</template>

<script setup>
import { ref, computed } from 'vue'

const props = defineProps({ content: String })
defineEmits(['apply-suggestion'])
const open = ref(true)

const suggestions = computed(() => {
  if (!props.content) return []
  const match = props.content.match(/(?:后续建议|拓展建议|建议|Suggestions?)\s*\n([\s\S]*?)(?:\n##|\n---|$)/i)
  if (!match) return []
  return match[1].split('\n')
    .map(l => l.replace(/^[-*]\s*/, '').trim())
    .filter(l => l.length > 2)
})

function renderMarkdown(text) {
  if (!text) return ''
  let html = text
  html = html.replace(/[\u{1F600}-\u{1F64F}\u{1F300}-\u{1F5FF}\u{1F680}-\u{1F6FF}\u{1F1E0}-\u{1F1FF}\u{2600}-\u{26FF}\u{2700}-\u{27BF}\u{FE00}-\u{FE0F}\u{1F900}-\u{1F9FF}\u{1FA00}-\u{1FA6F}\u{1FA70}-\u{1FAFF}\u{2702}-\u{27B0}\u{200D}\u{20E3}\u{E0020}-\u{E007F}]/gu, '')
  html = html.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')

  // Code blocks
  html = html.replace(/```(\w*)\n([\s\S]*?)```/g, (_, lang, code) => {
    const langLabel = lang ? `<span class="code-lang">${lang}</span>` : ''
    return `<div class="s-code-block">${langLabel}<pre><code>${code.trim()}</code></pre></div>`
  })
  // Inline code
  html = html.replace(/`([^`\n]+)`/g, '<code class="s-inline-code">$1</code>')
  // Bold
  html = html.replace(/\*\*(.+?)\*\*/g, '<strong>$1</strong>')
  // Italic
  html = html.replace(/(?<!\*)\*(?!\*)(.+?)(?<!\*)\*(?!\*)/g, '<em>$1</em>')
  // Strikethrough
  html = html.replace(/~~(.+?)~~/g, '<del>$1</del>')
  // Headers
  html = html.replace(/^#### (.+)$/gm, '<h5>$1</h5>')
  html = html.replace(/^### (.+)$/gm, '<h4>$1</h4>')
  html = html.replace(/^## (.+)$/gm, '<h3>$1</h3>')
  html = html.replace(/^# (.+)$/gm, '<h2>$1</h2>')
  // Blockquotes
  html = html.replace(/^&gt; (.+)$/gm, '<blockquote>$1</blockquote>')
  // Tables
  html = html.replace(/^(\|.+\|)\n(\|[-: |]+\|)\n((?:\|.+\|\n?)*)/gm, (_, header, sep, body) => {
    const ths = header.split('|').filter(c => c.trim()).map(c => `<th>${c.trim()}</th>`).join('')
    const rows = body.trim().split('\n').map(row => {
      const tds = row.split('|').filter(c => c.trim()).map(c => `<td>${c.trim()}</td>`).join('')
      return `<tr>${tds}</tr>`
    }).join('')
    return `<div class="s-table-wrap"><div class="s-table-scroll"><table class="s-table"><thead><tr>${ths}</tr></thead><tbody>${rows}</tbody></table></div></div>`
  })
  // Unordered lists
  html = html.replace(/^[-*+] (.+)$/gm, '<li class="s-ul">$1</li>')
  // Ordered lists
  html = html.replace(/^\d+\. (.+)$/gm, '<li class="s-ol">$1</li>')
  // Wrap consecutive ul items
  html = html.replace(/((?:<li class="s-ul">.*<\/li>\n?)+)/gs, '<ul class="s-list">$1</ul>')
  // Wrap consecutive ol items
  html = html.replace(/((?:<li class="s-ol">.*<\/li>\n?)+)/gs, '<ol class="s-list">$1</ol>')
  // Horizontal rules
  html = html.replace(/^---+$/gm, '<hr class="s-hr">')
  // URLs as links
  html = html.replace(/(^|[\s(])(https?:\/\/[^\s<>\)]+)/g, (m, prefix, url) => {
    return `${prefix}<a href="${url}" target="_blank" rel="noopener noreferrer" class="s-link">${url}</a>`
  })
  // Line breaks
  html = html.replace(/\n/g, '<br>')
  html = html.replace(/<br><br>/g, '<br>')
  html = html.replace(/<br>(<\/?(?:ul|ol|li|h[2-5]|blockquote|table|div|hr))/g, '$1')
  html = html.replace(/(<\/(?:ul|ol|li|h[2-5]|blockquote|table|div)>)<br>/g, '$1')
  return html
}
</script>

<style scoped>
.summary-card {
  margin: 12px 0;
  border: 1px solid #d1fae5;
  border-radius: 12px;
  overflow: hidden;
  background: linear-gradient(180deg, #f0fdf4 0%, #fff 100%);
  box-shadow: 0 1px 4px rgba(16, 185, 129, 0.08);
}
.summary-header {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 14px 20px;
  cursor: pointer;
  font-size: 13px;
  font-weight: 700;
  color: #065f46;
  user-select: none;
  background: linear-gradient(135deg, #ecfdf5 0%, #f0fdf4 100%);
  border-bottom: 1px solid #d1fae5;
}
.summary-title { flex: 1; }
.summary-body {
  padding: 24px 28px 28px;
}
.summary-content {
  font-size: 13px;
  line-height: 1.9;
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

/* ===== Paragraphs ===== */
.summary-content :deep(p) {
  margin: 8px 0;
}

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
.summary-content :deep(.s-ol) {
  counter-increment: s-counter;
}
.summary-content :deep(ol.s-list) {
  counter-reset: s-counter;
}
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

/* ===== Table ===== */
.summary-content :deep(.s-table-wrap) {
  margin: 14px 0;
  border-radius: 8px;
  border: 1px solid #d1d5db;
  overflow: hidden;
}
.summary-content :deep(.s-table-scroll) {
  overflow-x: auto;
}
.summary-content :deep(.s-table) {
  width: 100%;
  min-width: 300px;
  border-collapse: collapse;
  font-size: 12.5px;
}
.summary-content :deep(.s-table th),
.summary-content :deep(.s-table td) {
  padding: 10px 14px;
  border: 1px solid #d1d5db;
  text-align: left;
}
.summary-content :deep(.s-table th) {
  background: #f0fdf4;
  font-weight: 700;
  color: #065f46;
  white-space: nowrap;
}
.summary-content :deep(.s-table td) {
  color: #374151;
  line-height: 1.6;
  word-break: break-word;
}
.summary-content :deep(.s-table tr:nth-child(even) td) {
  background: #f9fafb;
}
.summary-content :deep(.s-table tr:hover td) {
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
.summary-content :deep(em) {
  color: #374151;
  font-style: italic;
}
.summary-content :deep(del) {
  color: #9ca3af;
  text-decoration: line-through;
}

/* ===== Suggestions ===== */
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
.suggestion-item {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 8px 12px;
  margin: 4px 0;
  font-size: 12.5px;
  color: #1e40af;
  cursor: pointer;
  border-radius: 6px;
  transition: background 0.15s;
  border: 1px solid transparent;
}
.suggestion-item:hover {
  background: #dbeafe;
  border-color: #bfdbfe;
}
</style>
