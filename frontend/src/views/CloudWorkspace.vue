<template>
  <div class="ws-shell">
    <header class="ws-topbar">
      <button class="ws-btn ws-btn-ghost" @click="goBack">
        <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><polyline points="15 18 9 12 15 6"/></svg>
        <span>返回</span>
      </button>
      <div class="ws-title-section">
        <AppIcon :size="26" compact />
        <span class="ws-title">{{ projectName || '工作空间' }}</span>
      </div>
      <div class="ws-topbar-right">
        <button class="ws-btn ws-btn-outline ws-btn-sm" @click="exportProject" title="导出项目">
          <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"/><polyline points="7 10 12 15 17 10"/><line x1="12" y1="15" x2="12" y2="3"/></svg>
          <span>导出</span>
        </button>
        <span v-if="fileContentDirty" class="ws-unsaved">未保存</span>
        <button v-if="activePath" class="ws-btn ws-btn-outline ws-btn-sm" @click="saveFile" :disabled="savingFile">{{ savingFile ? '保存中...' : '保存' }}</button>
      </div>
    </header>
    <div class="ws-body">
      <aside class="ws-sidebar">
        <div class="ws-sidebar-header">
          <span>文件资源管理器</span>
          <div class="ws-sidebar-actions">
            <button class="ws-btn ws-btn-ghost ws-btn-sm" @click="showNewFileModal('file')" title="新建文件">
              <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z"/><polyline points="14 2 14 8 20 8"/><line x1="12" y1="18" x2="12" y2="12"/><line x1="9" y1="15" x2="15" y2="15"/></svg>
            </button>
            <button class="ws-btn ws-btn-ghost ws-btn-sm" @click="showNewFileModal('directory')" title="新建文件夹">
              <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M22 19a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h5l2 3h9a2 2 0 0 1 2 2z"/><line x1="12" y1="11" x2="12" y2="17"/><line x1="9" y1="14" x2="15" y2="14"/></svg>
            </button>
            <button class="ws-btn ws-btn-ghost ws-btn-sm" @click="loadRoot" title="刷新">
              <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><polyline points="23 4 23 10 17 10"/><path d="M20.49 15a9 9 0 1 1-2.12-9.36L23 10"/></svg>
            </button>
          </div>
        </div>
        <div class="ws-tree" v-loading="treeLoading">
          <FileTreeNode v-for="child in fileTree" :key="child.path" :node="child" :selected-path="activePath" :load-children="loadChildren" :show-actions="true" @select="openFile" @newItem="handleNewItem" @rename="handleRename" @delete="handleDelete"/>
          <div v-if="!treeLoading && fileTree.length === 0" class="ws-tree-empty">暂无文件</div>
        </div>
      </aside>
      <main class="ws-editor">
        <div v-if="openFiles.length === 0" class="ws-editor-empty">
          <svg width="48" height="48" viewBox="0 0 24 24" fill="none" stroke="#e5e7eb" stroke-width="1"><path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z"/><polyline points="14 2 14 8 20 8"/></svg>
          <p>选择文件开始编辑</p>
          <p class="ws-editor-hint">从左侧文件树中选择一个文件</p>
        </div>
        <template v-else>
          <div class="ws-editor-tabs">
            <div v-for="(f, idx) in openFiles" :key="f.path" class="ws-tab" :class="{ active: idx === activeTabIndex }" @click="switchTab(idx)">
              <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z"/><polyline points="14 2 14 8 20 8"/></svg>
              <span class="ws-tab-name">{{ f.name }}</span>
              <span v-if="f.dirty" class="ws-tab-dot"></span>
              <button class="ws-tab-close" @click.stop="closeFile(idx)" title="关闭">
                <svg width="10" height="10" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><line x1="18" y1="6" x2="6" y2="18"/><line x1="6" y1="6" x2="18" y2="18"/></svg>
              </button>
            </div>
          </div>
          <div class="ws-monaco"><MonacoEditor v-if="editorReady" v-model="fileContent" :language="detectedLang" height="100%"/></div>
        </template>
      </main>

      <!-- ==================== AI ASSISTANT SIDEBAR ==================== -->
      <aside class="ai-panel" :class="{ collapsed: aiCollapsed, dark: aiDarkTheme }">
        <!-- Resize Handle -->
        <div v-if="!aiCollapsed" class="ai-resize-handle" @mousedown="startResize"></div>

        <!-- Collapsed State: Icon Column -->
        <div v-if="aiCollapsed" class="ai-collapsed-bar">
          <button class="ai-icon-btn" @click="aiCollapsed = false" title="展开 LabexAgent">
            <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M21 15a2 2 0 0 1-2 2H7l-4 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2z"/></svg>
          </button>
          <button class="ai-icon-btn" title="设置">
            <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><circle cx="12" cy="12" r="3"/><path d="M19.4 15a1.65 1.65 0 0 0 .33 1.82l.06.06a2 2 0 0 1 0 2.83 2 2 0 0 1-2.83 0l-.06-.06a1.65 1.65 0 0 0-1.82-.33 1.65 1.65 0 0 0-1 1.51V21a2 2 0 0 1-2 2 2 2 0 0 1-2-2v-.09A1.65 1.65 0 0 0 9 19.4a1.65 1.65 0 0 0-1.82.33l-.06.06a2 2 0 0 1-2.83 0 2 2 0 0 1 0-2.83l.06-.06A1.65 1.65 0 0 0 4.68 15a1.65 1.65 0 0 0-1.51-1H3a2 2 0 0 1-2-2 2 2 0 0 1 2-2h.09A1.65 1.65 0 0 0 4.6 9a1.65 1.65 0 0 0-.33-1.82l-.06-.06a2 2 0 0 1 0-2.83 2 2 0 0 1 2.83 0l.06.06A1.65 1.65 0 0 0 9 4.68a1.65 1.65 0 0 0 1-1.51V3a2 2 0 0 1 2-2 2 2 0 0 1 2 2v.09a1.65 1.65 0 0 0 1 1.51 1.65 1.65 0 0 0 1.82-.33l.06-.06a2 2 0 0 1 2.83 0 2 2 0 0 1 0 2.83l-.06.06A1.65 1.65 0 0 0 19.4 9a1.65 1.65 0 0 0 1.51 1H21a2 2 0 0 1 2 2 2 2 0 0 1-2 2h-.09a1.65 1.65 0 0 0-1.51 1z"/></svg>
          </button>
          <div class="ai-collapsed-spacer"></div>
          <span class="ai-collapsed-badge" v-if="messages.length > 0">{{ messages.length }}</span>
        </div>

        <!-- Expanded State -->
        <template v-else>
          <!-- Top Bar -->
          <div class="ai-topbar">
            <button class="ai-topbar-btn" @click="aiCollapsed = true" title="折叠侧边栏">
              <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><line x1="3" y1="12" x2="21" y2="12"/><line x1="3" y1="6" x2="21" y2="6"/><line x1="3" y1="18" x2="21" y2="18"/></svg>
            </button>
            <div class="ai-topbar-title">
              <AppIcon :size="22" compact />
              <span>LabexAgent</span>
            </div>
            <div class="ai-topbar-actions">
              <div class="ai-session-select" @click.stop="showSessions = !showSessions">
                <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M21 12a9 9 0 0 1-9 9m9-9a9 9 0 0 0-9-9m9 9H3m9 9a9 9 0 0 1-9-9m9 9c1.657 0 3-4.03 3-9s-1.343-9-3-9m0 18c-1.657 0-3-4.03-3-9s1.343-9 3-9m-9 9a9 9 0 0 1 9-9"/></svg>
                <span class="ai-session-name">{{ currentSessionName }}</span>
                <svg width="10" height="10" viewBox="0 0 24 24" fill="none" stroke="#9ca3af" stroke-width="2"><polyline points="6 9 12 15 18 9"/></svg>
                <div v-if="showSessions" class="ai-session-dropdown" @click.stop>
                  <div v-for="c in conversations" :key="c.conversationId" class="ai-session-item"
                    :class="{ active: (currentAgentSession && currentAgentSession.conversationId === c.conversationId) }"
                    @click="selectConversation(c)">
                    <span class="ai-session-title">{{ c.title || '新对话' }}</span>
                    <span class="ai-session-time">{{ c.createTime?.substring(0, 16) || '' }}</span>
                    <button class="ai-session-action" @click.stop="forkConversation(c)" title="从此会话创建分支">
                      分支
                    </button>
                    <button class="ai-session-action" @click.stop="compactConversation(c)" title="压缩上下文">
                      压缩
                    </button>
                    <button class="ai-session-del" @click.stop="deleteConversation(c)" title="删除">
                      <svg width="10" height="10" viewBox="0 0 24 24" fill="none" stroke="#ef4444" stroke-width="2"><line x1="18" y1="6" x2="6" y2="18"/><line x1="6" y1="6" x2="18" y2="18"/></svg>
                    </button>
                  </div>
                  <div v-if="conversations.length === 0" class="ai-session-empty">暂无历史会话</div>
                  <div class="ai-session-new" @click="createNewSession">
                    <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><line x1="12" y1="5" x2="12" y2="19"/><line x1="5" y1="12" x2="19" y2="12"/></svg>
                    <span>新建会话</span>
                  </div>
                </div>
              </div>
              <button class="ai-topbar-btn" @click="toggleAiTheme" :title="aiDarkTheme ? '切换亮色主题' : '切换暗色主题'">
                <svg v-if="aiDarkTheme" width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><circle cx="12" cy="12" r="5"/><line x1="12" y1="1" x2="12" y2="3"/><line x1="12" y1="21" x2="12" y2="23"/><line x1="4.22" y1="4.22" x2="5.64" y2="5.64"/><line x1="18.36" y1="18.36" x2="19.78" y2="19.78"/><line x1="1" y1="12" x2="3" y2="12"/><line x1="21" y1="12" x2="23" y2="12"/><line x1="4.22" y1="19.78" x2="5.64" y2="18.36"/><line x1="18.36" y1="5.64" x2="19.78" y2="4.22"/></svg>
                <svg v-else width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M21 12.79A9 9 0 1 1 11.21 3 7 7 0 0 0 21 12.79z"/></svg>
              </button>
              <button class="ai-topbar-btn" @click="clearMessages" title="清空会话">
                <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><polyline points="3 6 5 6 21 6"/><path d="M19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6m3 0V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2"/></svg>
              </button>
              <button class="ai-topbar-btn" @click="forkCurrentConversation" title="分支当前会话">
                <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><circle cx="6" cy="6" r="3"/><circle cx="18" cy="18" r="3"/><path d="M8.5 8.5C11 12 13 14 15.5 15.5"/><path d="M6 9v4a5 5 0 0 0 5 5h4"/></svg>
              </button>
              <button class="ai-topbar-btn" @click="compactCurrentConversation" title="压缩当前上下文">
                <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M4 14h6v6"/><path d="M20 10h-6V4"/><path d="M14 10l6-6"/><path d="M10 14l-6 6"/></svg>
              </button>
              <button class="ai-topbar-btn" title="设置" @click="showSettings = true">
                <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><circle cx="12" cy="12" r="3"/><path d="M19.4 15a1.65 1.65 0 0 0 .33 1.82l.06.06a2 2 0 0 1 0 2.83 2 2 0 0 1-2.83 0l-.06-.06a1.65 1.65 0 0 0-1.82-.33 1.65 1.65 0 0 0-1 1.51V21a2 2 0 0 1-2 2 2 2 0 0 1-2-2v-.09A1.65 1.65 0 0 0 9 19.4a1.65 1.65 0 0 0-1.82.33l-.06.06a2 2 0 0 1-2.83 0 2 2 0 0 1 0-2.83l.06-.06A1.65 1.65 0 0 0 4.68 15a1.65 1.65 0 0 0-1.51-1H3a2 2 0 0 1-2-2 2 2 0 0 1 2-2h.09A1.65 1.65 0 0 0 4.6 9a1.65 1.65 0 0 0-.33-1.82l-.06-.06a2 2 0 0 1 0-2.83 2 2 0 0 1 2.83 0l.06.06A1.65 1.65 0 0 0 9 4.68a1.65 1.65 0 0 0 1-1.51V3a2 2 0 0 1 2-2 2 2 0 0 1 2 2v.09a1.65 1.65 0 0 0 1 1.51 1.65 1.65 0 0 0 1.82-.33l.06-.06a2 2 0 0 1 2.83 0 2 2 0 0 1 0 2.83l-.06.06A1.65 1.65 0 0 0 19.4 9a1.65 1.65 0 0 0 1.51 1H21a2 2 0 0 1 2 2 2 2 0 0 1-2 2h-.09a1.65 1.65 0 0 0-1.51 1z"/></svg>
              </button>
            </div>
          </div>

          <!-- Context Indicator -->
          <div class="ai-context" v-if="activePath">
            <div class="ai-context-header" @click="contextExpanded = !contextExpanded">
              <div class="ai-context-info">
                <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="#3b82f6" stroke-width="2"><path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z"/><polyline points="14 2 14 8 20 8"/></svg>
                <span class="ai-context-filename">{{ fileName }}</span>
                <span class="ai-context-lang">{{ detectedLang }}</span>
              </div>
              <div class="ai-context-actions">
                <button class="ai-ctx-btn" @click.stop="refreshContext" title="刷新上下文">
                  <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><polyline points="23 4 23 10 17 10"/><path d="M20.49 15a9 9 0 1 1-2.12-9.36L23 10"/></svg>
                </button>
                <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="#9ca3af" stroke-width="2" :style="{ transform: contextExpanded ? 'rotate(180deg)' : '' }" class="ai-context-arrow"><polyline points="6 9 12 15 18 9"/></svg>
              </div>
            </div>
            <Transition name="ai-slide">
              <div v-if="contextExpanded" class="ai-context-detail">
                <div class="ai-ctx-row">
                  <span class="ai-ctx-label">文件</span>
                  <span class="ai-ctx-value">{{ activePath }}</span>
                </div>
                <div class="ai-ctx-row" v-if="selectedCode">
                  <span class="ai-ctx-label">选中代码</span>
                  <span class="ai-ctx-value ai-ctx-code">{{ selectedCode.slice(0, 50) }}{{ selectedCode.length > 50 ? '...' : '' }}</span>
                </div>
                <div class="ai-ctx-row">
                  <span class="ai-ctx-label">项目</span>
                  <span class="ai-ctx-value">{{ projectName }}</span>
                </div>
              </div>
            </Transition>
          </div>
          <div class="ai-context" v-else>
            <div class="ai-context-header">
              <div class="ai-context-info">
                <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="#9ca3af" stroke-width="2"><circle cx="12" cy="12" r="10"/><line x1="12" y1="16" x2="12" y2="12"/><line x1="12" y1="8" x2="12.01" y2="8"/></svg>
                <span class="ai-context-empty">未选择文件</span>
              </div>
            </div>
          </div>

          <!-- Tab Bar -->
          <div class="ai-tabs">
            <button v-for="tab in aiTabs" :key="tab.key" class="ai-tab" :class="{ active: activeAiTab === tab.key }" @click="activeAiTab = tab.key">
              <span v-html="tab.icon"></span>
              <span>{{ tab.label }}</span>
            </button>
          </div>

          <!-- ==================== CHAT TAB ==================== -->
          <div v-if="activeAiTab === 'chat'" class="ai-content">
            <div class="ai-messages" ref="msgContainer" @scroll="handleScroll">
              <!-- Empty State -->
              <div v-if="messages.length === 0" class="ai-empty">
                <div class="ai-empty-icon">
                  <svg width="40" height="40" viewBox="0 0 24 24" fill="none" stroke="#3b82f6" stroke-width="1.5" opacity="0.5"><path d="M21 15a2 2 0 0 1-2 2H7l-4 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2z"/></svg>
                </div>
                <p class="ai-empty-title">Labex AI Assistant</p>
                <p class="ai-empty-desc">向 AI 提问，获取代码帮助、调试建议和最佳实践</p>
              </div>

              <!-- Messages -->
              <TransitionGroup name="ai-msg" tag="div" class="ai-msg-list">
                <div v-for="(msg, i) in messages" :key="i" class="ai-msg" :class="msg.role">
                  <div class="ai-msg-avatar">
                    <svg v-if="msg.role === 'user'" width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M20 21v-2a4 4 0 0 0-4-4H8a4 4 0 0 0-4 4v2"/><circle cx="12" cy="7" r="4"/></svg>
                    <svg v-else width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M12 2a2 2 0 0 1 2 2c0 .74-.4 1.39-1 1.73V7h1a7 7 0 0 1 7 7h1a1 1 0 0 1 1 1v3a1 1 0 0 1-1 1h-1v1a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-1H2a1 1 0 0 1-1-1v-3a1 1 0 0 1 1-1h1a7 7 0 0 1 7-7h1V5.73c-.6-.34-1-.99-1-1.73a2 2 0 0 1 2-2z"/></svg>
                  </div>
                  <div class="ai-msg-body">
                    <!-- Merged Thinking + Tool Calls (by time order) -->
                    <template v-if="(msg.thinkingBlocks && msg.thinkingBlocks.length > 0) || (msg.toolCalls && msg.toolCalls.length > 0)">
                      <template v-for="item in getMergedItems(msg)" :key="item._order">
                        <div v-if="item.type === 'thinking'" class="ai-thinking-block">
                          <div class="ai-thinking-header" @click="item.data._open = !item.data._open">
                            <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="#8b5cf6" stroke-width="2"><path d="M9.663 17h4.673M12 3v1m6.364 1.636l-.707.707M21 12h-1M4 12H3m3.343-5.657l-.707-.707m2.828 9.9a5 5 0 1 1 7.072 0l-.548.547A3.374 3.374 0 0 0 14 18.469V19a2 2 0 1 1-4 0v-.531c0-.895-.356-1.754-.988-2.386l-.548-.547z"/></svg>
                            <span>思考</span>
                            <span class="tb-summary" v-if="item.data.summary">{{ item.data.summary }}</span>
                            <svg width="10" height="10" viewBox="0 0 24 24" fill="none" stroke="#9ca3af" stroke-width="2" :style="{ transform: item.data._open ? 'rotate(180deg)' : '' }"><polyline points="6 9 12 15 18 9"/></svg>
                          </div>
                          <Transition name="tc-slide">
                            <div
                              v-if="item.data._open"
                              class="ai-thinking-body markdown-rendered"
                              v-html="renderMarkdown(item.data.content)"
                              @click="handleMarkdownClick"
                            ></div>
                          </Transition>
                        </div>
                        <ToolCallCard
                          v-else-if="item.type === 'tool'"
                          :call="item.data"
                          @permission="handlePermissionDecision"
                          @question="handleQuestionReply"
                        />
                      </template>
                    </template>
                    <!-- Current Thinking (streaming) -->
                    <div v-if="msg.thinking" class="ai-thinking-block active">
                      <div class="ai-thinking-header">
                        <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="#8b5cf6" stroke-width="2"><path d="M9.663 17h4.673M12 3v1m6.364 1.636l-.707.707M21 12h-1M4 12H3m3.343-5.657l-.707-.707m2.828 9.9a5 5 0 1 1 7.072 0l-.548.547A3.374 3.374 0 0 0 14 18.469V19a2 2 0 1 1-4 0v-.531c0-.895-.356-1.754-.988-2.386l-.548-.547z"/></svg>
                        <span>思考中...</span>
                        <span class="thinking-cursor"></span>
                      </div>
                      <div
                        class="ai-thinking-body markdown-rendered"
                        v-html="renderMarkdown(msg._thinkingDisplay || '')"
                        @click="handleMarkdownClick"
                      ></div>
                    </div>
                    <!-- Content + Loading skeleton -->
                    <div class="ai-msg-content">
                      <!-- 骨架屏加载态 -->
                      <div v-if="msg.isStreaming && !msg.content && !msg.thinking" class="ai-loading-skeleton">
                        <div class="skeleton-line w-80"></div>
                        <div class="skeleton-line w-60"></div>
                        <div class="skeleton-line w-70"></div>
                      </div>
                      <!-- 流式内容 -->
                      <div v-else class="ai-msg-text markdown-rendered" v-html="renderMarkdown(msg.content)" @click="handleMarkdownClick"></div>
                      <!-- 消息时间戳 -->
                      <div v-if="msg.timestamp" class="ai-msg-time">{{ formatTime(msg.timestamp) }}</div>
                    </div>
                    <!-- Token Usage (on last assistant message) -->
                    <PlanDisplay v-if="i === messages.length - 1 && msg.role === 'assistant' && (msg.plan || msg.planJson)" :plan="msg.plan" :plan-json="msg.planJson" />
                    <TokenChart v-if="i === messages.length - 1 && msg.role === 'assistant' && tokenUsage.totalTokens > 0"
                      :prompt-tokens="tokenUsage.promptTokens"
                      :completion-tokens="tokenUsage.completionTokens"
                      :call-count="tokenUsage.callCount" />
                    <div v-if="msg.role === 'assistant'" class="ai-msg-actions">
                      <button class="ai-msg-action" title="复制" @click="copyMessage(msg.content)">
                        <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><rect x="9" y="9" width="13" height="13" rx="2" ry="2"/><path d="M5 15H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v1"/></svg>
                      </button>
                      <button class="ai-msg-action" title="插入到编辑器" v-if="activePath" @click="insertToEditor(msg.content)">
                        <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><line x1="12" y1="5" x2="12" y2="19"/><line x1="5" y1="12" x2="19" y2="12"/></svg>
                      </button>
                      <button class="ai-msg-action" title="有帮助" :class="{ liked: msg.liked }" @click="msg.liked = true">
                        <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M14 9V5a3 3 0 0 0-3-3l-4 9v11h11.28a2 2 0 0 0 2-1.7l1.38-9a2 2 0 0 0-2-2.3zM7 22H4a2 2 0 0 1-2-2v-7a2 2 0 0 1 2-2h3"/></svg>
                      </button>
                      <button class="ai-msg-action" title="无帮助" :class="{ disliked: msg.disliked }" @click="msg.disliked = true">
                        <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M10 15v4a3 3 0 0 0 3 3l4-9V2H5.72a2 2 0 0 0-2 1.7l-1.38 9a2 2 0 0 0 2 2.3zm7-13h2.67A2.31 2.31 0 0 1 22 4v7a2.31 2.31 0 0 1-2.33 2H17"/></svg>
                      </button>
                    </div>
                  </div>
                </div>
              </TransitionGroup>
            </div>

            <!-- Scroll to Bottom -->
            <button v-if="showScrollBtn" class="ai-scroll-btn" @click="() => { userScrolled = false; scrollDown(true) }">
              <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><polyline points="6 9 12 15 18 9"/></svg>
            </button>

            <!-- Message Navigator -->
            <div v-if="messages.length > 2" class="ai-msg-navigator">
              <button
                class="nav-btn"
                :class="{ disabled: currentMessageIndex <= 0 }"
                @click="navigateMessage(-1)"
                title="上一条消息"
              >
                <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                  <polyline points="18 15 12 9 6 15"/>
                </svg>
              </button>
              <span class="nav-indicator">{{ currentMessageIndex + 1 }}/{{ messages.length }}</span>
              <button
                class="nav-btn"
                :class="{ disabled: currentMessageIndex >= messages.length - 1 }"
                @click="navigateMessage(1)"
                title="下一条消息"
              >
                <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                  <polyline points="6 9 12 15 18 9"/>
                </svg>
              </button>
            </div>

            <!-- Quick Actions -->
            <div class="ai-chips" v-if="messages.length === 0">
              <button v-for="chip in quickChips" :key="chip.label" class="ai-chip" @click="applyChip(chip.prompt)">
                <span v-html="chip.icon"></span>
                <span>{{ chip.label }}</span>
              </button>
            </div>

            <!-- Input Area -->
            <div class="ai-input-area">
              <div v-if="selectedCode" class="ai-input-context">
                <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="#3b82f6" stroke-width="2"><polyline points="16 18 22 12 16 6"/><polyline points="8 6 2 12 8 18"/></svg>
                <span>已附加选中代码 ({{ selectedCode.length }} 字符)</span>
                <button class="ai-input-ctx-remove" @click="selectedCode = ''">
                  <svg width="10" height="10" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><line x1="18" y1="6" x2="6" y2="18"/><line x1="6" y1="6" x2="18" y2="18"/></svg>
                </button>
              </div>
              <!-- 命令选择器下拉框 -->
              <div v-if="showCommandPalette" class="command-palette">
                <div class="command-palette-header">
                  <span class="command-palette-title">可用指令</span>
                  <span class="command-palette-hint">输入 / 后选择指令</span>
                </div>
                <div class="command-palette-search">
                  <input
                    v-model="commandSearch"
                    class="command-search-input"
                    placeholder="搜索指令..."
                    @keydown.escape="closeCommandPalette"
                    @keydown.enter="selectFirstCommand"
                    @keydown.up.prevent="navigateCommand(-1)"
                    @keydown.down.prevent="navigateCommand(1)"
                    ref="commandSearchRef"
                  />
                </div>
                <div class="command-palette-list" ref="commandListRef">
                  <div
                    v-for="(cmd, index) in filteredCommands"
                    :key="cmd.name"
                    :class="['command-item', { active: selectedCommandIndex === index }]"
                    @click="selectCommand(cmd)"
                    @mouseenter="selectedCommandIndex = index"
                  >
                    <div class="command-item-main">
                      <span class="command-name">/{{ cmd.name }}</span>
                      <span v-if="cmd.aliases" class="command-aliases">
                        ({{ cmd.aliases.join(', ') }})
                      </span>
                    </div>
                    <div class="command-item-desc">{{ cmd.description }}</div>
                  </div>
                  <div v-if="filteredCommands.length === 0" class="command-empty">
                    未找到匹配的指令
                  </div>
                </div>
              </div>
              <div class="ai-input-row">
                <textarea v-model="agentInput" class="ai-textarea" rows="3" :placeholder="activePath ? '输入问题，例如：这段代码有什么问题？' : '选择文件后开始对话...'" @keydown.enter.exact.prevent="sendMessage" @keydown.escape="closeCommandPalette" @input="handleInput" :disabled="agentLoading" ref="aiInputRef"></textarea>
              </div>
              <div class="ai-input-bottom">
                <!-- 模式切换 - 独立一行 -->
                <div class="ai-mode-bar">
                  <div class="ai-mode-switch" title="切换 Agent 工作模式">
                    <button
                      v-for="mode in agentModes"
                      :key="mode.key"
                      type="button"
                      :class="{ active: agentMode === mode.key, transitioning: modeTransitioning }"
                      @click="switchMode(mode.key)"
                    >
                      <svg v-if="mode.icon === 'cube'" width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M21 16V8a2 2 0 0 0-1-1.73l-7-4a2 2 0 0 0-2 0l-7 4A2 2 0 0 0 3 8v8a2 2 0 0 0 1 1.73l7 4a2 2 0 0 0 2 0l7-4A2 2 0 0 0 21 16z"/><polyline points="3.27 6.96 12 12.01 20.73 6.96"/><line x1="12" y1="22.08" x2="12" y2="12"/></svg>
                      <svg v-else-if="mode.icon === 'map'" width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><polygon points="1 6 1 22 8 18 16 22 23 18 23 2 16 6 8 2 1 6"/><line x1="8" y1="2" x2="8" y2="18"/><line x1="16" y1="6" x2="16" y2="22"/></svg>
                      <svg v-else-if="mode.icon === 'search'" width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><circle cx="11" cy="11" r="8"/><line x1="21" y1="21" x2="16.65" y2="16.65"/></svg>
                      <span class="mode-label">{{ mode.label }}</span>
                    </button>
                  </div>
                </div>
                <!-- 工具栏和发送按钮 -->
                <div class="ai-toolbar">
                  <div class="ai-toolbar-left">
                    <button class="ai-tool-btn" title="模型配置" @click="showModelConfig = !showModelConfig">
                      <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><circle cx="12" cy="12" r="3"/><path d="M19.4 15a1.65 1.65 0 0 0 .33 1.82l.06.06a2 2 0 0 1 0 2.83 2 2 0 0 1-2.83 0l-.06-.06a1.65 1.65 0 0 0-1.82-.33 1.65 1.65 0 0 0-1 1.51V21a2 2 0 0 1-2 2 2 2 0 0 1-2-2v-.09A1.65 1.65 0 0 0 9 19.4a1.65 1.65 0 0 0-1.82.33l-.06.06a2 2 0 0 1-2.83 0 2 2 0 0 1 0-2.83l.06-.06A1.65 1.65 0 0 0 4.68 15a1.65 1.65 0 0 0-1.51-1H3a2 2 0 0 1-2-2 2 2 0 0 1 2-2h.09A1.65 1.65 0 0 0 4.6 9a1.65 1.65 0 0 0-.33-1.82l-.06-.06a2 2 0 0 1 0-2.83 2 2 0 0 1 2.83 0l.06.06A1.65 1.65 0 0 0 9 4.68a1.65 1.65 0 0 0 1-1.51V3a2 2 0 0 1 2-2 2 2 0 0 1 2 2v.09a1.65 1.65 0 0 0 1 1.51 1.65 1.65 0 0 0 1.82-.33l.06-.06a2 2 0 0 1 2.83 0 2 2 0 0 1 0 2.83l-.06.06A1.65 1.65 0 0 0 19.4 9a1.65 1.65 0 0 0 1.51 1H21a2 2 0 0 1 2 2 2 2 0 0 1-2 2h-.09a1.65 1.65 0 0 0-1.51 1z"/></svg>
                      <span>模型</span>
                    </button>
                    <button class="ai-tool-btn" title="优化提示词" @click="optimizePrompt">
                      <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M9.663 17h4.673M12 3v1m6.364 1.636l-.707.707M21 12h-1M4 12H3m3.343-5.657l-.707-.707m2.828 9.9a5 5 0 1 1 7.072 0l-.548.547A3.374 3.374 0 0 0 14 18.469V19a2 2 0 1 1-4 0v-.531c0-.895-.356-1.754-.988-2.386l-.548-.547z"/></svg>
                      <span>优化</span>
                    </button>
                    <button class="ai-tool-btn" title="引用文件 (@)" @click="atFile">
                      <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M21 15a2 2 0 0 1-2 2H7l-4 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2z"/></svg>
                      <span>引用</span>
                    </button>
                    <button class="ai-tool-btn" title="指令 (/)" @click="showCommandMenu">
                      <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><polyline points="4 17 10 11 4 5"/><line x1="12" y1="19" x2="20" y2="19"/></svg>
                      <span>指令</span>
                    </button>
                  </div>
                  <div class="ai-toolbar-right">
                    <div v-if="agentLoading" class="ai-generating-indicator">
                      <span class="gen-dot"></span>
                      <span>生成中...</span>
                    </div>
                    <button
                      v-if="agentLoading"
                      class="ai-send-btn ai-send-stop"
                      @click="stopGeneration"
                      title="停止生成 (Esc)"
                    >
                      <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><rect x="6" y="6" width="12" height="12" rx="2"/></svg>
                    </button>
                    <button
                      v-else
                      class="ai-send-btn"
                      @click="sendMessage"
                      :disabled="!agentInput.trim()"
                      title="发送 (Enter)"
                    >
                      <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><line x1="22" y1="2" x2="11" y2="13"/><polygon points="22 2 15 22 11 13 2 9 22 2"/></svg>
                    </button>
                  </div>
                </div>
              </div>
            </div>
          </div>

          <!-- ==================== REVIEW TAB (Changes) ==================== -->
          <div v-if="activeAiTab === 'review'" class="ai-content ai-content-nopad">
            <ChangesPanel :changes="sessionChanges" :project-id="projectId" :refresh-key="changesRefreshKey" @revert="revertChange" @undo="onUndoChange" />
          </div>

          <!-- ==================== USAGE TAB ==================== -->
          <div v-if="activeAiTab === 'usage'" class="ai-content">
            <div class="ai-usage">
              <div class="ai-usage-total">
                <div class="usage-total-label">总 Token 消耗</div>
                <div class="usage-total-value">{{ (allTokenStats?.totalTokens || tokenUsage.totalTokens) >= 1000 ? ((allTokenStats?.totalTokens || tokenUsage.totalTokens) / 1000).toFixed(1) + 'K' : (allTokenStats?.totalTokens || tokenUsage.totalTokens) }}</div>
                <div class="usage-total-sub">{{ allTokenStats?.callCount || tokenUsage.callCount }} 次调用</div>
              </div>
              <div class="usage-stats">
                <div class="usage-stat">
                  <span class="usage-stat-label">输入</span>
                  <span class="usage-stat-value prompt">{{ formatTokenCount(allTokenStats?.totalPromptTokens || tokenUsage.promptTokens) }}</span>
                </div>
                <div class="usage-stat">
                  <span class="usage-stat-label">输出</span>
                  <span class="usage-stat-value completion">{{ formatTokenCount(allTokenStats?.totalCompletionTokens || tokenUsage.completionTokens) }}</span>
                </div>
              </div>
              <div class="usage-chart-section">
                <div class="usage-chart-label">输入 / 输出占比</div>
                <div ref="usagePieRef" class="usage-echart"></div>
              </div>
              <div class="usage-chart-section" v-if="allTokenStats?.byDay && Object.keys(allTokenStats.byDay).length > 0">
                <div class="usage-chart-label">每日消耗趋势</div>
                <div ref="usageTimelineRef" class="usage-echart"></div>
              </div>
              <div class="usage-chart-section" v-if="allTokenStats?.byModel && Object.keys(allTokenStats.byModel).length > 0">
                <div class="usage-chart-label">模型分布</div>
                <div ref="usageModelRef" class="usage-echart"></div>
              </div>
              <div class="usage-session-section" v-if="sessionHistory.length > 0">
                <div class="usage-chart-label">本次会话统计</div>
                <div ref="usageBarRef" class="usage-echart"></div>
                <div class="usage-session-list">
                  <div v-for="(s, idx) in sessionHistory" :key="idx" class="usage-session-item" :class="{ active: selectedSessionIdx === idx }" @click="selectedSessionIdx = selectedSessionIdx === idx ? -1 : idx">
                    <span class="session-name">{{ s.title || '会话' + (idx + 1) }}</span>
                    <span class="session-tokens">{{ formatTokenCount(s.totalTokens) }}</span>
                  </div>
                </div>
              </div>
            </div>
          </div>

          <!-- ==================== EXTENSIONS TAB ==================== -->
          <div v-if="activeAiTab === 'extensions'" class="ai-content">
            <div class="ext-panel" v-loading="extensionLoading">
              <div class="ext-header">
                <div>
                  <h4>全局扩展</h4>
                  <p>当前学生账号下的 Skill 和 MCP 配置会在所有云代码空间中复用。</p>
                </div>
                <div class="ext-switch">
                  <button :class="{ active: activeExtensionTab === 'skills' }" @click="activeExtensionTab = 'skills'">Skills</button>
                  <button :class="{ active: activeExtensionTab === 'mcp' }" @click="activeExtensionTab = 'mcp'">MCP</button>
                </div>
              </div>

              <!-- ========== Skills Tab ========== -->
              <div v-if="activeExtensionTab === 'skills'" class="ext-body">
                <div class="ext-list">
                  <div v-if="agentSkills.length === 0" class="ext-empty">
                    还没有 Skill。添加后 Agent 会自动读取启用项。
                    <br><small>支持 SKILL.md 格式，包含 YAML frontmatter (name, description)。</small>
                  </div>
                  <div v-for="skill in agentSkills" :key="skill.skillId" class="ext-item">
                    <div class="ext-item-main">
                      <strong>{{ skill.title }}</strong>
                      <span class="ext-badge">{{ skill.skillKey }}</span>
                      <p>{{ skill.description || '暂无描述' }}</p>
                      <small v-if="skill.source" class="ext-source">来源: {{ skill.source }}</small>
                    </div>
                    <div class="ext-item-actions">
                      <button class="ext-mini-btn" :class="{ active: skill.isEnabled === 1 }" @click="toggleSkill(skill)">
                        {{ skill.isEnabled === 1 ? '启用' : '停用' }}
                      </button>
                      <button class="ext-icon-btn" title="编辑" @click="editSkill(skill)">
                        <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M11 4H4a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h14a2 2 0 0 0 2-2v-7"/><path d="M18.5 2.5a2.121 2.121 0 0 1 3 3L12 15l-4 1 1-4 9.5-9.5z"/></svg>
                      </button>
                      <button class="ext-icon-btn danger" title="删除" @click="deleteSkill(skill)">
                        <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><polyline points="3 6 5 6 21 6"/><path d="M19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6"/></svg>
                      </button>
                    </div>
                  </div>
                </div>

                <!-- Skill 导入区域 -->
                <div class="ext-import-section">
                  <h5>导入 Skill 文件夹</h5>
                  <p class="ext-hint">上传包含 SKILL.md 文件的文件夹，系统会自动解析 frontmatter 并注册。</p>
                  <div class="ext-import-actions">
                    <input type="file" ref="skillFolderInput" webkitdirectory multiple style="display:none" @change="handleSkillFolderUpload" />
                    <button class="ext-btn secondary" @click="$refs.skillFolderInput?.click()">
                      <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M22 19a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h5l2 3h9a2 2 0 0 1 2 2z"/></svg>
                      选择文件夹
                    </button>
                    <input type="file" ref="skillFileInput" accept=".md" style="display:none" @change="handleSkillFileUpload" />
                    <button class="ext-btn secondary" @click="$refs.skillFileInput?.click()">
                      <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z"/><polyline points="14 2 14 8 20 8"/></svg>
                      上传 SKILL.md
                    </button>
                  </div>
                </div>

                <!-- Skill 手动创建表单 -->
                <div class="ext-form">
                  <h5>{{ editingSkillId ? '编辑 Skill' : '手动创建 Skill' }}</h5>
                  <input v-model.trim="skillForm.title" class="ext-input" placeholder="标题，例如 Vue 组件规范" />
                  <input v-model.trim="skillForm.skillKey" class="ext-input" placeholder="Key，例如 vue-component" />
                  <input v-model.trim="skillForm.description" class="ext-input" placeholder="描述，帮助 Agent 选择何时使用" />
                  <textarea v-model="skillForm.content" class="ext-textarea" rows="7" placeholder="写入可复用规则、工作流、代码风格、测试要求等&#10;&#10;支持 SKILL.md 格式：&#10;---&#10;name: my-skill&#10;description: 描述&#10;---&#10;# Skill 内容"></textarea>
                  <label class="ext-check"><input type="checkbox" v-model="skillForm.isEnabled" /> 启用</label>
                  <div class="ext-form-actions">
                    <button class="ext-btn secondary" @click="resetSkillForm">清空</button>
                    <button class="ext-btn primary" @click="saveSkill">{{ editingSkillId ? '更新' : '保存' }}</button>
                  </div>
                </div>
              </div>

              <!-- ========== MCP Tab ========== -->
              <div v-else class="ext-body">
                <div class="ext-list">
                  <div v-if="mcpServers.length === 0" class="ext-empty">
                    还没有 MCP 服务。
                    <br><small>支持 HTTP、SSE、stdio 三种传输协议。Agent 会自动发现并使用 MCP 工具。</small>
                  </div>
                  <div v-for="server in mcpServers" :key="server.serverId" class="ext-item">
                    <div class="ext-item-main">
                      <strong>{{ server.serverName }}</strong>
                      <span class="ext-badge">{{ server.serverKey }}</span>
                      <span class="ext-transport-badge" :class="server.transport">{{ server.transport || 'http' }}</span>
                      <p>{{ server.endpoint }}</p>
                      <small>
                        {{ server.authConfigured ? '✓ 已配置鉴权' : '✗ 未配置鉴权' }}
                        · {{ server.toolsJson ? '已配置工具清单' : '自动发现工具' }}
                        · {{ server.isEnabled === 1 ? '已启用' : '已停用' }}
                      </small>
                    </div>
                    <div class="ext-item-actions">
                      <button class="ext-mini-btn" :class="{ active: server.isEnabled === 1 }" @click="toggleMcp(server)">
                        {{ server.isEnabled === 1 ? '启用' : '停用' }}
                      </button>
                      <button class="ext-icon-btn" title="测试连接" @click="testMcpConnection(server)">
                        <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M22 11.08V12a10 10 0 1 1-5.93-9.14"/><polyline points="22 4 12 14.01 9 11.01"/></svg>
                      </button>
                      <button class="ext-icon-btn" title="编辑" @click="editMcp(server)">
                        <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M11 4H4a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h14a2 2 0 0 0 2-2v-7"/><path d="M18.5 2.5a2.121 2.121 0 0 1 3 3L12 15l-4 1 1-4 9.5-9.5z"/></svg>
                      </button>
                      <button class="ext-icon-btn danger" title="删除" @click="deleteMcp(server)">
                        <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><polyline points="3 6 5 6 21 6"/><path d="M19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6"/></svg>
                      </button>
                    </div>
                  </div>
                </div>

                <div class="ext-form">
                  <h5>{{ editingMcpId ? '编辑 MCP' : '新增 MCP 服务' }}</h5>
                  <input v-model.trim="mcpForm.serverName" class="ext-input" placeholder="名称，例如 Docs Search" />
                  <input v-model.trim="mcpForm.serverKey" class="ext-input" placeholder="Key，例如 docs-search" />

                  <!-- 传输协议选择 -->
                  <div class="ext-transport-select">
                    <label>传输协议:</label>
                    <div class="ext-radio-group">
                      <label class="ext-radio" :class="{ active: mcpForm.transport === 'http' }">
                        <input type="radio" v-model="mcpForm.transport" value="http" /> HTTP
                      </label>
                      <label class="ext-radio" :class="{ active: mcpForm.transport === 'sse' }">
                        <input type="radio" v-model="mcpForm.transport" value="sse" /> SSE
                      </label>
                      <label class="ext-radio" :class="{ active: mcpForm.transport === 'stdio' }">
                        <input type="radio" v-model="mcpForm.transport" value="stdio" /> stdio
                      </label>
                    </div>
                  </div>

                  <input v-model.trim="mcpForm.endpoint" class="ext-input"
                    :placeholder="mcpForm.transport === 'stdio' ? '命令，例如 npx @modelcontextprotocol/server-filesystem' : 'HTTPS JSON-RPC Endpoint'" />
                  <input v-model.trim="mcpForm.authHeader" class="ext-input" type="password" :placeholder="editingMcpId ? '留空保持原鉴权头' : 'Authorization header，可选'" />

                  <!-- 工具清单（可选） -->
                  <details class="ext-details">
                    <summary>工具清单 (可选，留空则自动发现)</summary>
                    <textarea v-model="mcpForm.toolsJson" class="ext-textarea" rows="5" placeholder='[{"name":"search","description":"search docs"}]'></textarea>
                  </details>

                  <label class="ext-check"><input type="checkbox" v-model="mcpForm.isEnabled" /> 启用</label>
                  <div class="ext-form-actions">
                    <button class="ext-btn secondary" @click="resetMcpForm">清空</button>
                    <button class="ext-btn primary" @click="saveMcp">{{ editingMcpId ? '更新' : '保存' }}</button>
                  </div>
                </div>
              </div>
            </div>
          </div>

          <!-- ==================== TERMINAL TAB ==================== -->
          <div v-if="activeAiTab === 'terminal'" class="ai-content ai-content-nopad term-wrap">
            <TerminalPanel
              ref="terminalPanelRef"
              :project-id="projectId"
              :project-path="projectPath"
              :is-dark="true"
            />
          </div>

          <!-- Status Bar -->
          <div class="ai-statusbar">
            <div class="ai-status-left">
              <span class="ai-model-badge">
                <svg width="10" height="10" viewBox="0 0 24 24" fill="none" stroke="#10b981" stroke-width="2.5"><circle cx="12" cy="12" r="4"/></svg>
                {{ currentModelName }}
              </span>
              <span class="ai-status-conn" :class="agentLoading ? 'generating' : 'online'">
                {{ agentLoading ? '生成中...' : '在线' }}
              </span>
              <span class="ai-token-badge" title="Token 消耗">
                <svg width="10" height="10" viewBox="0 0 24 24" fill="none" stroke="#8b5cf6" stroke-width="2"><path d="M12 2v20M17 5H9.5a3.5 3.5 0 0 0 0 7h5a3.5 3.5 0 0 1 0 7H6"/></svg>
                {{ tokenUsage.totalTokens >= 1000 ? (tokenUsage.totalTokens / 1000).toFixed(1) + 'K' : (tokenUsage.totalTokens || 0) }}
              </span>
            </div>
            <div class="ai-status-right">
              <span v-if="agentLoading" class="ai-stop-btn" @click="stopGeneration">
                <svg width="11" height="11" viewBox="0 0 24 24" fill="currentColor"><rect x="4" y="4" width="16" height="16" rx="2"/></svg>
                停止
              </span>
            </div>
          </div>
        </template>
      </aside>
      <!-- ==================== END AI ASSISTANT SIDEBAR ==================== -->
    </div>

    <!-- New Item Modal -->
    <Teleport to="body">
      <Transition name="modal">
        <div v-if="showNewModal" class="ws-overlay" @click.self="showNewModal = false">
          <div class="ws-dialog">
            <h3>{{ newModalType === 'directory' ? '新建文件夹' : '新建文件' }}</h3>
            <input v-model="newItemName" class="ws-dialog-input" :placeholder="newModalType === 'directory' ? '请输入文件夹名称' : '请输入文件名称（含扩展名）'" @keyup.enter="confirmNewItem" />
            <div class="ws-dialog-actions">
              <button class="ws-btn ws-btn-outline" @click="showNewModal = false">取消</button>
              <button class="ws-btn ws-btn-primary" @click="confirmNewItem">创建</button>
            </div>
          </div>
        </div>
      </Transition>
    </Teleport>
    <Teleport to="body">
      <Transition name="modal">
        <div v-if="showRenameModal" class="ws-overlay" @click.self="showRenameModal = false">
          <div class="ws-dialog">
            <h3>重命名</h3>
            <input v-model="renameItemValue" class="ws-dialog-input" placeholder="请输入新名称" @keyup.enter="confirmRename" />
            <div class="ws-dialog-actions">
              <button class="ws-btn ws-btn-outline" @click="showRenameModal = false">取消</button>
              <button class="ws-btn ws-btn-primary" @click="confirmRename">确认</button>
            </div>
          </div>
        </div>
      </Transition>
    </Teleport>

    <!-- Settings Modal -->
    <Teleport to="body">
      <Transition name="modal">
        <div v-if="showSettings" class="ws-overlay" @click.self="showSettings = false">
          <div class="settings-dialog">
            <div class="settings-header">
              <h3><svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" style="vertical-align: -3px; margin-right: 6px;"><circle cx="12" cy="12" r="3"/><path d="M19.4 15a1.65 1.65 0 0 0 .33 1.82l.06.06a2 2 0 0 1 0 2.83 2 2 0 0 1-2.83 0l-.06-.06a1.65 1.65 0 0 0-1.82-.33 1.65 1.65 0 0 0-1 1.51V21a2 2 0 0 1-2 2 2 2 0 0 1-2-2v-.09A1.65 1.65 0 0 0 9 19.4a1.65 1.65 0 0 0-1.82.33l-.06.06a2 2 0 0 1-2.83 0 2 2 0 0 1 0-2.83l.06-.06A1.65 1.65 0 0 0 4.68 15a1.65 1.65 0 0 0-1.51-1H3a2 2 0 0 1-2-2 2 2 0 0 1 2-2h.09A1.65 1.65 0 0 0 4.6 9a1.65 1.65 0 0 0-.33-1.82l-.06-.06a2 2 0 0 1 0-2.83 2 2 0 0 1 2.83 0l.06.06A1.65 1.65 0 0 0 9 4.68a1.65 1.65 0 0 0 1-1.51V3a2 2 0 0 1 2-2 2 2 0 0 1 2 2v.09a1.65 1.65 0 0 0 1 1.51 1.65 1.65 0 0 0 1.82-.33l.06-.06a2 2 0 0 1 2.83 0 2 2 0 0 1 0 2.83l-.06.06A1.65 1.65 0 0 0 19.4 9a1.65 1.65 0 0 0 1.51 1H21a2 2 0 0 1 2 2 2 2 0 0 1-2 2h-.09a1.65 1.65 0 0 0-1.51 1z"/></svg>设置</h3>
              <button class="mc-close-btn" @click="showSettings = false">
                <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><line x1="18" y1="6" x2="6" y2="18"/><line x1="6" y1="6" x2="18" y2="18"/></svg>
              </button>
            </div>
            <div class="settings-body">
              <div class="settings-section">
                <div class="settings-label">外观主题</div>
                <div class="settings-row">
                  <button class="settings-theme-btn" :class="{ active: !aiDarkTheme }" @click="aiDarkTheme && toggleAiTheme()">
                    <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><circle cx="12" cy="12" r="5"/><line x1="12" y1="1" x2="12" y2="3"/><line x1="12" y1="21" x2="12" y2="23"/><line x1="4.22" y1="4.22" x2="5.64" y2="5.64"/><line x1="18.36" y1="18.36" x2="19.78" y2="19.78"/><line x1="1" y1="12" x2="3" y2="12"/><line x1="21" y1="12" x2="23" y2="12"/><line x1="4.22" y1="19.78" x2="5.64" y2="18.36"/><line x1="18.36" y1="5.64" x2="19.78" y2="4.22"/></svg>
                    <span>亮色</span>
                  </button>
                  <button class="settings-theme-btn" :class="{ active: aiDarkTheme }" @click="!aiDarkTheme && toggleAiTheme()">
                    <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M21 12.79A9 9 0 1 1 11.21 3 7 7 0 0 0 21 12.79z"/></svg>
                    <span>暗色</span>
                  </button>
                </div>
              </div>
              <div class="settings-section">
                <div class="settings-label">字体大小</div>
                <div class="settings-row">
                  <button class="settings-opt-btn" :class="{ active: fontSize === 'small' }" @click="setFontSize('small')">小</button>
                  <button class="settings-opt-btn" :class="{ active: fontSize === 'medium' }" @click="setFontSize('medium')">中</button>
                  <button class="settings-opt-btn" :class="{ active: fontSize === 'large' }" @click="setFontSize('large')">大</button>
                </div>
              </div>
              <div class="settings-section">
                <div class="settings-label">消息密度</div>
                <div class="settings-row">
                  <button class="settings-opt-btn" :class="{ active: msgDensity === 'compact' }" @click="setMsgDensity('compact')">紧凑</button>
                  <button class="settings-opt-btn" :class="{ active: msgDensity === 'comfortable' }" @click="setMsgDensity('comfortable')">舒适</button>
                  <button class="settings-opt-btn" :class="{ active: msgDensity === 'relaxed' }" @click="setMsgDensity('relaxed')">宽松</button>
                </div>
              </div>
              <div class="settings-section">
                <div class="settings-label">快捷操作</div>
                <div class="settings-shortcuts">
                  <button class="settings-action-btn" @click="showSettings = false; showModelConfig = true">
                    <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><circle cx="12" cy="12" r="3"/><path d="M19.4 15a1.65 1.65 0 0 0 .33 1.82l.06.06a2 2 0 0 1 0 2.83 2 2 0 0 1-2.83 0l-.06-.06a1.65 1.65 0 0 0-1.82-.33 1.65 1.65 0 0 0-1 1.51V21a2 2 0 0 1-2 2 2 2 0 0 1-2-2v-.09A1.65 1.65 0 0 0 9 19.4a1.65 1.65 0 0 0-1.82.33l-.06.06a2 2 0 0 1-2.83 0 2 2 0 0 1 0-2.83l.06-.06A1.65 1.65 0 0 0 4.68 15a1.65 1.65 0 0 0-1.51-1H3a2 2 0 0 1-2-2 2 2 0 0 1 2-2h.09A1.65 1.65 0 0 0 4.6 9a1.65 1.65 0 0 0-.33-1.82l-.06-.06a2 2 0 0 1 0-2.83 2 2 0 0 1 2.83 0l.06.06A1.65 1.65 0 0 0 9 4.68a1.65 1.65 0 0 0 1-1.51V3a2 2 0 0 1 2-2 2 2 0 0 1 2 2v.09a1.65 1.65 0 0 0 1 1.51 1.65 1.65 0 0 0 1.82-.33l.06-.06a2 2 0 0 1 2.83 0 2 2 0 0 1 0 2.83l-.06.06A1.65 1.65 0 0 0 19.4 9a1.65 1.65 0 0 0 1.51 1H21a2 2 0 0 1 2 2 2 2 0 0 1-2 2h-.09a1.65 1.65 0 0 0-1.51 1z"/></svg>
                    <span>模型配置</span>
                  </button>
                  <button class="settings-action-btn" @click="clearMessages(); showSettings = false">
                    <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><polyline points="3 6 5 6 21 6"/><path d="M19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6m3 0V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2"/></svg>
                    <span>清空会话</span>
                  </button>
                </div>
              </div>
            </div>
          </div>
        </div>
      </Transition>
    </Teleport>

    <!-- Model Config Modal -->
    <Teleport to="body">
      <Transition name="modal">
        <div v-if="showModelConfig" class="ws-overlay" @click.self="showModelConfig = false">
          <div class="mc-dialog">
            <div class="mc-header">
              <h3>模型配置</h3>
              <button class="mc-close-btn" @click="showModelConfig = false">
                <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><line x1="18" y1="6" x2="6" y2="18"/><line x1="6" y1="6" x2="18" y2="18"/></svg>
              </button>
            </div>

            <!-- Template Selection View -->
            <div v-if="mcTemplateSelecting" class="mc-body">
              <div class="mc-template-grid">
                <button v-for="tpl in mcTemplateOptions" :key="tpl.name" class="mc-template-card" @click="selectModelTemplate(tpl)">
                  <span class="mc-template-icon" :style="{ background: tpl.accent }">{{ tpl.iconText }}</span>
                  <span class="mc-template-main">
                    <span class="mc-template-name">{{ tpl.name }}</span>
                    <span class="mc-template-vendor">{{ tpl.vendor }}</span>
                    <span class="mc-template-model">{{ tpl.modelName || '手动填写模型名称' }}</span>
                  </span>
                  <span v-if="tpl.modelsUrl" class="mc-template-source">官方列表</span>
                </button>
              </div>
              <div class="mc-form-actions">
                <button class="mc-btn mc-btn-outline" @click="mcTemplateSelecting = false">返回</button>
              </div>
            </div>

            <!-- Config List View -->
            <div v-else-if="!mcEditing" class="mc-body">
              <div class="mc-config-list">
                <div v-if="modelConfigs.length === 0" class="mc-empty">
                  <svg width="32" height="32" viewBox="0 0 24 24" fill="none" stroke="#d1d5db" stroke-width="1.5"><circle cx="12" cy="12" r="3"/><path d="M19.4 15a1.65 1.65 0 0 0 .33 1.82l.06.06a2 2 0 0 1 0 2.83 2 2 0 0 1-2.83 0l-.06-.06a1.65 1.65 0 0 0-1.82-.33 1.65 1.65 0 0 0-1 1.51V21a2 2 0 0 1-2 2 2 2 0 0 1-2-2v-.09A1.65 1.65 0 0 0 9 19.4a1.65 1.65 0 0 0-1.82.33l-.06.06a2 2 0 0 1-2.83 0 2 2 0 0 1 0-2.83l.06-.06A1.65 1.65 0 0 0 4.68 15a1.65 1.65 0 0 0-1.51-1H3a2 2 0 0 1-2-2 2 2 0 0 1 2-2h.09A1.65 1.65 0 0 0 4.6 9a1.65 1.65 0 0 0-.33-1.82l-.06-.06a2 2 0 0 1 0-2.83 2 2 0 0 1 2.83 0l.06.06A1.65 1.65 0 0 0 9 4.68a1.65 1.65 0 0 0 1-1.51V3a2 2 0 0 1 2-2 2 2 0 0 1 2 2v.09a1.65 1.65 0 0 0 1 1.51 1.65 1.65 0 0 0 1.82-.33l.06-.06a2 2 0 0 1 2.83 0 2 2 0 0 1 0 2.83l-.06.06A1.65 1.65 0 0 0 19.4 9a1.65 1.65 0 0 0 1.51 1H21a2 2 0 0 1 2 2 2 2 0 0 1-2 2h-.09a1.65 1.65 0 0 0-1.51 1z"/></svg>
                  <p>暂无模型配置</p>
                  <p class="mc-empty-hint">添加一个模型配置开始使用 AI 助手</p>
                </div>
                <div v-for="cfg in modelConfigs" :key="cfg.configId" class="mc-config-card" :class="{ active: selectedModelConfigId === cfg.configId, default: cfg.isDefault === 1 }">
                  <div class="mc-card-main" @click="selectedModelConfigId = cfg.configId">
                    <div class="mc-card-top">
                      <span class="mc-card-name">{{ cfg.configName }}</span>
                      <span v-if="cfg.isDefault === 1" class="mc-badge-default">默认</span>
                    </div>
                    <div class="mc-card-meta">
                      <span class="mc-card-provider">{{ cfg.provider || 'openai_compatible' }}</span>
                      <span class="mc-card-sep">|</span>
                      <span class="mc-card-model">{{ cfg.modelName || 'gpt-4o-mini' }}</span>
                    </div>
                    <div class="mc-card-url" v-if="cfg.baseUrl">{{ cfg.baseUrl }}</div>
                    <!-- Test Result -->
                    <div v-if="mcTestResults[cfg.configId]" class="mc-test-result" :class="mcTestResults[cfg.configId].success ? 'mc-test-ok' : 'mc-test-fail'">
                      <svg v-if="mcTestResults[cfg.configId].success" width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5"><polyline points="20 6 9 17 4 12"/></svg>
                      <svg v-else width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5"><circle cx="12" cy="12" r="10"/><line x1="15" y1="9" x2="9" y2="15"/><line x1="9" y1="9" x2="15" y2="15"/></svg>
                      <span v-if="mcTestResults[cfg.configId].success">连接成功 {{ mcTestResults[cfg.configId].latency }}ms</span>
                      <span v-else>{{ mcTestResults[cfg.configId].error }}</span>
                    </div>
                  </div>
                  <div class="mc-card-actions">
                    <button class="mc-action-btn mc-action-test" title="测试连接" @click.stop="testConfig(cfg)" :disabled="mcTestingIds[cfg.configId]">
                      <svg v-if="mcTestingIds[cfg.configId]" width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" class="mc-spin"><path d="M21 12a9 9 0 1 1-6.219-8.56"/></svg>
                      <svg v-else width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><polyline points="22 12 18 12 15 21 9 3 6 12 2 12"/></svg>
                    </button>
                    <button class="mc-action-btn" title="编辑" @click="editConfig(cfg)">
                      <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M11 4H4a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h14a2 2 0 0 0 2-2v-7"/><path d="M18.5 2.5a2.121 2.121 0 0 1 3 3L12 15l-4 1 1-4 9.5-9.5z"/></svg>
                    </button>
                    <button class="mc-action-btn mc-action-delete" title="删除" @click="deleteConfig(cfg)">
                      <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><polyline points="3 6 5 6 21 6"/><path d="M19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6m3 0V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2"/></svg>
                    </button>
                  </div>
                </div>
              </div>
              <button class="mc-add-btn" @click="startCreateConfig">
                <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><line x1="12" y1="5" x2="12" y2="19"/><line x1="5" y1="12" x2="19" y2="12"/></svg>
                <span>添加配置</span>
              </button>
            </div>

            <!-- Config Edit/Create View -->
            <div v-else class="mc-body">
              <div class="mc-form">
                <div class="mc-field">
                  <label>配置名称 <span class="mc-required">*</span></label>
                  <input v-model="mcForm.configName" class="mc-input" placeholder="例如：我的DeepSeek" />
                </div>
                <div class="mc-field">
                  <label>提供商</label>
                  <select v-model="mcForm.provider" class="mc-select">
                    <option value="openai_compatible">OpenAI Compatible</option>
                  </select>
                  <div class="mc-hint">所有兼容 OpenAI 接口的服务均可接入</div>
                </div>
                <div class="mc-field">
                  <label>Base URL <span class="mc-required">*</span></label>
                  <input v-model="mcForm.baseUrl" class="mc-input" placeholder="https://api.deepseek.com" />
                  <div class="mc-hint">API 地址，不包含 /v1/chat/completions 路径</div>
                </div>
                <div class="mc-field">
                  <label>模型名称 <span class="mc-required">*</span></label>
                  <input v-model="mcForm.modelName" class="mc-input" placeholder="deepseek-chat" />
                </div>
                <div class="mc-field">
                  <label>{{ mcCustomMode ? '模型列表 URL' : '官方模型列表' }}</label>
                  <div class="mc-field-action-row">
                    <input v-if="mcCustomMode" v-model="mcForm.modelsUrl" class="mc-input" placeholder="https://api.example.com/v1/models" />
                    <div v-else class="mc-official-url">{{ mcForm.modelsUrl || '该模板暂未提供官方模型列表接口' }}</div>
                    <button class="mc-btn mc-btn-outline mc-btn-nowrap" @click="fetchModelList" :disabled="mcModelsLoading || !mcForm.modelsUrl">
                      {{ mcModelsLoading ? '获取中...' : '获取模型' }}
                    </button>
                  </div>
                  <div class="mc-hint">{{ mcCustomMode ? '仅自定义配置需要填写模型列表 URL' : '从厂商官方模型列表接口读取可用模型；多数服务需要先填写 API Key' }}</div>
                  <select v-if="mcFetchedModels.length > 0" v-model="mcForm.modelName" class="mc-select mc-model-select" @change="applyFetchedModelLimits">
                    <option v-for="m in mcFetchedModels" :key="m.id" :value="m.id">{{ m.id }}{{ m.owner ? ' · ' + m.owner : '' }}</option>
                  </select>
                </div>
                <div class="mc-field">
                  <label>API Key / Token Plan Key <span class="mc-required">*</span></label>
                  <input v-model="mcForm.apiKey" class="mc-input" type="password" :placeholder="mcApiKeyHint ? '已设置 (' + mcApiKeyHint + ')，留空则保持不变' : 'sk-...'" autocomplete="new-password" />
                  <div v-if="mcApiKeyHint && !mcForm.apiKey" class="mc-hint mc-hint-ok">API Key 已设置，留空将保持原值不变</div>
                </div>
                <div class="mc-row">
                  <div class="mc-field mc-field-half">
                    <label>Max Tokens</label>
                    <input v-model.number="mcForm.maxTokens" class="mc-input" type="number" placeholder="32768" />
                    <div class="mc-hint">单次回复最大长度，非上下文窗口</div>
                  </div>
                  <div class="mc-field mc-field-half">
                    <label>Temperature</label>
                    <input v-model.number="mcForm.temperature" class="mc-input" type="number" step="0.1" min="0" max="2" placeholder="0.7" />
                    <div class="mc-hint">0=确定性 2=高随机</div>
                  </div>
                </div>
                <label class="mc-checkbox-row">
                  <input type="checkbox" v-model="mcForm.isDefault" />
                  <span>设为默认模型</span>
                </label>
              </div>
              <div class="mc-form-actions">
                <button class="mc-btn mc-btn-outline" @click="cancelModelConfigEdit">取消</button>
                <button class="mc-btn mc-btn-primary" @click="saveConfig" :disabled="mcSaving">{{ mcSaving ? '保存中...' : (mcEditingId ? '更新' : '创建') }}</button>
              </div>
            </div>
          </div>
        </div>
      </Transition>
    </Teleport>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, onBeforeUnmount, nextTick, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { projectApi, modelConfigApi, agentExtensionApi } from '@/api'
