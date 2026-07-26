<template>
  <div class="ws-shell" :class="{ 'ws-dark': aiDarkTheme }">
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
        <button class="ws-btn ws-btn-outline ws-btn-sm ws-theme-settings-btn" @click="themeStore.openSettings()" title="主题设置">
          <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><circle cx="12" cy="12" r="3"/><path d="M19.4 15a1.65 1.65 0 0 0 .33 1.82l.06.06a2 2 0 0 1 0 2.83 2 2 0 0 1-2.83 0l-.06-.06a1.65 1.65 0 0 0-1.82-.33 1.65 1.65 0 0 0-1 1.51V21a2 2 0 0 1-2 2 2 2 0 0 1-2-2v-.09A1.65 1.65 0 0 0 9 19.4a1.65 1.65 0 0 0-1.82.33l-.06.06a2 2 0 0 1-2.83 0 2 2 0 0 1 0-2.83l.06-.06A1.65 1.65 0 0 0 4.68 15a1.65 1.65 0 0 0-1.51-1H3a2 2 0 0 1-2-2 2 2 0 0 1 2-2h.09A1.65 1.65 0 0 0 4.6 9a1.65 1.65 0 0 0-.33-1.82l-.06-.06a2 2 0 0 1 0-2.83 2 2 0 0 1 2.83 0l.06.06A1.65 1.65 0 0 0 9 4.68a1.65 1.65 0 0 0 1-1.51V3a2 2 0 0 1 2-2 2 2 0 0 1 2 2v.09a1.65 1.65 0 0 0 1 1.51 1.65 1.65 0 0 0 1.82-.33l.06-.06a2 2 0 0 1 2.83 0 2 2 0 0 1 0 2.83l-.06.06A1.65 1.65 0 0 0 19.4 9a1.65 1.65 0 0 0 1.51 1H21a2 2 0 0 1 2 2 2 2 0 0 1-2 2h-.09a1.65 1.65 0 0 0-1.51 1z"/></svg>
          <span>主题</span>
        </button>
        <button class="ws-btn ws-btn-outline ws-btn-sm" @click="exportProject" title="导出项目">
          <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"/><polyline points="7 10 12 15 17 10"/><line x1="12" y1="15" x2="12" y2="3"/></svg>
          <span>导出</span>
        </button>
        <span v-if="fileContentDirty" class="ws-unsaved">未保存</span>
        <button v-if="activePath" class="ws-btn ws-btn-outline ws-btn-sm" @click="saveFile" :disabled="savingFile">{{ savingFile ? '保存中...' : '保存' }}</button>
      </div>
    </header>
    <div class="ws-body">
      <aside class="ws-sidebar" :style="{ width: `${sidebarWidth}px` }">
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
        <div class="ws-tree" v-loading="treeLoading" @scroll="handleTreeScroll">
          <div v-if="treeError" class="ws-tree-error" role="alert">
            <span>{{ treeError }}</span>
            <button type="button" @click="loadRoot">重试</button>
          </div>
          <FileTreeNode v-for="child in fileTree" :key="child.path" :node="child" :selected-path="activePath" :load-children="loadChildren" :show-actions="true" @select="openFile" @newItem="handleNewItem" @rename="handleRename" @delete="handleDelete"/>
          <button v-if="treeNextOffset !== null" class="ws-tree-load-more" type="button" @click="loadMoreRoot">加载更多文件</button>
          <div v-if="!treeLoading && !treeError && fileTree.length === 0" class="ws-tree-empty">暂无文件</div>
        </div>
      </aside>
      <div class="ws-resize-handle" @pointerdown="startSidebarResize"></div>
      <div ref="workspaceCenterRef" class="ws-center">
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
          <div class="ws-monaco"><MonacoEditor v-if="editorReady" v-model="fileContent" :language="detectedLang" :theme="editorTheme" :read-only="activeFileReadOnly" height="100%"/></div>
        </template>
      </main>

      <div v-show="terminalPanelVisible" class="ws-terminal-resize-handle" @pointerdown="startTerminalResize" title="拖动调整终端高度"></div>
      <section v-show="terminalPanelVisible" class="ws-terminal-dock" :class="{ dark: aiDarkTheme }" :style="{ height: `${terminalHeight}px` }">
        <div class="ws-terminal-dock-header">
          <span>终端</span>
          <button class="ws-btn ws-btn-outline ws-btn-icon" @click="toggleTerminalPanel" title="关闭终端">
            <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><line x1="18" y1="6" x2="6" y2="18"/><line x1="6" y1="6" x2="18" y2="18"/></svg>
          </button>
        </div>
        <TerminalPanel ref="terminalPanelRef" :project-id="projectId" :project-path="projectPath" :is-dark="aiDarkTheme" @toggle-theme="toggleAiTheme" />
      </section>
      </div>

      <!-- ==================== AI ASSISTANT SIDEBAR ==================== -->
      <aside class="ai-panel" :class="{ collapsed: aiCollapsed, dark: aiDarkTheme }">
        <!-- Resize Handle -->
        <div v-if="!aiCollapsed" class="ai-resize-handle" @pointerdown="startResize"></div>

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
                <ConversationMenu
                  v-if="showSessions"
                  :conversations="conversations"
                  :current-conversation-id="currentAgentSession?.conversationId"
                  @select="selectConversation"
                  @fork="forkConversation"
                  @compact="compactConversation"
                  @delete="deleteConversation"
                  @create="createNewSession"
                />
                </div>
              <button class="ai-topbar-btn" @click="toggleAiTheme" :title="aiDarkTheme ? '切换亮色主题' : '切换暗色主题'">
                <svg v-if="aiDarkTheme" width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><circle cx="12" cy="12" r="5"/><line x1="12" y1="1" x2="12" y2="3"/><line x1="12" y1="21" x2="12" y2="23"/><line x1="4.22" y1="4.22" x2="5.64" y2="5.64"/><line x1="18.36" y1="18.36" x2="19.78" y2="19.78"/><line x1="1" y1="12" x2="3" y2="12"/><line x1="21" y1="12" x2="23" y2="12"/><line x1="4.22" y1="19.78" x2="5.64" y2="18.36"/><line x1="18.36" y1="5.64" x2="19.78" y2="4.22"/></svg>
                <svg v-else width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M21 12.79A9 9 0 1 1 11.21 3 7 7 0 0 0 21 12.79z"/></svg>
              </button>
              <button class="ai-topbar-btn" @click="openContextUsageDialog" title="查看上下文使用情况">
                <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><circle cx="12" cy="12" r="9"/><path d="M12 7v5l3 2"/></svg>
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
              <button class="ai-topbar-btn" title="设置" @click="themeStore.openSettings()">
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
            <button v-for="tab in aiTabs" :key="tab.key" class="ai-tab" :class="{ active: tab.key === 'terminal' ? terminalPanelVisible : activeAiTab === tab.key }" @click="selectAiTab(tab.key)">
              <span v-html="tab.icon"></span>
              <span>{{ tab.label }}</span>
            </button>
          </div>

          <!-- ==================== CHAT TAB ==================== -->
          <div v-if="activeAiTab === 'chat'" :class="['ai-content', { 'is-empty': messages.length === 0 }]">
            <div class="ai-messages" ref="msgContainer" @scroll="handleScroll">
              <button v-if="hasOlderMessages" class="ai-history-load" type="button" :disabled="loadingOlderMessages" @click="loadOlderHistory">
                {{ loadingOlderMessages ? '正在加载更早记录...' : '加载更早记录' }}
              </button>
              <!-- Empty State -->
              <div v-if="messages.length === 0" class="ai-empty">
                <h2 class="ai-empty-greeting">有什么我可以帮您的吗？</h2>
              </div>

              <!-- Messages -->
              <TransitionGroup :key="conversationRenderEpoch" name="ai-msg" tag="div" class="ai-msg-list">
                <div v-for="(msg, i) in messages" :key="i" class="ai-msg" :class="msg.role">
                  <div class="ai-msg-header">
                    <div class="ai-msg-avatar">
                      <svg v-if="msg.role === 'user'" width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M20 21v-2a4 4 0 0 0-4-4H8a4 4 0 0 0-4 4v2"/><circle cx="12" cy="7" r="4"/></svg>
                      <svg v-else width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M12 2a2 2 0 0 1 2 2c0 .74-.4 1.39-1 1.73V7h1a7 7 0 0 1 7 7h1a1 1 0 0 1 1 1v3a1 1 0 0 1-1 1h-1v1a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-1H2a1 1 0 0 1-1-1v-3a1 1 0 0 1 1-1h1a7 7 0 0 1 7-7h1V5.73c-.6-.34-1-.99-1-1.73a2 2 0 0 1 2-2z"/></svg>
                    </div>
                    <span class="ai-msg-name">{{ msg.role === 'user' ? 'You' : 'LabexAgent' }}</span>
                  </div>
                  <div class="ai-msg-body">
                    <!-- Merged Thinking + Tool Calls (by time order) -->
                    <template v-if="(msg.thinkingBlocks && msg.thinkingBlocks.length > 0) || (msg.toolCalls && msg.toolCalls.length > 0)">
                      <template v-for="item in getMergedItems(msg)" :key="item._order">
                        <div v-if="item.type === 'thinking'" class="ai-thinking-block" :class="{ 'is-open': item.data._open }">
                          <div class="ai-thinking-header" @click="item.data._open = !item.data._open">
                            <svg class="ai-think-chevron" width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" :style="{ transform: item.data._open ? 'rotate(90deg)' : '' }"><polyline points="9 18 15 12 9 6"/></svg>
                            <span>思考过程</span>
                            <span class="tb-summary" v-if="item.data.summary">{{ item.data.summary }}</span>
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
                        <div
                          v-else-if="item.type === 'context'"
                          class="ai-context-management-card"
                          :class="[`is-${item.data.status}`, `is-${item.data.phase}`]"
                          role="status"
                          aria-live="polite"
                        >
                          <div class="context-management-card-header">
                            <span class="context-management-indicator" aria-hidden="true"></span>
                            <strong>{{ contextManagementTitle(item.data) }}</strong>
                            <span class="context-management-status">{{ contextManagementStatusText(item.data) }}</span>
                            <button
                              v-if="item.data.status === 'running' && item.data.taskId"
                              class="context-management-cancel"
                              type="button"
                              @click.stop="cancelContextCompaction(item.data)"
                            >&#21462;&#28040;&#21387;&#32553;</button>
                          </div>
                          <div class="context-management-card-meta">
                            <span v-if="contextManagementStrategyText(item.data)">{{ contextManagementStrategyText(item.data) }}</span>
                            <span v-if="item.data.tokensBefore !== null">{{ formatTokenCount(item.data.tokensBefore) }} Token</span>
                            <span v-if="item.data.tokensAfter !== null">→ {{ formatTokenCount(item.data.tokensAfter) }} Token</span>
                            <span v-if="item.data.releasedTokens > 0" class="context-management-released">释放 {{ formatTokenCount(item.data.releasedTokens) }} Token</span>
                          </div>
                          <p v-if="item.data.reason" class="context-management-reason">{{ item.data.reason }}</p>
                        </div>
                        <ToolCallCard
                          v-else-if="item.type === 'tool'"
                          :call="item.data"
                          @permission="handlePermissionDecision"
                          @command-approval="handleCommandApproval"
                          @question="handleQuestionReply"
                        />
                      </template>
                    </template>
                    <!-- Current Thinking (streaming) -->
                    <div v-if="msg.thinking" class="ai-thinking-block active is-open">
                      <div class="ai-thinking-header">
                        <svg class="ai-think-chevron" width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" style="transform: rotate(90deg)"><polyline points="9 18 15 12 9 6"/></svg>
                        <span>思考过程...</span>
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
                      <button
                        v-if="msg.environmentBlocker && msg.taskId"
                        type="button"
                        class="ai-environment-retry"
                        :disabled="msg.environmentRetrying"
                        @click="retryEnvironmentTask(msg)"
                      >
                        {{ msg.environmentRetrying ? '\u6b63\u5728\u6062\u590d\u4efb\u52a1...' : '\u73af\u5883\u6062\u590d\u540e\u91cd\u8bd5' }}
                      </button>
                      <!-- 消息时间戳 -->
                      <div v-if="msg.timestamp" class="ai-msg-time">{{ formatTime(msg.timestamp) }}</div>
                    </div>
                    <!-- Token Usage (on last assistant message) -->
                    <PlanDisplay v-if="msg.role === 'assistant' && (msg.plan || msg.planJson)" :plan="msg.plan" :plan-json="msg.planJson" />
                    <TokenChart v-if="i === messages.length - 1 && msg.role === 'assistant' && tokenUsage.totalTokens > 0"
                      :prompt-tokens="tokenUsage.promptTokens"
                      :completion-tokens="tokenUsage.completionTokens"
                      :call-count="tokenUsage.callCount" />
                    <AgentTimer
                      v-if="msg.role === 'assistant' && msg.timing"
                      :started-at="msg.timing.startedAt"
                      :active-elapsed-ms="msg.timing.activeElapsedMs"
                      :is-running="msg.timing.isRunning" />
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
              <!-- 1. 外挂的模式切换排 (类似 Claude 的建议指令) -->
              <div class="ai-quick-actions">
                <button
                  v-for="mode in agentModes"
                  :key="mode.key"
                  :class="['ai-quick-pill', { active: agentMode === mode.key, transitioning: modeTransitioning }]"
                  @click="switchMode(mode.key)"
                >
                  <svg v-if="mode.icon === 'cube'" width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M21 16V8a2 2 0 0 0-1-1.73l-7-4a2 2 0 0 0-2 0l-7 4A2 2 0 0 0 3 8v8a2 2 0 0 0 1 1.73l7 4a2 2 0 0 0 2 0l7-4A2 2 0 0 0 21 16z"/><polyline points="3.27 6.96 12 12.01 20.73 6.96"/><line x1="12" y1="22.08" x2="12" y2="12"/></svg>
                  <svg v-else-if="mode.icon === 'map'" width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><polygon points="1 6 1 22 8 18 16 22 23 18 23 2 16 6 8 2 1 6"/><line x1="8" y1="2" x2="8" y2="18"/><line x1="16" y1="6" x2="16" y2="22"/></svg>
                  <svg v-else-if="mode.icon === 'search'" width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><circle cx="11" cy="11" r="8"/><line x1="21" y1="21" x2="16.65" y2="16.65"/></svg>
                  <span>{{ mode.label }}</span>
                </button>
              </div>

              <!-- 2. Claude 风格主输入框 -->
              <div class="ai-input-box">
                <!-- 上层文本区 -->
                <div class="ai-input-text-area">
                  <textarea v-model="agentInput" rows="1" :placeholder="activePath ? '输入问题，例如：这段代码有什么问题？' : 'How can I help you today?'" @keydown.enter.exact.prevent="sendMessage" @keydown.escape="closeCommandPalette" @input="handleInput" :disabled="agentLoading" ref="aiInputRef"></textarea>
                </div>

                <!-- 底层工具栏与发送按钮 -->
                <div class="ai-input-footer">
                  <div class="ai-input-toolbar">
                    <button class="ai-toolbar-btn" title="模型配置" @click="showModelConfig = !showModelConfig">
                      <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><circle cx="12" cy="12" r="3"/><path d="M19.4 15a1.65 1.65 0 0 0 .33 1.82l.06.06a2 2 0 0 1 0 2.83 2 2 0 0 1-2.83 0l-.06-.06a1.65 1.65 0 0 0-1.82-.33 1.65 1.65 0 0 0-1 1.51V21a2 2 0 0 1-2 2 2 2 0 0 1-2-2v-.09A1.65 1.65 0 0 0 9 19.4a1.65 1.65 0 0 0-1.82.33l-.06.06a2 2 0 0 1-2.83 0 2 2 0 0 1 0-2.83l.06-.06A1.65 1.65 0 0 0 4.68 15a1.65 1.65 0 0 0-1.51-1H3a2 2 0 0 1-2-2 2 2 0 0 1 2-2h.09A1.65 1.65 0 0 0 4.6 9a1.65 1.65 0 0 0-.33-1.82l-.06-.06a2 2 0 0 1 0-2.83 2 2 0 0 1 2.83 0l.06.06A1.65 1.65 0 0 0 9 4.68a1.65 1.65 0 0 0 1-1.51V3a2 2 0 0 1 2-2 2 2 0 0 1 2 2v.09a1.65 1.65 0 0 0 1 1.51 1.65 1.65 0 0 0 1.82-.33l.06-.06a2 2 0 0 1 2.83 0 2 2 0 0 1 0 2.83l-.06.06A1.65 1.65 0 0 0 19.4 9a1.65 1.65 0 0 0 1.51 1H21a2 2 0 0 1 2 2 2 2 0 0 1-2 2h-.09a1.65 1.65 0 0 0-1.51 1z"/></svg>
                      <span>模型</span>
                    </button>
                    <button class="ai-toolbar-btn" title="优化提示词" @click="optimizePrompt">
                      <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M9.663 17h4.673M12 3v1m6.364 1.636l-.707.707M21 12h-1M4 12H3m3.343-5.657l-.707-.707m2.828 9.9a5 5 0 1 1 7.072 0l-.548.547A3.374 3.374 0 0 0 14 18.469V19a2 2 0 1 1-4 0v-.531c0-.895-.356-1.754-.988-2.386l-.548-.547z"/></svg>
                      <span>优化</span>
                    </button>
                    <button class="ai-toolbar-btn" title="引用文件 (@)" @click="atFile">
                      <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M21 15a2 2 0 0 1-2 2H7l-4 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2z"/></svg>
                      <span>引用</span>
                    </button>
                    <button class="ai-toolbar-btn" title="指令 (/)" @click="showCommandMenu">
                      <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><polyline points="4 17 10 11 4 5"/><line x1="12" y1="19" x2="20" y2="19"/></svg>
                      <span>指令</span>
                    </button>
                  </div>

                  <div class="ai-bar-right">
                    <div v-if="agentLoading" class="ai-generating-indicator">
                      <span class="gen-dot"></span>
                    </div>
                    <ContextUsageIndicator :status="contextUsageStatus" @open="openContextUsageDialog" />
                    <button
                      v-if="agentLoading"
                      class="ai-submit-btn"
                      @click="stopGeneration"
                      title="停止生成 (Esc)"
                    >
                      <svg width="14" height="14" viewBox="0 0 24 24" fill="currentColor"><rect x="6" y="6" width="12" height="12" rx="2"/></svg>
                    </button>
                    <button
                      v-else
                      class="ai-submit-btn"
                      @click="sendMessage"
                      :disabled="!agentInput.trim()"
                      title="发送 (Enter)"
                    >
                      <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><line x1="22" y1="2" x2="11" y2="13"/><polygon points="22 2 15 22 11 13 2 9 22 2"/></svg>
                    </button>
                  </div>
                </div>
              </div>
            </div>
          </div>

          <ContextUsageDialog
            :open="showContextUsageDialog"
            :status="contextUsageStatus"
            :prediction="nextContextPreview"
            :prediction-loading="nextContextPreviewLoading"
            @close="showContextUsageDialog = false"
            @load-next-preview="loadNextContextPreview"
          />

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

    <ModelConfigDialog :state="modelConfigDialogState" :actions="modelConfigDialogActions" />
  </div>
