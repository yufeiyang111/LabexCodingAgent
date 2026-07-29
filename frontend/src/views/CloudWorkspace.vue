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
                      <ContextLimitBlockerCard
                        v-if="msg.contextLimitBlocker"
                        :blocker="msg.contextLimitBlocker"
                        :task-id="msg.taskId"
                        :retrying="msg.environmentRetrying"
                        @retry="retryEnvironmentTask(msg)"
                      />
                      <div v-else class="ai-msg-text markdown-rendered" v-html="renderMarkdown(msg.content)" @click="handleMarkdownClick"></div>
                      <button
                        v-if="msg.environmentBlocker && !msg.contextLimitBlocker && msg.taskId && msg.environmentBlocker.retryable !== false"
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
                    <CompletionEvidenceCard
                      v-if="msg.role === 'assistant' && msg.completionEvidence"
                      :evidence="msg.completionEvidence"
                    />
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
import ContextLimitBlockerCard from '@/components/cloud/ContextLimitBlockerCard.vue'
import CompletionEvidenceCard from '@/components/cloud/CompletionEvidenceCard.vue'
import ChangesPanel from '@/components/cloud/ChangesPanel.vue'
import PlanDisplay from '@/components/cloud/PlanDisplay.vue'
import AppIcon from '@/components/AppIcon.vue'
import * as echarts from 'echarts'
import { marked } from 'marked'
import hljs from 'highlight.js/lib/common'
import { useAgentStream } from '@/composables/useAgentStream'
import { useAgentTaskRuntime } from '@/composables/useAgentTaskRuntime'
import { useAgentEventTimeline } from '@/composables/useAgentEventTimeline'
import { useContextManagement, contextManagementTitle, contextManagementStatusText, contextManagementStrategyText } from '@/composables/useContextManagement'
import { useThemeStore } from '@/stores/theme'
import { loadWorkspaceResources } from '@/composables/workspaceInitialization'
import { useConversationState } from '@/composables/useConversationState'
import { createConversationSelectionGuard } from '@/composables/conversationSelectionGuard'
import { useAgentInteraction } from '@/composables/useAgentInteraction'
import { useAgentExtensions } from '@/composables/useAgentExtensions'
import { useWorkspaceFiles } from '@/composables/useWorkspaceFiles'
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
const sidebarWidth = ref(240)
const {
  fileTree,
  treeError,
  treeNextOffset,
  treeLoadingMore,
  treeLoading,
  activePath,
  fileContent,
  fileContentDirty,
  activeFileReadOnly,
  savingFile,
  editorReady,
  openFiles,
  activeTabIndex,
  detectedLang,
  showNewModal,
  newModalType,
  newItemParent,
  newItemName,
  showRenameModal,
  renameItemValue,
  renamingItemPath,
  loadRoot,
  loadMoreRoot,
  handleTreeScroll,
  loadChildren,
  openFile,
  switchTab,
  closeFile,
  saveFile,
  showNewFileModal,
  handleNewItem,
  confirmNewItem,
  handleRename,
  confirmRename,
  handleDelete
} = useWorkspaceFiles({
  projectId,
  api: projectApi,
  notify: ElMessage,
  confirmAction: (...args) => ElMessageBox.confirm(...args),
  nextTick
})

// AI Assistant state
const messages = ref([])
const conversationRenderEpoch = ref(0)
const agentInput = ref('')
const agentLoading = ref(false)
const agentMode = ref('build')
const msgContainer = ref(null)
const aiInputRef = ref(null)
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
const activeExtensionTab = ref('skills')
const {
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
} = useAgentExtensions({
  api: agentExtensionApi,
  notify: ElMessage,
  confirmAction: (...args) => ElMessageBox.confirm(...args)
})

// Review tab state (ChangesPanel 组件已替代硬编码数据)

// Terminal quick commands
// Terminal panel ref
const terminalPanelRef = ref(null)
const workspaceCenterRef = ref(null)
const terminalPanelVisible = ref(false)
const terminalHeight = ref(280)
const projectPath = ref('')

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
  const streamConversationGeneration = conversationSelectionGuard.capture()

  try {
    await streamAgent(projectId.value, {
      sessionId,
      conversationId: currentAgentSession.value?.conversationId,
      mode: agentMode.value,
      message: messageToSend,
      activePath: activePath.value || '',
      modelConfigId: selectedModelConfigId.value || null
    }, {
      onEvent: event => {
        if (!conversationSelectionGuard.isCurrent(streamConversationGeneration)) return
        handleAgentEvent(event, assistantMsg)
      }
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
    const stillOwnsConversation = conversationSelectionGuard.isCurrent(streamConversationGeneration)
    const shouldResumeTaskEvents = stillOwnsConversation
      && assistantMsg.resumeTaskEventsAfterStream === true
      && !!assistantMsg.taskId
    assistantMsg.resumeTaskEventsAfterStream = false
    if (shouldResumeTaskEvents) {
      assistantMsg.isStreaming = true
      if (assistantMsg.timing) assistantMsg.timing.isRunning = true
      agentLoading.value = true
      await replayResumedAgent(assistantMsg.taskId, assistantMsg)
    } else {
      assistantMsg.isStreaming = false
      stopMessageTimer(assistantMsg)
      await syncTaskTiming(assistantMsg)
      if (stillOwnsConversation) agentLoading.value = false
    }
    if (stillOwnsConversation) {
      await nextTick(); scrollDown()
    }
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
    assistantMsg.contextLimitBlocker = null
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
    return
  }
  if (result.success && payload?.action === 'answer') {
    const call = payload.call
    const assistantMsg = messages.value.find(message => message?.toolCalls?.includes(call))
    const taskId = call?.questionRequest?.taskId || assistantMsg?.taskId
    if (assistantMsg && taskId) {
      assistantMsg.isStreaming = true
      agentLoading.value = true
      void replayResumedAgent(taskId, assistantMsg)
    }
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

let handleAgentEvent = () => {}

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
  logTaskRecovery,
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
  handleAgentEvent: (event, assistantMsg) => handleAgentEvent(event, assistantMsg),
  reconcileRecoveredCommandApproval,
  createMessageTiming,
  stopMessageTimer,
  scrollDown
})

;({ handleAgentEvent } = useAgentEventTimeline({
  recordTaskEventCursor,
  currentAgentSession,
  scheduleAgentRender,
  startThinkingReveal,
  flushThinkingDisplay,
  trackFileChange,
  changesRefreshKey,
  attachCommandApproval,
  updateCommandApprovalLifecycle,
  attachUserQuestion,
  stopMessageTimer,
  agentLoading,
  logTaskRecovery,
  contextUsageStatus,
  reduceContextManagementEvent,
  tokenUsage,
  sessionHistory,
  currentSessionName
}))

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

async function selectAiTab(key) {
  if (key === 'terminal') {
    terminalPanelVisible.value = true
    await nextTick()
    terminalPanelRef.value?.fitAllTerminals()
    return
  }
  activeAiTab.value = key
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
  invalidateTaskRuntime()
  disconnectAgentStream()
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

<style scoped lang="scss">
@use '@/styles/cloud-workspace.scoped.scss';
</style>
<style lang="scss" src="@/styles/cloud-workspace.scss"></style>