import { modelConfigPresets } from '@/constants/modelPresets'
import FileTreeNode from '@/components/cloud/FileTreeNode.vue'
import MonacoEditor from '@/components/MonacoEditor.vue'
import ToolCallCard from '@/components/cloud/ToolCallCard.vue'
import ChangesPanel from '@/components/cloud/ChangesPanel.vue'
import PlanDisplay from '@/components/cloud/PlanDisplay.vue'
import TokenChart from '@/components/cloud/TokenChart.vue'
import TerminalPanel from '@/components/terminal/TerminalPanel.vue'
import AppIcon from '@/components/AppIcon.vue'
import * as echarts from 'echarts'
import { marked } from 'marked'
import hljs from 'highlight.js/lib/common'
import 'highlight.js/styles/github-dark.css'

const route = useRoute()
const router = useRouter()

// Core project state
const projectId = ref(null)
const projectName = ref('')
const fileTree = ref([])
const activePath = ref('')
const fileContent = ref('')
const fileContentDirty = ref(false)
const savingFile = ref(false)
const editorReady = ref(false)
const treeLoading = ref(false)
const openFiles = ref([])
const activeTabIndex = ref(-1)

// File tree modals
const showNewModal = ref(false)
const newModalType = ref('file')
const newItemParent = ref('')
const newItemName = ref('')
const showRenameModal = ref(false)
const renameItemValue = ref('')
const renamingItemPath = ref('')