</template>

<script setup>
import { ref, reactive, computed, onMounted, onBeforeUnmount, nextTick, watch, defineAsyncComponent } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { projectApi, modelConfigApi, agentExtensionApi } from '@/api'
import { modelConfigPresets } from '@/constants/modelPresets'
import FileTreeNode from '@/components/cloud/FileTreeNode.vue'
import ToolCallCard from '@/components/cloud/ToolCallCard.vue'
import ChangesPanel from '@/components/cloud/ChangesPanel.vue'
import PlanDisplay from '@/components/cloud/PlanDisplay.vue'
import AppIcon from '@/components/AppIcon.vue'
import * as echarts from 'echarts'
import { marked } from 'marked'
import hljs from 'highlight.js/lib/common'
import { useAgentStream } from '@/composables/useAgentStream'
import { useAgentTaskRuntime } from '@/composables/useAgentTaskRuntime'
import { useContextManagement, contextManagementTitle, contextManagementStatusText, contextManagementStrategyText } from '@/composables/useContextManagement'
import { useThemeStore } from '@/stores/theme'
import { loadWorkspaceResources } from '@/composables/workspaceInitialization'
import { useConversationState } from '@/composables/useConversationState'
import { createConversationSelectionGuard } from '@/composables/conversationSelectionGuard'
import { useAgentInteraction } from '@/composables/useAgentInteraction'
import { useChangeSetState } from '@/composables/useChangeSetState'
import { reduceContextManagementEvent, reduceHistoryEvent } from '@/composables/agentHistoryReducer'
import { normalizeSpecialMarkdownBlocks } from '@/utils/agentMarkdown'
import { resolveContextUsageStatus } from '@/composables/contextUsageStatus'
import { renderMermaidDiagram } from '@/utils/mermaidRenderer'
import { enhanceFileLinks } from '@/utils/fileLinks'
import 'highlight.js/styles/github.css'

const route = useRoute()
const router = useRouter()
const themeStore = useThemeStore()
const conversationSelectionGuard = createConversationSelectionGuard()
const { stream: streamAgent, replay: replayAgent, subscribe: subscribeAgent, disconnect: disconnectAgentStream, disconnectSubscription, stop: stopAgent } = useAgentStream()
const MonacoEditor = defineAsyncComponent(() => import('@/components/MonacoEditor.vue'))
const TerminalPanel = defineAsyncComponent(() => import('@/components/terminal/TerminalPanel.vue'))
const TokenChart = defineAsyncComponent(() => import('@/components/cloud/TokenChart.vue'))
const AgentTimer = defineAsyncComponent(() => import('@/components/cloud/AgentTimer.vue'))
const ContextUsageIndicator = defineAsyncComponent(() => import('@/components/cloud/ContextUsageIndicator.vue'))
const ContextUsageDialog = defineAsyncComponent(() => import('@/components/cloud/ContextUsageDialog.vue'))
const ConversationMenu = defineAsyncComponent(() => import('@/components/cloud/ConversationMenu.vue'))
const ModelConfigDialog = defineAsyncComponent(() => import('@/components/cloud/ModelConfigDialog.vue'))

// Core project state
const projectId = ref(null)
const projectName = ref('')
const fileTree = ref([])
const treeError = ref('')
const treeNextOffset = ref(null)
const treeLoadingMore = ref(false)
const sidebarWidth = ref(240)
const activePath = ref('')
const fileContent = ref('')
const fileContentDirty = ref(false)
const activeFileReadOnly = ref(false)
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
const conversationRenderEpoch = ref(0)
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
const contextUsageStatus = ref(null)
const showContextUsageDialog = ref(false)
const nextContextPreview = ref(null)
const nextContextPreviewLoading = ref(false)
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
const showSessions = ref(false)

// AI Panel UI state
const aiCollapsed = ref(false)
const aiDarkTheme = computed(() => themeStore.effectiveTheme === 'dark')
const editorTheme = computed(() => aiDarkTheme.value ? 'vs-dark' : 'vs')
const activeAiTab = ref('chat')
const contextExpanded = ref(false)
const showModelConfig = ref(false)

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
  contextWindowTokens: 1_000_000,
  promptCacheKeyEnabled: false,
  reasoningEffort: 'medium',
  imageInputEnabled: false,
  compactionAuto: true,
  compactionPrune: false,
  compactionTailTurns: 2,
  compactionPreserveRecentTokens: null,
  compactionReservedTokens: null,
  compactionModelConfigId: 0,
  compactionThresholdPercent: 90,
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
  contextWindowTokens: 1_000_000,
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
const workspaceCenterRef = ref(null)
const terminalPanelVisible = ref(false)
const terminalHeight = ref(280)
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

// Re-render mermaid diagrams whenever message content changes (covers both
// initial render and streaming updates). renderMermaidBlocks is a no-op for
// elements already marked data-rendered.
watch(
  () => messages.value.map((m) => `${m.role}|${m.content || ''}|${m._thinkingDisplay || ''}`).join('\n'),
  () => {
    nextTick(() => renderMermaidBlocks(document.querySelector('.ai-panel-body') || document))
  },
  { flush: 'post' }
)

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
  const startupConversationSelection = conversationSelectionGuard.capture()
  const secondaryResources = loadWorkspaceResources([
    () => loadModelConfigs(),
    () => loadAgentExtensions(),
    () => loadConversations()
  ])
  await loadRoot()
  void secondaryResources.then(async () => {
    if (conversationSelectionGuard.isCurrent(startupConversationSelection)
      && !currentAgentSession.value
      && messages.value.length === 0
      && conversations.value.length > 0) {
      await selectConversation(conversations.value[0], { explicit: false })
    }
  })
})

// 清理定时器，防止内存泄漏
onBeforeUnmount(() => {
  invalidateTaskRuntime()
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
  treeError.value = ''
  treeNextOffset.value = null
  try {
    const r = await projectApi.getTreePage(projectId.value, '', 0)
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

async function loadMoreRoot() {
  if (!projectId.value || treeNextOffset.value === null || treeLoadingMore.value) return
  treeLoadingMore.value = true
  try {
    const r = await projectApi.getTreePage(projectId.value, '', treeNextOffset.value)
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
    void loadMoreRoot()
  }
}

async function loadChildren(dirPath, offset = 0) {
  if (!projectId.value) return { entries: [], nextOffset: null }
  try {
    const r = await projectApi.getTreePage(projectId.value, dirPath, offset)
    return r.data || { entries: [], nextOffset: null }
  } catch (e) {
    return { entries: [], nextOffset: null }
  }
}
async function openFile(path) {
  if (!projectId.value) return
  const existingIdx = openFiles.value.findIndex(f => f.path === path)
  if (existingIdx >= 0) {
    activeTabIndex.value = existingIdx
    activePath.value = path
    activeFileReadOnly.value = Boolean(openFiles.value[existingIdx].readOnly)
    fileContent.value = openFiles.value[existingIdx].content
    fileContentDirty.value = false
    const ext = path.split('.').pop()?.toLowerCase()
    detectedLang.value = langMap[ext] || 'plaintext'
    return
  }
  let file
  try {
    const r = await projectApi.readFile(projectId.value, path)
    file = r.data || {}
  } catch (e) {
    return
  }
  const content = file.content || ''
  const readOnly = Boolean(file.readOnly)
  const ext = path.split('.').pop()?.toLowerCase()
  const name = path.split('/').pop() || path
  openFiles.value.push({ path, name, content, lang: langMap[ext] || 'plaintext', dirty: false, readOnly })
  activeTabIndex.value = openFiles.value.length - 1
  activePath.value = path
  activeFileReadOnly.value = readOnly
  fileContent.value = content
  fileContentDirty.value = false
  detectedLang.value = langMap[ext] || 'plaintext'
  if (file.truncated) {
    ElMessage.warning(`文件过大，仅加载前 ${Math.round(content.length / 1024)} KB 内容`)
  }
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
  activeFileReadOnly.value = Boolean(f.readOnly)
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
      activeFileReadOnly.value = false
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
  if (!projectId.value || !activePath.value || savingFile.value || activeFileReadOnly.value) return
  savingFile.value = true
  try { await projectApi.saveFile(projectId.value, activePath.value, fileContent.value); fileContentDirty.value = false; if (activeTabIndex.value >= 0 && activeTabIndex.value < openFiles.value.length) { openFiles.value[activeTabIndex.value].dirty = false } ElMessage.success('文件已保存') } catch (e) { ElMessage.error('保存失败') } finally { savingFile.value = false }
}
async function selectAiTab(key) {
  if (key === 'terminal') {
    terminalPanelVisible.value = true
    await nextTick()
    terminalPanelRef.value?.fitAllTerminals()
    return
  }
  activeAiTab.value = key
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
  if (/^\/(compact|summarize)\s*$/i.test(q)) {
    agentInput.value = ''
    await compactCurrentConversation()
    return
  }

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
  messages.value.push({ role: 'assistant', content: '', thinking: '', _thinkingDisplay: '', _thinkingTimer: null, thinkingBlocks: [], toolCalls: [], plan: null, isStreaming: true, error: null, _nextOrder: 0, timestamp: Date.now(), timing: createMessageTiming() })
  const assistantMsg = messages.value[messages.value.length - 1]
  agentInput.value = ''; agentLoading.value = true
  userScrolled.value = false
  await nextTick(); scrollDown(true)

  const sessionId = currentAgentSession.value?.sessionId || crypto.randomUUID()

  try {
    await streamAgent(projectId.value, {
      sessionId,
      conversationId: currentAgentSession.value?.conversationId,
      mode: agentMode.value,
      message: messageToSend,
      activePath: activePath.value || '',
      modelConfigId: selectedModelConfigId.value || null
    }, {
      onEvent: event => handleAgentEvent(event, assistantMsg)
    })
  } catch (e) {
    if (e.name === 'AbortError') {
      assistantMsg.content += '\n[连接已中断]'
    } else {
      assistantMsg.error = e.message
      assistantMsg.content = `错误：${e.message}`
    }
  } finally {
    flushThinkingDisplay(assistantMsg)
    assistantMsg.isStreaming = false
    stopMessageTimer(assistantMsg)
    await syncTaskTiming(assistantMsg)
    agentLoading.value = false
    await nextTick(); scrollDown()
  }
}

function handleAgentEvent(event, assistantMsg) {
  const type = event.type
  const data = event.data
  recordTaskEventCursor(data?.taskId || assistantMsg?.taskId, event.eventId)
  switch (type) {
    case 'SESSION':
      currentAgentSession.value = data
      assistantMsg.taskId = data.taskId || null
      if (assistantMsg.timing) assistantMsg.timing.taskId = assistantMsg.taskId
      break
    case 'THINK_START':
      if (assistantMsg.thinking) {
        assistantMsg.thinkingBlocks.push({ content: assistantMsg.thinking, summary: data.summary || '', iteration: data.iteration || 0, _open: false, _order: (assistantMsg._nextOrder = (assistantMsg._nextOrder || 0) + 1) })
      }
      assistantMsg.thinking = ''
      break
    case 'THINK_DELTA':
      assistantMsg.thinking += (data.delta || '')
      assistantMsg._thinkingDisplay = assistantMsg.thinking
      scheduleAgentRender()
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
      assistantMsg.toolCalls.push({ name: data.tool, args: data.arguments, summary: data.summary, result: null, status: 'running', toolCallId: data.toolCallId || '', startedAt: data.startedAt || Date.now(), execution: { phase: 'tool_delegate', elapsedMs: 0 }, _order: (assistantMsg._nextOrder = (assistantMsg._nextOrder || 0) + 1) })
      scheduleAgentRender()
      break
    case 'TOOL_EXECUTION_STARTED':
    case 'TOOL_PHASE_CHANGED':
    case 'TOOL_EXECUTION_COMPLETED':
    case 'TOOL_EXECUTION_FAILED':
    case 'TOOL_TIMED_OUT': {
      const toolCall = assistantMsg.toolCalls.find(call => call.toolCallId && call.toolCallId === data.toolCallId)
        || assistantMsg.toolCalls.at(-1)
      if (toolCall) {
        toolCall.execution = { phase: data.phase || 'tool_delegate', elapsedMs: data.elapsedMs || 0,
          delegateMs: data.delegateMs || 0, beforeSnapshotMs: data.beforeSnapshotMs || 0,
          afterSnapshotMs: data.afterSnapshotMs || 0, snapshotDiffMs: data.snapshotDiffMs || 0,
          postEditMs: data.postEditMs || 0, contextMs: data.contextMs || 0, error: data.error || '' }
        if (type === 'TOOL_EXECUTION_FAILED' || type === 'TOOL_TIMED_OUT') toolCall.status = 'error'
      }
      scheduleAgentRender()
      break
    }
    case 'OBSERVE':
      if (assistantMsg.toolCalls.length > 0) {
        const last = assistantMsg.toolCalls[assistantMsg.toolCalls.length - 1]
        last.result = data.result || data.content
        last.status = data.success ? 'completed' : 'error'
        last.projection = { resultChars: data.resultChars || 0, modelProjectionChars: data.modelProjectionChars || 0,
          truncated: data.modelProjectionTruncated === true }
        if (data.diff) {
          last.hasDiff = true
          trackFileChange(data, last)
        }
        if (data.pendingChangeId) {
          changesRefreshKey.value++
        }
      }
      scheduleAgentRender()
      break
    case 'COMMAND_APPROVAL_REQUIRED':
      attachCommandApproval(assistantMsg, data)
      break
    case 'COMMAND_APPROVAL_DECIDED':
    case 'COMMAND_APPROVAL_REJECTED':
    case 'COMMAND_APPROVAL_EXPIRED':
    case 'COMMAND_EXECUTION_STARTED':
    case 'COMMAND_EXECUTION_COMPLETED':
    case 'COMMAND_EXECUTION_FAILED':
    case 'COMMAND_EXECUTION_INTERRUPTED':
      updateCommandApprovalLifecycle(assistantMsg, type, data)
      scheduleAgentRender()
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
    case 'WORKSPACE_WAITING':
      assistantMsg.taskId = data.taskId || assistantMsg.taskId || null
      assistantMsg.workspaceWaiting = data
      break
    case 'ENVIRONMENT_BLOCKED':
      assistantMsg.taskId = data.taskId || assistantMsg.taskId || null
      assistantMsg.environmentBlocker = data
      assistantMsg.content = data.detail || '\u4f9d\u8d56\u73af\u5883\u6682\u65f6\u4e0d\u53ef\u7528\uff0c\u8bf7\u6062\u590d\u540e\u91cd\u8bd5\u3002'
      assistantMsg.isStreaming = false
      stopMessageTimer(assistantMsg)
      break
    case 'PLAN_UPDATE':
      assistantMsg.plan = data.summary || data.plan || null
      assistantMsg.planJson = data.planJson || null
      break
    case 'FINAL_DELTA':
      assistantMsg.content += (data.delta || '')
      scheduleAgentRender()
      break
    case 'FINAL':
      if (data.content && !assistantMsg.error) assistantMsg.content = data.content
      break
    case 'TASK_PAUSED':
      assistantMsg.waitingForCommandApproval = data.reason === 'command_approval'
      assistantMsg.isStreaming = false
      stopMessageTimer(assistantMsg)
      agentLoading.value = false
      logTaskRecovery('TASK_PAUSED', {
        taskId: data.taskId || assistantMsg.taskId,
        taskStatus: data.taskStatus || 'waiting_approval',
        reason: data.reason || '',
        resumeAgentLoop: data.resumeAgentLoop === true
      })
      break
    case 'DONE':
      flushThinkingDisplay(assistantMsg)
      assistantMsg.isStreaming = false
      stopMessageTimer(assistantMsg)
      agentLoading.value = false
      if (data.waitingForApproval) {
        assistantMsg.waitingForCommandApproval = true
        logTaskRecovery('TASK_WAITING_APPROVAL', {
          taskId: data.taskId || assistantMsg.taskId,
          taskStatus: data.taskStatus || 'waiting_approval',
          resumeAgentLoop: data.resumeAgentLoop === true
        })
      }
      break
    case 'CONTEXT_STATUS':
      contextUsageStatus.value = data
      break
    case 'COMPACTION_STARTED':
    case 'COMPACTION_PROGRESS':
    case 'CONTEXT_PRUNED':
    case 'COMPACTION_COMPLETED':
    case 'COMPACTION_FAILED':
    case 'COMPACTION_CANCELLED':
      reduceContextManagementEvent(type, data, assistantMsg)
      scheduleAgentRender()
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
    case 'ERROR': {
      const message = data.message || '模型服务调用失败'
      assistantMsg.error = message
      if (!assistantMsg.content) assistantMsg.content = `错误：${message}`
      assistantMsg.isStreaming = false
      stopMessageTimer(assistantMsg)
      break
    }
    case 'INTERRUPTED':
      assistantMsg.content += '\n[已中断]'
      assistantMsg.isStreaming = false
      stopMessageTimer(assistantMsg)
      break
  }
}

function createMessageTiming() {
  return {
    taskId: null,
    startedAt: Date.now(),
    activeElapsedMs: null,
    isRunning: true
  }
}

function stopMessageTimer(message) {
  if (message?.timing) message.timing.isRunning = false
}

function commandApprovalToolCall(message, approvalId) {
  if (!message || !approvalId) return null
  return message.toolCalls?.find(call => call?.commandApproval?.approvalId === approvalId) || null
}

function executionResultText(data, fallback) {
  const duration = Number(data?.durationMs)
  const durationText = Number.isFinite(duration) && duration >= 0 ? ` · ${duration} ms` : ''
  const exitCode = data?.exitCode === '' || data?.exitCode == null ? '' : ` · exit code ${data.exitCode}`
  return `${fallback}${durationText}${exitCode}`
}

function updateCommandApprovalLifecycle(message, type, data = {}) {
  const call = commandApprovalToolCall(message, data.approvalId)
  if (!call) return
  const decision = String(data.decision || '').toLowerCase()
  if (type === 'COMMAND_APPROVAL_DECIDED') {
    if (decision === 'rejected' || decision === 'expired') {
      call.status = 'error'
      call.result = decision === 'expired' ? '批准请求已过期，命令未执行' : '已拒绝命令，命令未执行'
    } else {
      call.status = 'running'
      call.result = '批准已记录，准备执行命令'
    }
    return
  }
  if (type === 'COMMAND_APPROVAL_REJECTED' || type === 'COMMAND_APPROVAL_EXPIRED') {
    call.status = 'error'
    call.result = type === 'COMMAND_APPROVAL_EXPIRED' ? '批准请求已过期，命令未执行' : '已拒绝命令，命令未执行'
    return
  }
  if (type === 'COMMAND_EXECUTION_STARTED') {
    call.status = 'running'
    call.result = '命令正在执行...'
    return
  }
  if (type === 'COMMAND_EXECUTION_COMPLETED') {
    call.status = 'completed'
    call.result = executionResultText(data, '命令执行完成')
    return
  }
  if (type === 'COMMAND_EXECUTION_FAILED') {
    call.status = 'error'
    call.result = executionResultText(data, '命令执行失败')
    return
  }
  if (type === 'COMMAND_EXECUTION_INTERRUPTED') {
    call.status = 'error'
    call.result = '命令执行已中断'
  }
}

function reconcileRecoveredCommandApproval(message, task) {
  const approval = task?.commandApproval
  if (!message || !approval?.approvalId) return
  message.toolCalls = message.toolCalls || []
  const taskStatus = String(task?.status || '').toLowerCase()
  const approvalStatus = String(approval.status || '').toLowerCase()
  message.toolCalls.forEach(call => {
    const historicalApprovalId = call?.commandApproval?.approvalId
    if (historicalApprovalId && historicalApprovalId !== approval.approvalId && call.status === 'waiting_approval') {
      call.status = 'error'
      call.result = '旧批准请求已失效，命令未执行'
    }
  })
  let call = commandApprovalToolCall(message, approval.approvalId)
  if (!call && approvalStatus === 'pending' && taskStatus === 'waiting_approval') {
    attachCommandApproval(message, approval)
    call = commandApprovalToolCall(message, approval.approvalId)
  }
  if (!call) return
  call.commandApproval = { ...call.commandApproval, ...approval }
  call.summary = approval.displayCommand || call.summary
  if (approvalStatus === 'pending' && taskStatus === 'waiting_approval') {
    call.status = 'waiting_approval'
    call.result = null
    return
  }
  if (approval.executionStatus === 'completed') {
    updateCommandApprovalLifecycle(message, 'COMMAND_EXECUTION_COMPLETED', approval)
    return
  }
  if (approval.executionStatus === 'failed' || approval.executionStatus === 'interrupted') {
    updateCommandApprovalLifecycle(message,
      approval.executionStatus === 'interrupted' ? 'COMMAND_EXECUTION_INTERRUPTED' : 'COMMAND_EXECUTION_FAILED', approval)
    return
  }
  if (approvalStatus === 'rejected' || approvalStatus === 'expired') {
    updateCommandApprovalLifecycle(message,
      approvalStatus === 'expired' ? 'COMMAND_APPROVAL_EXPIRED' : 'COMMAND_APPROVAL_REJECTED', approval)
    return
  }
  if (approvalStatus === 'approved' || approvalStatus === 'consumed') {
    call.status = 'running'
    call.result = '命令已批准，正在恢复执行'
  }
}

function attachCommandApproval(msg, data) {
  if (!msg || !data?.approvalId) return null
  msg.toolCalls = msg.toolCalls || []
  const existing = commandApprovalToolCall(msg, data.approvalId)
  if (existing) {
    existing.commandApproval = { ...existing.commandApproval, ...data }
    existing.summary = data.displayCommand || existing.summary
    return existing
  }
  const last = msg.toolCalls[msg.toolCalls.length - 1]
  const summary = data.displayCommand || '命令需要一次性批准'
  if (last && last.status === 'running' && last.name === data.tool) {
    last.status = 'waiting_approval'
    last.commandApproval = data
    last.summary = summary
    return last
  }
  const call = {
    name: data.tool || 'bash',
    args: { command: '<redacted; approval required>' },
    summary,
    result: null,
    status: 'waiting_approval',
    commandApproval: data,
    _order: (msg._nextOrder = (msg._nextOrder || 0) + 1)
  }
  msg.toolCalls.push(call)
  return call
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

async function handleCommandApproval(payload) {
  const approval = payload?.call?.commandApproval
  if (!approval?.approvalId) return

  const call = payload.call
  if (call._commandApprovalInFlight) {
    logTaskRecovery('COMMAND_APPROVAL_DUPLICATE_IGNORED', {
      taskId: approval.taskId,
      approvalId: approval.approvalId,
      action: payload.action
    })
    return
  }
  const decisionStartedAt = performance.now()
  call._commandApprovalInFlight = true
  logTaskRecovery('COMMAND_APPROVAL_DECISION_SUBMITTED', {
    taskId: approval.taskId,
    approvalId: approval.approvalId,
    action: payload.action
  })
  if (payload.action === 'reject') {
    call.status = 'error'
    call.result = '正在拒绝命令...'
  } else {
    call.status = 'running'
    call.result = '正在批准命令...'
  }

  try {
    const decision = await projectApi.agentDecideCommandApproval(projectId.value, approval.approvalId, {
      action: payload.action,
      decisionIdempotencyKey: call._commandDecisionIdempotencyKey ||
        (call._commandDecisionIdempotencyKey = crypto.randomUUID())
    })
    logTaskRecovery('COMMAND_APPROVAL_DECISION_RESULT', {
      taskId: approval.taskId,
      approvalId: approval.approvalId,
      status: decision?.data?.status || '',
      resumeAgentLoop: decision?.data?.resumeAgentLoop === true,
      durationMs: Math.round(performance.now() - decisionStartedAt)
    })
    if (decision?.data?.approvalUnavailable) {
      call.status = 'error'
      call.result = '批准请求不可用或已被处理'
      return
    }
    if (payload.action === 'reject') {
      call.status = 'error'
      call.result = '已拒绝命令，命令未执行'
      if (decision?.data?.resumeAgentLoop) {
        const assistantMsg = messages.value.find(message => message?.toolCalls?.includes(call))
        const taskId = approval.taskId || assistantMsg?.taskId
        if (assistantMsg && taskId) {
          assistantMsg.waitingForCommandApproval = false
          assistantMsg.isStreaming = true
          agentLoading.value = true
          void replayResumedAgent(taskId, assistantMsg)
        }
      }
      return
    }

    const executionStartedAt = performance.now()
    logTaskRecovery('COMMAND_APPROVAL_EXECUTION_REQUESTED', {
      taskId: approval.taskId,
      approvalId: approval.approvalId
    })
    const execution = await projectApi.agentExecuteCommandApproval(projectId.value, approval.approvalId)
    const executionData = execution?.data || {}
    logTaskRecovery('COMMAND_APPROVAL_EXECUTION_RESULT', {
      taskId: approval.taskId,
      approvalId: approval.approvalId,
      status: executionData.status || '',
      executionStatus: executionData.executionStatus || '',
      commandDurationMs: executionData.durationMs ?? null,
      httpDurationMs: Math.round(performance.now() - executionStartedAt),
      resumeAgentLoop: executionData.resumeAgentLoop === true
    })
    if (executionData.approvalUnavailable) {
      call.status = 'error'
      call.result = '批准请求不可用或已被处理'
      return
    }
    const executionStatus = executionData.executionStatus ||
      (executionData.status === 'completed' ? 'completed' : executionData.status === 'failed' ? 'failed' : '')
    if (executionStatus === 'completed' || executionStatus === 'failed') {
      call.status = executionStatus === 'completed' ? 'completed' : 'error'
      call.result = executionResultText(executionData,
        executionStatus === 'completed' ? '命令执行完成' : '命令执行失败')
      if (executionData.output) call.result += `

${executionData.output}`
    } else {
      call.status = 'running'
      call.result = '命令已批准，等待执行结果'
    }
    if (executionData.resumeAgentLoop) {
      const assistantMsg = messages.value.find(message => message?.toolCalls?.includes(call))
      const taskId = approval.taskId || assistantMsg?.taskId
      if (assistantMsg && taskId) {
        assistantMsg.waitingForCommandApproval = false
        assistantMsg.isStreaming = true
        agentLoading.value = true
        void replayResumedAgent(taskId, assistantMsg)
      }
    }
  } catch (error) {
    call.status = 'error'
    call.result = '命令审批失败：' + (error?.response?.data?.message || error?.message || '未知错误')
  } finally {
    call._commandApprovalInFlight = false
  }
}

async function retryEnvironmentTask(assistantMsg) {
  const taskId = assistantMsg?.taskId
  if (!taskId || assistantMsg.environmentRetrying) return
  assistantMsg.environmentRetrying = true
  try {
    await projectApi.agentRetryEnvironment(projectId.value, taskId)
    assistantMsg.environmentBlocker = null
    assistantMsg.workspaceWaiting = null
    assistantMsg.error = null
    assistantMsg.isStreaming = true
    agentLoading.value = true
    await replayResumedAgent(taskId, assistantMsg)
  } catch (error) {
    const message = error?.response?.data?.message || error?.message || '\u91cd\u8bd5\u4efb\u52a1\u5931\u8d25'
    assistantMsg.error = message
    assistantMsg.environmentBlocker = assistantMsg.environmentBlocker || {}
    assistantMsg.environmentBlocker.detail = message
    ElMessage.error(message)
  } finally {
    assistantMsg.environmentRetrying = false
  }
}

async function replayResumedAgent(taskId, assistantMsg) {
  try {
    logTaskRecovery('TASK_EVENT_RESUME_WAITING', { taskId })
    await resumeTaskEventSubscription(taskId, assistantMsg)
  } catch (error) {
    assistantMsg.error = '恢复 Agent 任务失败：' + (error?.message || '未知错误')
    if (!assistantMsg.content) assistantMsg.content = assistantMsg.error
    assistantMsg.isStreaming = false
    stopMessageTimer(assistantMsg)
    agentLoading.value = false
  }
}

async function handlePermissionDecision(payload) {
  await submitPermissionDecision(payload)
}

async function handleQuestionReply(payload) {
  const result = await submitQuestionReply(payload)
  if (result.reason === 'answer_required') {
    ElMessage.warning('\u8bf7\u5148\u8f93\u5165\u56de\u7b54')
  }
}

async function revertChange(change) {
  try {
    return await revertChangeState(change)
  } catch (error) {
    ElMessage.error('Revert failed: ' + (error?.response?.data?.message || error?.message))
    return { success: false }
  }
}

function onUndoChange(change) {
  const file = change?.relativePath || change?.file
  if (!removeChange(change)) return
  if (activePath.value === file) {
    fileContentDirty.value = false
  }
}

const currentAgentSession = ref(null)
const selectedModelConfigId = ref(null)
const modelConfigs = ref([])
const sessionChanges = ref([])
const changesRefreshKey = ref(0)

const modelConfigDialogState = reactive({
  showModelConfig, mcTemplateSelecting, mcTemplateOptions, mcEditing, modelConfigs,
  selectedModelConfigId, mcTestResults, mcTestingIds, mcForm, mcCustomMode,
  mcModelsLoading, mcFetchedModels, mcApiKeyHint, mcEditingId, mcSaving
})
const modelConfigDialogActions = {
  cancelModelConfigEdit, deleteConfig, editConfig, fetchModelList, saveConfig,
  selectModelTemplate, startCreateConfig, testConfig, applyFetchedModelLimits
}

function syncRevertedFile({ file, content }) {
  if (activePath.value === file) {
    fileContent.value = content
    fileContentDirty.value = false
  }
  const tab = openFiles.value.find(item => item.path === file)
  if (tab) {
    tab.content = content
    tab.dirty = false
  }
}

const { trackFileChange, revertChange: revertChangeState, removeChange } = useChangeSetState({
  projectId,
  api: projectApi,
  sessionChanges,
  onFileReverted: syncRevertedFile
})

const conversationState = useConversationState({
  projectId,
  api: projectApi,
  messages,
  sessionChanges,
  tokenUsage,
  agentLoading,
  currentAgentSession,
  replayHistoryEvent,
  onHistoryLoaded: initialScroll
})
const {
  conversations,
  currentSessionName,
  hasOlderMessages,
  loadingOlderMessages,
  clearConversationState,
  loadConversations,
  createNewSession: resetConversation,
  selectConversation: selectConversationState,
  loadConversationMessages: loadConversationMessagesState,
  loadOlderMessages,
  forkConversation: forkConversationState,
  compactConversation: compactConversationState,
  deleteConversation: deleteConversationState
} = conversationState

const { submitPermissionDecision, submitQuestionReply } = useAgentInteraction({
  projectId,
  api: projectApi
})

const {
  recordTaskEventCursor,
  invalidate: invalidateTaskRuntime,
  recoverActiveTaskForConversation,
  subscribeToTaskEvents,
  resumeTaskEventSubscription,
  syncTaskTiming,
  syncConversationTaskTimings
} = useAgentTaskRuntime({
  projectId,
  currentAgentSession,
  messages,
  agentLoading,
  api: projectApi,
  subscribeAgent,
  disconnectSubscription,
  handleAgentEvent,
  reconcileRecoveredCommandApproval,
  createMessageTiming,
  stopMessageTimer,
  scrollDown
})

const {
  compactConversation,
  compactCurrentConversation,
  cancelContextCompaction
} = useContextManagement({
  messages,
  agentLoading,
  projectId,
  currentAgentSession,
  conversations,
  selectedModelConfigId,
  api: projectApi,
  compactConversationState,
  subscribeToTaskEvents,
  reduceContextManagementEvent,
  notify: ElMessage,
  scheduleAgentRender
})

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
    contextWindowTokens: cfg.contextWindowTokens ?? 1_000_000,
    promptCacheKeyEnabled: cfg.promptCacheKeyEnabled === 1,
    reasoningEffort: cfg.reasoningEffort || 'medium',
    imageInputEnabled: cfg.imageInputEnabled === 1,
    compactionAuto: cfg.compactionAuto !== 0,
    compactionPrune: cfg.compactionPrune === 1,
    compactionTailTurns: cfg.compactionTailTurns ?? 2,
    compactionPreserveRecentTokens: cfg.compactionPreserveRecentTokens ?? null,
    compactionReservedTokens: cfg.compactionReservedTokens ?? null,
    compactionModelConfigId: cfg.compactionModelConfigId ?? 0,
    compactionThresholdPercent: cfg.compactionThresholdPercent ?? 90,
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
    contextWindowTokens: tpl.contextWindowTokens ?? 1_000_000,
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
  const preserveRecentTokens = f.compactionPreserveRecentTokens === '' ? null : f.compactionPreserveRecentTokens
  const reservedTokens = f.compactionReservedTokens === '' ? null : f.compactionReservedTokens
  const compactionModelConfigId = Number.isInteger(f.compactionModelConfigId) && f.compactionModelConfigId > 0
    ? f.compactionModelConfigId : 0
  if (!f.configName.trim()) { ElMessage.warning('请输入配置名称'); return }
  if (!f.baseUrl.trim()) { ElMessage.warning('请输入 Base URL'); return }
  if (!f.modelName.trim()) { ElMessage.warning('请输入模型名称'); return }
  if (!mcEditingId.value && !f.apiKey.trim()) { ElMessage.warning('请输入 API Key'); return }
  if (!Number.isFinite(f.maxTokens) || !Number.isInteger(f.maxTokens) || f.maxTokens <= 0) { ElMessage.warning('Max Tokens 必须为正整数'); return }
  if (!Number.isFinite(f.contextWindowTokens) || !Number.isInteger(f.contextWindowTokens) || f.contextWindowTokens <= 0) { ElMessage.warning('上下文窗口 Tokens 必须为正整数'); return }
  if (f.contextWindowTokens <= f.maxTokens) { ElMessage.warning('上下文窗口 Tokens 必须大于 Max Tokens'); return }
  if (!['low', 'medium', 'high', 'xhigh'].includes(f.reasoningEffort)) { ElMessage.warning('推理程度必须为低、中、高或超高'); return }
  if (!Number.isFinite(f.compactionTailTurns) || !Number.isInteger(f.compactionTailTurns) || f.compactionTailTurns <= 0) { ElMessage.warning('最近保留回合数必须为正整数'); return }
  if (!Number.isFinite(f.compactionThresholdPercent) || !Number.isInteger(f.compactionThresholdPercent) || f.compactionThresholdPercent < 70 || f.compactionThresholdPercent > 99) { ElMessage.warning('自动压缩触发阈值必须是 70 到 99 之间的整数'); return }
  if (preserveRecentTokens != null && (!Number.isFinite(preserveRecentTokens) || !Number.isInteger(preserveRecentTokens) || preserveRecentTokens <= 0)) { ElMessage.warning('最近上下文 Token 预算必须为正整数'); return }
  if (reservedTokens != null && (!Number.isFinite(reservedTokens) || !Number.isInteger(reservedTokens) || reservedTokens < 0)) { ElMessage.warning('压缩安全缓冲必须是非负整数'); return }
  mcSaving.value = true
  try {
    const payload = {
      configName: f.configName.trim(),
      provider: f.provider,
      modelName: f.modelName.trim(),
      baseUrl: f.baseUrl.trim(),
      maxTokens: f.maxTokens,
      contextWindowTokens: f.contextWindowTokens,
      promptCacheKeyEnabled: f.promptCacheKeyEnabled,
      reasoningEffort: f.reasoningEffort,
      imageInputEnabled: f.imageInputEnabled,
      compactionAuto: f.compactionAuto,
      compactionPrune: f.compactionPrune,
      compactionTailTurns: f.compactionTailTurns,
      compactionPreserveRecentTokens: preserveRecentTokens,
      compactionReservedTokens: reservedTokens,
      compactionModelConfigId,
      compactionThresholdPercent: f.compactionThresholdPercent,
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

async function stopGeneration() {
  agentLoading.value = false
  await stopAgent(projectId.value, currentAgentSession.value?.sessionId)
}
function startTerminalResize(event) {
  const center = workspaceCenterRef.value
  const handle = event.currentTarget
  if (!center || !handle) return
  event.preventDefault()
  const pointerId = event.pointerId
  const startY = event.clientY
  const startHeight = terminalHeight.value
  let latestY = startY
  let frame = null

  const applyHeight = () => {
    frame = null
    const maxHeight = Math.max(140, Math.floor(center.clientHeight * 0.7))
    terminalHeight.value = Math.min(maxHeight, Math.max(140, startHeight + startY - latestY))
    nextTick(() => terminalPanelRef.value?.fitAllTerminals())
  }
  const onMove = moveEvent => {
    latestY = moveEvent.clientY
    if (frame == null) frame = requestAnimationFrame(applyHeight)
  }
  const finish = () => {
    if (frame != null) cancelAnimationFrame(frame)
    handle.removeEventListener('pointermove', onMove)
    handle.removeEventListener('pointerup', finish)
    handle.removeEventListener('pointercancel', finish)
    if (handle.hasPointerCapture?.(pointerId)) handle.releasePointerCapture(pointerId)
    terminalPanelRef.value?.fitAllTerminals()
  }

  handle.setPointerCapture?.(pointerId)
  handle.addEventListener('pointermove', onMove)
  handle.addEventListener('pointerup', finish)
  handle.addEventListener('pointercancel', finish)
}

async function toggleTerminalPanel() {
  terminalPanelVisible.value = !terminalPanelVisible.value
  if (terminalPanelVisible.value) {
    await nextTick()
    terminalPanelRef.value?.fitAllTerminals()
  }
}
function resetRenderedConversation() {
  conversationRenderEpoch.value += 1
}

function clearMessages() {
  resetRenderedConversation()
  clearConversationState()
}

function createNewSession() {
  conversationSelectionGuard.invalidate()
  invalidateTaskRuntime()
  disconnectAgentStream()
  showSessions.value = false
  showContextUsageDialog.value = false
  contextUsageStatus.value = null
  nextContextPreview.value = null
  nextContextPreviewLoading.value = false
  selectedSessionIdx.value = -1
  resetRenderedConversation()
  resetConversation()
}

async function selectConversation(conversation, { explicit = true } = {}) {
  if (explicit) conversationSelectionGuard.invalidate()
  if (!conversation?.conversationId || currentAgentSession.value?.conversationId === conversation.conversationId) {
    return false
  }
  showSessions.value = false
  resetRenderedConversation()
  try {
    const loaded = await selectConversationState(conversation)
    if (loaded) {
      await syncConversationTaskTimings(conversation.conversationId)
      await loadContextUsageStatus(conversation.conversationId)
      void recoverActiveTaskForConversation(conversation.conversationId)
    }
    return loaded
  } catch (error) {
    ElMessage.error('\u52a0\u8f7d\u5386\u53f2\u6d88\u606f\u5931\u8d25')
    return false
  }
}

async function loadConversationMessages(conversationId) {
  resetRenderedConversation()
  try {
    const loaded = await loadConversationMessagesState(conversationId)
    if (loaded) {
      await syncConversationTaskTimings(conversationId)
      await loadContextUsageStatus(conversationId)
      void recoverActiveTaskForConversation(conversationId)
    }
    return loaded
  } catch (error) {
    ElMessage.error('\u52a0\u8f7d\u5386\u53f2\u6d88\u606f\u5931\u8d25')
    return false
  }
}

async function openContextUsageDialog() {
  const conversationId = currentAgentSession.value?.conversationId
  nextContextPreview.value = null
  if (conversationId) await loadContextUsageStatus(conversationId)
  showContextUsageDialog.value = true
}

async function loadNextContextPreview() {
  const conversationId = currentAgentSession.value?.conversationId
  if (!conversationId || !projectId.value || nextContextPreviewLoading.value) return

  const selectionGeneration = conversationSelectionGuard.capture()
  nextContextPreviewLoading.value = true
  try {
    const response = await projectApi.agentNextContextPreview(projectId.value, conversationId, {
      modelConfigId: selectedModelConfigId.value || null,
      activePath: activePath.value || '',
      agentMode: agentMode.value,
      draftMessage: agentInput.value.trim()
    })
    if (!conversationSelectionGuard.isCurrent(selectionGeneration)
      || currentAgentSession.value?.conversationId !== conversationId) return
    nextContextPreview.value = response.data || null
  } catch (error) {
    if (!conversationSelectionGuard.isCurrent(selectionGeneration)
      || currentAgentSession.value?.conversationId !== conversationId) return
    nextContextPreview.value = null
    ElMessage.error('\u751f\u6210\u4e0b\u4e00\u6b21\u8bf7\u6c42\u9884\u6d4b\u5931\u8d25: ' + (error?.response?.data?.message || error?.message || '\u672a\u77e5\u9519\u8bef'))
  } finally {
    nextContextPreviewLoading.value = false
  }
}

async function loadContextUsageStatus(conversationId) {
  if (!conversationId || !projectId.value) {
    contextUsageStatus.value = null
    return
  }
  try {
    const response = await projectApi.agentContextStatus(projectId.value, conversationId)
    if (currentAgentSession.value?.conversationId !== conversationId) return
    contextUsageStatus.value = resolveContextUsageStatus(contextUsageStatus.value, response.data, conversationId)
  } catch (error) {
    if (currentAgentSession.value?.conversationId !== conversationId) return
    contextUsageStatus.value = resolveContextUsageStatus(contextUsageStatus.value, null, conversationId)
  }
}

function replayHistoryEvent(type, data, message) {
  reduceHistoryEvent(type, data, message, {
    onPendingChange: () => { changesRefreshKey.value++ },
    onUserQuestion: attachUserQuestion,
    onTokenUsage: usage => {
      tokenUsage.value.promptTokens += usage.promptTokens || 0
      tokenUsage.value.completionTokens += usage.completionTokens || 0
      tokenUsage.value.totalTokens += usage.totalTokens || 0
      tokenUsage.value.callCount++
      tokenUsage.value.conversationTotal = usage.conversationTotal || tokenUsage.value.totalTokens
    },
    onContextStatus: status => { contextUsageStatus.value = status }
  })
}

async function forkConversation(conversation) {
  try {
    const result = await forkConversationState(conversation)
    if (!result.success) {
      ElMessage.error(result.message)
      return false
    }
    showSessions.value = false
    ElMessage.success('\u5df2\u521b\u5efa\u4f1a\u8bdd\u5206\u652f')
    return true
  } catch (error) {
    ElMessage.error('\u521b\u5efa\u5206\u652f\u5931\u8d25\uff1a' + (error?.response?.data?.message || error?.message || '\u672a\u77e5\u9519\u8bef'))
    return false
  }
}

async function forkCurrentConversation() {
  if (!currentAgentSession.value?.conversationId) {
    ElMessage.info('\u5f53\u524d\u8fd8\u6ca1\u6709\u53ef\u5206\u652f\u7684\u4f1a\u8bdd')
    return
  }
  const conversation = conversations.value.find(item => item.conversationId === currentAgentSession.value.conversationId)
  await forkConversation(conversation || { conversationId: currentAgentSession.value.conversationId })
}

async function deleteConversation(conversation) {
  try {
    return await deleteConversationState(conversation)
  } catch (error) {
    ElMessage.error('\u5220\u9664\u5931\u8d25')
    return false
  }
}

function getMergedItems(msg) {
  const items = []
  if (msg.thinkingBlocks) {
    for (const tb of msg.thinkingBlocks) {
      items.push({ type: 'thinking', data: tb, _order: tb._order || 0 })
    }
  }
  if (msg.contextManagementEvents) {
    for (const event of msg.contextManagementEvents) {
      items.push({ type: 'context', data: event, _order: event._order || 0 })
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

let agentRenderFrame = null
function scheduleAgentRender() {
  if (agentRenderFrame != null) return
  agentRenderFrame = requestAnimationFrame(async () => {
    agentRenderFrame = null
    await nextTick()
    scrollDown()
  })
}
function startThinkingReveal(msg) {
  msg._thinkingDisplay = msg.thinking || ''
}
function stopThinkingReveal(msg) {
  msg._thinkingTimer = null
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
async function loadOlderHistory() {
  const container = msgContainer.value
  const oldScrollHeight = container?.scrollHeight || 0
  const oldScrollTop = container?.scrollTop || 0
  const loaded = await loadOlderMessages()
  if (!loaded || !container) return
  await nextTick()
  container.scrollTop = container.scrollHeight - oldScrollHeight + oldScrollTop
}

function handleScroll() {
  if (!msgContainer.value) return
  const { scrollTop, scrollHeight, clientHeight } = msgContainer.value
  if (scrollTop <= 48 && hasOlderMessages.value && !loadingOlderMessages.value) {
    void loadOlderHistory()
  }
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
function toggleAiTheme() { themeStore.toggleLightDark() }
function refreshContext() { ElMessage.success('上下文已刷新') }
function copyMessage(content) { navigator.clipboard?.writeText(content); ElMessage.success('已复制') }
function insertToEditor(content) { ElMessage.success('代码已插入编辑器') }
function renderMarkdown(text) {
  if (!text) return ''
  const rawHtml = marked.parse(normalizeSpecialMarkdownBlocks(text), { gfm: true, breaks: true, silent: true })
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

    // Mermaid gets a custom block with chart/code tabs (rendered async after
    // the HTML is mounted). Other languages use the standard code block UI.
    if (lang === 'mermaid') {
      const wrapper = document.createElement('div')
      wrapper.className = 'mermaid-block'
      wrapper.dataset.code = rawCode

      const tabs = document.createElement('div')
      tabs.className = 'mermaid-tabs'
      const tabChart = document.createElement('button')
      tabChart.type = 'button'
      tabChart.className = 'mermaid-tab is-active'
      tabChart.dataset.view = 'chart'
      tabChart.innerHTML = `${iconSvg('chart')}<span>图表</span>`
      const tabCode = document.createElement('button')
      tabCode.type = 'button'
      tabCode.className = 'mermaid-tab'
      tabCode.dataset.view = 'code'
      tabCode.innerHTML = `${iconSvg('code')}<span>代码</span>`
      tabs.append(tabChart, tabCode)

      const tools = document.createElement('div')
      tools.className = 'mermaid-tools'
      const zoomOut = document.createElement('button')
      zoomOut.type = 'button'
      zoomOut.className = 'mermaid-tool-btn'
      zoomOut.dataset.action = 'zoom-out'
      zoomOut.title = '缩小'
      zoomOut.innerHTML = iconSvg('zoom-out')
      const zoomIn = document.createElement('button')
      zoomIn.type = 'button'
      zoomIn.className = 'mermaid-tool-btn'
      zoomIn.dataset.action = 'zoom-in'
      zoomIn.title = '放大'
      zoomIn.innerHTML = iconSvg('zoom-in')
      const dl = document.createElement('button')
      dl.type = 'button'
      dl.className = 'mermaid-tool-btn'
      dl.dataset.action = 'download'
      dl.title = '下载 SVG'
      dl.innerHTML = iconSvg('download')
      const fs = document.createElement('button')
      fs.type = 'button'
      fs.className = 'mermaid-tool-btn'
      fs.dataset.action = 'fullscreen'
      fs.title = '全屏'
      fs.innerHTML = iconSvg('fullscreen')
      tools.append(zoomOut, zoomIn, dl, fs)

      const header = document.createElement('div')
      header.className = 'mermaid-header'
      header.append(tabs, tools)

      const chartArea = document.createElement('div')
      chartArea.className = 'mermaid-chart'
      const status = document.createElement('div')
      status.className = 'mermaid-status'
      status.textContent = '正在渲染图表…'
      chartArea.append(status)

      const codeArea = document.createElement('div')
      codeArea.className = 'mermaid-code'
      codeArea.hidden = true
      const codePre = document.createElement('pre')
      const codeEl = document.createElement('code')
      codeEl.className = 'language-mermaid'
      codeEl.textContent = rawCode
      codePre.append(codeEl)
      codeArea.append(codePre)

      wrapper.append(header, chartArea, codeArea)
      pre.parentNode.insertBefore(wrapper, pre)
      pre.remove()
      return
    }

    const wrapper = document.createElement('div')
    wrapper.className = 'code-block'
    wrapper.dataset.lang = lang
    const header = document.createElement('div')
    header.className = 'code-block-header'
    const label = document.createElement('span')
    label.className = 'code-lang'
    label.innerHTML = `${iconSvg('code')}<span>${lang}</span>`
    const tools = document.createElement('div')
    tools.className = 'code-tools'
    const dl = document.createElement('button')
    dl.type = 'button'
    dl.className = 'code-tool-btn'
    dl.dataset.action = 'download'
    dl.title = '下载代码'
    dl.innerHTML = iconSvg('download')
    const copy = document.createElement('button')
    copy.type = 'button'
    copy.className = 'code-copy-btn'
    copy.dataset.code = rawCode
    copy.title = '复制代码'
    copy.innerHTML = `${iconSvg('copy')}<span>复制</span>`
    tools.append(dl, copy)
    header.append(label, tools)
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
    const tools = document.createElement('div')
    tools.className = 'msg-table-tools'
    const copy = document.createElement('button')
    copy.type = 'button'
    copy.className = 'msg-table-tool'
    copy.dataset.action = 'copy'
    copy.dataset.table = tableText
    copy.title = '复制为 TSV'
    copy.setAttribute('aria-label', '复制')
    copy.innerHTML = iconSvg('copy')
    const dl = document.createElement('button')
    dl.type = 'button'
    dl.className = 'msg-table-tool'
    dl.dataset.action = 'download'
    dl.dataset.table = tableText
    dl.title = '下载为 CSV'
    dl.setAttribute('aria-label', '下载')
    dl.innerHTML = iconSvg('download')
    const fs = document.createElement('button')
    fs.type = 'button'
    fs.className = 'msg-table-tool'
    fs.dataset.action = 'fullscreen'
    fs.title = '全屏查看'
    fs.setAttribute('aria-label', '全屏')
    fs.innerHTML = iconSvg('fullscreen')
    tools.append(copy, dl, fs)
    header.append(tag, tools)
    const scroll = document.createElement('div')
    scroll.className = 'msg-table-scroll'
    table.parentNode.insertBefore(wrap, table)
    scroll.appendChild(table)
    wrap.append(header, scroll)
  })

  enhanceCallouts(template.content)
  enhanceFileLinks(template.content, { iconHtml: iconSvg('file') })

  return template.innerHTML
}

// File-link detection lives in src/utils/fileLinks.js — imported above.

function iconSvg(name) {
  const stroke = 'currentColor'
  const sw = 2
  switch (name) {
    case 'copy':
      return `<svg viewBox="0 0 24 24" width="13" height="13" fill="none" stroke="${stroke}" stroke-width="${sw}" stroke-linecap="round" stroke-linejoin="round"><rect x="9" y="9" width="11" height="11" rx="2"/><path d="M5 15H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v1"/></svg>`
    case 'download':
      return `<svg viewBox="0 0 24 24" width="13" height="13" fill="none" stroke="${stroke}" stroke-width="${sw}" stroke-linecap="round" stroke-linejoin="round"><path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"/><polyline points="7 10 12 15 17 10"/><line x1="12" y1="15" x2="12" y2="3"/></svg>`
    case 'zoom-in':
      return `<svg viewBox="0 0 24 24" width="13" height="13" fill="none" stroke="${stroke}" stroke-width="${sw}" stroke-linecap="round" stroke-linejoin="round"><circle cx="11" cy="11" r="7"/><line x1="21" y1="21" x2="16.65" y2="16.65"/><line x1="11" y1="8" x2="11" y2="14"/><line x1="8" y1="11" x2="14" y1="11"/></svg>`
    case 'zoom-out':
      return `<svg viewBox="0 0 24 24" width="13" height="13" fill="none" stroke="${stroke}" stroke-width="${sw}" stroke-linecap="round" stroke-linejoin="round"><circle cx="11" cy="11" r="7"/><line x1="21" y1="21" x2="16.65" y2="16.65"/><line x1="8" y1="11" x2="14" y1="11"/></svg>`
    case 'fullscreen':
      return `<svg viewBox="0 0 24 24" width="13" height="13" fill="none" stroke="${stroke}" stroke-width="${sw}" stroke-linecap="round" stroke-linejoin="round"><path d="M8 3H5a2 2 0 0 0-2 2v3"/><path d="M21 8V5a2 2 0 0 0-2-2h-3"/><path d="M3 16v3a2 2 0 0 0 2 2h3"/><path d="M16 21h3a2 2 0 0 0 2-2v-3"/></svg>`
    case 'code':
      return `<svg viewBox="0 0 24 24" width="13" height="13" fill="none" stroke="${stroke}" stroke-width="${sw}" stroke-linecap="round" stroke-linejoin="round"><polyline points="16 18 22 12 16 6"/><polyline points="8 6 2 12 8 18"/></svg>`
    case 'chart':
      return `<svg viewBox="0 0 24 24" width="13" height="13" fill="none" stroke="${stroke}" stroke-width="${sw}" stroke-linecap="round" stroke-linejoin="round"><line x1="18" y1="20" x2="18" y2="10"/><line x1="12" y1="20" x2="12" y2="4"/><line x1="6" y1="20" x2="6" y2="14"/></svg>`
    case 'table':
      return `<svg viewBox="0 0 24 24" width="13" height="13" fill="none" stroke="${stroke}" stroke-width="${sw}" stroke-linecap="round" stroke-linejoin="round"><rect x="3" y="3" width="18" height="18" rx="2"/><line x1="3" y1="9" x2="21" y2="9"/><line x1="3" y1="15" x2="21" y2="15"/><line x1="9" y1="3" x2="9" y2="21"/></svg>`
    case 'file':
      return `<svg viewBox="0 0 24 24" width="12" height="12" fill="none" stroke="${stroke}" stroke-width="${sw}" stroke-linecap="round" stroke-linejoin="round"><path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z"/><polyline points="14 2 14 8 20 8"/></svg>`
    case 'check':
      return `<svg viewBox="0 0 24 24" width="12" height="12" fill="none" stroke="${stroke}" stroke-width="${sw}" stroke-linecap="round" stroke-linejoin="round"><polyline points="20 6 9 17 4 12"/></svg>`
    default:
      return ''
  }
}

function downloadTextFile(filename, text) {
  const blob = new Blob([text], { type: 'text/plain;charset=utf-8' })
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = filename
  document.body.appendChild(a)
  a.click()
  a.remove()
  setTimeout(() => URL.revokeObjectURL(url), 1000)
}

function downloadBlob(filename, blob) {
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = filename
  document.body.appendChild(a)
  a.click()
  a.remove()
  setTimeout(() => URL.revokeObjectURL(url), 1000)
}

// Render every .mermaid-block contained in `root` that hasn't been rendered
// yet (data-rendered !== '1'). Called after the HTML is mounted.
async function renderMermaidBlocks(root) {
  if (!root || typeof root.querySelectorAll !== 'function') return
  const blocks = root.querySelectorAll('.mermaid-block:not([data-rendered="1"])')
  for (const block of blocks) {
    if (block.dataset.rendered === 'pending') continue
    block.dataset.rendered = 'pending'
    const code = block.dataset.code || ''
    const chart = block.querySelector('.mermaid-chart')
    const status = block.querySelector('.mermaid-status')
    const result = await renderMermaidDiagram(code)
    if (!chart) continue
    if (result.ok) {
      chart.innerHTML = result.svg
      const svg = chart.querySelector('svg')
      if (svg) {
        svg.style.maxWidth = '100%'
        svg.style.height = 'auto'
        svg.dataset.scale = svg.dataset.scale || '1'
      }
    } else {
      if (status) status.remove()
      const err = document.createElement('div')
      err.className = 'mermaid-error'
      err.textContent = result.error
      chart.append(err)
    }
    block.dataset.rendered = '1'
  }
}

function handleMarkdownClick(event) {
  // File path — open in workspace
  const fileLink = event.target?.closest?.('.file-link')
  if (fileLink) {
    const path = fileLink.dataset.path
    if (path) {
      openFile(path).then(() => {
        if (!openFiles.value.some((f) => f.path === path)) {
          ElMessage.warning(`无法打开 ${path}`)
        }
      })
    }
    return
  }

  // Code block — copy
  const copyBtn = event.target?.closest?.('.code-copy-btn')
  if (copyBtn) {
    navigator.clipboard?.writeText(copyBtn.dataset.code || '')
    ElMessage.success('代码已复制')
    return
  }

  // Code block — download
  const codeDlBtn = event.target?.closest?.('.code-block .code-tool-btn[data-action="download"]')
  if (codeDlBtn) {
    const block = codeDlBtn.closest('.code-block')
    const lang = block?.dataset.lang || 'txt'
    const realCode = block?.querySelector('pre code')?.textContent || ''
    const ext = lang === 'text' ? 'txt' : lang
    downloadTextFile(`code.${ext}`, realCode)
    return
  }

  // Table — copy / download / fullscreen
  const tableTool = event.target?.closest?.('.msg-table-tool')
  if (tableTool) {
    const wrap = tableTool.closest('.msg-table-wrap')
    const action = tableTool.dataset.action
    if (action === 'copy') {
      navigator.clipboard?.writeText(tableTool.dataset.table || '')
      ElMessage.success('表格已复制（TSV，可粘贴到 Excel）')
      return
    }
    if (action === 'download') {
      const tsv = tableTool.dataset.table || ''
      const csv = tsv.replace(/\t/g, ',').replace(/\n/g, '\r\n')
      downloadTextFile('table.csv', csv)
      return
    }
    if (action === 'fullscreen') {
      if (wrap) wrap.classList.toggle('is-fullscreen')
      return
    }
  }

  // Mermaid — tab switch
  const mermaidTab = event.target?.closest?.('.mermaid-tab')
  if (mermaidTab) {
    const block = mermaidTab.closest('.mermaid-block')
    if (!block) return
    const view = mermaidTab.dataset.view
    block.querySelectorAll('.mermaid-tab').forEach((t) => t.classList.toggle('is-active', t === mermaidTab))
    const chart = block.querySelector('.mermaid-chart')
    const code = block.querySelector('.mermaid-code')
    if (chart) chart.hidden = view !== 'chart'
    if (code) code.hidden = view !== 'code'
    return
  }

  // Mermaid — tool button (zoom in/out, download, fullscreen)
  const toolBtn = event.target?.closest?.('.mermaid-tool-btn')
  if (toolBtn) {
    const block = toolBtn.closest('.mermaid-block')
    if (!block) return
    const action = toolBtn.dataset.action
    if (action === 'zoom-in' || action === 'zoom-out') {
      const svg = block.querySelector('.mermaid-chart svg')
      if (!svg) return
      const cur = parseFloat(svg.dataset.scale || '1')
      const next = action === 'zoom-in' ? Math.min(cur + 0.2, 3) : Math.max(cur - 0.2, 0.4)
      svg.dataset.scale = String(next)
      svg.style.transform = `scale(${next})`
      svg.style.transformOrigin = 'top center'
      svg.style.transition = 'transform 0.2s'
      return
    }
    if (action === 'download') {
      const svg = block.querySelector('.mermaid-chart svg')
      if (!svg) return
      const serialized = new XMLSerializer().serializeToString(svg)
      const blob = new Blob([serialized], { type: 'image/svg+xml;charset=utf-8' })
      downloadBlob('diagram.svg', blob)
      return
    }
    if (action === 'fullscreen') {
      block.classList.toggle('is-fullscreen')
      return
    }
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
    const r = await projectApi.optimizePrompt(projectId.value, {
      message: originalPrompt,
      modelConfigId: selectedModelConfigId.value || null
    })
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

function startSidebarResize(e) {
  const handle = e.currentTarget
  const startX = e.clientX
  const startWidth = sidebarWidth.value
  let latestX = startX
  let frame = null
  handle.setPointerCapture?.(e.pointerId)
  const apply = () => {
    frame = null
    sidebarWidth.value = Math.max(180, Math.min(startWidth + latestX - startX, 520))
  }
  const move = event => { latestX = event.clientX; if (frame == null) frame = requestAnimationFrame(apply) }
  const finish = () => {
    if (frame != null) { cancelAnimationFrame(frame); frame = null; apply() }
    handle.removeEventListener('pointermove', move)
    handle.removeEventListener('pointerup', finish)
    handle.removeEventListener('pointercancel', finish)
  }
  handle.addEventListener('pointermove', move)
  handle.addEventListener('pointerup', finish)
  handle.addEventListener('pointercancel', finish)
}

// Resize
let resizeFrame = null
function startResize(e) {
  const handle = e.currentTarget
  const panel = handle.closest('.ai-panel')
  if (!panel) return
  const startX = e.clientX
  const startWidth = panel.offsetWidth || 340
  let latestX = startX
  panel.classList.add('is-resizing')
  handle.setPointerCapture?.(e.pointerId)

  const applyWidth = () => {
    resizeFrame = null
    const diff = startX - latestX
    const width = Math.max(280, Math.min(startWidth + diff, window.innerWidth * 0.7))
    panel.style.width = `${width}px`
  }
  const onMove = event => {
    latestX = event.clientX
    if (resizeFrame == null) resizeFrame = requestAnimationFrame(applyWidth)
  }
  const finish = () => {
    if (resizeFrame != null) {
      cancelAnimationFrame(resizeFrame)
      resizeFrame = null
      applyWidth()
    }
    panel.classList.remove('is-resizing')
    handle.removeEventListener('pointermove', onMove)
    handle.removeEventListener('pointerup', finish)
    handle.removeEventListener('pointercancel', finish)
  }
  handle.addEventListener('pointermove', onMove)
  handle.addEventListener('pointerup', finish)
  handle.addEventListener('pointercancel', finish)
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
.ws-body { flex: 1; display: flex; overflow: hidden; min-height: 0; }
.ws-center { flex: 1; min-width: 0; min-height: 0; display: flex; flex-direction: column; overflow: hidden; background: #fff; }

/* ===== Left Sidebar (File Tree) ===== */
.ws-sidebar { width: 240px; border-right: 1px solid #f0f0f0; display: flex; flex-direction: column; background: #fafbfc; flex-shrink: 0; }
.ws-sidebar-header { display: flex; align-items: center; justify-content: space-between; padding: 10px 12px; font-size: 11px; font-weight: 600; color: #9ca3af; text-transform: uppercase; letter-spacing: 0.5px; border-bottom: 1px solid #f0f0f0; }
.ws-sidebar-actions { display: flex; gap: 2px; text-transform: none; letter-spacing: 0; font-size: 12px; font-weight: 500; color: #374151; }
.ws-resize-handle { width: 5px; cursor: col-resize; flex: 0 0 5px; background: transparent; }
.ws-resize-handle:hover { background: #cbd5e1; }
.ws-tree { flex: 1; overflow-y: auto; padding: 6px 4px; }
.ws-tree *:focus { outline: none !important; }
.ws-tree *:focus-visible { outline: none !important; }
.ws-tree-empty { padding: 16px; color: #9ca3af; font-size: 13px; text-align: center; }
.ws-tree-error { margin: 8px; padding: 8px; border: 1px solid #fecaca; border-radius: 6px; background: #fef2f2; color: #b91c1c; font-size: 12px; line-height: 1.5; }
.ws-tree-error button { margin-top: 6px; border: 0; border-radius: 4px; padding: 4px 7px; background: #fee2e2; color: inherit; font: inherit; cursor: pointer; }
.ws-tree-load-more { display: block; width: calc(100% - 8px); margin: 6px 4px; padding: 7px 8px; border: 1px solid #dbe1f0; border-radius: 6px; background: #fff; color: #4f46e5; font: inherit; font-size: 12px; cursor: pointer; }
.ws-tree-load-more:hover { background: #eef2ff; border-color: #c7d2fe; }

/* ===== Editor ===== */
.ws-editor { flex: 1; display: flex; flex-direction: column; overflow: hidden; background: #fff; min-width: 0; min-height: 0; }
.ws-terminal-resize-handle { flex: 0 0 5px; height: 5px; cursor: row-resize; background: transparent; border-top: 1px solid #dfe3e8; position: relative; z-index: 3; touch-action: none; }
.ws-terminal-resize-handle::after { content: ''; position: absolute; left: 50%; top: 1px; width: 44px; height: 2px; transform: translateX(-50%); border-radius: 2px; background: #c7cdd4; opacity: 0; transition: opacity .15s; }
.ws-terminal-resize-handle:hover::after { opacity: 1; }
.ws-terminal-dock { flex: 0 0 auto; min-height: 140px; max-height: 70%; border-top: 0; background: #fff; display: flex; flex-direction: column; overflow: hidden; }
.ws-terminal-dock-header { height: 34px; flex: 0 0 auto; display: flex; align-items: center; justify-content: space-between; padding: 0 10px; background: #f6f8fa; border-bottom: 1px solid #dfe3e8; }
.ws-terminal-dock.dark .ws-terminal-dock-header { background: #252526; color: #d4d4d4; border-color: #3c3c3c; }
.ws-terminal-dock :deep(.terminal-panel) { flex: 1; min-height: 0; }
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


/* 上下文压缩状态卡片 */
.ai-context-management-card {
  margin: 8px 0;
  padding: 10px 12px;
  border: 1px solid var(--ai-border-strong);
  border-left: 3px solid #3b82f6;
  border-radius: 10px;
  background: color-mix(in srgb, var(--ai-bg-secondary) 88%, #3b82f6 12%);
  box-shadow: 0 8px 20px rgba(15, 23, 42, 0.06);
}
.context-management-card-header,
.context-management-card-meta {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 7px;
}
.context-management-card-header {
  color: var(--ai-text);
  font-size: 12.5px;
}
.context-management-status {
  margin-left: auto;
  color: var(--ai-text-muted);
  font-size: 11px;
}
.context-management-cancel {
  margin-left: auto;
  border: 1px solid var(--ai-border-strong);
  border-radius: 6px;
  background: transparent;
  color: var(--ai-text-muted);
  cursor: pointer;
  font-size: 11px;
  line-height: 22px;
  padding: 0 7px;
}
.context-management-cancel:hover { color: #dc2626; border-color: #fca5a5; background: #fef2f2; }

.context-management-indicator {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: #22c55e;
  box-shadow: 0 0 0 3px rgba(34, 197, 94, 0.14);
}
.ai-context-management-card.is-running .context-management-indicator {
  background: #3b82f6;
  animation: context-management-pulse 1.25s ease-in-out infinite;
}
.ai-context-management-card.is-warning {
  border-left-color: #f59e0b;
  background: color-mix(in srgb, var(--ai-bg-secondary) 88%, #f59e0b 12%);
}
.ai-context-management-card.is-warning .context-management-indicator {
  background: #f59e0b;
  box-shadow: 0 0 0 3px rgba(245, 158, 11, 0.14);
}
.context-management-card-meta {
  margin-top: 7px;
  color: var(--ai-text-muted);
  font-size: 11.5px;
}
.context-management-card-meta span:not(:last-child)::after {
  margin-left: 7px;
  color: var(--ai-text-faint);
  content: '\00b7';
}
.context-management-released {
  color: #059669;
  font-weight: 700;
}
.context-management-reason {
  margin: 7px 0 0;
  color: var(--ai-text-muted);
  font-size: 11.5px;
  line-height: 1.55;
}
@keyframes context-management-pulse {
  0%, 100% { box-shadow: 0 0 0 3px rgba(59, 130, 246, 0.12); transform: scale(1); }
  50% { box-shadow: 0 0 0 7px rgba(59, 130, 246, 0); transform: scale(1.08); }
}

/* ===== Command Palette ===== */
.command-palette {
  position: absolute;
  bottom: 100%;
  left: 0;
  width: 100%;
  max-height: 340px;
  background: var(--ai-bg);
  border: 1px solid var(--ai-border);
  border-radius: 12px;
  box-shadow: 0 12px 32px rgba(0, 0, 0, 0.12);
  margin-bottom: 8px;
  display: flex;
  flex-direction: column;
  overflow: hidden;
  z-index: 100;
}

.command-palette-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 10px 14px;
  background: var(--ai-bg-secondary);
  border-bottom: 1px solid var(--ai-border);
}

.command-palette-title {
  font-size: 13px;
  font-weight: 700;
  color: var(--ai-text);
}

.command-palette-hint {
  font-size: 12px;
  color: var(--ai-text-muted);
}

.command-palette-search {
  padding: 10px;
  border-bottom: 1px solid var(--ai-border);
}

.command-search-input {
  width: 100%;
  padding: 8px 12px;
  font-size: 13.5px;
  background: transparent;
  border: 1px solid var(--ai-border-strong);
  border-radius: 6px;
  color: var(--ai-text);
  outline: none;
  transition: border-color 0.15s;
}

.command-search-input:focus {
  border-color: var(--ai-accent);
}

.command-palette-list {
  flex: 1;
  overflow-y: auto;
  padding: 8px;
}

.command-item {
  display: flex;
  flex-direction: column;
  padding: 10px 12px;
  border-radius: 8px;
  cursor: pointer;
  transition: background 0.15s;
}

.command-item.active,
.command-item:hover {
  background: var(--ai-bg-hover);
}

.command-item-main {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 4px;
}

.command-name {
  font-size: 14px;
  font-weight: 700;
  color: var(--ai-text);
}

.command-aliases {
  font-size: 12px;
  color: var(--ai-text-muted);
}

.command-item-desc {
  font-size: 12.5px;
  color: var(--ai-text-muted);
}

.command-empty {
  padding: 24px;
  text-align: center;
  font-size: 13.5px;
  color: var(--ai-text-muted);
}

</style>
<style>
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
  --ai-bg: #fcf9f2;
  --ai-bg-secondary: #f2ecdf;
  --ai-bg-tertiary: #e9e2d3;
  --ai-bg-elevated: #fcf9f2;
  --ai-border: rgba(0,0,0,0.06);
  --ai-border-strong: #e0d8c8;
  --ai-border-focus: #5c5545;
  --ai-text: #2f2b26;
  --ai-text-secondary: #4a453d;
  --ai-text-muted: #736d62;
  --ai-text-faint: #a1a1aa;
  --ai-accent: #18181b;
  --ai-accent-hover: #3f3f46;
  --ai-accent-bg: #f4f4f5;
  --ai-accent-border: #e4e4e7;
  --ai-purple: #d97757;
  --ai-purple-text: #c26143;
  --ai-purple-bg: #fff7ed;
  --ai-purple-border: #ffedd5;
  --ai-purple-deep: #b35035;
  --ai-green: #10b981;
  --ai-red: #ef4444;
  --ai-red-hover: #dc2626;
  --ai-yellow: #f59e0b;
  --ai-shadow-sm: 0 1px 2px rgba(0,0,0,0.03);
  --ai-shadow-md: 0 4px 12px rgba(0,0,0,0.05);
  --ai-shadow-lg: 0 8px 24px rgba(0,0,0,0.08);
  --ai-code-bg: #1e1e2e;
  --ai-code-text: #cdd6f4;
  --ai-inline-code-bg: #f4f4f5;
  --ai-inline-code-border: transparent;
  --ai-inline-code-text: #e11d48;
  --ai-font-size: 14px;
  --ai-msg-gap: 20px;
  --ai-radius: 12px;
  --ai-radius-sm: 8px;

  position: relative;
  width: 420px;
  min-width: 320px;
  max-width: 70vw;
  display: flex;
  flex-direction: column;
  background: var(--ai-bg-secondary);
  color: var(--ai-text);
  font-family: 'Inter', ui-sans-serif, system-ui, -apple-system, sans-serif;
  font-size: var(--ai-font-size);
  flex-shrink: 0;
  border-left: 1px solid var(--ai-border);
  transition: width 0.15s ease;
}

/* ===== Dark Theme ===== */
.ai-panel.dark {
  --ai-bg: #27272a;
  --ai-bg-secondary: #18181b;
  --ai-bg-tertiary: #3f3f46;
  --ai-bg-elevated: #27272a;
  --ai-border: rgba(255,255,255,0.08);
  --ai-border-strong: rgba(255,255,255,0.15);
  --ai-border-focus: #fafafa;
  --ai-text: #fafafa;
  --ai-text-secondary: #e4e4e7;
  --ai-text-muted: #a1a1aa;
  --ai-text-faint: #71717a;
  --ai-accent: #fafafa;
  --ai-accent-hover: #e4e4e7;
  --ai-accent-bg: rgba(255,255,255,0.1);
  --ai-accent-border: rgba(255,255,255,0.2);
  --ai-purple: #d97757;
  --ai-purple-text: #fdbca4;
  --ai-purple-bg: rgba(217,119,87,0.15);
  --ai-purple-border: rgba(217,119,87,0.3);
  --ai-purple-deep: #d97757;
  --ai-green: #9ece6a;
  --ai-red: #f7768e;
  --ai-red-hover: #e05f75;
  --ai-yellow: #e0af68;
  --ai-shadow-sm: 0 1px 4px rgba(0,0,0,0.3);
  --ai-shadow-md: 0 4px 16px rgba(0,0,0,0.4);
  --ai-shadow-lg: 0 8px 24px rgba(0,0,0,0.5);
  --ai-code-bg: #13141c;
  --ai-code-text: #c0caf5;
  --ai-inline-code-bg: #3f3f46;
  --ai-inline-code-border: transparent;
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
.ai-panel.is-resizing { transition: none; }

.ai-resize-handle:hover,
.ai-resize-handle:active {
  background: var(--ai-accent);
}

.ai-msg {
  display: flex;
  flex-direction: column;
  gap: 6px;
  animation: aiMsgIn 0.3s cubic-bezier(0.4, 0, 0.2, 1);
  min-width: 0;
  overflow: hidden;
  margin-bottom: var(--ai-msg-gap, 24px);
}
@keyframes aiMsgIn {
  from { opacity: 0; transform: translateY(8px); }
  to { opacity: 1; transform: translateY(0); }
}
@keyframes fadeIn {
  from { opacity: 0; }
  to { opacity: 1; }
}
.ai-msg-header {
  display: flex;
  align-items: center;
  gap: 12px;
}
.ai-msg-name {
  font-weight: 600;
  font-size: 14px;
  color: var(--ai-text);
}
.ai-msg-avatar {
  width: 28px;
  height: 28px;
  border-radius: 8px;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
}
.ai-msg.user .ai-msg-avatar {
  background: var(--ai-bg-tertiary);
  color: var(--ai-text);
}
.ai-msg.assistant .ai-msg-avatar {
  background: transparent;
  color: var(--ai-purple);
}
.ai-msg-body {
  flex: 1;
  min-width: 0;
  max-width: 100%;
  overflow: hidden;
  padding-left: 40px; /* Aligns with the text, skipping the 28px avatar + 12px gap */
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
  flex-shrink: 0;
  backdrop-filter: blur(12px);
  background: color-mix(in srgb, var(--ai-bg-secondary) 85%, transparent);
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
  font-size: 14px;
  font-weight: 600;
  font-family: 'Georgia', 'Times New Roman', serif;
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
  padding: 4px;
  background: var(--ai-bg-tertiary);
  border-radius: var(--ai-radius-sm);
  margin: 10px 12px;
  flex-shrink: 0;
}
.ai-tab {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 6px;
  flex: 1;
  padding: 6px 12px;
  font-size: 13px;
  font-weight: 500;
  color: var(--ai-text-muted);
  border: none;
  background: transparent;
  border-radius: 6px;
  cursor: pointer;
  transition: all 0.2s cubic-bezier(0.4, 0, 0.2, 1);
  font-family: inherit;
  white-space: nowrap;
}
.ai-tab:hover {
  color: var(--ai-text-secondary);
}
.ai-tab.active {
  color: var(--ai-text);
  background: var(--ai-bg-elevated);
  box-shadow: var(--ai-shadow-sm);
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
  min-width: 0;
}
.ai-messages::-webkit-scrollbar { width: 4px; }
.ai-messages::-webkit-scrollbar-track { background: transparent; }
.ai-messages::-webkit-scrollbar-thumb { background: var(--ai-border-strong); border-radius: 4px; }
.ai-history-load { display: block; margin: 0 auto 10px; padding: 5px 10px; border: 1px solid var(--ai-border); border-radius: 999px; background: var(--ai-bg-secondary); color: var(--ai-text-secondary); font: inherit; font-size: 12px; cursor: pointer; }
.ai-history-load:hover:not(:disabled) { border-color: var(--ai-accent-border); color: var(--ai-accent); }
.ai-history-load:disabled { cursor: wait; opacity: 0.65; }
.ai-content.is-empty {
  justify-content: center;
}
.ai-content.is-empty .ai-messages {
  flex: none;
  height: auto;
  overflow: visible;
  padding: 0;
}
.ai-empty {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 8px;
  padding: 0 16px 24px 16px;
  text-align: center;
}
.ai-empty-greeting {
  font-family: 'Georgia', 'Times New Roman', serif;
  font-size: 24px;
  font-weight: 500;
  color: var(--ai-text);
  margin: 0;
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
.ai-msg-content {
  font-size: var(--ai-font-size, 14px);
  line-height: 1.7;
  word-break: break-word;
  overflow-wrap: anywhere;
  min-width: 0;
  max-width: 100%;
  overflow-x: auto;
}
.ai-msg.user .ai-msg-content,
.ai-msg.assistant .ai-msg-content {
  background: transparent;
  color: var(--ai-text);
  border: none;
  border-radius: 0;
  box-shadow: none;
  padding: 0;
  max-width: 100%;
}
.ai-msg-text {
  white-space: normal;
  word-break: break-word;
  overflow-wrap: anywhere;
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

/* ===== Markdown-rendered HTML (v-html content) =====
   These rules style the elements produced by renderMarkdown/processMarkdown.
   They live here in <style scoped> so they carry [data-v-xxx] and outrank
   any future unscoped .markdown-rendered overrides. */
.markdown-rendered :deep(.msg-hr) {
  border: none;
  border-top: 1px solid var(--ai-border-strong);
  margin: 14px 0;
}
.markdown-rendered :deep(h5) {
  font-size: 12.5px;
  font-weight: 700;
  color: var(--ai-text-secondary);
  margin: 10px 0 4px;
}

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
  background: #fafafa;
  border: 1px dashed #d1d5db;
  border-radius: 10px;
  cursor: pointer;
  font-size: 11.5px;
  color: #6b7280;
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
  max-height: 400px;
  margin: 4px 0 0 18px;
  padding: 4px 0;
  overflow-y: auto;
  border: none;
  border-radius: 0;
  background: transparent;
  color: #7a6e5d;
  font-family: 'Georgia', 'Times New Roman', serif;
  font-style: italic;
  font-size: 13.5px;
  line-height: 1.6;
  white-space: normal;
}
.ai-thinking-body:empty::after {
  content: '思考中...';
  color: var(--ai-text-faint);
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
.ai-environment-retry {
  margin-top: 10px;
  border: 1px solid #f59e0b;
  border-radius: 6px;
  padding: 6px 10px;
  color: #92400e;
  background: #fffbeb;
  cursor: pointer;
}
.ai-environment-retry:disabled { opacity: .6; cursor: wait; }

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
.ai-content.is-empty .ai-input-area {
  border-top: none;
  background: transparent;
  width: 100%;
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
/* Claude 风格输入框布局重构 */
.ai-quick-actions {
  display: flex;
  gap: 8px;
  margin-bottom: 12px;
  flex-wrap: wrap;
}
.ai-quick-pill {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 6px 14px;
  background: var(--ai-bg);
  border: 1px solid var(--ai-border);
  border-radius: 20px;
  font-size: 13px;
  color: var(--ai-text-secondary);
  cursor: pointer;
  transition: all 0.2s cubic-bezier(0.4, 0, 0.2, 1);
  box-shadow: 0 1px 2px rgba(0,0,0,0.02);
}
.ai-quick-pill:hover:not(.active) {
  background: var(--ai-bg-secondary);
  color: var(--ai-text);
  transform: translateY(-1px);
  box-shadow: 0 3px 6px rgba(0,0,0,0.04);
}
.ai-quick-pill.active {
  background: var(--ai-text);
  color: #fff;
  border-color: var(--ai-text);
}

.ai-input-box {
  background: var(--ai-bg);
  border: 1px solid var(--ai-border);
  border-radius: 16px;
  padding: 12px 14px 10px;
  display: flex;
  flex-direction: column;
  gap: 10px;
  box-shadow: 0 4px 24px rgba(0,0,0,0.06);
  transition: box-shadow 0.2s, border-color 0.2s;
}
.ai-input-box:focus-within {
  border-color: var(--ai-accent);
  box-shadow: 0 4px 20px rgba(0,0,0,0.08);
}

.ai-input-text-area {
  display: flex;
}
.ai-input-text-area textarea {
  width: 100%;
  border: none;
  background: transparent;
  resize: none;
  font-size: 14px;
  line-height: 1.5;
  color: var(--ai-text);
  outline: none;
  min-height: 24px;
  max-height: 200px;
  padding: 0;
  font-family: inherit;
}
.ai-input-text-area textarea::placeholder {
  color: var(--ai-text-faint);
}

.ai-input-footer {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-top: 8px;
}
.ai-input-toolbar {
  display: flex;
  align-items: center;
  gap: 4px;
}
.ai-toolbar-btn {
  background: transparent;
  border: none;
  border-radius: 6px;
  padding: 6px;
  color: var(--ai-text-faint);
  display: flex;
  align-items: center;
  justify-content: center;
  cursor: pointer;
  transition: all 0.2s;
}
.ai-toolbar-btn:hover {
  background: var(--ai-bg-elevated);
  color: var(--ai-text-secondary);
}
.ai-toolbar-btn span {
  display: none; /* Hide labels to make it compact like Claude */
}
.ai-bar-right {
  display: flex;
  align-items: center;
  gap: 10px;
}

.ai-generating-indicator {
  display: flex;
  align-items: center;
  gap: 6px;
}
.gen-dot {
  width: 8px;
  height: 8px;
  background: var(--ai-accent);
  border-radius: 50%;
  animation: gen-pulse 1s infinite;
}
@keyframes gen-pulse {
  0%, 100% { opacity: 0.5; transform: scale(0.8); }
  50% { opacity: 1; transform: scale(1.1); }
}

.ai-submit-btn {
  width: 32px;
  height: 32px;
  border-radius: 50%;
  border: none;
  background: var(--ai-text);
  color: var(--ai-bg);
  display: flex;
  align-items: center;
  justify-content: center;
  cursor: pointer;
  transition: all 0.2s;
  box-shadow: 0 2px 8px rgba(0,0,0,0.1);
}
.ai-submit-btn:hover:not(:disabled) {
  transform: translateY(-1px);
  box-shadow: 0 4px 12px rgba(0,0,0,0.15);
  opacity: 0.9;
}
.ai-submit-btn:disabled {
  background: var(--ai-border-strong);
  color: var(--ai-text-faint);
  cursor: not-allowed;
  box-shadow: none;
}
.ai-submit-btn:disabled {
  background: var(--ai-border);
  color: #fff;
  cursor: not-allowed;
  box-shadow: none;
  transform: none;
}
.ai-send-stop {
  background: var(--ai-red);
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

/* File path chip — clickable, opens the file in the left workspace tree */
.markdown-rendered :deep(.file-link) {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 1px 7px 1px 5px;
  margin: 0 1px;
  color: var(--ai-accent);
  background: var(--ai-accent-bg);
  border: 1px solid color-mix(in srgb, var(--ai-accent) 22%, transparent);
  border-radius: 5px;
  font-family: 'JetBrains Mono', 'Fira Code', Consolas, monospace;
  font-size: 0.92em;
  font-weight: 500;
  cursor: pointer;
  white-space: nowrap;
  max-width: 100%;
  overflow: hidden;
  text-overflow: ellipsis;
  transition: all 0.15s;
  vertical-align: baseline;
}
.markdown-rendered :deep(.file-link:hover) {
  background: color-mix(in srgb, var(--ai-accent) 14%, var(--ai-bg));
  border-color: color-mix(in srgb, var(--ai-accent) 40%, transparent);
  color: var(--ai-accent);
}
.markdown-rendered :deep(.file-link:focus-visible) {
  outline: 2px solid color-mix(in srgb, var(--ai-accent) 50%, transparent);
  outline-offset: 1px;
}
.markdown-rendered :deep(.file-link svg) {
  flex-shrink: 0;
  opacity: 0.85;
}
.markdown-rendered :deep(.file-link .file-link-text) {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
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
  font-size: 16.5px;
  margin: 18px 0 10px;
  padding: 6px 0 6px 12px;
  border-bottom: 1px solid var(--ai-border-strong);
  border-left: 3px solid var(--ai-accent);
  background: linear-gradient(90deg, var(--ai-accent-bg) 0%, transparent 70%);
}
.markdown-rendered :deep(h3) {
  font-size: 14.5px;
  margin: 14px 0 6px;
  padding-left: 10px;
  border-left: 3px solid var(--ai-border-strong);
  color: var(--ai-text);
}
.markdown-rendered :deep(ul),
.markdown-rendered :deep(ol) {
  margin: 8px 0;
  padding-left: 22px;
  line-height: 1.7;
}
.markdown-rendered :deep(ul) {
  list-style: none;
}
.markdown-rendered :deep(ul > li) {
  position: relative;
  padding-left: 4px;
  margin: 4px 0;
}
.markdown-rendered :deep(ul > li::before) {
  content: '';
  position: absolute;
  left: -14px;
  top: 0.7em;
  width: 5px;
  height: 5px;
  border-radius: 50%;
  background: var(--ai-accent);
  opacity: 0.85;
}
.markdown-rendered :deep(ol > li) {
  margin: 4px 0;
  padding-left: 4px;
}
.markdown-rendered :deep(ol > li::marker) {
  color: var(--ai-accent);
  font-weight: 700;
  font-variant-numeric: tabular-nums;
}
.markdown-rendered :deep(li > p) {
  margin: 2px 0;
}
.markdown-rendered :deep(li > ul),
.markdown-rendered :deep(li > ol) {
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
  border: 1px solid var(--ai-border-strong);
  border-radius: 9px;
  overflow: hidden;
  background: var(--ai-bg-secondary);
  box-shadow: 0 1px 2px rgba(15, 23, 42, 0.04), 0 4px 12px rgba(15, 23, 42, 0.04);
}
.markdown-rendered :deep(.code-block-header) {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  min-height: 34px;
  padding: 6px 8px 6px 12px;
  background: linear-gradient(180deg, var(--ai-bg-tertiary) 0%, var(--ai-bg-secondary) 100%);
  border-bottom: 1px solid var(--ai-border-strong);
}
.markdown-rendered :deep(.code-lang) {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  padding: 3px 10px;
  color: var(--ai-accent);
  background: var(--ai-accent-bg);
  border: 1px solid color-mix(in srgb, var(--ai-accent) 18%, transparent);
  border-radius: 999px;
  font-family: 'JetBrains Mono', 'Fira Code', Consolas, monospace;
  font-size: 10.5px;
  font-weight: 700;
  letter-spacing: 0.02em;
  text-transform: lowercase;
}
.markdown-rendered :deep(.code-tools) {
  display: inline-flex;
  align-items: center;
  gap: 4px;
}
.markdown-rendered :deep(.code-tool-btn),
.markdown-rendered :deep(.code-copy-btn) {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 4px 10px;
  background: var(--ai-bg);
  color: var(--ai-text-muted);
  border: 1px solid var(--ai-border);
  border-radius: 7px;
  cursor: pointer;
  font-size: 11px;
  font-weight: 600;
  transition: all 0.15s;
}
.markdown-rendered :deep(.code-tool-btn) {
  padding: 4px 6px;
}
.markdown-rendered :deep(.code-tool-btn:hover),
.markdown-rendered :deep(.code-copy-btn:hover) {
  background: var(--ai-accent-bg);
  color: var(--ai-accent);
  border-color: color-mix(in srgb, var(--ai-accent) 28%, transparent);
}
.markdown-rendered :deep(pre) {
  margin: 0;
  padding: 8px 16px 14px 16px;
  overflow-x: auto;
  background: transparent;
  color: var(--ai-text-secondary);
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

/* ===== Mermaid block (chart / code tabs + tools) ===== */
.markdown-rendered :deep(.mermaid-block) {
  position: relative;
  margin: 14px 0 16px;
  border: 1px solid var(--ai-border-strong);
  border-radius: 10px;
  overflow: hidden;
  background: var(--ai-bg);
  box-shadow: 0 1px 2px rgba(15, 23, 42, 0.04), 0 4px 12px rgba(15, 23, 42, 0.04);
}
.markdown-rendered :deep(.mermaid-block.is-fullscreen) {
  position: fixed;
  inset: 5vh 5vw;
  z-index: 9999;
  margin: 0;
  background: var(--ai-bg);
  display: flex;
  flex-direction: column;
}
.markdown-rendered :deep(.mermaid-header) {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  min-height: 36px;
  padding: 4px 6px 4px 10px;
  background: linear-gradient(180deg, var(--ai-bg-tertiary) 0%, var(--ai-bg) 100%);
  border-bottom: 1px solid var(--ai-border-strong);
}
.markdown-rendered :deep(.mermaid-tabs) {
  display: inline-flex;
  align-items: center;
  gap: 2px;
  padding: 2px;
  background: var(--ai-bg-secondary);
  border: 1px solid var(--ai-border);
  border-radius: 7px;
}
.markdown-rendered :deep(.mermaid-tab) {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 3px 10px;
  background: transparent;
  color: var(--ai-text-muted);
  border: none;
  border-radius: 5px;
  cursor: pointer;
  font-size: 11.5px;
  font-weight: 600;
  transition: all 0.15s;
}
.markdown-rendered :deep(.mermaid-tab:hover) {
  color: var(--ai-text);
}
.markdown-rendered :deep(.mermaid-tab.is-active) {
  background: var(--ai-bg);
  color: var(--ai-text);
  box-shadow: 0 1px 2px rgba(15, 23, 42, 0.06);
}
.markdown-rendered :deep(.mermaid-tools) {
  display: inline-flex;
  align-items: center;
  gap: 2px;
}
.markdown-rendered :deep(.mermaid-tool-btn) {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 26px;
  height: 26px;
  padding: 0;
  background: transparent;
  color: var(--ai-text-muted);
  border: 1px solid transparent;
  border-radius: 6px;
  cursor: pointer;
  transition: all 0.15s;
}
.markdown-rendered :deep(.mermaid-tool-btn:hover) {
  background: var(--ai-bg-secondary);
  color: var(--ai-accent);
  border-color: var(--ai-border);
}
.markdown-rendered :deep(.mermaid-chart) {
  display: flex;
  align-items: center;
  justify-content: center;
  min-height: 160px;
  padding: 18px 20px;
  background: var(--ai-bg);
  overflow: auto;
}
.markdown-rendered :deep(.mermaid-chart svg) {
  max-width: 100%;
  height: auto;
}
.markdown-rendered :deep(.mermaid-status) {
  color: var(--ai-text-muted);
  font-size: 12px;
  font-style: italic;
}
.markdown-rendered :deep(.mermaid-error) {
  margin: 12px 0;
  padding: 10px 12px;
  color: #b91c1c;
  background: color-mix(in srgb, #ef4444 8%, transparent);
  border: 1px solid color-mix(in srgb, #ef4444 30%, transparent);
  border-radius: 6px;
  font-family: 'JetBrains Mono', monospace;
  font-size: 12px;
  white-space: pre-wrap;
  word-break: break-word;
}
.markdown-rendered :deep(.mermaid-code) {
  background: var(--ai-bg-secondary);
}
.markdown-rendered :deep(.mermaid-code pre) {
  margin: 0;
  padding: 12px 16px;
}
.markdown-rendered :deep(.mermaid-block.is-fullscreen .mermaid-chart) {
  flex: 1;
  min-height: 0;
}
.markdown-rendered :deep(.msg-table-wrap) {
  position: relative;
  margin: 14px 0 16px;
  overflow: hidden;
  border: 1px solid #6b7280 !important;
  border-radius: 10px;
  background: var(--ai-bg);
  box-shadow: 0 1px 2px rgba(15, 23, 42, 0.04), 0 4px 12px rgba(15, 23, 42, 0.04);
}
.markdown-rendered :deep(.msg-table-wrap.is-fullscreen) {
  position: fixed;
  inset: 5vh 5vw;
  z-index: 9999;
  margin: 0;
  display: flex;
  flex-direction: column;
}
.markdown-rendered :deep(.msg-table-wrap.is-fullscreen .msg-table-scroll) {
  flex: 1;
  overflow: auto;
}
.markdown-rendered :deep(.msg-table-header) {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  min-height: 40px;
  padding: 0 12px 0 18px;
  border-bottom: 1px solid var(--ai-border-strong);
  background: var(--ai-bg-secondary);
}
.markdown-rendered :deep(.msg-table-tag) {
  font-size: 13.5px;
  font-weight: 700;
  color: var(--ai-text);
  letter-spacing: 0.01em;
}
.markdown-rendered :deep(.msg-table-tools) {
  display: inline-flex;
  align-items: center;
  gap: 4px;
}
.markdown-rendered :deep(.msg-table-tool) {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 30px;
  height: 30px;
  padding: 0;
  background: transparent;
  color: var(--ai-text-muted);
  border: 1px solid transparent;
  border-radius: 7px;
  cursor: pointer;
  transition: all 0.15s;
}
.markdown-rendered :deep(.msg-table-tool svg) {
  width: 14px;
  height: 14px;
}
.markdown-rendered :deep(.msg-table-tool:hover) {
  background: var(--ai-bg);
  color: var(--ai-accent);
  border-color: var(--ai-border);
}
.markdown-rendered :deep(.msg-table-scroll) {
  overflow-x: auto;
  /* Edge fade to signal horizontal overflow */
  -webkit-mask-image: linear-gradient(90deg, transparent 0, #000 16px, #000 calc(100% - 16px), transparent 100%);
  mask-image: linear-gradient(90deg, transparent 0, #000 16px, #000 calc(100% - 16px), transparent 100%);
  scrollbar-width: thin;
  scrollbar-color: color-mix(in srgb, var(--ai-text-faint) 60%, transparent) transparent;
}
.markdown-rendered :deep(.msg-table-scroll)::-webkit-scrollbar {
  height: 8px;
}
.markdown-rendered :deep(.msg-table-scroll)::-webkit-scrollbar-thumb {
  background: color-mix(in srgb, var(--ai-text-faint) 50%, transparent);
  border-radius: 4px;
}
.markdown-rendered :deep(.msg-table-scroll)::-webkit-scrollbar-thumb:hover {
  background: color-mix(in srgb, var(--ai-text-muted) 70%, transparent);
}
.markdown-rendered :deep(table.msg-table) {
  width: 100%;
  min-width: 380px;
  border-collapse: separate;
  border-spacing: 0;
  font-size: 13px;
  line-height: 1.55;
  table-layout: auto;
}
.markdown-rendered :deep(.msg-table th),
.markdown-rendered :deep(.msg-table td) {
  padding: 14px 18px;
  border-bottom: 1px solid #6b7280 !important;
  text-align: left;
  vertical-align: top;
}
.markdown-rendered :deep(.msg-table thead th) {
  background: var(--ai-bg-secondary);
  color: var(--ai-text);
  font-weight: 700;
  font-size: 13px;
  letter-spacing: 0.01em;
  white-space: nowrap;
  border-bottom: 2px solid #374151 !important;
}
.markdown-rendered :deep(.msg-table tbody tr td:first-child) {
  font-weight: 600;
  color: var(--ai-text);
}
.markdown-rendered :deep(.msg-table tbody tr:last-child td) {
  border-bottom: 0;
}
.markdown-rendered :deep(.msg-table tbody tr) {
  transition: background 0.12s;
}
.markdown-rendered :deep(.msg-table tbody tr:hover td) {
  background: var(--ai-accent-bg);
}
.markdown-rendered :deep(blockquote:not(.msg-callout)) {
  position: relative;
  margin: 10px 0;
  padding: 10px 14px 10px 18px;
  border-left: 3px solid var(--ai-accent);
  border-radius: 8px;
  background: var(--ai-accent-bg);
  color: var(--ai-text-muted);
  font-style: italic;
  line-height: 1.7;
}
.markdown-rendered :deep(blockquote:not(.msg-callout))::before {
  content: '\201C';
  position: absolute;
  top: 4px;
  left: 6px;
  color: var(--ai-accent);
  font-family: Georgia, 'Times New Roman', serif;
  font-size: 22px;
  line-height: 1;
  opacity: 0.55;
  pointer-events: none;
}
.markdown-rendered :deep(.msg-callout) {
  position: relative;
  margin: 12px 0;
  padding: 12px 14px 12px 16px;
  border: 1px solid var(--ai-border-strong);
  border-left-width: 4px;
  border-radius: 9px;
  background: var(--ai-bg-secondary);
  box-shadow: 0 1px 2px rgba(15, 23, 42, 0.04), 0 4px 12px rgba(15, 23, 42, 0.04);
  color: var(--ai-text-muted);
  line-height: 1.65;
}
.markdown-rendered :deep(.msg-callout-title) {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-bottom: 6px;
  color: var(--ai-text);
  font-size: 12px;
  font-weight: 700;
  letter-spacing: 0.02em;
}
.markdown-rendered :deep(.msg-callout-title::before) {
  content: '';
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: currentColor;
  opacity: 0.85;
}
.markdown-rendered :deep(.msg-callout-note) { border-left-color: #3b82f6; background: color-mix(in srgb, var(--ai-bg-secondary) 86%, #3b82f6 14%); }
.markdown-rendered :deep(.msg-callout-note) .msg-callout-title { color: #2563eb; }
.markdown-rendered :deep(.msg-callout-tip) { border-left-color: #10b981; background: color-mix(in srgb, var(--ai-bg-secondary) 86%, #10b981 14%); }
.markdown-rendered :deep(.msg-callout-tip) .msg-callout-title { color: #047857; }
.markdown-rendered :deep(.msg-callout-success) { border-left-color: #22c55e; background: color-mix(in srgb, var(--ai-bg-secondary) 86%, #22c55e 14%); }
.markdown-rendered :deep(.msg-callout-success) .msg-callout-title { color: #15803d; }
.markdown-rendered :deep(.msg-callout-warning) { border-left-color: #f59e0b; background: color-mix(in srgb, var(--ai-bg-secondary) 86%, #f59e0b 14%); }
.markdown-rendered :deep(.msg-callout-warning) .msg-callout-title { color: #b45309; }
.markdown-rendered :deep(.msg-callout-important) { border-left-color: #8b5cf6; background: color-mix(in srgb, var(--ai-bg-secondary) 86%, #8b5cf6 14%); }
.markdown-rendered :deep(.msg-callout-important) .msg-callout-title { color: #6d28d9; }
.markdown-rendered :deep(.msg-callout-error) { border-left-color: #ef4444; background: color-mix(in srgb, var(--ai-bg-secondary) 86%, #ef4444 14%); }
.markdown-rendered :deep(.msg-callout-error) .msg-callout-title { color: #b91c1c; }
.markdown-rendered :deep(.msg-callout p:last-child) { margin-bottom: 0; }

/* Modern thinking timeline */
.ai-thinking-block {
  margin: 4px 0 8px 0;
  border: none;
  background: transparent;
  box-shadow: none;
}
.ai-thinking-block::before {
  display: none;
}
.ai-thinking-block.active {
  border-color: transparent;
}
.ai-thinking-header {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 4px 8px;
  border-radius: 6px;
  background: transparent;
  color: var(--ai-text-muted);
  cursor: pointer;
  font-size: 13px;
  font-weight: 500;
  user-select: none;
  transition: background 0.2s, color 0.2s;
}
.ai-thinking-header:hover {
  background: var(--ai-bg-tertiary);
  color: var(--ai-text-secondary);
}
.ai-think-chevron {
  transition: transform 0.2s ease;
  color: inherit;
}
.tb-summary {
  min-width: 0;
  margin-right: auto;
  overflow: hidden;
  color: inherit;
  font-size: 12px;
  font-weight: 400;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.ai-thinking-body {
  max-height: 400px;
  margin: 4px 0 0 18px;
  padding: 4px 0;
  overflow-y: auto;
  border: none;
  border-radius: 0;
  background: transparent;
  color: #7a6e5d; /* distinct muted brown */
  font-family: 'Georgia', 'Times New Roman', serif;
  font-style: italic;
  font-size: 13.5px;
  line-height: 1.6;
  white-space: normal;
}
.ai-thinking-body:empty::after {
  content: '正在思考...';
  color: var(--ai-text-faint);
}

/* ===== Command Palette ===== */
.command-palette {
  position: absolute;
  bottom: 100%;
  left: 0;
  width: 100%;
  max-height: 340px;
  background: var(--ai-bg);
  border: 1px solid var(--ai-border);
  border-radius: 12px;
  box-shadow: 0 12px 32px rgba(0, 0, 0, 0.12);
  margin-bottom: 8px;
  display: flex;
  flex-direction: column;
  overflow: hidden;
  z-index: 100;
}

.command-palette-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 10px 14px;
  background: var(--ai-bg-secondary);
  border-bottom: 1px solid var(--ai-border);
}

.command-palette-title {
  font-size: 13px;
  font-weight: 700;
  color: var(--ai-text);
}

.command-palette-hint {
  font-size: 12px;
  color: var(--ai-text-muted);
}

.command-palette-search {
  padding: 10px;
  border-bottom: 1px solid var(--ai-border);
}

.command-search-input {
  width: 100%;
  padding: 8px 12px;
  font-size: 13.5px;
  background: transparent;
  border: 1px solid var(--ai-border-strong);
  border-radius: 6px;
  color: var(--ai-text);
  outline: none;
  transition: border-color 0.15s;
}

.command-search-input:focus {
  border-color: var(--ai-accent);
}

.command-palette-list {
  flex: 1;
  overflow-y: auto;
  padding: 8px;
}

.command-item {
  display: flex;
  flex-direction: column;
  padding: 10px 12px;
  border-radius: 8px;
  cursor: pointer;
  transition: background 0.15s;
}

.command-item.active,
.command-item:hover {
  background: var(--ai-bg-hover);
}

.command-item-main {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 4px;
}

.command-name {
  font-size: 14px;
  font-weight: 700;
  color: var(--ai-text);
}

.command-aliases {
  font-size: 12px;
  color: var(--ai-text-muted);
}

.command-item-desc {
  font-size: 12.5px;
  color: var(--ai-text-muted);
}

.command-empty {
  padding: 24px;
  text-align: center;
  font-size: 13.5px;
  color: var(--ai-text-muted);
}

</style>