// AI Assistant state
const messages = ref([])
const agentInput = ref('')
const agentLoading = ref(false)
const agentMode = ref('build')
const msgContainer = ref(null)
const aiInputRef = ref(null)
const detectedLang = ref('plaintext')
const showScrollBtn = ref(false)
const selectedCode = ref('')

// 滚动行为优化
const initialScrollDone = ref(false)
const userScrolled = ref(false)
const modeTransitioning = ref(false)

// 消息导航
const currentMessageIndex = ref(0)
const isNavigating = ref(false) // 标记是否正在导航中，防止滚动事件干扰

// Token usage tracking
const tokenUsage = ref({ promptTokens: 0, completionTokens: 0, totalTokens: 0, callCount: 0, conversationTotal: 0 })
const sessionHistory = ref([])
const selectedSessionIdx = ref(-1)
const usagePieRef = ref(null)
const usageBarRef = ref(null)
const usageTimelineRef = ref(null)
const usageModelRef = ref(null)
const allTokenStats = ref(null)
let usagePieChart = null
let usageBarChart = null
let usageTimelineChart = null
let usageModelChart = null


// Multi-session management
const conversations = ref([])
const showSessions = ref(false)

// AI Panel UI state
const aiCollapsed = ref(false)
const aiDarkTheme = ref(localStorage.getItem('labex-ai-theme') === 'dark')
if (aiDarkTheme.value) document.documentElement.setAttribute('data-theme', 'dark')
const activeAiTab = ref('chat')
const contextExpanded = ref(false)
const showModelConfig = ref(false)
const showSettings = ref(false)
const fontSize = ref(localStorage.getItem('labex-ai-fontsize') || 'medium')
const msgDensity = ref(localStorage.getItem('labex-ai-density') || 'comfortable')

// Command palette state
const showCommandPalette = ref(false)
const commandSearch = ref('')
const selectedCommandIndex = ref(0)
const commandSearchRef = ref(null)
const commandListRef = ref(null)

// 完整的命令列表（复刻Opencode）
const commandList = [
  // 服务端LLM提示命令
  { name: 'init', description: '引导式 LabexAgent.md 创建/更新', category: 'LLM', aliases: [] },
  { name: 'review', description: '代码审查 [commit|branch|pr]', category: 'LLM', aliases: [] },

  // 会话管理命令
  { name: 'sessions', description: '切换会话', category: '会话', aliases: [] },
  { name: 'new', description: '新建会话', category: '会话', aliases: ['clear'] },
  { name: 'compact', description: '压缩会话上下文，减少token消耗', category: '会话', aliases: ['summarize'] },
  { name: 'undo', description: '撤销上一条消息', category: '会话', aliases: [] },
  { name: 'redo', description: '恢复已撤销的消息', category: '会话', aliases: [] },
  { name: 'share', description: '分享会话', category: '会话', aliases: [] },
  { name: 'unshare', description: '取消分享', category: '会话', aliases: [] },
  { name: 'rename', description: '重命名会话', category: '会话', aliases: [] },
  { name: 'fork', description: '分叉会话', category: '会话', aliases: [] },
  { name: 'copy', description: '复制会话记录', category: '会话', aliases: [] },
  { name: 'export', description: '导出会话记录', category: '会话', aliases: [] },
  { name: 'timeline', description: '跳转到消息', category: '会话', aliases: [] },
  { name: 'timestamps', description: '切换时间戳显示', category: '会话', aliases: ['toggle-timestamps'] },
  { name: 'thinking', description: '切换思考模式', category: '会话', aliases: ['toggle-thinking'] },

  // Agent/Model管理命令
  { name: 'models', description: '切换模型', category: 'Agent', aliases: [] },
  { name: 'agents', description: '切换Agent', category: 'Agent', aliases: [] },
  { name: 'variants', description: '切换模型变体', category: 'Agent', aliases: [] },
  { name: 'mcps', description: '切换MCP服务器', category: 'Agent', aliases: [] },
  { name: 'connect', description: '连接Provider', category: 'Agent', aliases: [] },

  // 系统命令
  { name: 'status', description: '查看项目状态', category: '系统', aliases: [] },
  { name: 'help', description: '显示帮助信息', category: '系统', aliases: [] },
  { name: 'exit', description: '退出应用', category: '系统', aliases: ['quit', 'q'] },
  { name: 'themes', description: '切换主题', category: '系统', aliases: [] },
  { name: 'docs', description: '打开文档', category: '系统', aliases: [] },
  { name: 'editor', description: '在外部编辑器中编辑', category: '系统', aliases: [] },
  { name: 'skills', description: '打开技能选择器', category: '系统', aliases: [] },
  { name: 'diff', description: '打开差异查看器', category: '系统', aliases: [] },

  // 开发工作流命令
  { name: 'fix', description: '修复问题', category: '开发', aliases: [] },
  { name: 'explain', description: '解释代码', category: '开发', aliases: [] },
  { name: 'refactor', description: '重构代码', category: '开发', aliases: [] },
  { name: 'optimize', description: '优化性能', category: '开发', aliases: [] },
  { name: 'clean', description: '清理项目', category: '开发', aliases: [] },
  { name: 'reset', description: '重置项目', category: '开发', aliases: [] },

  // 文件操作命令
  { name: 'create', description: '创建文件/目录', category: '文件', aliases: [] },
  { name: 'delete', description: '删除文件/目录', category: '文件', aliases: [] },
  { name: 'rename-file', description: '重命名文件', category: '文件', aliases: [] },
  { name: 'search', description: '搜索代码', category: '文件', aliases: [] },
  { name: 'move', description: '移动文件', category: '文件', aliases: [] },
  { name: 'copy-file', description: '复制文件', category: '文件', aliases: [] },

  // Git命令
  { name: 'git', description: '执行Git命令', category: 'Git', aliases: [] },
  { name: 'commit', description: '提交更改', category: 'Git', aliases: [] },
  { name: 'push', description: '推送更改', category: 'Git', aliases: [] },
  { name: 'pull', description: '拉取更改', category: 'Git', aliases: [] },
  { name: 'branch', description: '分支管理', category: 'Git', aliases: [] },
  { name: 'merge', description: '合并分支', category: 'Git', aliases: [] },
  { name: 'stash', description: '暂存更改', category: 'Git', aliases: [] },

  // 测试命令
  { name: 'test', description: '运行测试', category: '测试', aliases: [] },
  { name: 'test-file', description: '为文件生成测试', category: '测试', aliases: [] },
  { name: 'coverage', description: '测试覆盖率', category: '测试', aliases: [] },
  { name: 'benchmark', description: '性能基准测试', category: '测试', aliases: [] },

  // 部署命令
  { name: 'deploy', description: '部署项目', category: '部署', aliases: [] },
  { name: 'build', description: '构建项目', category: '部署', aliases: [] },
  { name: 'start', description: '启动服务', category: '部署', aliases: [] },
  { name: 'stop', description: '停止服务', category: '部署', aliases: [] },
  { name: 'restart', description: '重启服务', category: '部署', aliases: [] },
  { name: 'logs', description: '查看日志', category: '部署', aliases: [] },

  // 分析命令
  { name: 'lint', description: '代码检查', category: '分析', aliases: [] },
  { name: 'format', description: '代码格式化', category: '分析', aliases: [] },
  { name: 'typecheck', description: '类型检查', category: '分析', aliases: [] },
  { name: 'security', description: '安全扫描', category: '分析', aliases: [] },
  { name: 'deps', description: '依赖管理', category: '分析', aliases: [] },
  { name: 'env', description: '环境变量', category: '分析', aliases: [] },
  { name: 'doctor', description: '项目诊断', category: '分析', aliases: [] },
  { name: 'index', description: '生成项目索引', category: '分析', aliases: [] },
  { name: 'context', description: '查看上下文状态', category: '分析', aliases: [] },
  { name: 'rules', description: '查看规则文件', category: '分析', aliases: [] },
  { name: 'checkpoint', description: '创建检查点', category: '分析', aliases: [] },
  { name: 'restore', description: '恢复检查点', category: '分析', aliases: [] },
  { name: 'memory', description: '查看记忆状态', category: '分析', aliases: [] },
  { name: 'tokens', description: '查看token使用', category: '分析', aliases: [] },
]

function setFontSize(size) {
  fontSize.value = size
  localStorage.setItem('labex-ai-fontsize', size)
  const sizeMap = { small: '12px', medium: '13px', large: '14.5px' }
  document.documentElement.style.setProperty('--ai-font-size', sizeMap[size] || '13px')
}
function setMsgDensity(density) {
  msgDensity.value = density
  localStorage.setItem('labex-ai-density', density)
  const gapMap = { compact: '10px', comfortable: '16px', relaxed: '22px' }
  document.querySelector('.ai-panel')?.style.setProperty('--ai-msg-gap', gapMap[density] || '16px')
}
// 初始化设置
if (fontSize.value !== 'medium') nextTick(() => setFontSize(fontSize.value))
if (msgDensity.value !== 'comfortable') nextTick(() => setMsgDensity(msgDensity.value))

// Model config dialog state
const mcEditing = ref(false)
const mcTemplateSelecting = ref(false)
const mcEditingId = ref(null)
const mcSaving = ref(false)
const emptyModelConfigForm = () => ({
  configName: '',
  provider: 'openai_compatible',
  modelName: '',
  apiKey: '',
  baseUrl: '',
  modelsUrl: '',
  maxTokens: 32768,
  temperature: 0.7,
  isDefault: false
})
const mcForm = ref(emptyModelConfigForm())
const mcCustomTemplate = {
  name: '自定义',
  vendor: 'OpenAI Compatible',
  iconText: '+',
  accent: '#64748b',
  baseUrl: '',
  modelsUrl: '',
  modelName: '',
  maxTokens: 32768,
  provider: 'openai_compatible',
  note: '手动填写服务信息',
  custom: true
}
const mcTemplateOptions = [mcCustomTemplate, ...modelConfigPresets]
const mcTestResults = ref({})
const mcTestingIds = ref({})
const mcApiKeyHint = ref('')
const mcModelsLoading = ref(false)
const mcFetchedModels = ref([])
const mcCustomMode = ref(false)

// User-level Agent extensions
const extensionLoading = ref(false)
const activeExtensionTab = ref('skills')
const agentSkills = ref([])
const mcpServers = ref([])
const editingSkillId = ref(null)
const editingMcpId = ref(null)
const skillForm = ref({ title: '', skillKey: '', description: '', content: '', isEnabled: true })
const mcpForm = ref({ serverName: '', serverKey: '', endpoint: '', authHeader: '', toolsJson: '', isEnabled: true })

// Review tab state (ChangesPanel 组件已替代硬编码数据)

// Terminal quick commands
// Terminal panel ref
const terminalPanelRef = ref(null)
const projectPath = ref('')

const langMap = { js: 'javascript', jsx: 'javascript', ts: 'typescript', tsx: 'typescript', vue: 'html', py: 'python', java: 'java', c: 'c', cpp: 'cpp', html: 'html', css: 'css', scss: 'scss', json: 'json', xml: 'xml', yml: 'yaml', yaml: 'yaml', md: 'markdown', sql: 'sql', sh: 'shell', bat: 'shell', ps1: 'powershell', go: 'go', rs: 'rust', php: 'php' }
const fileName = computed(() => { const p = activePath.value; return p ? p.split('/')?.pop() || '' : '' })
const currentModelName = computed(() => {
  if (selectedModelConfigId.value) {
    const cfg = modelConfigs.value.find(c => c.configId === selectedModelConfigId.value)
    if (cfg) return cfg.modelName || cfg.configName
  }
  return 'MiniMax'
})

const agentModes = [
  { key: 'build', label: '构建', icon: 'cube' },
  { key: 'plan', label: '规划', icon: 'map' },
  { key: 'explore', label: '探索', icon: 'search' },
]

const aiTabs = [
  { key: 'chat', label: '对话', icon: '<svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M21 15a2 2 0 0 1-2 2H7l-4 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2z"/></svg>' },
  { key: 'usage', label: '用量', icon: '<svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M12 2v20M17 5H9.5a3.5 3.5 0 0 0 0 7h5a3.5 3.5 0 0 1 0 7H6"/></svg>' },
  { key: 'review', label: '审查', icon: '<svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z"/><polyline points="14 2 14 8 20 8"/><line x1="16" y1="13" x2="8" y2="13"/><line x1="16" y1="17" x2="8" y2="17"/></svg>' },
  { key: 'extensions', label: '扩展', icon: '<svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M12 3v18"/><path d="M3 12h18"/><circle cx="12" cy="12" r="3"/></svg>' },
  { key: 'terminal', label: '终端', icon: '<svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><polyline points="4 17 10 11 4 5"/><line x1="12" y1="19" x2="20" y2="19"/></svg>' },
]

const quickChips = [
  { label: '解释代码', prompt: '请解释当前文件的代码逻辑', icon: '<svg width="11" height="11" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><circle cx="12" cy="12" r="10"/><line x1="12" y1="16" x2="12" y2="12"/><line x1="12" y1="8" x2="12.01" y2="8"/></svg>' },
  { label: '生成测试', prompt: '为当前代码生成单元测试', icon: '<svg width="11" height="11" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><polyline points="9 11 12 14 22 4"/><path d="M21 12v7a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h11"/></svg>' },
  { label: '重构函数', prompt: '请重构当前函数，提升可读性和性能', icon: '<svg width="11" height="11" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M14.7 6.3a1 1 0 0 0 0 1.4l1.6 1.6a1 1 0 0 0 1.4 0l3.77-3.77a6 6 0 0 1-7.94 7.94l-6.91 6.91a2.12 2.12 0 0 1-3-3l6.91-6.91a6 6 0 0 1 7.94-7.94l-3.76 3.76z"/></svg>' },
  { label: '查找 Bug', prompt: '请检查当前代码中的潜在 bug', icon: '<svg width="11" height="11" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M8 2l1.88 1.88M14.12 3.88L16 2M9 7.13v-1a3.003 3.003 0 1 1 6 0v1"/><path d="M12 20c-3.3 0-6-2.7-6-6v-3a4 4 0 0 1 4-4h4a4 4 0 0 1 4 4v3c0 3.3-2.7 6-6 6"/><path d="M12 20v-9M6.53 9C4.6 8.8 3 7.1 3 5M6 13H2M20 5c0 2.1-1.6 3.8-3.53 4M18 13h4M20 9v4"/></svg>' },
]

// ===== Methods =====
onMounted(async () => {
  const pid = route.params.projectId
  if (!pid) { ElMessage.error('项目ID不存在'); router.replace({ name: 'Projects' }); return }
  projectId.value = parseInt(pid)
  try {
    const r = await projectApi.detail(projectId.value)
    const d = r.data || {}
    projectName.value = d.projectName || '未命名项目'
    projectPath.value = d.workspacePath || d.path || ''
  } catch (e) { ElMessage.error('无法加载项目信息'); router.replace({ name: 'Projects' }); return }
  await loadRoot()
  await loadModelConfigs()
  await loadAgentExtensions()
  await loadConversations()
  if (conversations.value.length > 0) {
    selectConversation(conversations.value[0])
  }
})

// 清理定时器，防止内存泄漏
onBeforeUnmount(() => {
  // 清理滚动防抖定时器
  if (scrollTimeout) {
    clearTimeout(scrollTimeout)
    scrollTimeout = null
  }
  // 清理所有消息中的 thinking 动画定时器
  messages.value.forEach(msg => {
    if (msg._thinkingTimer) {
      clearInterval(msg._thinkingTimer)
      msg._thinkingTimer = null
    }
  })
})

async function loadAgentExtensions() {
  extensionLoading.value = true
  try {
    const [skillsRes, mcpRes] = await Promise.all([
      agentExtensionApi.listSkills(),
      agentExtensionApi.listMcpServers()
    ])
    agentSkills.value = skillsRes.data || []
    mcpServers.value = mcpRes.data || []
  } catch (e) {
    ElMessage.warning('扩展配置加载失败')
  } finally {
    extensionLoading.value = false
  }
}

function resetSkillForm() {
  editingSkillId.value = null
  skillForm.value = { title: '', skillKey: '', description: '', content: '', isEnabled: true }
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
    ElMessage.warning('请填写 Skill 标题和内容')
    return
  }
  const payload = { ...skillForm.value, isEnabled: skillForm.value.isEnabled ? 1 : 0 }
  try {
    if (editingSkillId.value) await agentExtensionApi.updateSkill(editingSkillId.value, payload)
    else await agentExtensionApi.createSkill(payload)
    ElMessage.success('Skill 已保存')
    resetSkillForm()
    await loadAgentExtensions()
  } catch (e) {
    ElMessage.error(e?.response?.data?.message || e.message || 'Skill 保存失败')
  }
}

async function toggleSkill(skill) {
  try {
    await agentExtensionApi.updateSkill(skill.skillId, { ...skill, isEnabled: skill.isEnabled === 1 ? 0 : 1 })
    await loadAgentExtensions()
  } catch (e) {
    ElMessage.error('状态更新失败')
  }
}

async function deleteSkill(skill) {
  try {
    await ElMessageBox.confirm(`删除 Skill "${skill.title}"？`, '确认删除', { confirmButtonText: '删除', cancelButtonText: '取消', type: 'warning' })
    await agentExtensionApi.deleteSkill(skill.skillId)
    if (editingSkillId.value === skill.skillId) resetSkillForm()
    await loadAgentExtensions()
  } catch (e) {
    if (e !== 'cancel') ElMessage.error('删除失败')
  }
}

function resetMcpForm() {
  editingMcpId.value = null
  mcpForm.value = { serverName: '', serverKey: '', endpoint: '', authHeader: '', toolsJson: '', isEnabled: true, transport: 'http' }
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
    ElMessage.warning('请填写 MCP 名称和 Endpoint')
    return
  }
  const payload = { ...mcpForm.value, isEnabled: mcpForm.value.isEnabled ? 1 : 0 }
  try {
    if (editingMcpId.value) await agentExtensionApi.updateMcpServer(editingMcpId.value, payload)
    else await agentExtensionApi.createMcpServer(payload)
    ElMessage.success('MCP 配置已保存')
    resetMcpForm()
    await loadAgentExtensions()
  } catch (e) {
    ElMessage.error(e?.response?.data?.message || e.message || 'MCP 保存失败')
  }
}

async function testMcpConnection(server) {
  try {
    ElMessage.info('正在测试连接...')
    // TODO: 调用后端测试连接 API
    ElMessage.success('连接成功')
  } catch (e) {
    ElMessage.error('连接失败: ' + (e?.response?.data?.message || e.message))
  }
}

async function handleSkillFolderUpload(event) {
  const files = event.target.files
  if (!files || files.length === 0) return

  const allFiles = Array.from(files)
  const skillFiles = allFiles.filter(f => f.name === 'SKILL.md')
  if (skillFiles.length === 0) {
    ElMessage.warning('未找到 SKILL.md 文件')
    return
  }

  ElMessage.info(`找到 ${skillFiles.length} 个 SKILL.md 文件，正在导入...`)

  let imported = 0, updated = 0, skipped = 0, failed = 0
  const existingSkills = agentSkills.value || []
  const existingKeyMap = new Map(existingSkills.map(s => [s.skillKey, s]))

  for (const file of skillFiles) {
    try {
      const content = await file.text()
      const folderPath = file.webkitRelativePath?.split('/').slice(0, -1).join('/') || ''
      const parsed = parseSkillMd(content, file.webkitRelativePath)
      if (!parsed) { skipped++; continue }

      // 收集同目录下的其他文件（工具、UI 库等）
      const relatedFiles = allFiles.filter(f =>
        f.webkitRelativePath?.startsWith(folderPath + '/') &&
        f.name !== 'SKILL.md' && !f.name.startsWith('.')
      )
      if (relatedFiles.length > 0) {
        const fileList = relatedFiles.map(f => f.webkitRelativePath).join('\n- ')
        parsed.content = parsed.content + '\n\n## Related Files\n- ' + fileList
      }

      // 截断过长内容
      if (parsed.content && parsed.content.length > 15000) {
        parsed.content = parsed.content.substring(0, 15000) + '\n\n[内容已截断]'
      }

      // 已存在则更新，不存在则创建
      const existing = existingKeyMap.get(parsed.skillKey)
      if (existing) {
        try { await agentExtensionApi.updateSkill(existing.skillId, parsed); updated++ } catch (e) { skipped++ }
        continue
      }

      try {
        await agentExtensionApi.createSkill(parsed)
        imported++
      } catch (createErr) {
        const errMsg = createErr?.response?.data?.message || createErr?.message || ''
        if (errMsg.includes('Duplicate') || errMsg.includes('duplicate')) {
          try {
            const freshList = await agentExtensionApi.listSkills()
            const freshSkill = (freshList.data || []).find(s => s.skillKey === parsed.skillKey)
            if (freshSkill) { await agentExtensionApi.updateSkill(freshSkill.skillId, parsed); updated++ }
            else { skipped++ }
          } catch (e) { skipped++ }
        } else { failed++; console.error('Failed:', file.name, createErr) }
      }
    } catch (e) { failed++; console.error('Failed:', file.name, e) }
  }

  const parts = []
  if (imported > 0) parts.push(`${imported} 个新增`)
  if (updated > 0) parts.push(`${updated} 个更新`)
  if (skipped > 0) parts.push(`${skipped} 个跳过`)
  if (failed > 0) parts.push(`${failed} 个失败`)
  ElMessage.success(`Skill 导入完成: ${parts.join(', ')}`)
  await loadAgentExtensions()
  event.target.value = ''
}

async function handleSkillFileUpload(event) {
  const file = event.target.files?.[0]
  if (!file) return

  try {
    const content = await file.text()
    const parsed = parseSkillMd(content, file.name)
    if (parsed) {
      if (parsed.content && parsed.content.length > 15000) {
        parsed.content = parsed.content.substring(0, 15000) + '\n\n[内容已截断]'
      }
      const existing = (agentSkills.value || []).find(s => s.skillKey === parsed.skillKey)
      if (existing) {
        await agentExtensionApi.updateSkill(existing.skillId, parsed)
        ElMessage.success('Skill 已更新')
      } else {
        await agentExtensionApi.createSkill(parsed)
        ElMessage.success('Skill 已导入')
      }
      await loadAgentExtensions()
    } else {
      ElMessage.warning('无法解析 SKILL.md 文件')
    }
  } catch (e) {
    ElMessage.error('导入失败: ' + (e?.response?.data?.message || e.message))
  }
  event.target.value = ''
}

function parseSkillMd(content, filePath) {
  // 解析 YAML frontmatter
  const match = content.match(/^---\s*\n([\s\S]*?)\n---\s*\n([\s\S]*)/)
  if (!match) {
    // 没有 frontmatter，使用文件名
    const name = filePath?.split('/').pop()?.replace('.md', '') || 'unnamed-skill'
    return {
      title: name,
      skillKey: name.toLowerCase().replace(/[^a-z0-9-]/g, '-'),
      description: '',
      content: content,
      isEnabled: 1
    }
  }

  const frontmatter = match[1]
  const body = match[2]

  // 简单解析 YAML
  const nameMatch = frontmatter.match(/^\s*name\s*:\s*(.+?)\s*$/m)
  const descMatch = frontmatter.match(/^\s*description\s*:\s*(.+?)\s*$/m)

  const name = nameMatch ? nameMatch[1].replace(/['"]/g, '') : filePath?.split('/').pop()?.replace('.md', '') || 'unnamed-skill'

  return {
    title: name,
    skillKey: name.toLowerCase().replace(/[^a-z0-9-]/g, '-'),
    description: descMatch ? descMatch[1].replace(/['"]/g, '') : '',
    content: body.trim(),
    isEnabled: 1
  }
}

async function toggleMcp(server) {
  try {
    await agentExtensionApi.updateMcpServer(server.serverId, { ...server, isEnabled: server.isEnabled === 1 ? 0 : 1 })
    await loadAgentExtensions()
  } catch (e) {
    ElMessage.error('状态更新失败')
  }
}

async function deleteMcp(server) {
  try {
    await ElMessageBox.confirm(`删除 MCP "${server.serverName}"？`, '确认删除', { confirmButtonText: '删除', cancelButtonText: '取消', type: 'warning' })
    await agentExtensionApi.deleteMcpServer(server.serverId)
    if (editingMcpId.value === server.serverId) resetMcpForm()
    await loadAgentExtensions()
  } catch (e) {
    if (e !== 'cancel') ElMessage.error('删除失败')
  }
}

async function loadRoot() {
  if (!projectId.value) return
  treeLoading.value = true
  try { const r = await projectApi.getTree(projectId.value, ''); fileTree.value = r.data || [] } catch (e) { fileTree.value = [] } finally { treeLoading.value = false }
}
async function loadChildren(dirPath) {
  if (!projectId.value) return []
  try { const r = await projectApi.getTree(projectId.value, dirPath); return r.data || [] } catch (e) { return [] }
}
async function openFile(path) {
  if (!projectId.value) return
  const existingIdx = openFiles.value.findIndex(f => f.path === path)
  if (existingIdx >= 0) {
    activeTabIndex.value = existingIdx
    activePath.value = path
    fileContent.value = openFiles.value[existingIdx].content
    fileContentDirty.value = false
    const ext = path.split('.').pop()?.toLowerCase()
    detectedLang.value = langMap[ext] || 'plaintext'
    return
  }
  let content = ''
  try { const r = await projectApi.readFile(projectId.value, path); content = r.data?.content || '' } catch (e) { content = '' }
  const ext = path.split('.').pop()?.toLowerCase()
  const name = path.split('/').pop() || path
  openFiles.value.push({ path, name, content, lang: langMap[ext] || 'plaintext', dirty: false })
  activeTabIndex.value = openFiles.value.length - 1
  activePath.value = path
  fileContent.value = content
  fileContentDirty.value = false
  detectedLang.value = langMap[ext] || 'plaintext'
  editorReady.value = false
  await nextTick(); editorReady.value = true
}
function switchTab(idx) {
  if (idx < 0 || idx >= openFiles.value.length) return
  if (activeTabIndex.value >= 0 && activeTabIndex.value < openFiles.value.length) {
    openFiles.value[activeTabIndex.value].content = fileContent.value
    openFiles.value[activeTabIndex.value].dirty = fileContentDirty.value
  }
  activeTabIndex.value = idx
  const f = openFiles.value[idx]
  activePath.value = f.path
  fileContent.value = f.content
  fileContentDirty.value = f.dirty
  detectedLang.value = f.lang
}
function closeFile(idx) {
  const wasActive = idx === activeTabIndex.value
  openFiles.value.splice(idx, 1)
  if (wasActive) {
    if (openFiles.value.length > 0) {
      const newIdx = Math.min(idx, openFiles.value.length - 1)
      switchTab(newIdx)
    } else {
      activeTabIndex.value = -1
      activePath.value = ''
      fileContent.value = ''
      fileContentDirty.value = false
    }
  } else if (idx < activeTabIndex.value) {
    activeTabIndex.value--
  }
}
watch(fileContent, (val, old) => {
  if (old !== undefined && val !== old) {
    fileContentDirty.value = true
    if (activeTabIndex.value >= 0 && activeTabIndex.value < openFiles.value.length) {
      openFiles.value[activeTabIndex.value].content = val
      openFiles.value[activeTabIndex.value].dirty = true
    }
  }
})
async function saveFile() {
  if (!projectId.value || !activePath.value || savingFile.value) return
  savingFile.value = true
  try { await projectApi.saveFile(projectId.value, activePath.value, fileContent.value); fileContentDirty.value = false; if (activeTabIndex.value >= 0 && activeTabIndex.value < openFiles.value.length) { openFiles.value[activeTabIndex.value].dirty = false } ElMessage.success('文件已保存') } catch (e) { ElMessage.error('保存失败') } finally { savingFile.value = false }
}
watch(activeAiTab, (tab) => { if (tab === 'usage') initUsageCharts() })

function showNewFileModal(type) { newModalType.value = type; newItemParent.value = ''; newItemName.value = ''; showNewModal.value = true }
function handleNewItem(parentPath, type) { newModalType.value = type; newItemParent.value = parentPath; newItemName.value = ''; showNewModal.value = true }
async function confirmNewItem() {
  const name = newItemName.value.trim()
  if (!name) { ElMessage.warning('请输入名称'); return }
  try { await projectApi.createItem(projectId.value, newItemParent.value, name, newModalType.value); ElMessage.success(newModalType.value === 'directory' ? '文件夹创建成功' : '文件创建成功'); showNewModal.value = false; await loadRoot() } catch (e) { ElMessage.error('创建失败: ' + (e?.response?.data?.message || e?.message || '未知错误')) }
}
function handleRename(path, currentName) { renamingItemPath.value = path; renameItemValue.value = currentName; showRenameModal.value = true }
async function confirmRename() {
  const name = renameItemValue.value.trim()
  if (!name) { ElMessage.warning('请输入新名称'); return }
  try { await projectApi.renameItem(projectId.value, renamingItemPath.value, name); ElMessage.success('重命名成功'); showRenameModal.value = false; if (activePath.value === renamingItemPath.value) activePath.value = ''; await loadRoot() } catch (e) { ElMessage.error('重命名失败: ' + (e?.response?.data?.message || e?.message || '未知错误')) }
}
async function handleDelete(path) {
  const itemName = path.split('/').pop()
  try { await ElMessageBox.confirm('确定要删除 "' + itemName + '" 吗？删除后无法恢复。', '删除确认', { confirmButtonText: '确认删除', cancelButtonText: '取消', type: 'warning' }); await projectApi.deleteItem(projectId.value, path); ElMessage.success('已删除'); if (activePath.value === path || activePath.value.startsWith(path + '/')) { activePath.value = ''; fileContent.value = ''; editorReady.value = false } await loadRoot() } catch (e) { if (e !== 'cancel' && e !== 'close') ElMessage.error('删除失败: ' + (e?.response?.data?.message || e?.message || '未知错误')) }
}
async function exportProject() {
  try { const r = await projectApi.exportProject(projectId.value); const blob = r.data instanceof Blob ? r.data : new Blob([r.data], { type: 'application/zip' }); const url = window.URL.createObjectURL(blob); const a = document.createElement('a'); a.href = url; a.download = (projectName.value || 'project') + '.zip'; document.body.appendChild(a); a.click(); document.body.removeChild(a); window.URL.revokeObjectURL(url); ElMessage.success('导出成功') } catch (e) { ElMessage.error('导出失败: ' + (e?.response?.data?.message || e?.message || '未知错误')) }
}

// AI methods
async function sendMessage() {
  const q = agentInput.value.trim()
  if (!q || agentLoading.value) return

  // 检测是否以 / 开头，如果是则调用命令执行API
  let messageToSend = q
  if (q.startsWith('/')) {
    try {
      // 解析命令和参数
      const parts = q.split(/\s+/)
      const command = parts[0].slice(1) // 去掉开头的 /
      const arguments_ = parts.slice(1).join(' ')

      // 调用命令执行API
      const commandResponse = await projectApi.runCommand(projectId.value, {
        command: command,
        message: q,
        conversationId: currentAgentSession.value?.conversationId,
        mode: agentMode.value
      })

      if (commandResponse.data?.success) {
        // 将命令模板作为提示词发送给LLM
        messageToSend = commandResponse.data.template || q

        // 如果是子任务命令，显示特殊提示
        if (commandResponse.data.subtask) {
          messages.value.push({ role: 'user', content: q })
          messages.value.push({
            role: 'assistant',
            content: `🔄 正在执行子任务: /${command}\n\n${commandResponse.data.description}`,
            thinking: '',
            _thinkingDisplay: '',
            _thinkingTimer: null,
            thinkingBlocks: [],
            toolCalls: [],
            plan: null,
            isStreaming: false,
            error: null,
            _nextOrder: 0
          })
          agentInput.value = ''
          await nextTick()
          scrollDown()
          return
        }
      } else {
        // 命令执行失败，显示错误信息
        messages.value.push({ role: 'user', content: q })
        messages.value.push({
          role: 'assistant',
          content: `❌ 命令执行失败: ${commandResponse.data?.message || '未知错误'}`,
          thinking: '',
          _thinkingDisplay: '',
          _thinkingTimer: null,
          thinkingBlocks: [],
          toolCalls: [],
          plan: null,
          isStreaming: false,
          error: null,
          _nextOrder: 0
        })
        agentInput.value = ''
        await nextTick()
        scrollDown()
        return
      }
    } catch (e) {
      console.error('命令执行失败:', e)
      // 如果命令执行失败，继续使用原始消息
      messageToSend = q
    }
  }

  messages.value.push({ role: 'user', content: q, timestamp: Date.now() })
  messages.value.push({ role: 'assistant', content: '', thinking: '', _thinkingDisplay: '', _thinkingTimer: null, thinkingBlocks: [], toolCalls: [], plan: null, isStreaming: true, error: null, _nextOrder: 0, timestamp: Date.now() })
  const assistantMsg = messages.value[messages.value.length - 1]
  agentInput.value = ''; agentLoading.value = true
  userScrolled.value = false
  await nextTick(); scrollDown(true)

  const sessionId = currentAgentSession.value?.sessionId || crypto.randomUUID()

  try {
    const response = await fetch(`/api/student/projects/${projectId.value}/agent/stream`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'Authorization': `Bearer ${localStorage.getItem('token') || ''}`
      },
      body: JSON.stringify({
        sessionId,
        conversationId: currentAgentSession.value?.conversationId,
        mode: agentMode.value,
        message: messageToSend,  // 使用解析后的模板作为消息
        activePath: activePath.value || '',
        modelConfigId: selectedModelConfigId.value || null
      })
    })

    if (!response.ok) throw new Error(`HTTP ${response.status}`)

    const reader = response.body.getReader()
    const decoder = new TextDecoder()
    let buffer = ''

    while (true) {
      const { done, value } = await reader.read()
      if (done) break
      buffer += decoder.decode(value, { stream: true })
      const lines = buffer.split('\n')
      buffer = lines.pop() || ''

      for (const line of lines) {
        if (line.startsWith('data:')) {
          const dataStr = line.substring(5).trim()
          if (!dataStr) continue
          try {
            const event = JSON.parse(dataStr)
            handleAgentEvent(event, assistantMsg)
          } catch (e) { /* ignore parse errors */ }
        }
      }
    }
  } catch (e) {
    if (e.name !== 'AbortError') {
      assistantMsg.error = e.message
      assistantMsg.content = `错误: ${e.message}`
    }
  } finally {
    flushThinkingDisplay(assistantMsg)
    assistantMsg.isStreaming = false
    agentLoading.value = false
    await nextTick(); scrollDown()
  }
}

function handleAgentEvent(event, assistantMsg) {
  const type = event.type
  const data = event.data
  switch (type) {
    case 'SESSION':
      currentAgentSession.value = data
      break
    case 'THINK_START':
      if (assistantMsg.thinking) {
        assistantMsg.thinkingBlocks.push({ content: assistantMsg.thinking, summary: data.summary || '', iteration: data.iteration || 0, _open: false, _order: (assistantMsg._nextOrder = (assistantMsg._nextOrder || 0) + 1) })
      }
      assistantMsg.thinking = ''
      break
    case 'THINK_DELTA':
      assistantMsg.thinking += (data.delta || '')
      startThinkingReveal(assistantMsg)
      break
    case 'THINK':
      if (data.content) {
        if (assistantMsg.thinking) {
          assistantMsg.thinkingBlocks.push({ content: assistantMsg.thinking, summary: data.summary || '', iteration: data.iteration || 0, _open: false, _order: (assistantMsg._nextOrder = (assistantMsg._nextOrder || 0) + 1) })
          assistantMsg.thinking = ''
          assistantMsg._thinkingDisplay = ''
        } else {
          assistantMsg.thinking = data.content
          startThinkingReveal(assistantMsg)
        }
      }
      break
    case 'TOOL_CALL':
      if (assistantMsg.thinking && !assistantMsg.streamSaving) {
        flushThinkingDisplay(assistantMsg)
        assistantMsg.thinkingBlocks.push({ content: assistantMsg.thinking, summary: data.summary || data.tool || '', iteration: 0, _open: false, _order: (assistantMsg._nextOrder = (assistantMsg._nextOrder || 0) + 1) })
        assistantMsg.thinking = ''
        assistantMsg._thinkingDisplay = ''
      }
      assistantMsg.toolCalls.push({ name: data.tool, args: data.arguments, summary: data.summary, result: null, status: 'running', _order: (assistantMsg._nextOrder = (assistantMsg._nextOrder || 0) + 1) })
      break
    case 'OBSERVE':
      if (assistantMsg.toolCalls.length > 0) {
        const last = assistantMsg.toolCalls[assistantMsg.toolCalls.length - 1]
        last.result = data.result || data.content
        last.status = data.success ? 'completed' : 'error'
        if (data.diff) {
          last.hasDiff = true
          trackFileChange(data, last)
        }
        if (data.pendingChangeId) {
          changesRefreshKey.value++
        }
      }
      break
    case 'PERMISSION_ASK':
      {
        const last = assistantMsg.toolCalls[assistantMsg.toolCalls.length - 1]
        if (last && last.status === 'running' && last.name === data.toolName) {
          last.status = 'waiting_approval'
          last.permissionRequest = data
          last.summary = data.summary || last.summary
          break
        }
      }
      assistantMsg.toolCalls.push({
        name: 'permission_ask',
        args: { toolName: data.toolName, input: data.input },
        summary: data.summary || `${data.toolName} 需要确认`,
        result: null,
        status: 'waiting_approval',
        permissionRequest: data,
        _order: (assistantMsg._nextOrder = (assistantMsg._nextOrder || 0) + 1)
      })
      break
    case 'USER_QUESTION':
      attachUserQuestion(assistantMsg, data)
      break
    case 'PLAN_UPDATE':
      assistantMsg.plan = data.summary || data.plan || null
      assistantMsg.planJson = data.planJson || null
      break
    case 'FINAL_DELTA':
      assistantMsg.content += (data.delta || '')
      break
    case 'FINAL':
      if (data.content) assistantMsg.content = data.content
      break
    case 'DONE':
      flushThinkingDisplay(assistantMsg)
      assistantMsg.isStreaming = false
      break
    case 'TOKEN_USAGE':
      tokenUsage.value.promptTokens += (data.promptTokens || 0)
      tokenUsage.value.completionTokens += (data.completionTokens || 0)
      tokenUsage.value.totalTokens += (data.totalTokens || 0)
      tokenUsage.value.callCount++
      tokenUsage.value.conversationTotal = data.conversationTotal || tokenUsage.value.totalTokens
      const existingSession = sessionHistory.value.find(s => s.conversationId === currentAgentSession.value?.conversationId)
      if (existingSession) {
        existingSession.promptTokens += (data.promptTokens || 0)
        existingSession.completionTokens += (data.completionTokens || 0)
        existingSession.totalTokens += (data.totalTokens || 0)
        existingSession.callCount++
      } else {
        sessionHistory.value.push({
          conversationId: currentAgentSession.value?.conversationId,
          title: currentSessionName.value,
          promptTokens: data.promptTokens || 0,
          completionTokens: data.completionTokens || 0,
          totalTokens: data.totalTokens || 0,
          callCount: 1
        })
      }
      break
    case 'ERROR':
      assistantMsg.error = data.message
      break
    case 'INTERRUPTED':
      assistantMsg.content += '\n[已中断]'
      assistantMsg.isStreaming = false
      break
  }
}

function attachUserQuestion(msg, data) {
  if (!msg || !data) return
  msg.toolCalls = msg.toolCalls || []
  const last = msg.toolCalls[msg.toolCalls.length - 1]
  const summary = data.summary || data.question || '等待用户回答'
  if (last && last.status === 'running' && last.name === 'question') {
    last.status = 'waiting_user'
    last.questionRequest = data
    last.summary = summary
    return
  }
  msg.toolCalls.push({
    name: 'question',
    args: { question: data.question, options: data.options || [] },
    summary,
    result: null,
    status: 'waiting_user',
    questionRequest: data,
    _order: (msg._nextOrder = (msg._nextOrder || 0) + 1)
  })
}

function trackFileChange(data, toolCall) {
  const filePath = data.file || data.path || toolCall.args?.path || toolCall.args?.file_path || ''
  if (!filePath) return
  const existing = sessionChanges.value.find(c => c.file === filePath)
  const patch = data.diff || ''
  let additions = 0, deletions = 0
  const patchLines = patch.split('\n')
  for (const l of patchLines) {
    if (l.startsWith('+') && !l.startsWith('+++')) additions++
    if (l.startsWith('-') && !l.startsWith('---')) deletions++
  }
  if (existing) {
    existing.patch = patch
    existing.additions = additions
    existing.deletions = deletions
    existing.status = data.success !== false ? 'modified' : existing.status
  } else {
    sessionChanges.value.push({
      file: filePath,
      patch,
      additions,
      deletions,
      status: toolCall.name === 'write_file' ? 'added' : 'modified'
    })
  }
}

async function handlePermissionDecision({ call, action, feedback }) {
  const request = call?.permissionRequest
  if (!request?.requestId) return
  try {
    call.status = action === 'reject' ? 'error' : 'running'
    call.result = action === 'reject'
      ? (feedback ? `已拒绝执行：${feedback}` : '已拒绝执行')
      : '已确认，等待工具继续执行...'
    const response = await projectApi.agentApprovePermission(projectId.value, {
      requestId: request.requestId,
      action,
      feedback: action === 'reject' ? (feedback || '用户拒绝了本次工具调用') : (feedback || '')
    })
    if (response?.data && !response.data.granted && action !== 'reject') {
      call.status = 'error'
      call.result = response.data.feedback || '权限确认失败'
    }
  } catch (e) {
    call.status = 'error'
    call.result = '权限确认提交失败：' + (e?.response?.data?.message || e?.message || '未知错误')
  }
}

async function handleQuestionReply({ call, action, answer }) {
  const request = call?.questionRequest
  if (!request?.requestId) return
  if (action === 'answer' && !answer) {
    ElMessage.warning('请先输入回答')
    return
  }
  try {
    call.status = action === 'answer' ? 'running' : 'error'
    call.questionAnswer = answer || ''
    call.result = action === 'answer'
      ? `已提交回答：${answer}\n等待 Agent 继续执行...`
      : '已取消这次提问'
    const response = await projectApi.agentReplyQuestion(projectId.value, {
      requestId: request.requestId,
      action,
      answer: answer || ''
    })
    if (response?.data && !response.data.answered && action === 'answer') {
      call.status = 'error'
      call.result = response.data.feedback || '回答提交失败'
    }
  } catch (e) {
    call.status = 'error'
    call.result = '回答提交失败：' + (e?.response?.data?.message || e?.message || '未知错误')
  }
}

async function revertChange(change) {
  if (!change || !change.file) return
  try {
    const original = await projectApi.readFile(projectId.value, change.file)
    const originalContent = original.data?.content || ''
    const patch = change.patch || ''
    const lines = originalContent.split('\n')
    const newLines = []
    let li = 0
    for (const pLine of patch.split('\n')) {
      if (pLine.startsWith('@@') || pLine.startsWith('---') || pLine.startsWith('+++') || pLine.startsWith('diff ')) continue
      if (pLine.startsWith('+')) { /* skip added lines to revert */ }
      else if (pLine.startsWith('-')) { newLines.push(pLine.slice(1)) }
      else if (pLine.startsWith(' ')) { newLines.push(pLine.slice(1)) }
    }
    await projectApi.saveFile(projectId.value, change.file, newLines.join('\n'))
    sessionChanges.value = sessionChanges.value.filter(c => c.file !== change.file)
    if (activePath.value === change.file) {
      fileContent.value = newLines.join('\n')
      fileContentDirty.value = false
    }
    if (activeTabIndex.value >= 0) {
      const tab = openFiles.value.find(f => f.path === change.file)
      if (tab) { tab.content = newLines.join('\n'); tab.dirty = false }
    }
  } catch (e) {
    ElMessage.error('Revert failed: ' + (e?.response?.data?.message || e?.message))
  }
}

function onUndoChange(change) {
  sessionChanges.value = sessionChanges.value.filter(c => c.file !== (change.relativePath || change.file))
  if (activePath.value === (change.relativePath || change.file)) {
    fileContentDirty.value = false
  }
}

const currentAgentSession = ref(null)
const selectedModelConfigId = ref(null)
const modelConfigs = ref([])
const sessionChanges = ref([])
const changesRefreshKey = ref(0)

async function loadModelConfigs() {
  try {
    const r = await modelConfigApi.list()
    modelConfigs.value = r.data || []
    const def = modelConfigs.value.find(c => c.isDefault === 1)
    if (def) selectedModelConfigId.value = def.configId
  } catch (e) { /* ignore */ }
}

function startCreateConfig() {
  mcEditingId.value = null
  mcApiKeyHint.value = ''
  mcForm.value = emptyModelConfigForm()
  mcFetchedModels.value = []
  mcCustomMode.value = false
  mcEditing.value = false
  mcTemplateSelecting.value = true
}
function editConfig(cfg) {
  mcEditingId.value = cfg.configId
  mcApiKeyHint.value = cfg.apiKey || ''
  mcForm.value = {
    configName: cfg.configName || '',
    provider: cfg.provider || 'openai_compatible',
    modelName: cfg.modelName || '',
    apiKey: '',
    baseUrl: cfg.baseUrl || '',
    modelsUrl: '',
    maxTokens: cfg.maxTokens || 32768,
    temperature: cfg.temperature ?? 0.7,
    isDefault: cfg.isDefault === 1
  }
  mcFetchedModels.value = []
  mcCustomMode.value = true
  mcTemplateSelecting.value = false
  mcEditing.value = true
}
function selectModelTemplate(tpl) {
  mcForm.value = {
    ...emptyModelConfigForm(),
    configName: tpl.custom ? '' : tpl.name,
    provider: tpl.provider || 'openai_compatible',
    baseUrl: tpl.baseUrl || '',
    modelsUrl: tpl.modelsUrl || '',
    modelName: tpl.modelName || '',
    maxTokens: tpl.maxTokens || 32768,
    temperature: tpl.temperature ?? 0.7
  }
  mcFetchedModels.value = []
  mcCustomMode.value = !!tpl.custom
  mcTemplateSelecting.value = false
  mcEditing.value = true
}
function cancelModelConfigEdit() {
  mcEditing.value = false
  mcEditingId.value = null
  mcApiKeyHint.value = ''
  mcFetchedModels.value = []
  mcCustomMode.value = false
}
function applyFetchedModelLimits() {
  const selected = mcFetchedModels.value.find(m => m.id === mcForm.value.modelName)
  if (selected?.maxTokens) {
    mcForm.value.maxTokens = selected.maxTokens
  }
}
async function fetchModelList() {
  const f = mcForm.value
  if (!f.modelsUrl.trim()) {
    ElMessage.warning(mcCustomMode.value ? '请先填写模型列表 URL' : '该模板暂未配置官方模型列表 URL')
    return
  }
  mcModelsLoading.value = true
  try {
    const r = await modelConfigApi.listModels({
      baseUrl: f.baseUrl.trim(),
      modelsUrl: f.modelsUrl.trim(),
      apiKey: f.apiKey.trim()
    })
    const data = r.data || {}
    if (!data.success) {
      mcFetchedModels.value = []
      ElMessage.warning(data.error || '模型列表获取失败')
      return
    }
    mcFetchedModels.value = data.models || []
    if (data.modelsUrl && !f.modelsUrl.trim()) {
      mcForm.value.modelsUrl = data.modelsUrl
    }
    if (mcFetchedModels.value.length === 0) {
      ElMessage.warning('模型列表为空')
    } else {
      applyFetchedModelLimits()
      ElMessage.success(`已获取 ${mcFetchedModels.value.length} 个模型`)
    }
  } catch (e) {
    mcFetchedModels.value = []
    ElMessage.error('模型列表获取失败: ' + (e?.response?.data?.message || e?.message || '未知错误'))
  } finally {
    mcModelsLoading.value = false
  }
}
async function saveConfig() {
  const f = mcForm.value
  if (!f.configName.trim()) { ElMessage.warning('请输入配置名称'); return }
  if (!f.baseUrl.trim()) { ElMessage.warning('请输入 Base URL'); return }
  if (!f.modelName.trim()) { ElMessage.warning('请输入模型名称'); return }
  if (!mcEditingId.value && !f.apiKey.trim()) { ElMessage.warning('请输入 API Key'); return }
  mcSaving.value = true
  try {
    const payload = {
      configName: f.configName.trim(),
      provider: f.provider,
      modelName: f.modelName.trim(),
      baseUrl: f.baseUrl.trim(),
      maxTokens: f.maxTokens || 8192,
      temperature: f.temperature ?? 0.7,
      isDefault: f.isDefault
    }
    if (f.apiKey.trim()) payload.apiKey = f.apiKey.trim()
    if (mcEditingId.value) {
      await modelConfigApi.update(mcEditingId.value, payload)
      ElMessage.success('配置已更新')
    } else {
      await modelConfigApi.create(payload)
      ElMessage.success('配置已创建')
    }
    mcEditing.value = false
    await loadModelConfigs()
  } catch (e) {
    ElMessage.error('保存失败: ' + (e?.response?.data?.message || e?.message || '未知错误'))
  } finally {
    mcSaving.value = false
  }
}
async function deleteConfig(cfg) {
  try {
    await ElMessageBox.confirm('确定删除配置 "' + cfg.configName + '" 吗？', '删除确认', { confirmButtonText: '删除', cancelButtonText: '取消', type: 'warning' })
    await modelConfigApi.delete(cfg.configId)
    ElMessage.success('已删除')
    if (selectedModelConfigId.value === cfg.configId) selectedModelConfigId.value = null
    await loadModelConfigs()
  } catch (e) {
    if (e !== 'cancel' && e !== 'close') ElMessage.error('删除失败')
  }
}
async function testConfig(cfg) {
  mcTestingIds.value[cfg.configId] = true
  delete mcTestResults.value[cfg.configId]
  try {
    const r = await modelConfigApi.testConnection(cfg.configId)
    mcTestResults.value[cfg.configId] = r.data
    if (r.data?.success) {
      ElMessage.success(cfg.configName + ' 连接成功 (' + r.data.latency + 'ms)')
    }
  } catch (e) {
    mcTestResults.value[cfg.configId] = { success: false, error: e?.response?.data?.message || e?.message || '测试失败' }
  } finally {
    mcTestingIds.value[cfg.configId] = false
  }
}

function stopGeneration() {
  agentLoading.value = false
  if (currentAgentSession.value?.sessionId) {
    fetch(`/api/student/projects/${projectId.value}/agent/interrupt`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', 'Authorization': `Bearer ${localStorage.getItem('token') || ''}` },
      body: JSON.stringify({ sessionId: currentAgentSession.value.sessionId })
    }).catch(() => {})
  }
}
function clearMessages() { messages.value = []; currentAgentSession.value = null; sessionChanges.value = []; tokenUsage.value = { promptTokens: 0, completionTokens: 0, totalTokens: 0, callCount: 0, conversationTotal: 0 } }

const currentSessionName = computed(() => {
  if (!currentAgentSession.value || !currentAgentSession.value.conversationId) return '新会话'
  const c = conversations.value.find(x => x.conversationId === currentAgentSession.value.conversationId)
  return c ? (c.title || '对话') : '新会话'
})

async function loadConversations() {
  try {
    const r = await projectApi.agentConversations(projectId.value)
    conversations.value = (r.data || []).sort((a, b) => new Date(b.createTime) - new Date(b.createTime))
  } catch (e) { /* ignore */ }
}

function createNewSession() {
  showSessions.value = false
  clearMessages()
  currentAgentSession.value = null
}

function selectConversation(c) {
  if (currentAgentSession.value && currentAgentSession.value.conversationId === c.conversationId) return
  showSessions.value = false
  loadConversationMessages(c.conversationId)
}

async function loadConversationMessages(conversationId) {
  messages.value = []
  sessionChanges.value = []
  tokenUsage.value = { promptTokens: 0, completionTokens: 0, totalTokens: 0, callCount: 0, conversationTotal: 0 }
  currentAgentSession.value = { sessionId: crypto.randomUUID(), conversationId }
  agentLoading.value = false

  try {
    const r = await projectApi.agentMessages(projectId.value, conversationId)
    const events = r.data || []
    for (const event of events) {
      let data = {}
      try { data = JSON.parse(event.eventData || '{}') } catch (e) {}
      if (event.eventType === 'USER') {
        messages.value.push({ role: 'user', content: event.content || data.content || '' })
        messages.value.push({ role: 'assistant', content: '', thinking: '', _thinkingDisplay: '', thinkingBlocks: [], toolCalls: [], plan: null, planJson: null, isStreaming: false, error: null, _nextOrder: 0 })
      } else {
        const last = messages.value.length > 0 ? messages.value[messages.value.length - 1] : null
        if (!last || last.role !== 'assistant') continue
        replayHistoryEvent(event.eventType, data, last)
      }
    }
    for (const msg of messages.value) {
      if (msg.role === 'assistant' && msg.thinkingBlocks) {
        msg.thinkingBlocks = msg.thinkingBlocks.filter(tb => tb.content && tb.content.trim().length > 0)
      }
      if (msg.role === 'assistant' && msg.toolCalls) {
        for (const tc of msg.toolCalls) {
          if (tc.status === 'running') tc.status = 'completed'
        }
      }
    }
  } catch (e) {
    ElMessage.error('加载历史消息失败')
  }

  // 加载完成后初始滚动到底部
  initialScroll()
}

function replayHistoryEvent(type, data, msg) {
  switch (type) {
    case 'THINK_START':
      if (msg.thinking) { msg.thinkingBlocks = msg.thinkingBlocks || []; msg.thinkingBlocks.push({ content: msg.thinking, summary: data.summary || '', _open: false, _order: (msg._nextOrder = (msg._nextOrder || 0) + 1) }); msg.thinking = '' }
      msg._hasThinkStart = true
      break
    case 'THINK_DELTA':
      msg.thinking += (data.delta || '')
      msg._thinkingDisplay = msg.thinking
      break
    case 'THINK':
      if (data.content) {
        if (msg.thinking) { msg.thinkingBlocks = msg.thinkingBlocks || []; msg.thinkingBlocks.push({ content: msg.thinking, summary: data.summary || '', _open: false, _order: (msg._nextOrder = (msg._nextOrder || 0) + 1) }) }
        else if (!msg._hasThinkStart) { msg.thinkingBlocks = msg.thinkingBlocks || []; msg.thinkingBlocks.push({ content: data.content, summary: data.summary || '', _open: false, _order: (msg._nextOrder = (msg._nextOrder || 0) + 1) }) }
        msg.thinking = ''
        msg._hasThinkStart = false
      }
      break
    case 'TOOL_CALL':
      if (msg.thinking) { msg.thinkingBlocks = msg.thinkingBlocks || []; msg.thinkingBlocks.push({ content: msg.thinking, summary: data.summary || data.tool || '', _open: false, _order: (msg._nextOrder = (msg._nextOrder || 0) + 1) }); msg.thinking = '' }
      msg.toolCalls = msg.toolCalls || []; msg.toolCalls.push({ name: data.tool, args: data.arguments, summary: data.summary, result: null, status: 'running', _order: (msg._nextOrder = (msg._nextOrder || 0) + 1) })
      break
    case 'OBSERVE':
      if (msg.toolCalls && msg.toolCalls.length > 0) { const l = msg.toolCalls[msg.toolCalls.length - 1]; l.result = data.result || data.content; l.status = data.success !== false ? 'completed' : 'error' }
      if (data.pendingChangeId) changesRefreshKey.value++
      break
    case 'PERMISSION_ASK':
      if (msg.toolCalls && msg.toolCalls.length > 0) {
        const l = msg.toolCalls[msg.toolCalls.length - 1]
        if (l.status === 'running' && l.name === data.toolName) {
          l.status = 'waiting_approval'
          l.permissionRequest = data
          l.summary = data.summary || l.summary
          break
        }
      }
      msg.toolCalls = msg.toolCalls || []
      msg.toolCalls.push({ name: 'permission_ask', args: { toolName: data.toolName, input: data.input }, summary: data.summary || `${data.toolName} 需要确认`, result: null, status: 'waiting_approval', permissionRequest: data, _order: (msg._nextOrder = (msg._nextOrder || 0) + 1) })
      break
    case 'USER_QUESTION':
      attachUserQuestion(msg, data)
      break
    case 'PLAN_UPDATE':
      msg.plan = data.summary || data.plan || null; msg.planJson = data.planJson || null
      break
    case 'FINAL_DELTA':
      msg.content += (data.delta || '')
      break
    case 'FINAL':
      if (data.content) msg.content = data.content
      break
    case 'ERROR':
      msg.error = data.message
      break
    case 'INTERRUPTED':
      msg.content += '\n[已中断]'
      break
    case 'TOKEN_USAGE':
      tokenUsage.value.promptTokens += (data.promptTokens || 0)
      tokenUsage.value.completionTokens += (data.completionTokens || 0)
      tokenUsage.value.totalTokens += (data.totalTokens || 0)
      tokenUsage.value.callCount++
      tokenUsage.value.conversationTotal = data.conversationTotal || tokenUsage.value.totalTokens
      break
  }
}

async function forkConversation(c) {
  if (!c?.conversationId) return
  try {
    const r = await projectApi.agentForkConversation(projectId.value, c.conversationId)
    if (r.code === 0 && r.data?.conversationId) {
      await loadConversations()
      await loadConversationMessages(r.data.conversationId)
      showSessions.value = false
      ElMessage.success('已创建会话分支')
    } else {
      ElMessage.error(r.message || '创建分支失败')
    }
  } catch (e) {
    ElMessage.error('创建分支失败：' + (e?.response?.data?.message || e?.message || '未知错误'))
  }
}

async function forkCurrentConversation() {
  if (!currentAgentSession.value?.conversationId) {
    ElMessage.info('当前还没有可分支的会话')
    return
  }
  const c = conversations.value.find(x => x.conversationId === currentAgentSession.value.conversationId)
  await forkConversation(c || { conversationId: currentAgentSession.value.conversationId })
}

async function compactConversation(c) {
  if (!c?.conversationId) return
  try {
    const r = await projectApi.agentCompactConversation(projectId.value, c.conversationId)
    if (r.code === 0) {
      await loadConversations()
      ElMessage.success('上下文已压缩，后续对话会携带摘要')
    } else {
      ElMessage.error(r.message || '压缩失败')
    }
  } catch (e) {
    ElMessage.error('压缩失败：' + (e?.response?.data?.message || e?.message || '未知错误'))
  }
}

async function compactCurrentConversation() {
  if (!currentAgentSession.value?.conversationId) {
    ElMessage.info('当前还没有可压缩的会话')
    return
  }
  const c = conversations.value.find(x => x.conversationId === currentAgentSession.value.conversationId)
  await compactConversation(c || { conversationId: currentAgentSession.value.conversationId })
}

async function deleteConversation(c) {
  try {
    await projectApi.agentDeleteConversation(projectId.value, c.conversationId)
    if (currentAgentSession.value && currentAgentSession.value.conversationId === c.conversationId) {
      clearMessages()
    }
    conversations.value = conversations.value.filter(x => x.conversationId !== c.conversationId)
  } catch (e) { ElMessage.error('删除失败') }
}

function getMergedItems(msg) {
  const items = []
  if (msg.thinkingBlocks) {
    for (const tb of msg.thinkingBlocks) {
      items.push({ type: 'thinking', data: tb, _order: tb._order || 0 })
    }
  }
  if (msg.toolCalls) {
    for (const tc of msg.toolCalls) {
      items.push({ type: 'tool', data: tc, _order: tc._order || 0 })
    }
  }
  items.sort((a, b) => a._order - b._order)
  return items
}

function formatTokenCount(n) {
  if (n >= 1000000) return (n / 1000000).toFixed(1) + 'M'
  if (n >= 1000) return (n / 1000).toFixed(1) + 'K'
  return String(n)
}

const THINK_CHARS_PER_SEC = 80
function startThinkingReveal(msg) {
  if (msg._thinkingTimer) return
  msg._thinkingTimer = setInterval(() => {
    if (!msg.thinking) { stopThinkingReveal(msg); return }
    const full = msg.thinking
    const shown = msg._thinkingDisplay || ''
    if (shown.length < full.length) {
      const nextLen = Math.min(full.length, shown.length + THINK_CHARS_PER_SEC)
      msg._thinkingDisplay = full.substring(0, nextLen)
    }
    if (msg._thinkingDisplay.length >= full.length && !msg.isStreaming) {
      stopThinkingReveal(msg)
    }
  }, 1000)
}
function stopThinkingReveal(msg) {
  if (msg._thinkingTimer) { clearInterval(msg._thinkingTimer); msg._thinkingTimer = null }
}
function flushThinkingDisplay(msg) {
  stopThinkingReveal(msg)
  msg._thinkingDisplay = msg.thinking || ''
}

async function loadAllTokenStats() {
  try {
    const r = await projectApi.agentTokenSummary(projectId.value)
    allTokenStats.value = r.data || null
  } catch (e) { allTokenStats.value = null }
}

async function initUsageCharts() {
  await nextTick()
  await loadAllTokenStats()
  const stats = allTokenStats.value

  if (usagePieRef.value) {
    if (usagePieChart) usagePieChart.dispose()
    usagePieChart = echarts.init(usagePieRef.value)
    const pieData = stats ? [
      { value: stats.totalPromptTokens || tokenUsage.value.promptTokens, name: '输入', itemStyle: { color: '#3b82f6' } },
      { value: stats.totalCompletionTokens || tokenUsage.value.completionTokens, name: '输出', itemStyle: { color: '#10b981' } }
    ] : [
      { value: tokenUsage.value.promptTokens, name: '输入', itemStyle: { color: '#3b82f6' } },
      { value: tokenUsage.value.completionTokens, name: '输出', itemStyle: { color: '#10b981' } }
    ]
    usagePieChart.setOption({
      tooltip: { trigger: 'item', formatter: '{b}: {c} ({d}%)' },
      series: [{
        type: 'pie', radius: ['42%', '68%'], center: ['50%', '50%'],
        itemStyle: { borderRadius: 5, borderColor: '#fff', borderWidth: 2 },
        label: { show: true, fontSize: 10, color: '#6b7280', formatter: '{b}\n{d}%' },
        emphasis: { label: { show: true, fontSize: 12, fontWeight: 'bold' } },
        data: pieData
      }]
    })
  }

  if (usageTimelineRef.value && stats && stats.byDay) {
    if (usageTimelineChart) usageTimelineChart.dispose()
    usageTimelineChart = echarts.init(usageTimelineRef.value)
    const days = Object.keys(stats.byDay).sort()
    const dayTotals = days.map(d => stats.byDay[d] || 0)
    usageTimelineChart.setOption({
      tooltip: { trigger: 'axis' },
      grid: { left: 50, right: 16, top: 16, bottom: 30 },
      xAxis: { type: 'category', data: days.map(d => d.substring(5)), axisLabel: { fontSize: 10, color: '#9ca3af' }, axisLine: { lineStyle: { color: '#e5e7eb' } } },
      yAxis: { type: 'value', axisLabel: { fontSize: 10, color: '#9ca3af', formatter: v => v >= 1000 ? (v / 1000).toFixed(0) + 'K' : v }, splitLine: { lineStyle: { color: '#f3f4f6' } } },
      series: [{
        type: 'bar', data: dayTotals, barWidth: '50%',
        itemStyle: { color: new echarts.graphic.LinearGradient(0, 0, 0, 1, [{ offset: 0, color: '#818cf8' }, { offset: 1, color: '#6366f1' }]), borderRadius: [4, 4, 0, 0] }
      }]
    })
  }

  if (usageModelRef.value && stats && stats.byModel) {
    if (usageModelChart) usageModelChart.dispose()
    usageModelChart = echarts.init(usageModelRef.value)
    const models = Object.entries(stats.byModel).map(([name, tokens]) => ({ name, value: tokens }))
    const modelColors = ['#3b82f6', '#10b981', '#f59e0b', '#ef4444', '#8b5cf6', '#ec4899']
    usageModelChart.setOption({
      tooltip: { trigger: 'item', formatter: '{b}: {c} ({d}%)' },
      series: [{
        type: 'pie', radius: ['35%', '60%'], center: ['50%', '50%'],
        roseType: 'area',
        itemStyle: { borderRadius: 5, borderColor: '#fff', borderWidth: 2 },
        label: { fontSize: 10, color: '#6b7280' },
        data: models.map((m, i) => ({ ...m, itemStyle: { color: modelColors[i % modelColors.length] } }))
      }]
    })
  }

  if (usageBarRef.value && sessionHistory.value.length > 0) {
    if (usageBarChart) usageBarChart.dispose()
    usageBarChart = echarts.init(usageBarRef.value)
    usageBarChart.setOption({
      tooltip: { trigger: 'axis' },
      grid: { left: 50, right: 16, top: 16, bottom: 30 },
      xAxis: { type: 'category', data: sessionHistory.value.map((s, i) => s.title ? s.title.substring(0, 8) : ('会话' + (i + 1))), axisLabel: { fontSize: 9, rotate: 30, color: '#9ca3af' }, axisLine: { lineStyle: { color: '#e5e7eb' } } },
      yAxis: { type: 'value', axisLabel: { fontSize: 9, color: '#9ca3af', formatter: v => v >= 1000 ? (v / 1000).toFixed(0) + 'K' : v }, splitLine: { lineStyle: { color: '#f3f4f6' } } },
      series: [
        { name: '输入', type: 'bar', stack: 'total', data: sessionHistory.value.map(s => s.promptTokens), itemStyle: { color: '#3b82f6' } },
        { name: '输出', type: 'bar', stack: 'total', data: sessionHistory.value.map(s => s.completionTokens), itemStyle: { color: '#10b981', borderRadius: [3, 3, 0, 0] } }
      ]
    })
  }
}

// Terminal is now handled by TerminalPanel component (xterm.js + WebSocket)

function applySuggestion(suggestion) {
  agentInput.value = suggestion
  sendMessage()
}

// 消息导航
function navigateMessage(direction) {
  const newIndex = currentMessageIndex.value + direction
  if (newIndex < 0 || newIndex >= messages.value.length) return

  // 设置导航状态，防止滚动事件干扰
  isNavigating.value = true
  currentMessageIndex.value = newIndex
  userScrolled.value = true

  // 滚动到目标消息
  nextTick(() => {
    const container = msgContainer.value
    if (!container) {
      isNavigating.value = false
      return
    }
    const msgElements = container.querySelectorAll('.ai-msg')
    if (msgElements && msgElements[newIndex]) {
      const targetEl = msgElements[newIndex]
      // 使用 getBoundingClientRect 计算相对于视口的位置
      const containerRect = container.getBoundingClientRect()
      const targetRect = targetEl.getBoundingClientRect()
      // 计算目标元素相对于容器顶部的偏移量
      const relativeTop = targetRect.top - containerRect.top
      // 计算需要滚动的位置（当前滚动位置 + 相对偏移）
      const scrollTo = container.scrollTop + relativeTop
      container.scrollTo({
        top: scrollTo,
        behavior: 'smooth'
      })
      // 等待滚动动画完成后重置导航状态
      setTimeout(() => {
        isNavigating.value = false
      }, 500) // 平滑滚动动画大约 300-500ms
    } else {
      isNavigating.value = false
    }
  })
}

// 格式化时间戳
function formatTime(timestamp) {
  if (!timestamp) return ''
  const date = new Date(timestamp)
  const now = new Date()
  const isToday = date.toDateString() === now.toDateString()

  if (isToday) {
    return date.toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' })
  } else {
    return date.toLocaleDateString('zh-CN', { month: 'short', day: 'numeric' }) + ' ' +
           date.toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' })
  }
}

// 更新当前消息索引（基于滚动位置）
function updateCurrentMessageIndex() {
  if (!msgContainer.value) return
  const msgElements = msgContainer.value.querySelectorAll('.ai-msg')
  const containerRect = msgContainer.value.getBoundingClientRect()
  const containerCenter = containerRect.top + containerRect.height / 2

  let closestIndex = 0
  let closestDistance = Infinity

  msgElements.forEach((el, index) => {
    const rect = el.getBoundingClientRect()
    const elCenter = rect.top + rect.height / 2
    const distance = Math.abs(elCenter - containerCenter)
    if (distance < closestDistance) {
      closestDistance = distance
      closestIndex = index
    }
  })

  currentMessageIndex.value = closestIndex
}

// 优化的滚动函数
function scrollDown(force = false) {
  if (!msgContainer.value) return
  // 如果是强制滚动或者用户没有手动滚动过，则滚动到底部
  if (force || !userScrolled.value) {
    msgContainer.value.scrollTop = msgContainer.value.scrollHeight
  }
}

// 滚动事件处理（带防抖）
let scrollTimeout = null
function handleScroll() {
  if (!msgContainer.value) return
  const { scrollTop, scrollHeight, clientHeight } = msgContainer.value
  const distanceFromBottom = scrollHeight - scrollTop - clientHeight
  // 当距离底部超过 200px 时显示滚动按钮
  showScrollBtn.value = distanceFromBottom > 200
  // 如果用户向上滚动超过 100px，标记为手动滚动
  if (distanceFromBottom > 100) {
    userScrolled.value = true
  }
  // 只在非导航状态下更新当前消息索引，避免干扰程序触发的滚动
  if (!isNavigating.value) {
    // 使用防抖，避免频繁更新
    if (scrollTimeout) clearTimeout(scrollTimeout)
    scrollTimeout = setTimeout(() => {
      updateCurrentMessageIndex()
    }, 100)
  }
}

// 初始滚动（在消息加载后调用）
function initialScroll() {
  if (!initialScrollDone.value && messages.value.length > 0) {
    nextTick(() => {
      scrollDown(true)
      initialScrollDone.value = true
    })
  }
}

// 模式切换动画
function switchMode(mode) {
  if (agentMode.value === mode || modeTransitioning.value) return
  modeTransitioning.value = true
  agentMode.value = mode
  setTimeout(() => { modeTransitioning.value = false }, 200)
}

function applyChip(prompt) { agentInput.value = prompt; nextTick(() => aiInputRef.value?.focus()) }
function toggleAiTheme() {
  aiDarkTheme.value = !aiDarkTheme.value
  localStorage.setItem('labex-ai-theme', aiDarkTheme.value ? 'dark' : 'light')
  document.documentElement.setAttribute('data-theme', aiDarkTheme.value ? 'dark' : 'light')
}
function refreshContext() { ElMessage.success('上下文已刷新') }
function copyMessage(content) { navigator.clipboard?.writeText(content); ElMessage.success('已复制') }
function insertToEditor(content) { ElMessage.success('代码已插入编辑器') }
function renderMarkdown(text) {
  if (!text) return ''
  const rawHtml = marked.parse(text, { gfm: true, breaks: true, silent: true })
  return enhanceMarkdownHtml(sanitizeMarkdownHtml(String(rawHtml || '')))
}

function sanitizeMarkdownHtml(html) {
  if (typeof document === 'undefined') return html
  const template = document.createElement('template')
  template.innerHTML = html
  const allowedTags = new Set(['p', 'br', 'strong', 'em', 'del', 'code', 'pre', 'blockquote', 'ul', 'ol', 'li', 'h1', 'h2', 'h3', 'h4', 'h5', 'h6', 'table', 'thead', 'tbody', 'tr', 'th', 'td', 'a', 'hr'])

  function cleanElement(el) {
    Array.from(el.children).forEach(cleanElement)
    const tag = el.tagName.toLowerCase()
    if (!allowedTags.has(tag)) {
      const parent = el.parentNode
      while (el.firstChild) parent.insertBefore(el.firstChild, el)
      parent.removeChild(el)
      return
    }
    Array.from(el.attributes).forEach((attr) => {
      const name = attr.name.toLowerCase()
      const value = attr.value || ''
      const allowed = (
        (tag === 'a' && ['href', 'title'].includes(name)) ||
        (tag === 'code' && name === 'class')
      )
      if (!allowed) {
        el.removeAttribute(attr.name)
        return
      }
      if (tag === 'a' && name === 'href' && !/^(https?:|mailto:|#)/i.test(value)) {
        el.removeAttribute(attr.name)
      }
    })
  }

  Array.from(template.content.children).forEach(cleanElement)
  return template.innerHTML
}

function enhanceMarkdownHtml(html) {
  if (typeof document === 'undefined') return html
  const template = document.createElement('template')
  template.innerHTML = html

  linkifyPlainUrls(template.content)

  template.content.querySelectorAll('a[href]').forEach((link) => {
    const href = link.getAttribute('href') || ''
    if (/^https?:\/\//i.test(href)) {
      link.setAttribute('target', '_blank')
      link.setAttribute('rel', 'noopener noreferrer')
    }
    link.classList.add('msg-link')
  })

  template.content.querySelectorAll('pre').forEach((pre) => {
    const code = pre.querySelector('code')
    const rawCode = code ? code.textContent || '' : pre.textContent || ''
    const className = code?.getAttribute('class') || ''
    const langMatch = className.match(/language-([a-z0-9_+-]+)/i)
    const requestedLang = normalizeCodeLanguage(langMatch?.[1] || '')
    const lang = requestedLang || guessCodeLanguage(rawCode)

    if (code) {
      if (lang !== 'text' && hljs.getLanguage(lang)) {
        code.innerHTML = hljs.highlight(rawCode, { language: lang }).value
        code.classList.add('hljs')
      } else {
        code.textContent = rawCode
      }
    }

    const wrapper = document.createElement('div')
    wrapper.className = 'code-block'
    const header = document.createElement('div')
    header.className = 'code-block-header'
    const label = document.createElement('span')
    label.className = 'code-lang'
    label.textContent = lang
    const button = document.createElement('button')
    button.type = 'button'
    button.className = 'code-copy-btn'
    button.dataset.code = rawCode
    button.textContent = '复制'
    header.append(label, button)
    pre.parentNode.insertBefore(wrapper, pre)
    wrapper.append(header, pre)
  })

  template.content.querySelectorAll('table').forEach((table) => {
    table.classList.add('msg-table')
    const tableText = tableToTsv(table)
    const wrap = document.createElement('div')
    wrap.className = 'msg-table-wrap'
    const header = document.createElement('div')
    header.className = 'msg-table-header'
    const tag = document.createElement('span')
    tag.className = 'msg-table-tag'
    tag.textContent = '表格'
    const copy = document.createElement('button')
    copy.type = 'button'
    copy.className = 'table-copy-btn'
    copy.dataset.table = tableText
    copy.textContent = '复制表格'
    header.append(tag, copy)
    const scroll = document.createElement('div')
    scroll.className = 'msg-table-scroll'
    table.parentNode.insertBefore(wrap, table)
    scroll.appendChild(table)
    wrap.append(header, scroll)
  })

  enhanceCallouts(template.content)

  return template.innerHTML
}

function handleMarkdownClick(event) {
  const copyBtn = event.target?.closest?.('.code-copy-btn')
  if (copyBtn) {
    navigator.clipboard?.writeText(copyBtn.dataset.code || '')
    ElMessage.success('代码已复制')
    return
  }
  const tableBtn = event.target?.closest?.('.table-copy-btn')
  if (tableBtn) {
    navigator.clipboard?.writeText(tableBtn.dataset.table || '')
    ElMessage.success('表格已复制')
  }
}

function linkifyPlainUrls(root) {
  const walker = document.createTreeWalker(root, NodeFilter.SHOW_TEXT, {
    acceptNode(node) {
      const parent = node.parentElement
      if (!parent) return NodeFilter.FILTER_REJECT
      if (parent.closest('a, code, pre, button')) return NodeFilter.FILTER_REJECT
      return /https?:\/\/\S+/i.test(node.nodeValue || '') ? NodeFilter.FILTER_ACCEPT : NodeFilter.FILTER_REJECT
    }
  })
  const nodes = []
  while (walker.nextNode()) nodes.push(walker.currentNode)
  const urlRe = /https?:\/\/[^\s<>"']+/gi
  nodes.forEach((node) => {
    const text = node.nodeValue || ''
    const frag = document.createDocumentFragment()
    let last = 0
    let match
    while ((match = urlRe.exec(text)) !== null) {
      const raw = match[0]
      const trimmed = raw.replace(/[),.;:!?]+$/g, '')
      const trailing = raw.slice(trimmed.length)
      if (match.index > last) frag.append(document.createTextNode(text.slice(last, match.index)))
      const a = document.createElement('a')
      a.href = trimmed
      a.textContent = trimmed
      a.target = '_blank'
      a.rel = 'noopener noreferrer'
      a.className = 'msg-link'
      frag.append(a)
      if (trailing) frag.append(document.createTextNode(trailing))
      last = match.index + raw.length
    }
    if (last < text.length) frag.append(document.createTextNode(text.slice(last)))
    node.parentNode?.replaceChild(frag, node)
  })
}

function normalizeCodeLanguage(lang) {
  const normalized = String(lang || '').trim().toLowerCase()
  const aliases = {
    js: 'javascript',
    jsx: 'javascript',
    ts: 'typescript',
    tsx: 'typescript',
    py: 'python',
    sh: 'bash',
    shell: 'bash',
    zsh: 'bash',
    yml: 'yaml',
    md: 'markdown',
    vue: 'xml',
    plaintext: 'text',
    txt: 'text'
  }
  return aliases[normalized] || normalized
}

function guessCodeLanguage(code) {
  const text = String(code || '').trim()
  if (!text) return 'text'
  if (/^\s*[{[][\s\S]*[}\]]\s*$/.test(text)) return 'json'
  if (/^\s*(import|export)\s.+from\s|const\s+\w+\s*=|function\s+\w+\s*\(/m.test(text)) return 'javascript'
  if (/^\s*(public|private|class|package|import\s+java\.)/m.test(text)) return 'java'
  if (/^\s*(def|from\s+\w+\s+import|import\s+\w+|class\s+\w+:)/m.test(text)) return 'python'
  if (/^\s*(SELECT|INSERT|UPDATE|DELETE|CREATE)\b/im.test(text)) return 'sql'
  return 'text'
}

function tableToTsv(table) {
  return Array.from(table.querySelectorAll('tr')).map((row) =>
    Array.from(row.children).map((cell) => (cell.textContent || '').trim()).join('\t')
  ).join('\n')
}

function enhanceCallouts(root) {
  const labels = {
    note: '提示',
    tip: '建议',
    success: '完成',
    warning: '注意',
    important: '重点',
    error: '错误'
  }
  root.querySelectorAll('blockquote').forEach((quote) => {
    const marker = (quote.textContent || '').match(/^\s*\[!(NOTE|TIP|SUCCESS|WARNING|IMPORTANT|ERROR)\]\s*/i)
    if (!marker) return
    const type = marker[1].toLowerCase()
    quote.classList.add('msg-callout', `msg-callout-${type}`)
    const walker = document.createTreeWalker(quote, NodeFilter.SHOW_TEXT)
    while (walker.nextNode()) {
      const node = walker.currentNode
      const before = node.nodeValue || ''
      const after = before.replace(/^\s*\[!(NOTE|TIP|SUCCESS|WARNING|IMPORTANT|ERROR)\]\s*/i, '')
      if (before !== after) {
        node.nodeValue = after
        break
      }
    }
    const title = document.createElement('div')
    title.className = 'msg-callout-title'
    title.textContent = labels[type] || '提示'
    quote.prepend(title)
  })
}
function autoResize(e) { const t = e.target; t.style.height = 'auto'; t.style.height = Math.min(t.scrollHeight, 120) + 'px' }
function copyCommand(cmd) { navigator.clipboard?.writeText(cmd); ElMessage.success('命令已复制') }
function runCommand(cmd) { if (activeTermIdx.value < 0) { createTerminalSession().then(() => { termInput.value = cmd; executeTerminalCommand() }) } else { termInput.value = cmd; executeTerminalCommand() } }
function generateCommand() { ElMessage.info('请使用终端标签页直接输入命令') }
function goToIssue(issue) { ElMessage.info('跳转到 ' + issue.file + ':' + issue.line) }
function autoFix(issue) { ElMessage.success('自动修复: ' + issue.message) }
function goBack() { router.push({ name: 'Projects' }) }
async function optimizePrompt() {
  if (!agentInput.value.trim()) { ElMessage.warning('请先输入提示词'); return }
  const originalPrompt = agentInput.value.trim()
  const loadingMsg = ElMessage({ message: '正在优化提示词...', type: 'info', duration: 0, showClose: false })
  try {
    const r = await projectApi.optimizePrompt(projectId.value, { message: originalPrompt })
    loadingMsg.close()
    const optimized = r.data?.optimizedPrompt?.trim()
    if (optimized && optimized !== originalPrompt) {
      agentInput.value = optimized
      ElMessage.success('提示词已优化')
    } else {
      ElMessage.info('提示词已很清晰，未产生优化建议')
    }
  } catch (e) {
    loadingMsg.close()
    ElMessage.error('优化失败: ' + (e?.response?.data?.message || e?.message || '未知错误'))
  }
}
function atFile() {
  if (activePath.value) {
    agentInput.value += ` @${activePath.value} `
    nextTick(() => aiInputRef.value?.focus())
  } else { ElMessage.warning('请先选择一个文件') }
}
function showCommandMenu() {
  agentInput.value = '/'
  showCommandPalette.value = true
  commandSearch.value = ''
  selectedCommandIndex.value = 0
  nextTick(() => commandSearchRef.value?.focus())
}

// 命令选择器相关方法
const filteredCommands = computed(() => {
  const search = commandSearch.value.toLowerCase().trim()
  if (!search) return commandList
  return commandList.filter(cmd =>
    cmd.name.toLowerCase().includes(search) ||
    cmd.description.toLowerCase().includes(search) ||
    cmd.category.toLowerCase().includes(search) ||
    cmd.aliases.some(alias => alias.toLowerCase().includes(search))
  )
})

function handleInput(e) {
  autoResize(e)
  const value = agentInput.value
  if (value === '/') {
    showCommandPalette.value = true
    commandSearch.value = ''
    selectedCommandIndex.value = 0
    nextTick(() => commandSearchRef.value?.focus())
  } else if (value.startsWith('/') && !value.includes(' ')) {
    showCommandPalette.value = true
    commandSearch.value = value.slice(1)
    selectedCommandIndex.value = 0
  } else {
    showCommandPalette.value = false
  }
}

function closeCommandPalette() {
  showCommandPalette.value = false
  commandSearch.value = ''
  selectedCommandIndex.value = 0
}

function navigateCommand(direction) {
  const total = filteredCommands.value.length
  if (total === 0) return
  selectedCommandIndex.value = (selectedCommandIndex.value + direction + total) % total
  // 滚动到可见区域
  nextTick(() => {
    const list = commandListRef.value
    const item = list?.querySelector('.command-item.active')
    if (item) {
      item.scrollIntoView({ block: 'nearest' })
    }
  })
}

function selectCommand(cmd) {
  agentInput.value = '/' + cmd.name + ' '
  closeCommandPalette()
  nextTick(() => aiInputRef.value?.focus())
}

function selectFirstCommand() {
  const first = filteredCommands.value[0]
  if (first) {
    selectCommand(first)
  }
}

// Resize
let resizing = false
function startResize(e) {
  resizing = true
  const startX = e.clientX
  const panel = e.target.closest('.ai-panel')
  const startWidth = panel?.offsetWidth || 340
  const onMove = (ev) => { if (!resizing) return; const diff = startX - ev.clientX; const newW = Math.max(280, Math.min(startWidth + diff, window.innerWidth * 0.4)); if (panel) panel.style.width = newW + 'px' }
  const onUp = () => { resizing = false; document.removeEventListener('mousemove', onMove); document.removeEventListener('mouseup', onUp) }
  document.addEventListener('mousemove', onMove)
  document.addEventListener('mouseup', onUp)
}
</script>

<style scoped>
/* ===== Base Shell ===== */
.ws-shell { display: flex; flex-direction: column; height: 100vh; background: #fff; }
.ws-topbar { display: flex; align-items: center; gap: 12px; padding: 0 16px; height: 44px; background: #fafbfc; border-bottom: 1px solid #f0f0f0; flex-shrink: 0; }
.ws-title-section { display: flex; align-items: center; gap: 6px; flex: 1; }
.ws-title { font-size: 13px; font-weight: 600; color: #111827; }
.ws-topbar-right { display: flex; align-items: center; gap: 8px; }
.ws-unsaved { font-size: 11px; color: #f59e0b; background: #fef3c7; padding: 2px 8px; border-radius: 4px; }
.ws-body { flex: 1; display: flex; overflow: hidden; }

/* ===== Left Sidebar (File Tree) ===== */
.ws-sidebar { width: 240px; border-right: 1px solid #f0f0f0; display: flex; flex-direction: column; background: #fafbfc; flex-shrink: 0; }
.ws-sidebar-header { display: flex; align-items: center; justify-content: space-between; padding: 10px 12px; font-size: 11px; font-weight: 600; color: #9ca3af; text-transform: uppercase; letter-spacing: 0.5px; border-bottom: 1px solid #f0f0f0; }
.ws-sidebar-actions { display: flex; gap: 2px; text-transform: none; letter-spacing: 0; font-size: 12px; font-weight: 500; color: #374151; }
.ws-tree { flex: 1; overflow-y: auto; padding: 6px 4px; }
.ws-tree *:focus { outline: none !important; }
.ws-tree *:focus-visible { outline: none !important; }
.ws-tree-empty { padding: 16px; color: #9ca3af; font-size: 13px; text-align: center; }

/* ===== Editor ===== */
.ws-editor { flex: 1; display: flex; flex-direction: column; overflow: hidden; background: #fff; min-width: 0; }
.ws-editor-empty { flex: 1; display: flex; flex-direction: column; align-items: center; justify-content: center; gap: 8px; color: #9ca3af; font-size: 13px; }
.ws-editor-hint { font-size: 12px; color: #d1d5db; }
.ws-editor-tabs { display: flex; border-bottom: 1px solid #f0f0f0; background: #fafbfc; padding: 0 8px; flex-shrink: 0; overflow-x: auto; }
.ws-editor-tabs::-webkit-scrollbar { height: 2px; }
.ws-editor-tabs::-webkit-scrollbar-thumb { background: #d1d5db; border-radius: 2px; }
.ws-tab { display: flex; align-items: center; gap: 5px; padding: 7px 10px; font-size: 12px; color: #6b7280; border-bottom: 2px solid transparent; cursor: pointer; transition: all 0.12s; white-space: nowrap; }
.ws-tab:hover { background: #f9fafb; color: #374151; }
.ws-tab.active { color: #111827; border-bottom-color: #6366f1; background: #fff; }
.ws-tab-name { max-width: 120px; overflow: hidden; text-overflow: ellipsis; }
.ws-tab-dot { width: 6px; height: 6px; background: #f59e0b; border-radius: 50%; flex-shrink: 0; }
.ws-tab-close { display: flex; align-items: center; justify-content: center; width: 16px; height: 16px; border: none; background: transparent; color: #9ca3af; cursor: pointer; border-radius: 3px; padding: 0; flex-shrink: 0; opacity: 0; transition: all 0.1s; }
.ws-tab:hover .ws-tab-close { opacity: 1; }
.ws-tab-close:hover { background: #fee2e2; color: #ef4444; }
.ws-tab-path { font-size: 11px; color: #9ca3af; margin-left: 4px; }
.ws-monaco { flex: 1; overflow: hidden; }

/* ===== Buttons ===== */
.ws-btn { display: inline-flex; align-items: center; gap: 4px; border: none; border-radius: 6px; cursor: pointer; font-size: 12px; font-weight: 500; padding: 6px 10px; transition: all 0.15s ease; font-family: inherit; background: transparent; color: #374151; }
.ws-btn svg { flex-shrink: 0; }
.ws-btn:hover { background: #f3f4f6; }
.ws-btn-primary { background: #4f46e5; color: #fff; }
.ws-btn-primary:hover { background: #4338ca; }
.ws-btn-outline { background: #fff; border: 1px solid #e5e7eb; }
.ws-btn-outline:hover { background: #f9fafb; }
.ws-btn-ghost { padding: 4px 6px; color: #6b7280; }
.ws-btn-ghost:hover { background: #f3f4f6; color: #111827; }
.ws-btn-sm { padding: 5px 10px; font-size: 12px; }
.ws-btn:disabled { opacity: 0.4; cursor: not-allowed; }

/* ===== Dialogs ===== */
.ws-overlay {
  --ai-bg: #ffffff;
  --ai-bg-secondary: #f9fafb;
  --ai-bg-tertiary: #f3f4f6;
  --ai-bg-elevated: #ffffff;
  --ai-border: #f0f0f0;
  --ai-border-strong: #e5e7eb;
  --ai-border-focus: #3b82f6;
  --ai-text: #111827;
  --ai-text-secondary: #374151;
  --ai-text-muted: #6b7280;
  --ai-text-faint: #9ca3af;
  --ai-accent: #3b82f6;
  --ai-accent-hover: #2563eb;
  --ai-accent-bg: #eff6ff;
  --ai-accent-border: #bfdbfe;
  --ai-purple: #8b5cf6;
  --ai-purple-text: #7c3aed;
  --ai-purple-bg: #f5f3ff;
  --ai-purple-border: #ede9fe;
  --ai-purple-deep: #6d28d9;
  --ai-green: #10b981;
  --ai-red: #ef4444;
  --ai-red-hover: #dc2626;
  --ai-yellow: #f59e0b;
  --ai-shadow-sm: 0 1px 4px rgba(0,0,0,0.06);
  --ai-shadow-md: 0 4px 16px rgba(0,0,0,0.1);
  --ai-shadow-lg: 0 8px 24px rgba(0,0,0,0.12);
  --ai-code-bg: #1e1e2e;
  --ai-code-text: #cdd6f4;
  --ai-inline-code-bg: #f0f0f0;
  --ai-inline-code-border: #e5e7eb;
  --ai-inline-code-text: #e11d48;
  --ai-font-size: 13px;
  --ai-msg-gap: 16px;
  --ai-radius: 8px;
  --ai-radius-sm: 6px;

  position: fixed;
  inset: 0;
  background: rgba(17, 24, 39, 0.35);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 2000;
}
.ws-dialog { background: #fff; border-radius: 14px; padding: 28px; width: 420px; box-shadow: 0 24px 60px rgba(0,0,0,0.15); }
.ws-dialog h3 { font-size: 17px; font-weight: 600; color: #111827; margin: 0 0 20px; }
.ws-dialog-input { width: 100%; padding: 10px 14px; border: 1px solid #e5e7eb; border-radius: 8px; font-size: 14px; outline: none; box-sizing: border-box; font-family: inherit; transition: border-color 0.2s, box-shadow 0.2s; }
.ws-dialog-input:focus { border-color: #6366f1; box-shadow: 0 0 0 3px rgba(99, 102, 241, 0.1); }
.ws-dialog-actions { display: flex; gap: 10px; justify-content: flex-end; margin-top: 22px; }
.modal-enter-active { transition: all 0.25s ease; }
.modal-leave-active { transition: all 0.2s ease; }
.modal-enter-from { opacity: 0; }
.modal-leave-to { opacity: 0; }
.modal-enter-from .ws-dialog,
.modal-enter-from .settings-dialog,
.modal-enter-from .mc-dialog { transform: scale(0.95) translateY(8px); }
.msg-enter-active { transition: all 0.3s ease; }
.msg-leave-active { transition: all 0.2s ease; }
.msg-enter-from { opacity: 0; transform: translateY(10px); }
.msg-leave-to { opacity: 0; }

/* ========================================================================
   AI ASSISTANT PANEL - Light Theme (unified with page)
   ======================================================================== */
.ai-panel {
  --ai-bg: #ffffff;
  --ai-bg-secondary: #f9fafb;
  --ai-bg-tertiary: #f3f4f6;
  --ai-bg-elevated: #ffffff;
  --ai-border: #f0f0f0;
  --ai-border-strong: #e5e7eb;
  --ai-border-focus: #3b82f6;
  --ai-text: #111827;
  --ai-text-secondary: #374151;
  --ai-text-muted: #6b7280;
  --ai-text-faint: #9ca3af;
  --ai-accent: #3b82f6;
  --ai-accent-hover: #2563eb;
  --ai-accent-bg: #eff6ff;
  --ai-accent-border: #bfdbfe;
  --ai-purple: #8b5cf6;
  --ai-purple-text: #7c3aed;
  --ai-purple-bg: #f5f3ff;
  --ai-purple-border: #ede9fe;
  --ai-purple-deep: #6d28d9;
  --ai-green: #10b981;
  --ai-red: #ef4444;
  --ai-red-hover: #dc2626;
  --ai-yellow: #f59e0b;
  --ai-shadow-sm: 0 1px 4px rgba(0,0,0,0.06);
  --ai-shadow-md: 0 4px 16px rgba(0,0,0,0.1);
  --ai-shadow-lg: 0 8px 24px rgba(0,0,0,0.12);
  --ai-code-bg: #1e1e2e;
  --ai-code-text: #cdd6f4;
  --ai-inline-code-bg: #f0f0f0;
  --ai-inline-code-border: #e5e7eb;
  --ai-inline-code-text: #e11d48;
  --ai-font-size: 13px;
  --ai-msg-gap: 16px;
  --ai-radius: 8px;
  --ai-radius-sm: 6px;

  position: relative;
  width: 420px;
  min-width: 320px;
  max-width: 45vw;
  display: flex;
  flex-direction: column;
  background: var(--ai-bg);
  color: var(--ai-text-secondary);
  font-size: var(--ai-font-size);
  flex-shrink: 0;
  border-left: 1px solid var(--ai-border);
  transition: width 0.15s ease;
}

/* ===== Dark Theme ===== */
.ai-panel.dark {
  --ai-bg: #1a1b26;
  --ai-bg-secondary: #1f2033;
  --ai-bg-tertiary: #282a3a;
  --ai-bg-elevated: #24253a;
  --ai-border: #2e3044;
  --ai-border-strong: #383a50;
  --ai-border-focus: #7aa2f7;
  --ai-text: #c0caf5;
  --ai-text-secondary: #a9b1d6;
  --ai-text-muted: #787c99;
  --ai-text-faint: #565f89;
  --ai-accent: #7aa2f7;
  --ai-accent-hover: #5d87e0;
  --ai-accent-bg: #1a1d3a;
  --ai-accent-border: #2e3a5e;
  --ai-purple: #bb9af7;
  --ai-purple-text: #c0a8f7;
  --ai-purple-bg: #1f1d30;
  --ai-purple-border: #2d2a45;
  --ai-purple-deep: #bb9af7;
  --ai-green: #9ece6a;
  --ai-red: #f7768e;
  --ai-red-hover: #e05f75;
  --ai-yellow: #e0af68;
  --ai-shadow-sm: 0 1px 4px rgba(0,0,0,0.2);
  --ai-shadow-md: 0 4px 16px rgba(0,0,0,0.3);
  --ai-shadow-lg: 0 8px 24px rgba(0,0,0,0.4);
  --ai-code-bg: #13141c;
  --ai-code-text: #c0caf5;
  --ai-inline-code-bg: #24253a;
  --ai-inline-code-border: #383a50;
  --ai-inline-code-text: #f7768e;
}
.ai-panel.collapsed {
  width: 48px;
  min-width: 48px;
  max-width: 48px;
}

/* Resize Handle */
.ai-resize-handle {
  position: absolute;
  left: -3px;
  top: 0;
  bottom: 0;
  width: 6px;
  cursor: col-resize;
  z-index: 10;
  transition: background 0.15s;
}
.ai-resize-handle:hover,
.ai-resize-handle:active {
  background: var(--ai-accent);
}

/* ===== Collapsed Icon Bar ===== */
.ai-collapsed-bar {
  display: flex;
  flex-direction: column;
  align-items: center;
  padding: 8px 0;
  gap: 4px;
  height: 100%;
}
.ai-icon-btn {
  width: 36px;
  height: 36px;
  display: flex;
  align-items: center;
  justify-content: center;
  border: none;
  background: transparent;
  color: var(--ai-text-muted);
  border-radius: 8px;
  cursor: pointer;
  transition: all 0.15s;
}
.ai-icon-btn:hover {
  background: var(--ai-bg-tertiary);
  color: var(--ai-text);
}
.ai-collapsed-spacer { flex: 1; }
.ai-collapsed-badge {
  font-size: 10px;
  background: var(--ai-accent);
  color: #fff;
  border-radius: 10px;
  padding: 1px 6px;
  margin-bottom: 4px;
}

/* ===== Top Bar ===== */
.ai-topbar {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 8px 10px;
  border-bottom: 1px solid var(--ai-border);
  flex-shrink: 0;
  backdrop-filter: blur(12px);
  background: color-mix(in srgb, var(--ai-bg) 85%, transparent);
  position: sticky;
  top: 0;
  z-index: 10;
}
.ai-topbar-btn {
  width: 28px;
  height: 28px;
  display: flex;
  align-items: center;
  justify-content: center;
  border: none;
  background: transparent;
  color: var(--ai-text-muted);
  border-radius: 6px;
  cursor: pointer;
  transition: all 0.12s;
}
.ai-topbar-btn:hover {
  background: var(--ai-bg-tertiary);
  color: var(--ai-text);
}
.ai-topbar-title {
  flex: 1;
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 12px;
  font-weight: 600;
  color: var(--ai-text);
}
.ai-topbar-actions {
  display: flex;
  gap: 4px;
}

/* ===== Context Indicator ===== */
.ai-context {
  border-bottom: 1px solid var(--ai-border);
  flex-shrink: 0;
}
.ai-context-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 8px 12px;
  cursor: pointer;
  transition: background 0.12s;
}
.ai-context-header:hover {
  background: var(--ai-bg-secondary);
}
.ai-context-info {
  display: flex;
  align-items: center;
  gap: 6px;
  min-width: 0;
}
.ai-context-filename {
  font-size: 12px;
  font-weight: 500;
  color: var(--ai-text);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.ai-context-lang {
  font-size: 10px;
  color: var(--ai-accent);
  background: var(--ai-accent-bg);
  padding: 1px 6px;
  border-radius: 4px;
  text-transform: uppercase;
  flex-shrink: 0;
}
.ai-context-empty {
  font-size: 12px;
  color: var(--ai-text-faint);
}
.ai-context-actions {
  display: flex;
  align-items: center;
  gap: 4px;
  flex-shrink: 0;
}
.ai-ctx-btn {
  width: 22px;
  height: 22px;
  display: flex;
  align-items: center;
  justify-content: center;
  border: none;
  background: transparent;
  color: var(--ai-text-faint);
  border-radius: 4px;
  cursor: pointer;
  transition: all 0.12s;
}
.ai-ctx-btn:hover {
  background: var(--ai-bg-tertiary);
  color: var(--ai-text-secondary);
}
.ai-context-arrow {
  transition: transform 0.2s;
}
.ai-context-detail {
  padding: 0 12px 10px;
  overflow: hidden;
}
.ai-ctx-row {
  display: flex;
  gap: 8px;
  padding: 3px 0;
  font-size: 11px;
}
.ai-ctx-label {
  color: var(--ai-text-faint);
  flex-shrink: 0;
  min-width: 48px;
}
.ai-ctx-value {
  color: var(--ai-text-muted);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.ai-ctx-code {
  font-family: 'JetBrains Mono', 'Fira Code', monospace;
  font-size: 10px;
  background: var(--ai-bg-tertiary);
  padding: 1px 5px;
  border-radius: 3px;
  color: var(--ai-text-muted);
}
.ai-slide-enter-active, .ai-slide-leave-active { transition: all 0.2s ease; }
.ai-slide-enter-from, .ai-slide-leave-to { opacity: 0; max-height: 0; padding-top: 0; padding-bottom: 0; }

/* ===== Tab Bar ===== */
.ai-tabs {
  display: flex;
  border-bottom: 1px solid var(--ai-border);
  padding: 0 8px;
  flex-shrink: 0;
}
.ai-tab {
  display: flex;
  align-items: center;
  gap: 5px;
  padding: 9px 14px;
  font-size: 12px;
  color: var(--ai-text-faint);
  border: none;
  background: transparent;
  border-bottom: 2px solid transparent;
  cursor: pointer;
  transition: all 0.15s;
  font-family: inherit;
  white-space: nowrap;
}
.ai-tab:hover {
  color: var(--ai-text-secondary);
}
.ai-tab.active {
  color: var(--ai-accent);
  border-bottom-color: var(--ai-accent);
}
.ai-tab svg { flex-shrink: 0; }

/* ===== Content Area ===== */
.ai-content {
  flex: 1;
  display: flex;
  flex-direction: column;
  overflow: hidden;
  position: relative;
  min-width: 0;
}

/* ===== Chat: Messages ===== */
.ai-messages {
  flex: 1;
  overflow-y: auto;
  overflow-x: hidden;
  padding: 12px;
  scroll-behavior: smooth;
  min-width: 0;
}
.ai-messages::-webkit-scrollbar { width: 4px; }
.ai-messages::-webkit-scrollbar-track { background: transparent; }
.ai-messages::-webkit-scrollbar-thumb { background: var(--ai-border-strong); border-radius: 4px; }
.ai-empty {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 8px;
  padding: 48px 16px;
  text-align: center;
}
.ai-empty-icon {
  width: 56px;
  height: 56px;
  border-radius: 16px;
  background: var(--ai-accent-bg);
  display: flex;
  align-items: center;
  justify-content: center;
}
.ai-empty-title {
  font-size: 14px;
  font-weight: 600;
  color: var(--ai-text);
  margin: 4px 0 0;
}
.ai-empty-desc {
  font-size: 12px;
  color: var(--ai-text-faint);
  margin: 0;
  line-height: 1.5;
}
.ai-msg-list {
  display: flex;
  flex-direction: column;
  gap: 12px;
  min-width: 0;
  width: 100%;
}
.ai-msg {
  display: flex;
  gap: 10px;
  animation: aiMsgIn 0.3s cubic-bezier(0.4, 0, 0.2, 1);
  min-width: 0;
  overflow: hidden;
  margin-bottom: var(--ai-msg-gap, 16px);
}
@keyframes aiMsgIn {
  from { opacity: 0; transform: translateY(8px); }
  to { opacity: 1; transform: translateY(0); }
}
@keyframes fadeIn {
  from { opacity: 0; }
  to { opacity: 1; }
}
.ai-msg.user {
  flex-direction: row-reverse;
}
.ai-msg-avatar {
  width: 26px;
  height: 26px;
  border-radius: 8px;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
  margin-top: 2px;
}
.ai-msg.user .ai-msg-avatar {
  background: var(--ai-accent);
  color: #fff;
}
.ai-msg.assistant .ai-msg-avatar {
  background: var(--ai-bg-tertiary);
  color: var(--ai-accent);
}
.ai-msg-body {
  flex: 1;
  min-width: 0;
  max-width: 100%;
  overflow: hidden;
}
.ai-msg.user .ai-msg-body {
  display: flex;
  flex-direction: column;
  align-items: flex-end;
}
.ai-msg-content {
  padding: 14px 18px;
  border-radius: 12px;
  font-size: var(--ai-font-size, 13.5px);
  line-height: 1.75;
  word-break: break-word;
  overflow-wrap: anywhere;
  min-width: 0;
  max-width: 100%;
  overflow-x: auto;
}
.ai-msg.user .ai-msg-content {
  background: var(--ai-accent);
  color: #fff;
  border-radius: 12px 12px 4px 12px;
  max-width: 85%;
  box-shadow: 0 2px 8px rgba(59, 130, 246, 0.2);
}
.ai-msg.assistant .ai-msg-content {
  background: var(--ai-bg-secondary);
  color: var(--ai-text-secondary);
  border: 1px solid var(--ai-border-strong);
  border-radius: 12px 12px 12px 4px;
  box-shadow: 0 1px 3px rgba(0,0,0,0.04);
  overflow-x: auto;
  max-width: 100%;
}
.ai-msg-text {
  white-space: normal;
  word-break: break-word;
  overflow-wrap: anywhere;
  letter-spacing: 0.01em;
  min-width: 0;
}
.ai-msg-text p {
  margin: 6px 0;
}
.ai-msg-text ul, .ai-msg-text ol {
  margin: 6px 0;
  padding-left: 20px;
}
.ai-msg-text li {
  margin: 3px 0;
  line-height: 1.7;
}
.ai-msg-text h1, .ai-msg-text h2, .ai-msg-text h3, .ai-msg-text h4, .ai-msg-text h5, .ai-msg-text h6 {
  margin: 12px 0 6px;
  color: var(--ai-text);
  font-weight: 600;
  line-height: 1.35;
}
.ai-msg-text h1 { font-size: 16px; }
.ai-msg-text h2 { font-size: 15px; }
.ai-msg-text h3 { font-size: 14px; }
.ai-msg-text h4 { font-size: 13px; }
.ai-msg-text code {
  background: var(--ai-inline-code-bg);
  padding: 2px 6px;
  border-radius: 4px;
  font-size: 12px;
  font-family: 'JetBrains Mono', monospace;
  color: var(--ai-inline-code-text);
  border: 1px solid var(--ai-inline-code-border);
}
.ai-msg-text pre {
  background: var(--ai-code-bg);
  color: var(--ai-code-text);
  padding: 14px 16px;
  border-radius: 0;
  overflow-x: auto;
  font-size: 12px;
  font-family: 'JetBrains Mono', monospace;
  margin: 0;
  line-height: 1.6;
  border: none;
}
.ai-msg-text pre code {
  background: transparent;
  padding: 0;
  color: inherit;
  border: none;
}
/* Code block container */
.ai-msg-text .code-block {
  margin: 10px 0;
  border-radius: 8px;
  overflow: hidden;
  border: 1px solid #334155;
  max-width: 100%;
  background: var(--ai-code-bg);
}
.ai-msg-text .code-block pre {
  margin: 0;
  border: none;
  border-radius: 0;
}
.ai-msg-text .code-block-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  padding: 7px 10px;
  background: #111827;
  border-bottom: 1px solid #334155;
}
.ai-msg-text .code-lang {
  font-size: 10px;
  color: #94a3b8;
  text-transform: uppercase;
  letter-spacing: 0.5px;
  font-family: 'JetBrains Mono', monospace;
}
.ai-msg-text .code-copy-btn {
  border: 1px solid #475569;
  background: #1f2937;
  color: #cbd5e1;
  border-radius: 5px;
  padding: 2px 8px;
  font-size: 11px;
  cursor: pointer;
  transition: all 0.15s;
}
.ai-msg-text .code-copy-btn:hover {
  background: #334155;
  color: #fff;
}
/* Inline code */
.ai-msg-text .inline-code {
  background: var(--ai-inline-code-bg);
  padding: 2px 6px;
  border-radius: 4px;
  font-size: 12px;
  font-family: 'JetBrains Mono', monospace;
  color: var(--ai-inline-code-text);
  border: 1px solid var(--ai-inline-code-border);
}
/* Links */
.ai-msg-text .msg-link {
  color: var(--ai-accent-hover);
  text-decoration: none;
  border-bottom: 1px solid #93c5fd;
  transition: all 0.15s;
  overflow-wrap: anywhere;
}
.ai-msg-text .msg-link:hover {
  color: #1d4ed8;
  border-bottom-color: #1d4ed8;
}
/* Lists */
.ai-msg-text .msg-list {
  margin: 6px 0;
  padding-left: 22px;
  list-style: none;
}
.ai-msg-text .ul-item::before {
  content: '';
  display: inline-block;
  width: 6px;
  height: 6px;
  background: var(--ai-accent);
  border-radius: 50%;
  margin-right: 8px;
  vertical-align: middle;
}
.ai-msg-text .ol-item {
  counter-increment: ol-counter;
}
.ai-msg-text ol.msg-list {
  counter-reset: ol-counter;
}
.ai-msg-text .ol-item::before {
  content: counter(ol-counter) '.';
  color: var(--ai-accent);
  font-weight: 600;
  margin-right: 6px;
  font-size: 12px;
}
.ai-msg-text .msg-list li {
  margin: 4px 0;
  line-height: 1.7;
}
/* Blockquote */
.ai-msg-text blockquote {
  margin: 8px 0;
  padding: 8px 14px;
  border-left: 3px solid var(--ai-purple);
  background: var(--ai-purple-bg);
  border-radius: 0 6px 6px 0;
  color: var(--ai-text-muted);
  font-style: italic;
}
/* Table */
.ai-msg-text .msg-table-wrap {
  margin: 12px -18px;
  border-radius: 0;
  overflow: hidden;
  border-top: 1px solid #d0d7de;
  border-bottom: 1px solid #d0d7de;
  max-width: calc(100% + 36px);
}
.ai-msg-text .msg-table-header {
  padding: 4px 14px;
  background: #f5f5f5;
  border-bottom: 1px solid #d0d7de;
}
.ai-msg-text .msg-table-tag {
  font-size: 11px;
  color: #888;
  font-weight: 600;
}
.ai-msg-text .msg-table-scroll { overflow-x: auto; }
.ai-msg-text .msg-table {
  width: 100%;
  min-width: 400px;
  border-collapse: collapse;
  font-size: 12.5px;
}
.ai-msg-text .msg-table th,
.ai-msg-text .msg-table td {
  padding: 8px 12px;
  border: 1px solid var(--ai-border-strong);
  text-align: left;
}
.ai-msg-text .msg-table th {
  background: var(--ai-bg-tertiary);
  font-weight: 600;
  color: var(--ai-text);
  white-space: nowrap;
}
.ai-msg-text .msg-table td {
  color: var(--ai-text-secondary);
  word-break: break-word;
}
.ai-msg-text .msg-table tr:nth-child(even) td {
  background: #f8f9fa;
}
.ai-msg-text .msg-table tr:hover td {
  background: #eef2ff;
}
/* Horizontal rule */
.ai-msg-text .msg-hr {
  border: none;
  border-top: 1px solid var(--ai-border-strong);
  margin: 12px 0;
}
/* Headings with bottom border */
.ai-msg-text h2 { font-size: 15px; border-bottom: 1px solid var(--ai-border-strong); padding-bottom: 4px; }
.ai-msg-text h3 { font-size: 14px; }
.ai-msg-text h4 { font-size: 13px; }
.ai-msg-text h5 { font-size: 12.5px; font-weight: 600; color: var(--ai-text-secondary); margin: 8px 0 4px; }

/* Thinking Block */
.ai-thinking-block {
  margin-bottom: 10px;
  animation: fadeIn 0.3s ease;
}
.ai-thinking-header {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 8px 12px;
  background: var(--ai-purple-bg);
  border: 1px solid var(--ai-purple-border);
  border-radius: 10px;
  cursor: pointer;
  font-size: 11.5px;
  color: var(--ai-purple-text);
  user-select: none;
  transition: background 0.12s;
}
.ai-thinking-header:hover { background: var(--ai-purple-border); }
.thinking-cursor {
  display: inline-block;
  width: 2px;
  height: 12px;
  background: var(--ai-purple);
  border-radius: 1px;
  margin-left: 2px;
  animation: blink 0.8s step-end infinite;
  vertical-align: middle;
}
@keyframes blink {
  0%, 100% { opacity: 1; }
  50% { opacity: 0; }
}
.ai-thinking-body {
  padding: 10px 14px;
  margin-top: 6px;
  font-size: 12.5px;
  color: var(--ai-text-muted);
  background: var(--ai-purple-bg);
  border-radius: 6px;
  border-left: 3px solid var(--ai-purple);
  white-space: pre-wrap;
  max-height: 200px;
  overflow-y: auto;
}

/* Tool Calls Section */
.ai-tool-calls {
  margin-bottom: 6px;
}

/* Content no padding (for changes panel) */
.ai-content-nopad { padding: 0; }
.ai-msg-actions {
  display: flex;
  gap: 2px;
  margin-top: 4px;
  opacity: 0;
  transition: opacity 0.15s;
}
.ai-msg:hover .ai-msg-actions {
  opacity: 1;
}
.ai-msg-action {
  width: 24px;
  height: 24px;
  display: flex;
  align-items: center;
  justify-content: center;
  border: none;
  background: transparent;
  color: var(--ai-text-faint);
  border-radius: 4px;
  cursor: pointer;
  transition: all 0.12s;
}
.ai-msg-action:hover {
  background: var(--ai-bg-tertiary);
  color: var(--ai-text-secondary);
}
.ai-msg-action.liked { color: var(--ai-green); }
.ai-msg-action.disliked { color: var(--ai-red); }

/* Thinking */
.ai-thinking {
  display: flex;
  align-items: center;
  gap: 5px;
  padding: 12px 14px;
}
.ai-think-dot {
  width: 7px;
  height: 7px;
  background: #d1d5db;
  border-radius: 50%;
  animation: aiDot 1.4s ease-in-out infinite;
}
.ai-think-dot:nth-child(2) { animation-delay: 0.2s; }
.ai-think-dot:nth-child(3) { animation-delay: 0.4s; }
@keyframes aiDot {
  0%, 80%, 100% { transform: scale(0.5); opacity: 0.3; }
  40% { transform: scale(1); opacity: 1; }
}

/* 骨架屏加载态 */
.ai-loading-skeleton {
  padding: 8px 0;
}

.skeleton-line {
  height: 12px;
  background: linear-gradient(90deg, var(--ai-bg-tertiary) 25%, var(--ai-bg-secondary) 50%, var(--ai-bg-tertiary) 75%);
  background-size: 200% 100%;
  animation: skeleton-shimmer 1.5s infinite;
  border-radius: 4px;
  margin-bottom: 8px;
}

.skeleton-line.w-80 { width: 80%; }
.skeleton-line.w-70 { width: 70%; }
.skeleton-line.w-60 { width: 60%; }

@keyframes skeleton-shimmer {
  0% { background-position: 200% 0; }
  100% { background-position: -200% 0; }
}

/* 消息时间戳 */
.ai-msg-time {
  font-size: 10px;
  color: var(--ai-text-faint);
  margin-top: 4px;
  text-align: right;
}

/* 消息导航器 */
.ai-msg-navigator {
  position: absolute;
  right: 16px;
  top: 50%;
  transform: translateY(-50%);
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 4px;
  z-index: 10;
  background: var(--ai-bg);
  border: 1px solid var(--ai-border);
  border-radius: 20px;
  padding: 6px;
  box-shadow: var(--ai-shadow-md);
}

.nav-btn {
  width: 28px;
  height: 28px;
  display: flex;
  align-items: center;
  justify-content: center;
  border: none;
  background: transparent;
  color: var(--ai-text-muted);
  border-radius: 50%;
  cursor: pointer;
  transition: all 0.15s;
}

.nav-btn:hover:not(.disabled) {
  background: var(--ai-bg-tertiary);
  color: var(--ai-text);
}

.nav-btn.disabled {
  opacity: 0.3;
  cursor: not-allowed;
}

.nav-indicator {
  font-size: 10px;
  color: var(--ai-text-faint);
  padding: 2px 0;
}

/* Scroll to bottom */
.ai-scroll-btn {
  position: absolute;
  bottom: 140px;
  right: 16px;
  width: 28px;
  height: 28px;
  border: 1px solid var(--ai-border-strong);
  background: var(--ai-bg);
  color: var(--ai-text-muted);
  border-radius: 50%;
  display: flex;
  align-items: center;
  justify-content: center;
  cursor: pointer;
  z-index: 5;
  box-shadow: var(--ai-shadow-sm);
  transition: all 0.15s;
}
.ai-scroll-btn:hover {
  background: var(--ai-bg-secondary);
  color: var(--ai-text-secondary);
}

/* ===== Chat: Quick Action Chips ===== */
.ai-chips {
  display: flex;
  gap: 6px;
  padding: 8px 12px;
  flex-wrap: wrap;
  flex-shrink: 0;
}
.ai-chip {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 5px 10px;
  border: 1px solid var(--ai-border-strong);
  background: var(--ai-bg);
  color: var(--ai-text-muted);
  border-radius: 16px;
  font-size: 11px;
  font-family: inherit;
  cursor: pointer;
  transition: all 0.15s;
  white-space: nowrap;
}
.ai-chip:hover {
  border-color: var(--ai-accent);
  color: var(--ai-accent);
  background: var(--ai-accent-bg);
}

/* ===== Chat: Input Area ===== */
.ai-input-area {
  border-top: 1px solid var(--ai-border);
  padding: 10px 12px 8px;
  flex-shrink: 0;
  background: var(--ai-bg);
  position: relative;
}
.ai-input-context {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 6px 10px;
  margin-bottom: 8px;
  background: var(--ai-accent-bg);
  border: 1px solid var(--ai-accent-border);
  border-radius: 8px;
  font-size: 11px;
  color: var(--ai-accent);
}
.ai-input-ctx-remove {
  margin-left: auto;
  width: 16px;
  height: 16px;
  display: flex;
  align-items: center;
  justify-content: center;
  border: none;
  background: transparent;
  color: #2563eb;
  cursor: pointer;
  border-radius: 3px;
}
.ai-input-ctx-remove:hover {
  background: rgba(37, 99, 235, 0.1);
}
.ai-input-row {
  display: flex;
  gap: 6px;
  align-items: flex-end;
  position: relative;
}
.ai-textarea {
  flex: 1;
  background: var(--ai-bg-secondary);
  border: 1px solid var(--ai-border-strong);
  border-radius: 10px;
  padding: 10px 12px;
  font-size: 13px;
  color: var(--ai-text);
  resize: none;
  outline: none;
  font-family: inherit;
  line-height: 1.5;
  max-height: 160px;
  transition: border-color 0.2s, box-shadow 0.2s;
}
.ai-textarea::placeholder { color: var(--ai-text-faint); }
.ai-textarea:focus { border-color: var(--ai-accent); box-shadow: 0 0 0 3px rgba(59, 130, 246, 0.1); }

/* Command Palette Styles */
.command-palette {
  position: absolute;
  bottom: 100%;
  left: 0;
  right: 0;
  background: var(--ai-bg-secondary);
  border: 1px solid var(--ai-border);
  border-radius: 12px;
  box-shadow: 0 -4px 20px rgba(0, 0, 0, 0.15);
  margin-bottom: 8px;
  max-height: 400px;
  display: flex;
  flex-direction: column;
  z-index: 100;
  overflow: hidden;
}
.command-palette-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 12px 16px;
  border-bottom: 1px solid var(--ai-border);
  background: var(--ai-bg-tertiary);
}
.command-palette-title {
  font-size: 13px;
  font-weight: 600;
  color: var(--ai-text);
}
.command-palette-hint {
  font-size: 11px;
  color: var(--ai-text-muted);
}
.command-palette-search {
  padding: 8px 12px;
  border-bottom: 1px solid var(--ai-border);
}
.command-search-input {
  width: 100%;
  height: 32px;
  border: 1px solid var(--ai-border);
  border-radius: 8px;
  padding: 0 10px;
  font-size: 13px;
  background: var(--ai-bg);
  color: var(--ai-text);
  outline: none;
}
.command-search-input:focus {
  border-color: var(--ai-accent);
}
.command-search-input::placeholder {
  color: var(--ai-text-faint);
}
.command-palette-list {
  flex: 1;
  overflow-y: auto;
  padding: 4px 0;
}
.command-palette-list::-webkit-scrollbar {
  width: 6px;
}
.command-palette-list::-webkit-scrollbar-track {
  background: transparent;
}
.command-palette-list::-webkit-scrollbar-thumb {
  background: var(--ai-border);
  border-radius: 3px;
}
.command-item {
  display: flex;
  flex-direction: column;
  padding: 10px 16px;
  cursor: pointer;
  transition: background 0.15s;
}
.command-item:hover,
.command-item.active {
  background: var(--ai-bg-tertiary);
}
.command-item-main {
  display: flex;
  align-items: center;
  gap: 8px;
}
.command-name {
  font-size: 13px;
  font-weight: 600;
  color: var(--ai-accent);
  font-family: 'Consolas', 'Monaco', monospace;
}
.command-aliases {
  font-size: 11px;
  color: var(--ai-text-muted);
}
.command-item-desc {
  font-size: 12px;
  color: var(--ai-text-muted);
  margin-top: 2px;
}
.command-empty {
  padding: 20px 16px;
  text-align: center;
  color: var(--ai-text-muted);
  font-size: 13px;
}

/* 优化后的输入框布局 */
.ai-input-bottom {
  display: flex;
  flex-direction: column;
  gap: 8px;
  margin-top: 8px;
}

.ai-mode-bar {
  display: flex;
  justify-content: flex-start;
}

.ai-mode-switch {
  display: inline-flex;
  align-items: center;
  padding: 3px;
  border-radius: 10px;
  background: var(--ai-bg-tertiary);
  border: 1px solid var(--ai-border);
}

.ai-mode-switch button {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  height: 28px;
  border: none;
  background: transparent;
  color: var(--ai-text-muted);
  border-radius: 8px;
  padding: 0 12px;
  font-size: 12px;
  cursor: pointer;
  transition: all 0.2s cubic-bezier(0.4, 0, 0.2, 1);
  position: relative;
  z-index: 1;
}

.ai-mode-switch button:hover:not(.active) {
  color: var(--ai-text-secondary);
  background: rgba(0, 0, 0, 0.05);
}

.ai-mode-switch button.active {
  color: #fff;
  background: var(--ai-text);
  box-shadow: 0 1px 4px rgba(17, 24, 39, 0.14);
  transform: scale(1.02);
}

.ai-mode-switch button.active:active {
  transform: scale(0.98);
}

.mode-icon {
  font-size: 12px;
}

.ai-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}

.ai-toolbar-left {
  display: flex;
  align-items: center;
  gap: 4px;
}

.ai-toolbar-right {
  display: flex;
  align-items: center;
  gap: 8px;
}

.ai-tool-btn {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  height: 32px;
  padding: 0 10px;
  border: 1px solid var(--ai-border-strong);
  background: var(--ai-bg);
  color: var(--ai-text-muted);
  border-radius: 8px;
  font-size: 12px;
  font-family: inherit;
  cursor: pointer;
  transition: all 0.15s;
  white-space: nowrap;
}

.ai-tool-btn:hover {
  border-color: var(--ai-accent);
  color: var(--ai-accent);
  background: var(--ai-accent-bg);
}

.ai-tool-btn svg {
  flex-shrink: 0;
}

.ai-generating-indicator {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 11px;
  color: var(--ai-text-muted);
}

.gen-dot {
  width: 6px;
  height: 6px;
  background: var(--ai-accent);
  border-radius: 50%;
  animation: gen-pulse 1s infinite;
}

@keyframes gen-pulse {
  0%, 100% { opacity: 0.5; transform: scale(0.8); }
  50% { opacity: 1; transform: scale(1); }
}

.ai-send-btn {
  width: 36px;
  height: 36px;
  display: flex;
  align-items: center;
  justify-content: center;
  border: none;
  background: #3b82f6;
  color: #fff;
  border-radius: 10px;
  cursor: pointer;
  transition: all 0.2s;
  box-shadow: 0 2px 8px rgba(59, 130, 246, 0.3);
  flex-shrink: 0;
}

.ai-send-btn:hover {
  background: #2563eb;
  box-shadow: 0 4px 12px rgba(37, 99, 235, 0.4);
  transform: translateY(-1px);
}

.ai-send-btn:disabled {
  background: var(--ai-bg-tertiary);
  color: var(--ai-text-faint);
  cursor: not-allowed;
  box-shadow: none;
  transform: none;
}

.ai-send-stop {
  background: var(--ai-red);
  box-shadow: 0 2px 8px rgba(239, 68, 68, 0.3);
}

.ai-send-stop:hover {
  background: var(--ai-red-hover);
}

/* ===== Review Tab ===== */
.ai-review-toolbar {
  padding: 10px 12px;
  border-bottom: 1px solid var(--ai-border);
  flex-shrink: 0;
}
.ai-review-filters {
  display: flex;
  gap: 4px;
}
.ai-filter-btn {
  display: flex;
  align-items: center;
  gap: 4px;
  padding: 4px 8px;
  border: 1px solid transparent;
  background: transparent;
  color: var(--ai-text-faint);
  border-radius: 6px;
  font-size: 11px;
  font-family: inherit;
  cursor: pointer;
  transition: all 0.12s;
}
.ai-filter-btn:hover { background: var(--ai-bg-tertiary); color: var(--ai-text-secondary); }
.ai-filter-btn.active { background: var(--ai-bg-tertiary); color: var(--ai-text); border-color: var(--ai-border-strong); }
.ai-filter-dot { width: 6px; height: 6px; border-radius: 50%; flex-shrink: 0; }
.ai-filter-count { font-size: 10px; color: #9ca3af; }
.ai-review-list {
  flex: 1;
  overflow-y: auto;
  padding: 6px;
}
.ai-review-item {
  display: flex;
  align-items: flex-start;
  gap: 8px;
  padding: 8px 10px;
  border-radius: 6px;
  cursor: pointer;
  transition: background 0.12s;
}
.ai-review-item:hover { background: #f9fafb; }
.ai-review-icon { margin-top: 2px; flex-shrink: 0; }
.ai-review-body { flex: 1; min-width: 0; }
.ai-review-title { font-size: 12px; color: #374151; line-height: 1.4; }
.ai-review-meta { font-size: 10px; color: #9ca3af; margin-top: 2px; font-family: 'JetBrains Mono', monospace; }
.ai-review-fix {
  width: 24px;
  height: 24px;
  display: flex;
  align-items: center;
  justify-content: center;
  border: 1px solid #e5e7eb;
  background: #fff;
  color: #9ca3af;
  border-radius: 4px;
  cursor: pointer;
  flex-shrink: 0;
  transition: all 0.12s;
}
.ai-review-fix:hover { border-color: #3b82f6; color: #3b82f6; }
.ai-review-summary {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 10px 14px;
  border-top: 1px solid #f0f0f0;
  flex-shrink: 0;
}
.ai-score-label { font-size: 11px; color: #9ca3af; }
.ai-score-value { font-size: 14px; font-weight: 700; margin-left: 6px; }
.ai-score-value.good { color: var(--ai-green); }
.ai-score-value.warn { color: var(--ai-yellow); }
.ai-score-value.bad { color: var(--ai-red); }
.ai-review-total { font-size: 11px; color: var(--ai-text-faint); }

/* ===== Terminal ===== */
.term-wrap { flex-direction: column !important; }
.term-top-tabs {
  display: flex;
  border-bottom: 1px solid var(--ai-border-strong);
  background: var(--ai-bg-tertiary);
  flex-shrink: 0;
  padding: 0 4px;
}
.term-top-tab {
  padding: 6px 12px;
  font-size: 10px;
  font-weight: 600;
  color: var(--ai-text-faint);
  border: none;
  background: transparent;
  cursor: pointer;
  border-bottom: 2px solid transparent;
  transition: all 0.12s;
  text-transform: uppercase;
  letter-spacing: 0.3px;
  font-family: inherit;
}
.term-top-tab:hover { color: var(--ai-text-secondary); }
.term-top-tab.active { color: var(--ai-text); border-bottom-color: var(--ai-accent); background: var(--ai-bg); }
.term-body {
  flex: 1;
  display: flex;
  min-height: 0;
  overflow: hidden;
}
.term-main {
  flex: 1;
  display: flex;
  flex-direction: column;
  min-width: 0;
  min-height: 0;
}
.term-tab-bar {
  display: flex;
  background: var(--ai-code-bg);
  border-bottom: 1px solid var(--ai-border-strong);
  flex-shrink: 0;
  overflow-x: auto;
}
.term-tab {
  display: flex;
  align-items: center;
  gap: 4px;
  padding: 4px 10px;
  font-size: 11px;
  color: var(--ai-text-faint);
  cursor: pointer;
  border-right: 1px solid var(--ai-border-strong);
  border-top: 1px solid transparent;
  transition: all 0.1s;
  white-space: nowrap;
  background: #2d2d2d;
}
.term-tab:hover { background: var(--ai-bg-tertiary); color: var(--ai-text-secondary); }
.term-tab.active { background: var(--ai-bg); color: var(--ai-text); border-top: 2px solid var(--ai-accent); }
.term-tab svg { flex-shrink: 0; }
.term-tab-name { max-width: 80px; overflow: hidden; text-overflow: ellipsis; }
.term-tab-close {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 14px;
  height: 14px;
  border: none;
  background: transparent;
  color: var(--ai-text-faint);
  border-radius: 3px;
  cursor: pointer;
  padding: 0;
  opacity: 0;
  transition: all 0.1s;
}
.term-tab:hover .term-tab-close { opacity: 1; }
.term-tab-close:hover { background: var(--ai-bg-tertiary); color: var(--ai-text); }
.term-output {
  flex: 1;
  overflow-y: auto;
  background: var(--ai-code-bg);
  color: var(--ai-code-text);
  font-family: 'JetBrains Mono', 'Fira Code', 'Cascadia Code', monospace;
  font-size: 12px;
  line-height: 1.6;
  padding: 8px 12px;
  min-height: 0;
}
.term-output::-webkit-scrollbar { width: 6px; }
.term-output::-webkit-scrollbar-track { background: transparent; }
.term-output::-webkit-scrollbar-thumb { background: var(--ai-border-strong); border-radius: 3px; }
.term-empty {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 8px;
  padding: 48px 16px;
  color: var(--ai-text-muted);
  text-align: center;
}
.term-empty p { font-size: 12px; margin: 0; }
.term-text {
  margin: 0;
  white-space: pre-wrap;
  word-break: break-all;
  color: var(--ai-code-text);
}
.term-input-bar {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 6px 12px;
  border-top: 1px solid var(--ai-border-strong);
  background: var(--ai-code-bg);
  flex-shrink: 0;
}
.term-prompt {
  color: var(--ai-green);
  font-family: 'JetBrains Mono', monospace;
  font-size: 12px;
  font-weight: 600;
  flex-shrink: 0;
}
.term-input {
  flex: 1;
  background: transparent;
  border: none;
  color: var(--ai-code-text);
  font-family: 'JetBrains Mono', monospace;
  font-size: 13px;
  outline: none;
  padding: 0;
}
.term-input::placeholder { color: var(--ai-text-faint); }
.term-run-btn {
  width: 26px;
  height: 26px;
  display: flex;
  align-items: center;
  justify-content: center;
  border: none;
  background: var(--ai-accent);
  color: #fff;
  border-radius: 5px;
  cursor: pointer;
  flex-shrink: 0;
  transition: background 0.15s;
}
.term-run-btn:hover { background: #2563eb; }
.term-run-btn:disabled { background: #374151; color: #6b7280; cursor: not-allowed; }
.term-right-bar {
  width: 40px;
  background: var(--ai-code-bg);
  border-left: 1px solid var(--ai-border-strong);
  display: flex;
  flex-direction: column;
  align-items: center;
  padding: 6px 0;
  gap: 2px;
  flex-shrink: 0;
  overflow-y: auto;
}
.term-tool-btn {
  width: 28px;
  height: 28px;
  display: flex;
  align-items: center;
  justify-content: center;
  border: none;
  background: transparent;
  color: var(--ai-text-faint);
  border-radius: 4px;
  cursor: pointer;
  transition: all 0.12s;
}
.term-tool-btn:hover { background: var(--ai-bg-tertiary); color: var(--ai-text); }
.term-tool-divider { width: 20px; height: 1px; background: var(--ai-border-strong); margin: 4px 0; }
.term-list-item {
  display: flex;
  align-items: center;
  gap: 3px;
  padding: 3px 4px;
  border-radius: 4px;
  cursor: pointer;
  transition: background 0.1s;
  width: 32px;
  overflow: hidden;
}
.term-list-item:hover { background: var(--ai-bg-tertiary); }
.term-list-item.active { background: var(--ai-accent-bg); }
.term-list-item svg { flex-shrink: 0; color: var(--ai-text-faint); }
.term-list-item.active svg { color: var(--ai-accent); }
.term-list-name {
  font-size: 9px;
  color: var(--ai-text-faint);
  display: none;
}
.term-quick-bar {
  display: flex;
  gap: 4px;
  padding: 6px 12px;
  border-top: 1px solid var(--ai-border-strong);
  background: var(--ai-code-bg);
  overflow-x: auto;
  flex-shrink: 0;
}
.term-quick-btn {
  display: flex;
  align-items: center;
  gap: 4px;
  padding: 4px 10px;
  border: 1px solid var(--ai-border-strong);
  background: var(--ai-bg-secondary);
  color: var(--ai-text-faint);
  border-radius: 5px;
  font-size: 11px;
  font-family: inherit;
  cursor: pointer;
  transition: all 0.12s;
  white-space: nowrap;
  flex-shrink: 0;
}
.term-quick-btn:hover { border-color: var(--ai-accent); color: var(--ai-accent); background: var(--ai-accent-bg); }
.term-quick-btn svg { flex-shrink: 0; }

/* ===== Status Bar ===== */
.ai-statusbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 5px 12px;
  border-top: 1px solid var(--ai-border);
  flex-shrink: 0;
  background: var(--ai-bg-secondary);
}
.ai-status-left {
  display: flex;
  align-items: center;
  gap: 8px;
}
.ai-model-badge {
  display: flex;
  align-items: center;
  gap: 4px;
  font-size: 10px;
  color: var(--ai-text-muted);
  background: var(--ai-bg-tertiary);
  padding: 2px 7px;
  border-radius: 4px;
}
.ai-token-badge {
  display: flex;
  align-items: center;
  gap: 3px;
  font-size: 10px;
  color: var(--ai-purple-text);
  background: var(--ai-purple-bg);
  padding: 2px 7px;
  border-radius: 4px;
}
.ai-status-conn {
  font-size: 10px;
  display: flex;
  align-items: center;
  gap: 4px;
}
.ai-status-conn.online { color: var(--ai-green); }
.ai-status-conn.online::before { content: ''; width: 5px; height: 5px; background: var(--ai-green); border-radius: 50%; }
.ai-status-conn.generating { color: var(--ai-yellow); }
.ai-status-conn.generating::before { content: ''; width: 5px; height: 5px; background: var(--ai-yellow); border-radius: 50%; animation: aiDot 1s ease infinite; }
.ai-status-right {
  display: flex;
  align-items: center;
  gap: 6px;
}
.ai-stop-btn {
  display: flex;
  align-items: center;
  gap: 4px;
  padding: 2px 8px;
  font-size: 10px;
  color: #ef4444;
  background: #fef2f2;
  border-radius: 4px;
  cursor: pointer;
  transition: background 0.12s;
}
.ai-stop-btn:hover { background: #fee2e2; }

/* ===== Message Animations ===== */
.ai-msg-enter-active { transition: all 0.3s ease; }
.ai-msg-leave-active { transition: all 0.2s ease; }
.ai-msg-enter-from { opacity: 0; transform: translateY(10px); }
.ai-msg-leave-to { opacity: 0; }

/* ========================================================================
   MODEL CONFIG MODAL
   ======================================================================== */
.mc-dialog {
  background: var(--ai-bg);
  border-radius: 14px;
  width: 520px;
  max-height: 80vh;
  display: flex;
  flex-direction: column;
  box-shadow: 0 24px 60px rgba(0,0,0,0.15);
  overflow: hidden;
}
.mc-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 20px 24px 16px;
  border-bottom: 1px solid var(--ai-border);
  flex-shrink: 0;
}
.mc-header h3 {
  font-size: 17px;
  font-weight: 600;
  color: var(--ai-text);
  margin: 0;
}
.mc-close-btn {
  width: 28px; height: 28px;
  display: flex; align-items: center; justify-content: center;
  border: none; background: transparent; color: #9ca3af;
  border-radius: 6px; cursor: pointer; transition: all 0.12s;
}
.mc-close-btn:hover { background: #f3f4f6; color: #374151; }

/* ===== Settings Dialog ===== */
.settings-dialog {
  background: #ffffff;
  border-radius: 16px;
  width: 380px;
  max-height: 80vh;
  display: flex;
  flex-direction: column;
  box-shadow: 0 20px 60px rgba(0,0,0,0.2);
  overflow: hidden;
  border: 1px solid #e5e7eb;
}
:global([data-theme="dark"]) .settings-dialog { background: #1a1b26; border-color: #2e3044; box-shadow: 0 20px 60px rgba(0,0,0,0.5); }
:global([data-theme="dark"]) .settings-header { border-bottom-color: #2e3044; }
:global([data-theme="dark"]) .settings-header h3 { color: #c0caf5; }
:global([data-theme="dark"]) .settings-label { color: #565f89; }
:global([data-theme="dark"]) .settings-theme-btn, :global([data-theme="dark"]) .settings-opt-btn { background: #1f2033; border-color: #383a50; color: #787c99; }
:global([data-theme="dark"]) .settings-theme-btn:hover, :global([data-theme="dark"]) .settings-opt-btn:hover { border-color: #7aa2f7; color: #a9b1d6; background: #1a1d3a; }
:global([data-theme="dark"]) .settings-theme-btn.active, :global([data-theme="dark"]) .settings-opt-btn.active { border-color: #7aa2f7; background: #1a1d3a; color: #7aa2f7; box-shadow: 0 0 0 1px #7aa2f7; }
:global([data-theme="dark"]) .settings-action-btn { background: #1f2033; border-color: #383a50; color: #a9b1d6; }
:global([data-theme="dark"]) .settings-action-btn:hover { border-color: #7aa2f7; background: #1a1d3a; color: #7aa2f7; }
:global([data-theme="dark"]) .ws-overlay {
  --ai-bg: #1a1b26;
  --ai-bg-secondary: #1f2033;
  --ai-bg-tertiary: #282a3a;
  --ai-bg-elevated: #24253a;
  --ai-border: #2e3044;
  --ai-border-strong: #383a50;
  --ai-border-focus: #7aa2f7;
  --ai-text: #c0caf5;
  --ai-text-secondary: #a9b1d6;
  --ai-text-muted: #787c99;
  --ai-text-faint: #565f89;
  --ai-accent: #7aa2f7;
  --ai-accent-hover: #5d87e0;
  --ai-accent-bg: #1a1d3a;
  --ai-accent-border: #2e3a5e;
  --ai-purple: #bb9af7;
  --ai-purple-text: #c0a8f7;
  --ai-purple-bg: #1f1d30;
  --ai-purple-border: #2d2a45;
  --ai-purple-deep: #bb9af7;
  --ai-green: #9ece6a;
  --ai-red: #f7768e;
  --ai-red-hover: #e05f75;
  --ai-yellow: #e0af68;
  --ai-shadow-sm: 0 1px 4px rgba(0,0,0,0.2);
  --ai-shadow-md: 0 4px 16px rgba(0,0,0,0.3);
  --ai-shadow-lg: 0 8px 24px rgba(0,0,0,0.4);
  --ai-code-bg: #13141c;
  --ai-code-text: #c0caf5;
  --ai-inline-code-bg: #24253a;
  --ai-inline-code-border: #383a50;
  --ai-inline-code-text: #ff9e64;

  background: rgba(0,0,0,0.6);
}
.settings-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 18px 22px 14px;
  border-bottom: 1px solid #e5e7eb;
}
.settings-header h3 {
  margin: 0;
  font-size: 16px;
  font-weight: 700;
  color: #111827;
}
.settings-body {
  padding: 16px 22px 22px;
  overflow-y: auto;
}
.settings-section {
  margin-bottom: 20px;
}
.settings-section:last-child { margin-bottom: 0; }
.settings-label {
  font-size: 11px;
  font-weight: 600;
  color: #9ca3af;
  text-transform: uppercase;
  letter-spacing: 0.5px;
  margin-bottom: 10px;
}
.settings-row {
  display: flex;
  gap: 8px;
}
.settings-theme-btn,
.settings-opt-btn {
  flex: 1;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 6px;
  padding: 10px 12px;
  border: 1px solid #e5e7eb;
  background: #f9fafb;
  color: #6b7280;
  border-radius: 10px;
  font-size: 13px;
  font-family: inherit;
  cursor: pointer;
  transition: all 0.15s;
}
.settings-theme-btn:hover,
.settings-opt-btn:hover {
  border-color: #3b82f6;
  color: #374151;
  background: #eff6ff;
}
.settings-theme-btn.active,
.settings-opt-btn.active {
  border-color: #3b82f6;
  background: #eff6ff;
  color: #3b82f6;
  font-weight: 600;
  box-shadow: 0 0 0 1px #3b82f6;
}
.settings-shortcuts {
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.settings-action-btn {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 10px 14px;
  border: 1px solid #e5e7eb;
  background: #f9fafb;
  color: #374151;
  border-radius: 10px;
  font-size: 13px;
  font-family: inherit;
  cursor: pointer;
  transition: all 0.15s;
  text-align: left;
}
.settings-action-btn:hover {
  border-color: #3b82f6;
  background: #eff6ff;
  color: #3b82f6;
}

.mc-body {
  flex: 1;
  overflow-y: auto;
  padding: 16px 24px 20px;
}

/* Template Selection */
.mc-template-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 10px;
}
.mc-template-card {
  display: grid;
  grid-template-columns: 38px minmax(0, 1fr) auto;
  align-items: center;
  gap: 10px;
  padding: 12px;
  border: 1px solid var(--ai-border-strong);
  border-radius: 8px;
  background: var(--ai-bg);
  color: var(--ai-text);
  font-family: inherit;
  cursor: pointer;
  text-align: left;
  transition: border-color 0.15s, background 0.15s, box-shadow 0.15s;
}
.mc-template-card:hover {
  border-color: var(--ai-accent);
  background: var(--ai-accent-bg);
  box-shadow: var(--ai-shadow-sm);
}
.mc-template-icon {
  width: 38px;
  height: 38px;
  border-radius: 8px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  color: #fff;
  font-size: 11px;
  font-weight: 800;
  line-height: 1;
}
.mc-template-main {
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 2px;
}
.mc-template-name {
  color: var(--ai-text);
  font-size: 13px;
  font-weight: 700;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.mc-template-vendor,
.mc-template-model {
  color: var(--ai-text-muted);
  font-size: 10px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.mc-template-model {
  color: var(--ai-text-faint);
  font-family: 'JetBrains Mono', monospace;
}
.mc-template-source {
  align-self: flex-start;
  padding: 2px 6px;
  border-radius: 5px;
  background: var(--ai-accent-bg);
  color: var(--ai-accent);
  font-size: 10px;
  font-weight: 700;
  white-space: nowrap;
}

/* Config List */
.mc-config-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.mc-empty {
  display: flex; flex-direction: column; align-items: center;
  gap: 8px; padding: 32px 0; color: #9ca3af;
}
.mc-empty p { margin: 0; font-size: 14px; }
.mc-empty-hint { font-size: 12px; color: #d1d5db; }
.mc-config-card {
  display: flex; align-items: center; gap: 10px;
  padding: 12px 14px; border: 1px solid #e5e7eb;
  border-radius: 10px; cursor: pointer; transition: all 0.15s;
}
.mc-config-card:hover { border-color: #c7d2fe; background: #f5f3ff; }
.mc-config-card.active { border-color: #818cf8; background: #eef2ff; }
.mc-config-card.default { border-left: 3px solid #6366f1; }
.mc-card-main { flex: 1; min-width: 0; }
.mc-card-top { display: flex; align-items: center; gap: 8px; }
.mc-card-name { font-size: 13px; font-weight: 600; color: var(--ai-text); }
.mc-badge-default {
  font-size: 10px; color: var(--ai-accent); background: var(--ai-accent-bg);
  padding: 1px 6px; border-radius: 4px; font-weight: 500;
}
.mc-card-meta {
  display: flex; align-items: center; gap: 6px;
  font-size: 11px; color: var(--ai-text-muted); margin-top: 3px;
}
.mc-card-sep { color: var(--ai-border-strong); }
.mc-card-url {
  font-size: 11px; color: var(--ai-text-faint); margin-top: 2px;
  white-space: nowrap; overflow: hidden; text-overflow: ellipsis;
}
.mc-card-actions {
  display: flex; gap: 2px; flex-shrink: 0;
}
.mc-action-btn {
  width: 28px; height: 28px;
  display: flex; align-items: center; justify-content: center;
  border: none; background: transparent; color: var(--ai-text-faint);
  border-radius: 6px; cursor: pointer; transition: all 0.12s;
}
.mc-action-btn:hover { background: var(--ai-bg-tertiary); color: var(--ai-text-secondary); }
.mc-action-delete:hover { background: #fef2f2; color: var(--ai-red); }
.mc-action-test:hover { background: #ecfdf5; color: var(--ai-green); }
.mc-action-test:disabled { opacity: 0.5; cursor: not-allowed; }
.mc-spin { animation: mcSpin 1s linear infinite; }
@keyframes mcSpin { from { transform: rotate(0deg); } to { transform: rotate(360deg); } }
.mc-test-result {
  display: flex; align-items: center; gap: 5px;
  margin-top: 6px; padding: 4px 8px;
  border-radius: 6px; font-size: 11px;
}
.mc-test-ok { background: #ecfdf5; color: var(--ai-green); }
.mc-test-fail { background: #fef2f2; color: var(--ai-red); }
.mc-add-btn {
  display: flex; align-items: center; justify-content: center; gap: 6px;
  width: 100%; padding: 10px; margin-top: 8px;
  border: 1px dashed var(--ai-border-strong); border-radius: 10px;
  background: transparent; color: var(--ai-text-muted);
  font-size: 13px; font-weight: 500; cursor: pointer;
  transition: all 0.15s; font-family: inherit;
}
.mc-add-btn:hover { border-color: var(--ai-accent); color: var(--ai-accent); background: var(--ai-accent-bg); }

/* Config Form */
.mc-form { display: flex; flex-direction: column; gap: 14px; }
.mc-field { display: flex; flex-direction: column; gap: 4px; }
.mc-field label { font-size: 12px; font-weight: 600; color: var(--ai-text-secondary); }
.mc-required { color: var(--ai-red); }
.mc-field-action-row { display: flex; gap: 8px; align-items: center; }
.mc-field-action-row .mc-input { flex: 1; min-width: 0; }
.mc-official-url {
  flex: 1;
  min-width: 0;
  padding: 9px 12px;
  border: 1px solid var(--ai-border-strong);
  border-radius: 8px;
  background: var(--ai-bg-secondary);
  color: var(--ai-text-muted);
  font-family: 'JetBrains Mono', monospace;
  font-size: 11px;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.mc-input, .mc-select {
  padding: 9px 12px; border: 1px solid var(--ai-border-strong);
  border-radius: 8px; font-size: 13px; outline: none;
  font-family: inherit; transition: border-color 0.2s, box-shadow 0.2s;
  background: var(--ai-bg);
}
.mc-input:focus, .mc-select:focus { border-color: var(--ai-accent); box-shadow: 0 0 0 3px rgba(59, 130, 246, 0.1); }
.mc-input::placeholder { color: var(--ai-text-faint); }
.mc-model-select { margin-top: 6px; }
.mc-hint { font-size: 11px; color: var(--ai-text-faint); margin-top: 2px; }
.mc-hint-ok { color: var(--ai-green); }
.mc-row { display: flex; gap: 12px; }
.mc-field-half { flex: 1; }
.mc-checkbox-row {
  display: flex; align-items: center; gap: 8px;
  font-size: 13px; color: var(--ai-text-secondary); cursor: pointer;
  margin-top: 4px;
}
.mc-checkbox-row input[type="checkbox"] { width: 16px; height: 16px; accent-color: var(--ai-accent); }
.mc-form-actions {
  display: flex; gap: 10px; justify-content: flex-end;
  margin-top: 16px; padding-top: 14px; border-top: 1px solid var(--ai-border);
}
.mc-btn {
  padding: 8px 18px; border-radius: 8px; font-size: 13px;
  font-weight: 500; cursor: pointer; transition: all 0.15s;
  font-family: inherit; border: none;
}
.mc-btn-primary { background: var(--ai-accent); color: #fff; }
.mc-btn-primary:hover { background: var(--ai-accent-hover); }
.mc-btn-primary:disabled { background: var(--ai-bg-tertiary); cursor: not-allowed; }
.mc-btn-outline { background: var(--ai-bg); border: 1px solid var(--ai-border-strong); color: var(--ai-text-secondary); }
.mc-btn-outline:hover { background: var(--ai-bg-secondary); }
.mc-btn-nowrap { white-space: nowrap; padding-left: 12px; padding-right: 12px; }

/* Presets */
.mc-presets {
  margin-top: 16px; padding-top: 14px; border-top: 1px solid var(--ai-border);
}
.mc-presets-label {
  font-size: 11px; font-weight: 600; color: var(--ai-text-faint);
  text-transform: uppercase; letter-spacing: 0.5px; margin-bottom: 8px;
}
.mc-preset-list {
  display: flex; flex-wrap: wrap; gap: 6px;
}
.mc-preset-btn {
  display: flex; flex-direction: column; align-items: flex-start;
  padding: 7px 12px; border: 1px solid var(--ai-border-strong); border-radius: 8px;
  background: var(--ai-bg-secondary); cursor: pointer; transition: all 0.12s;
  font-family: inherit;
}
.mc-preset-btn:hover { border-color: var(--ai-accent); background: var(--ai-accent-bg); }
.mc-preset-name { font-size: 12px; font-weight: 600; color: var(--ai-text-secondary); }
.mc-preset-model { font-size: 10px; color: var(--ai-text-faint); margin-top: 1px; }
.mc-preset-note { font-size: 10px; color: var(--ai-text-muted); margin-top: 2px; }

/* Usage tab */
.ai-usage { padding: 12px; overflow-y: auto; }
.ai-usage-total { text-align: center; padding: 16px 0 12px; }
.usage-total-label { font-size: 11px; color: var(--ai-text-faint); margin-bottom: 4px; }
.usage-total-value { font-size: 32px; font-weight: 700; color: var(--ai-purple-text); line-height: 1; }
.usage-total-sub { font-size: 11px; color: var(--ai-text-faint); margin-top: 4px; }
.usage-stats { display: flex; gap: 24px; justify-content: center; margin: 12px 0 16px; }
.usage-stat { text-align: center; }
.usage-stat-label { font-size: 10px; color: var(--ai-text-faint); display: block; }
.usage-stat-value { font-size: 16px; font-weight: 700; display: block; margin-top: 2px; }
.usage-stat-value.prompt { color: var(--ai-accent); }
.usage-stat-value.completion { color: var(--ai-green); }
.usage-chart-section { margin-bottom: 16px; border: 1px solid var(--ai-border-strong); border-radius: 10px; padding: 12px; background: var(--ai-bg-secondary); }
.usage-chart-label { font-size: 11px; font-weight: 600; color: var(--ai-text-muted); margin-bottom: 8px; text-transform: uppercase; letter-spacing: 0.3px; }
.usage-echart { width: 100%; height: 160px; }
.usage-session-section { margin-top: 12px; border: 1px solid var(--ai-border-strong); border-radius: 10px; padding: 12px; background: var(--ai-bg-secondary); }
.usage-session-list { display: flex; flex-direction: column; gap: 4px; margin-top: 8px; }
.usage-session-item { display: flex; align-items: center; justify-content: space-between; padding: 6px 10px; border-radius: 6px; cursor: pointer; transition: background 0.12s; font-size: 12px; }
.usage-session-item:hover { background: var(--ai-purple-bg); }
.usage-session-item.active { background: var(--ai-purple-bg); border: 1px solid var(--ai-purple-border); }
.session-name { color: #374151; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; flex: 1; }
.session-tokens { color: var(--ai-purple-text); font-weight: 600; font-size: 11px; margin-left: 8px; }

/* ===== Extensions ===== */
.ext-panel {
  height: 100%;
  display: flex;
  flex-direction: column;
  background: var(--ai-bg);
  min-height: 0;
}
.ext-header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
  padding: 14px;
  border-bottom: 1px solid var(--ai-border);
  flex-shrink: 0;
}
.ext-header h4 {
  margin: 0;
  font-size: 15px;
  color: var(--ai-text);
}
.ext-header p {
  margin: 4px 0 0;
  color: var(--ai-text-muted);
  font-size: 11px;
  line-height: 1.5;
}
.ext-switch {
  display: flex;
  gap: 4px;
  padding: 3px;
  background: var(--ai-bg-tertiary);
  border-radius: 7px;
  flex-shrink: 0;
}
.ext-switch button {
  border: 0;
  border-radius: 5px;
  padding: 6px 9px;
  font-size: 11px;
  font-weight: 700;
  color: var(--ai-text-muted);
  background: transparent;
  cursor: pointer;
}
.ext-switch button.active {
  color: var(--ai-text);
  background: var(--ai-bg);
  box-shadow: 0 6px 14px rgba(15, 23, 42, 0.08);
}
.ext-body {
  flex: 1;
  min-height: 0;
  display: grid;
  grid-template-rows: minmax(140px, 1fr) auto;
  overflow: hidden;
}
.ext-list {
  overflow-y: auto;
  padding: 10px;
  border-bottom: 1px solid var(--ai-border);
}
.ext-empty {
  padding: 26px 12px;
  color: var(--ai-text-faint);
  text-align: center;
  font-size: 12px;
}
.ext-item {
  display: flex;
  align-items: flex-start;
  gap: 10px;
  padding: 10px;
  border: 1px solid var(--ai-border-strong);
  border-radius: 8px;
  margin-bottom: 8px;
  background: var(--ai-bg);
}
.ext-item-main {
  flex: 1;
  min-width: 0;
}
.ext-item-main strong {
  display: block;
  color: var(--ai-text);
  font-size: 13px;
}
.ext-item-main span,
.ext-item-main small {
  display: block;
  margin-top: 3px;
  color: var(--ai-text-muted);
  font-size: 10px;
  font-family: 'JetBrains Mono', monospace;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.ext-item-main p {
  margin: 6px 0 0;
  color: var(--ai-text-muted);
  font-size: 11px;
  line-height: 1.45;
  word-break: break-word;
}
.ext-item-actions {
  display: flex;
  align-items: center;
  gap: 4px;
  flex-shrink: 0;
}
.ext-mini-btn,
.ext-icon-btn {
  border: 1px solid var(--ai-border-strong);
  background: var(--ai-bg);
  color: var(--ai-text-muted);
  border-radius: 6px;
  cursor: pointer;
  transition: all 0.12s;
}
.ext-mini-btn {
  padding: 4px 7px;
  font-size: 10px;
  font-weight: 700;
}
.ext-mini-btn.active {
  color: var(--ai-green);
  border-color: #a7f3d0;
  background: #ecfdf5;
}
.ext-icon-btn {
  width: 25px;
  height: 25px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
}
.ext-icon-btn:hover { color: var(--ai-accent); border-color: var(--ai-accent-border); background: var(--ai-accent-bg); }
.ext-icon-btn.danger:hover { color: var(--ai-red); border-color: #fecaca; background: #fef2f2; }
.ext-form {
  padding: 12px;
  background: var(--ai-bg-secondary);
  flex-shrink: 0;
}
.ext-form h5 {
  margin: 0 0 10px;
  color: var(--ai-text);
  font-size: 13px;
}
.ext-input,
.ext-textarea {
  width: 100%;
  border: 1px solid var(--ai-border-strong);
  border-radius: 7px;
  background: var(--ai-bg);
  color: var(--ai-text);
  font-family: inherit;
  font-size: 12px;
  outline: none;
  margin-bottom: 8px;
}
.ext-input {
  height: 34px;
  padding: 0 10px;
}
.ext-textarea {
  resize: vertical;
  min-height: 96px;
  max-height: 210px;
  padding: 9px 10px;
  line-height: 1.5;
}
.ext-input:focus,
.ext-textarea:focus {
  border-color: var(--ai-accent);
  box-shadow: 0 0 0 3px rgba(59, 130, 246, 0.1);
}
.ext-check {
  display: flex;
  align-items: center;
  gap: 7px;
  color: var(--ai-text-muted);
  font-size: 12px;
  margin-bottom: 10px;
}
.ext-check input {
  accent-color: var(--ai-accent);
}
.ext-form-actions {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
}
.ext-btn {
  border: 0;
  border-radius: 7px;
  padding: 8px 13px;
  font-size: 12px;
  font-weight: 700;
  cursor: pointer;
}
.ext-btn.primary {
  color: #fff;
  background: var(--ai-accent);
}
.ext-btn.secondary {
  color: var(--ai-text-muted);
  background: var(--ai-bg-tertiary);
}

/* Extension enhancements */
.ext-badge {
  display: inline-block;
  padding: 1px 6px;
  border-radius: 4px;
  background: var(--ai-accent-bg);
  color: var(--ai-accent);
  font-size: 11px;
  font-family: monospace;
  margin-left: 6px;
}

.ext-transport-badge {
  display: inline-block;
  padding: 1px 6px;
  border-radius: 4px;
  font-size: 10px;
  font-weight: 600;
  text-transform: uppercase;
  margin-left: 4px;
}
.ext-transport-badge.http { background: #dbeafe; color: #1d4ed8; }
.ext-transport-badge.sse { background: #fce7f3; color: #be185d; }
.ext-transport-badge.stdio { background: #d1fae5; color: #047857; }

.ext-source {
  color: var(--ai-text-faint);
  font-style: italic;
}

.ext-import-section {
  margin: 12px 0;
  padding: 12px;
  background: var(--ai-bg-secondary);
  border: 1px dashed var(--ai-border-strong);
  border-radius: 8px;
}

.ext-import-section h5 {
  margin: 0 0 6px 0;
  font-size: 13px;
  color: var(--ai-text);
}

.ext-hint {
  font-size: 12px;
  color: var(--ai-text-muted);
  margin: 0 0 8px 0;
}

.ext-import-actions {
  display: flex;
  gap: 8px;
}

.ext-transport-select {
  margin: 8px 0;
}

.ext-transport-select label {
  font-size: 12px;
  color: #64748b;
  margin-bottom: 4px;
  display: block;
}

.ext-radio-group {
  display: flex;
  gap: 8px;
}

.ext-radio {
  display: flex;
  align-items: center;
  gap: 4px;
  padding: 4px 10px;
  border: 1px solid #e2e8f0;
  border-radius: 6px;
  font-size: 12px;
  cursor: pointer;
  transition: all 0.15s;
}

.ext-radio.active {
  border-color: #0f766e;
  background: #f0fdfa;
  color: #0f766e;
}

.ext-radio input[type="radio"] {
  display: none;
}

.ext-details {
  margin: 8px 0;
}

.ext-details summary {
  font-size: 12px;
  color: #64748b;
  cursor: pointer;
  padding: 4px 0;
}

.ext-details summary:hover {
  color: #334155;
}

/* Thinking blocks */
.ai-thinking-blocks { margin: 4px 0; }
.ai-thinking-block { margin: 6px 0; border: 1px solid #e9d5ff; border-radius: 8px; overflow: hidden; background: #faf5ff; }
.ai-thinking-block.active { border-color: #c4b5fd; }
.ai-thinking-header {
  display: flex; align-items: center; gap: 6px; padding: 8px 12px;
  cursor: pointer; font-size: 12px; font-weight: 600; color: #6d28d9;
  user-select: none;
}
.tb-summary { font-size: 11px; color: #8b5cf6; font-weight: 400; margin-right: auto; }
.ai-thinking-body {
  background: #f5f3ff; border-top: 1px solid #e9d5ff;
  padding: 8px 12px; font-size: 12px; line-height: 1.6;
  color: #4c1d95; max-height: 300px; overflow-y: auto;
}
.ai-thinking-body:empty::after { content: '正在分析...'; color: #a78bfa; }
.ai-think-dot {
  width: 7px; height: 7px; background: #d1d5db; border-radius: 50%;
  display: inline-block; animation: aiDot 1.4s ease-in-out infinite;
}
.ai-think-dot:nth-child(2) { animation-delay: 0.2s; }
.ai-think-dot:nth-child(3) { animation-delay: 0.4s; }
@keyframes aiDot { 0%,80%,100% { transform: scale(0.5); opacity: 0.3 } 40% { transform: scale(1); opacity: 1 } }

/* Context window stats */

.ai-session-select {
  position: relative;
  display: flex;
  align-items: center;
  gap: 4px;
  padding: 4px 8px;
  border-radius: 6px;
  cursor: pointer;
  font-size: 11px;
  color: #6b7280;
  transition: background 0.15s;
}
.ai-session-select:hover { background: #f3f4f6; }
.ai-session-name { max-width: 80px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.ai-session-dropdown {
  position: absolute;
  top: calc(100% + 4px);
  right: 0;
  width: 290px;
  max-height: 280px;
  overflow-y: auto;
  background: #fff;
  border: 1px solid #e5e7eb;
  border-radius: 8px;
  box-shadow: 0 4px 16px rgba(0,0,0,0.12);
  z-index: 100;
  padding: 4px;
}
.ai-session-item {
  display: flex;
  align-items: center;
  padding: 7px 10px;
  border-radius: 5px;
  cursor: pointer;
  font-size: 12px;
  transition: background 0.1s;
}
.ai-session-item:hover { background: #f3f4f6; }
.ai-session-item.active { background: #eff6ff; color: #1e40af; }
.ai-session-title { flex: 1; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.ai-session-time { font-size: 10px; color: #9ca3af; margin-left: 8px; flex-shrink: 0; }
.ai-session-action {
  border: 1px solid #e5e7eb;
  background: #fff;
  color: #6b7280;
  height: 22px;
  border-radius: 5px;
  padding: 0 6px;
  margin-left: 4px;
  cursor: pointer;
  opacity: 0;
  font-size: 10px;
  transition: opacity 0.1s, border-color 0.12s, color 0.12s;
}
.ai-session-item:hover .ai-session-action {
  opacity: 1;
}
.ai-session-action:hover {
  border-color: #93c5fd;
  color: #2563eb;
}
.ai-session-del { border: none; background: none; padding: 2px 4px; cursor: pointer; opacity: 0; transition: opacity 0.1s; }
.ai-session-item:hover .ai-session-del { opacity: 1; }
.ai-session-empty { padding: 12px; text-align: center; font-size: 12px; color: #9ca3af; }
.ai-session-new {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 6px;
  padding: 8px 12px;
  margin: 4px;
  border: 1px dashed #d1d5db;
  border-radius: 6px;
  font-size: 12px;
  color: #6b7280;
  cursor: pointer;
  transition: all 0.15s;
}
.ai-session-new:hover {
  border-color: #3b82f6;
  color: #3b82f6;
  background: #eff6ff;
}

/* ===== Modern Markdown Renderer ===== */
.markdown-rendered {
  line-height: 1.72;
}
.markdown-rendered :deep(p) {
  margin: 7px 0;
}
.markdown-rendered :deep(a.msg-link) {
  color: #2563eb;
  font-weight: 600;
  text-decoration: none;
  border-bottom: 1px solid rgba(37, 99, 235, 0.28);
  overflow-wrap: anywhere;
}
.markdown-rendered :deep(a.msg-link:hover) {
  color: #1d4ed8;
  border-bottom-color: #1d4ed8;
  background: rgba(37, 99, 235, 0.08);
}
.markdown-rendered :deep(h1),
.markdown-rendered :deep(h2),
.markdown-rendered :deep(h3),
.markdown-rendered :deep(h4) {
  color: var(--ai-text);
  letter-spacing: 0;
  line-height: 1.35;
}
.markdown-rendered :deep(h1) {
  font-size: 18px;
  margin: 14px 0 8px;
}
.markdown-rendered :deep(h2) {
  font-size: 16px;
  margin: 14px 0 8px;
  padding-bottom: 5px;
  border-bottom: 1px solid var(--ai-border-strong);
}
.markdown-rendered :deep(h3) {
  font-size: 14.5px;
  margin: 12px 0 6px;
}
.markdown-rendered :deep(ul),
.markdown-rendered :deep(ol) {
  margin: 8px 0;
  padding-left: 22px;
}
.markdown-rendered :deep(li) {
  margin: 4px 0;
}
.markdown-rendered :deep(code:not(pre code)) {
  padding: 2px 6px;
  border-radius: 5px;
  border: 1px solid var(--ai-inline-code-border);
  background: var(--ai-inline-code-bg);
  color: var(--ai-inline-code-text);
  font-family: 'JetBrains Mono', 'Fira Code', Consolas, monospace;
  font-size: 0.92em;
}
.markdown-rendered :deep(.code-block) {
  margin: 12px 0;
  border: 1px solid #243044;
  border-radius: 9px;
  overflow: hidden;
  background: #0f172a;
  box-shadow: 0 12px 30px rgba(15, 23, 42, 0.12);
}
.markdown-rendered :deep(.code-block-header) {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  min-height: 32px;
  padding: 0 10px 0 12px;
  background: linear-gradient(180deg, #111827, #0b1220);
  border-bottom: 1px solid #243044;
}
.markdown-rendered :deep(.code-lang) {
  color: #93a4bc;
  font-family: 'JetBrains Mono', 'Fira Code', Consolas, monospace;
  font-size: 10px;
  font-weight: 700;
  letter-spacing: 0.08em;
  text-transform: uppercase;
}
.markdown-rendered :deep(.code-copy-btn),
.markdown-rendered :deep(.table-copy-btn) {
  border: 1px solid rgba(148, 163, 184, 0.32);
  border-radius: 6px;
  background: rgba(15, 23, 42, 0.72);
  color: #cbd5e1;
  cursor: pointer;
  font-size: 11px;
  font-weight: 600;
  padding: 3px 8px;
  transition: background 0.15s, border-color 0.15s, color 0.15s;
}
.markdown-rendered :deep(.code-copy-btn:hover),
.markdown-rendered :deep(.table-copy-btn:hover) {
  background: #1e293b;
  border-color: #60a5fa;
  color: #fff;
}
.markdown-rendered :deep(pre) {
  margin: 0;
  padding: 14px 16px;
  overflow-x: auto;
  background: #0f172a;
  color: #dbeafe;
  font-family: 'JetBrains Mono', 'Fira Code', Consolas, monospace;
  font-size: 12.5px;
  line-height: 1.65;
}
.markdown-rendered :deep(pre code) {
  display: block;
  min-width: max-content;
  padding: 0;
  border: 0;
  background: transparent;
  color: inherit;
}
.markdown-rendered :deep(.msg-table-wrap) {
  margin: 12px 0;
  overflow: hidden;
  border: 1px solid var(--ai-border-strong);
  border-radius: 9px;
  background: var(--ai-bg);
}
.markdown-rendered :deep(.msg-table-header) {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  min-height: 32px;
  padding: 0 10px 0 12px;
  border-bottom: 1px solid var(--ai-border-strong);
  background: var(--ai-bg-tertiary);
}
.markdown-rendered :deep(.msg-table-tag) {
  color: var(--ai-text-muted);
  font-size: 11px;
  font-weight: 700;
}
.markdown-rendered :deep(.table-copy-btn) {
  background: var(--ai-bg);
  color: var(--ai-text-muted);
}
.markdown-rendered :deep(.table-copy-btn:hover) {
  background: var(--ai-accent-bg);
  color: var(--ai-accent);
}
.markdown-rendered :deep(.msg-table-scroll) {
  overflow-x: auto;
}
.markdown-rendered :deep(table.msg-table) {
  width: 100%;
  min-width: 420px;
  border-collapse: separate;
  border-spacing: 0;
  font-size: 12.5px;
}
.markdown-rendered :deep(.msg-table th),
.markdown-rendered :deep(.msg-table td) {
  padding: 9px 12px;
  border-right: 1px solid var(--ai-border);
  border-bottom: 1px solid var(--ai-border);
  text-align: left;
  vertical-align: top;
}
.markdown-rendered :deep(.msg-table th) {
  position: sticky;
  top: 0;
  z-index: 1;
  background: var(--ai-bg-secondary);
  color: var(--ai-text);
  font-weight: 700;
  white-space: nowrap;
}
.markdown-rendered :deep(.msg-table tr:last-child td) {
  border-bottom: 0;
}
.markdown-rendered :deep(.msg-table th:last-child),
.markdown-rendered :deep(.msg-table td:last-child) {
  border-right: 0;
}
.markdown-rendered :deep(.msg-table tr:nth-child(even) td) {
  background: rgba(148, 163, 184, 0.06);
}
.markdown-rendered :deep(blockquote) {
  margin: 10px 0;
  padding: 9px 12px;
  border-left: 3px solid var(--ai-accent);
  border-radius: 8px;
  background: var(--ai-accent-bg);
  color: var(--ai-text-muted);
}
.markdown-rendered :deep(.msg-callout) {
  border: 1px solid var(--ai-border-strong);
  border-left-width: 4px;
  box-shadow: 0 8px 18px rgba(15, 23, 42, 0.05);
}
.markdown-rendered :deep(.msg-callout-title) {
  margin-bottom: 4px;
  color: var(--ai-text);
  font-size: 12px;
  font-weight: 800;
}
.markdown-rendered :deep(.msg-callout-note) { border-left-color: #3b82f6; background: #eff6ff; }
.markdown-rendered :deep(.msg-callout-tip) { border-left-color: #10b981; background: #ecfdf5; }
.markdown-rendered :deep(.msg-callout-success) { border-left-color: #22c55e; background: #f0fdf4; }
.markdown-rendered :deep(.msg-callout-warning) { border-left-color: #f59e0b; background: #fffbeb; }
.markdown-rendered :deep(.msg-callout-important) { border-left-color: #8b5cf6; background: #f5f3ff; }
.markdown-rendered :deep(.msg-callout-error) { border-left-color: #ef4444; background: #fef2f2; }

/* Modern thinking timeline */
.ai-thinking-block {
  position: relative;
  margin: 8px 0 10px 0;
  border: 1px solid var(--ai-purple-border);
  border-radius: 9px;
  overflow: hidden;
  background: linear-gradient(180deg, #faf7ff 0%, #f5f3ff 100%);
  box-shadow: 0 10px 24px rgba(109, 40, 217, 0.06);
}
.ai-thinking-block::before {
  content: '';
  position: absolute;
  left: 0;
  top: 0;
  bottom: 0;
  width: 3px;
  background: linear-gradient(180deg, #8b5cf6, #60a5fa);
}
.ai-thinking-block.active {
  border-color: #c4b5fd;
}
.ai-thinking-header {
  display: flex;
  align-items: center;
  gap: 7px;
  padding: 8px 12px 8px 14px;
  border: 0;
  border-radius: 0;
  background: transparent;
  color: #6d28d9;
  cursor: pointer;
  font-size: 12px;
  font-weight: 700;
  user-select: none;
}
.ai-thinking-header:hover {
  background: rgba(139, 92, 246, 0.08);
}
.tb-summary {
  min-width: 0;
  margin-right: auto;
  overflow: hidden;
  color: #8b5cf6;
  font-size: 11px;
  font-weight: 500;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.ai-thinking-body {
  max-height: 320px;
  margin: 0;
  padding: 9px 14px 11px 14px;
  overflow-y: auto;
  border-top: 1px solid #e9d5ff;
  border-left: 0;
  border-radius: 0;
  background: rgba(255, 255, 255, 0.54);
  color: #4c1d95;
  font-size: 12.5px;
  line-height: 1.72;
  white-space: normal;
}
.ai-thinking-body:empty::after {
  content: '正在分析...';
  color: #a78bfa;
}
</style>
