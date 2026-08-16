<template>
  <div class="ws-shell" :class="{ 'ws-dark': aiDarkTheme }">
    <!-- 1. 顶部固定导航栏 -->
    <WorkspaceTopBar
      :project-name="projectName"
      :active-path="activePath"
      :file-content-dirty="fileContentDirty"
      :saving-file="savingFile"
      :explorer-visible="explorerVisible"
      :terminal-visible="terminalPanelVisible"
      :ai-panel-visible="!aiCollapsed && !isAgentInCenter"
      @go-back="goBack"
      @toggle-explorer="explorerVisible = !explorerVisible"
      @toggle-terminal="toggleTerminalPanel"
      @toggle-ai-panel="toggleAiPanelLayout"
      @open-theme-settings="themeStore.openSettings()"
      @export-project="exportProject"
      @save-file="saveFile"
    >
      <template #theme-button>
        <button class="ws-btn ws-btn-outline ws-btn-sm ws-theme-settings-btn" title="主题设置" @click="themeStore.openSettings()">
          <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><circle cx="12" cy="12" r="3"/><path d="M19.4 15a1.65 1.65 0 0 0 .33 1.82l.06.06a2 2 0 0 1 0 2.83 2 2 0 0 1-2.83 0l-.06-.06a1.65 1.65 0 0 0-1.82-.33 1.65 1.65 0 0 0-1 1.51V21a2 2 0 0 1-2 2 2 2 0 0 1-2-2v-.09A1.65 1.65 0 0 0 9 19.4a1.65 1.65 0 0 0-1.82.33l-.06.06a2 2 0 0 1-2.83 0 2 2 0 0 1 0-2.83l.06-.06A1.65 1.65 0 0 0 4.68 15a1.65 1.65 0 0 0-1.51-1H3a2 2 0 0 1-2-2 2 2 0 0 1 2-2h.09A1.65 1.65 0 0 0 4.6 9a1.65 1.65 0 0 0-.33-1.82l-.06-.06a2 2 0 0 1 0-2.83 2 2 0 0 1 2.83 0l.06.06A1.65 1.65 0 0 0 9 4.68a1.65 1.65 0 0 0 1-1.51V3a2 2 0 0 1 2-2 2 2 0 0 1 2 2v.09a1.65 1.65 0 0 0 1 1.51 1.65 1.65 0 0 0 1.82-.33l.06-.06a2 2 0 0 1 2.83 0 2 2 0 0 1 0 2.83l-.06.06A1.65 1.65 0 0 0 19.4 9a1.65 1.65 0 0 0 1.51 1H21a2 2 0 0 1 2 2 2 2 0 0 1-2 2h-.09a1.65 1.65 0 0 0-1.51 1z"/></svg>
          <span>主题</span>
        </button>
      </template>
    </WorkspaceTopBar>

    <div class="ws-body">
      <!-- 2. 左侧资源管理器 / 会话列表面板 -->
      <aside v-show="explorerVisible" class="ws-sidebar" :style="{ width: `${sidebarWidth}px` }">
        <SidebarNav :view="sidebarView" @change="sidebarView = $event" />
        <FileExplorerPanel
          v-show="sidebarView === 'files'"
          :file-tree="fileTree"
          :tree-error="treeError"
          :tree-loading="treeLoading"
          :tree-next-offset="treeNextOffset"
          :active-path="activePath"
          :load-children="loadChildren"
          :refresh-key="treeRefreshKey"
          :show-new-modal="showNewModal"
          :new-modal-type="newModalType"
          v-model:new-item-name="newItemName"
          :show-rename-modal="showRenameModal"
          v-model:rename-value="renameItemValue"
          @select="handleSelectTreeFile"
          @new-item="handleNewItem"
          @rename="handleRename"
          @delete="handleDelete"
          @refresh="loadRoot"
          @load-more="loadMoreRoot"
          @scroll="handleTreeScroll"
          @create-file="showNewFileModal('file')"
          @create-dir="showNewFileModal('directory')"
          @close-new="showNewModal = false"
          @close-rename="showRenameModal = false"
          @confirm-new="confirmNewItem"
          @confirm-rename="confirmRename"
        />
        <ConversationPanel
          v-show="sidebarView === 'conversations'"
          :conversations="conversations"
          :current-conversation-id="currentAgentSession?.conversationId"
          @select="selectConversation"
          @fork="forkConversation"
          @compact="compactConversation"
          @delete="deleteConversation"
          @create="createNewSession"
        />
      </aside>

      <!-- 左侧栏拖拽拉伸条 -->
      <div v-if="explorerVisible" class="ws-resize-handle" @pointerdown="startSidebarResize"></div>

      <!-- 3. 中间工作区：支持中心 AI 工作空间 / 代码编辑器 / 底部终端 -->
      <div
        ref="workspaceCenterRef"
        class="ws-center"
        :class="{ 'is-drop-target': centerDropOverlayActive }"
        @dragover.prevent="onCenterDragOver"
        @dragleave="onCenterDragLeave"
        @drop.prevent="onCenterDrop"
      >
        <!-- 拖放 AI 放大提示遮罩 -->
        <div v-if="centerDropOverlayActive" class="center-drop-zone-overlay">
          <div class="drop-hint-box">
            <svg width="28" height="28" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><polygon points="13 2 3 14 12 14 11 22 21 10 12 10 13 2"/></svg>
            <span>释放以在中心区域放大 LabexAgent</span>
          </div>
        </div>

        <!-- 标签页条 (始终在顶部，展示所有打开的文件标签 + LabexAgent 标签) -->
        <div v-if="openFiles.length > 0 || isAgentInCenter" class="ws-editor-tabs">
          <!-- 文件 Tabs -->
          <div
            v-for="(f, idx) in openFiles"
            :key="f.path"
            class="ws-tab"
            :class="{ active: !isAgentTabActive && idx === activeTabIndex }"
            draggable="true"
            @dragstart="onTabDragStart($event, idx)"
            @dragover.prevent="onTabDragOver($event, idx)"
            @drop.prevent="onTabDrop($event, idx)"
            @click="onSelectFileTab(idx)"
            @mouseup="e => { if (e.button === 1) { e.preventDefault(); closeFile(idx) } }"
          >
            <FileIcon :name="f.name" :size="12" />
            <span class="ws-tab-name">{{ f.name }}</span>
            <span v-if="f.dirty" class="ws-tab-dot"></span>
            <button class="ws-tab-close" @click.stop="closeFile(idx)" title="关闭">
              <svg width="10" height="10" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><line x1="18" y1="6" x2="6" y2="18"/><line x1="6" y1="6" x2="18" y2="18"/></svg>
            </button>
          </div>

          <!-- LabexAgent 专属标签页 -->
          <div
            v-if="isAgentInCenter"
            class="ws-tab ws-tab-agent"
            :class="{ active: isAgentTabActive }"
            @click="onSelectAgentTab"
            @mouseup="e => { if (e.button === 1) { e.preventDefault(); closeAgentTab() } }"
            title="LabexAgent 智能助手"
          >
            <span class="agent-tab-sparkle">
              <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2"><polygon points="13 2 3 14 12 14 11 22 21 10 12 10 13 2"/></svg>
            </span>
            <span class="ws-tab-name">LabexAgent</span>
            <button class="ws-tab-close" @click.stop="closeAgentTab" title="还原至侧边栏">
              <svg width="10" height="10" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><line x1="18" y1="6" x2="6" y2="18"/><line x1="6" y1="6" x2="18" y2="18"/></svg>
            </button>
          </div>
        </div>

        <!-- 主内容区：根据激活的标签显示 CenterAiWorkspace 还是 MonacoEditor -->
        <main class="ws-editor">
          <!-- 当处于 LabexAgent 标签时显示放大的 AI 视图 -->
          <CenterAiWorkspace
            v-if="isAgentInCenter && isAgentTabActive"
            :messages="messages"
            v-model:agent-input="agentInput"
            v-model:agent-mode="agentMode"
            :current-model="currentModelName"
            :thinking-level="thinkingLevel"
            :available-models="modelConfigs"
            :agent-loading="agentLoading"
            :current-session-name="currentSessionName"
            :has-older-messages="hasOlderMessages"
            :loading-older-messages="loadingOlderMessages"
            :show-thinking-process="showThinkingProcess"
            :supports-images="currentModelSupportsImages"
            :selected-code="selectedCode"
            :pending-images="pendingImageAttachments"
            :context-usage-status="contextUsageStatus"
            :token-usage="tokenUsage"
            :quick-chips="quickChips"
            :active-path="activePath"
            :get-merged-items="getMergedItems"
            :render-thinking-markdown="renderThinkingMarkdown"
            :render-message-markdown="renderMessageMarkdown"
            @dock-back="dockAiBackToSidebar"
            @mode-change="switchMode"
            @send="sendMessage"
            @stop="stopGeneration"
            @trigger-commands="showCommandMenu"
            @trigger-at-file="atFile"
            @optimize-prompt="optimizePrompt"
            @clear-selected-code="selectedCode = ''"
            @preview-image="openImagePreview"
            @remove-image="removePendingImage"
            @image-files="handleDroppedImageFiles"
            @open-context-dialog="openContextUsageDialog"
            @change-model="handleSelectModelByName"
            @change-thinking="handleChangeThinkingLevel"
            @open-model-config="showModelConfig = true"
            @load-older-history="loadOlderHistory"
            @apply-chip="prompt => { agentInput = prompt; sendMessage() }"
            @markdown-click="handleMarkdownClick"
            @permission="handlePermissionDecision"
            @command-approval="handleCommandApproval"
            @question="handleQuestionReply"
            @copy-message="copyMessage"
            @insert-editor="insertToEditor"
            @review-changes="handleReviewChanges"
            @open-file-diff="handleOpenFileDiff"
          />

          <!-- 当处于文件编辑标签时显示 Monaco 编辑器 -->
          <div v-else-if="openFiles.length > 0 && activeTabIndex >= 0" class="ws-monaco">
            <MonacoEditor
              v-if="editorReady"
              v-model="fileContent"
              :language="detectedLang"
              :theme="editorTheme"
              :read-only="activeFileReadOnly"
              height="100%"
            />
          </div>

          <!-- 空白状态 -->
          <div v-else class="ws-editor-empty">
            <svg width="48" height="48" viewBox="0 0 24 24" fill="none" stroke="#e5e7eb" stroke-width="1"><path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z"/><polyline points="14 2 14 8 20 8"/></svg>
            <p>选择文件开始编辑</p>
            <p class="ws-editor-hint">从左侧文件树中选择一个文件，或将右侧 LabexAgent 拖入此处放大</p>
          </div>
        </main>

        <!-- 底部终端拉伸条与面板 -->
        <div v-if="terminalPanelVisible" class="ws-terminal-resize-handle" @pointerdown="startTerminalResize" title="拖动调整终端高度"></div>
        <section v-show="terminalPanelVisible" class="ws-terminal-dock" :class="{ dark: aiDarkTheme }" :style="{ height: `${terminalHeight}px` }">
          <div class="ws-terminal-dock-header">
            <span>终端</span>
            <button class="ws-btn ws-btn-outline ws-btn-icon" @click="toggleTerminalPanel" title="关闭终端">
              <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><line x1="18" y1="6" x2="6" y2="18"/><line x1="6" y1="6" x2="18" y2="18"/></svg>
            </button>
          </div>
          <TerminalPanel ref="terminalPanelRef" :project-id="projectId" :project-path="projectPath" :is-dark="aiDarkTheme" @toggle-theme="toggleAiTheme" @command-finished="onTerminalCommandFinished" />
        </section>
      </div>

      <!-- 右侧 AI 面板拖拽拉伸条 -->
      <div v-if="!aiCollapsed && !isAgentInCenter" class="ai-resize-handle" @pointerdown="startResize"></div>

      <!-- ==================== AI ASSISTANT SIDEBAR ==================== -->
      <aside
        v-if="!isAgentInCenter"
        class="ai-panel"
        :class="{ collapsed: aiCollapsed, dark: aiDarkTheme }"
        :style="!aiCollapsed ? { width: `${aiPanelWidth}px` } : {}"
      >
        <!-- Collapsed State: Icon Column -->
        <div v-if="aiCollapsed" class="ai-collapsed-bar">
          <button class="ai-icon-btn" @click="aiCollapsed = false" title="展开 LabexAgent">
            <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M21 15a2 2 0 0 1-2 2H7l-4 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2z"/></svg>
          </button>
          <button class="ai-icon-btn" title="设置" @click="themeStore.openSettings()">
            <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><circle cx="12" cy="12" r="3"/><path d="M19.4 15a1.65 1.65 0 0 0 .33 1.82l.06.06a2 2 0 0 1 0 2.83 2 2 0 0 1-2.83 0l-.06-.06a1.65 1.65 0 0 0-1.82-.33 1.65 1.65 0 0 0-1 1.51V21a2 2 0 0 1-2 2 2 2 0 0 1-2-2v-.09A1.65 1.65 0 0 0 9 19.4a1.65 1.65 0 0 0-1.82.33l-.06.06a2 2 0 0 1-2.83 0 2 2 0 0 1 0-2.83l.06-.06A1.65 1.65 0 0 0 4.68 15a1.65 1.65 0 0 0-1.51-1H3a2 2 0 0 1-2-2 2 2 0 0 1 2-2h.09A1.65 1.65 0 0 0 4.6 9a1.65 1.65 0 0 0-.33-1.82l-.06-.06a2 2 0 0 1 0-2.83 2 2 0 0 1 2.83 0l.06.06A1.65 1.65 0 0 0 9 4.68a1.65 1.65 0 0 0 1-1.51V3a2 2 0 0 1 2-2 2 2 0 0 1 2-2v.09a1.65 1.65 0 0 0 1 1.51 1.65 1.65 0 0 0 1.82-.33l.06-.06a2 2 0 0 1 2.83 0 2 2 0 0 1 0 2.83l-.06.06A1.65 1.65 0 0 0 19.4 9a1.65 1.65 0 0 0 1.51 1H21a2 2 0 0 1 2 2 2 2 0 0 1-2 2h-.09a1.65 1.65 0 0 0-1.51 1z"/></svg>
          </button>
          <div class="ai-collapsed-spacer"></div>
          <span class="ai-collapsed-badge" v-if="messages.length > 0">{{ messages.length }}</span>
        </div>

        <!-- Expanded State -->
        <template v-else>
          <!-- Top Bar (支持拖拽到中心) -->
          <div
            class="ai-topbar"
            draggable="true"
            @dragstart="onAiHeaderDragStart"
            @dragend="onAiHeaderDragEnd"
            title="可拖拽此头部至中心区域放大"
          >
            <button class="ai-topbar-btn" @click="aiCollapsed = true" title="折叠侧边栏">
              <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><line x1="3" y1="12" x2="21" y2="12"/><line x1="3" y1="6" x2="21" y2="6"/><line x1="3" y1="18" x2="21" y2="18"/></svg>
            </button>
            <div class="ai-topbar-title">
              <AppIcon :size="20" compact />
              <span>LabexAgent</span>
            </div>
            <div class="ai-topbar-actions">
              <button class="ai-topbar-btn" @click="moveAiToCenter" title="在中部放大 LabexAgent">
                <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><polyline points="15 3 21 3 21 9"/><polyline points="9 21 3 21 3 15"/><line x1="21" y1="3" x2="14" y2="10"/><line x1="3" y1="21" x2="10" y2="14"/></svg>
              </button>
              <div class="ai-session-select" @click="showConversationPanel" title="打开左侧会话列表">
                <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M21 12a9 9 0 0 1-9 9m9-9a9 9 0 0 0-9-9m9 9H3m9 9a9 9 0 0 1-9-9m9 9c1.657 0 3-4.03 3-9s-1.343-9-3-9m0 18c-1.657 0-3-4.03-3-9s1.343-9 3-9m-9 9a9 9 0 0 1 9-9"/></svg>
                <span class="ai-session-name">{{ currentSessionName }}</span>
              </div>
              <button class="ai-topbar-btn" @click="toggleAiTheme" :title="aiDarkTheme ? '切换亮色主题' : '切换暗色主题'">
                <svg v-if="aiDarkTheme" width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><circle cx="12" cy="12" r="5"/><line x1="12" y1="1" x2="12" y2="3"/><line x1="12" y1="21" x2="12" y2="23"/><line x1="4.22" y1="4.22" x2="5.64" y2="5.64"/><line x1="18.36" y1="18.36" x2="19.78" y2="19.78"/><line x1="1" y1="12" x2="3" y2="12"/><line x1="21" y1="12" x2="23" y2="12"/><line x1="4.22" y1="19.78" x2="5.64" y2="18.36"/><line x1="18.36" y1="5.64" x2="19.78" y2="4.22"/></svg>
                <svg v-else width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M21 12.79A9 9 0 1 1 11.21 3 7 7 0 0 0 21 12.79z"/></svg>
              </button>
              <button class="ai-topbar-btn" @click="clearMessages" title="清空会话">
                <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><polyline points="3 6 5 6 21 6"/><path d="M19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6m3 0V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2"/></svg>
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
          <div v-show="activeAiTab === 'chat'" :class="['ai-content', { 'is-empty': messages.length === 0 }]">
            <div class="ai-messages" ref="msgContainer" @scroll="handleScroll">
              <button v-if="hasOlderMessages" class="ai-history-load" type="button" :disabled="loadingOlderMessages" @click="loadOlderHistory">
                {{ loadingOlderMessages ? '正在加载更早记录...' : '加载更早记录' }}
              </button>
              <!-- Empty State -->
              <div v-if="messages.length === 0" class="ai-empty">
                <h2 class="ai-empty-greeting">有什么我可以帮您的吗？</h2>
                <div class="center-quick-chips" style="margin-top: 12px;">
                  <button v-for="chip in quickChips" :key="chip.label" class="quick-chip-btn" @click="agentInput = chip.prompt; sendMessage()">
                    <span v-html="chip.icon"></span>
                    <span>{{ chip.label }}</span>
                  </button>
                </div>
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
                    <!-- Merged Thinking + Tool Calls -->
                    <template v-if="(showThinkingProcess && msg.thinkingBlocks && msg.thinkingBlocks.length > 0) || (msg.toolCalls && msg.toolCalls.length > 0)">
                      <template v-for="item in getMergedItems(msg)" :key="item._order">
                        <div
                          v-if="item.type === 'context'"
                          class="ai-context-management-card"
                          :class="[`is-${item.data.status}`, `is-${item.data.phase}`]"
                          role="status"
                          aria-live="polite"
                        >
                          <div class="context-management-card-header">
                            <span class="context-management-indicator context-management-pulse" aria-hidden="true"></span>
                            <strong>{{ contextManagementTitle(item.data) }}</strong>
                            <span class="context-management-status">{{ contextManagementStatusText(item.data) }}</span>
                            <button
                              v-if="item.data.status === 'running' && item.data.taskId"
                              class="context-management-cancel"
                              type="button"
                              @click.stop="cancelContextCompaction(item.data)"
                            >取消压缩</button>
                          </div>
                          <div class="context-management-card-meta">
                            <span v-if="contextManagementStrategyText(item.data)">{{ contextManagementStrategyText(item.data) }}</span>
                            <span v-if="item.data.tokensBefore !== null">{{ formatTokenCount(item.data.tokensBefore) }} Token</span>
                            <span v-if="item.data.tokensAfter !== null">→ {{ formatTokenCount(item.data.tokensAfter) }} Token</span>
                            <span v-if="item.data.releasedTokens > 0" class="context-management-released">释放 {{ formatTokenCount(item.data.releasedTokens) }} Token</span>
                          </div>
                          <p v-if="item.data.reason" class="context-management-reason">{{ item.data.reason }}</p>
                        </div>
                        <ThinkingProcessBlock
                          v-else-if="item.type === 'thinking'"
                          :content="item.data.content"
                          :rendered-content="renderThinkingMarkdown(item.data.content)"
                          :summary="item.data.summary"
                          :default-open="item.data._open"
                          @markdown-click="handleMarkdownClick"
                        >
                          <div
                            class="thinking-content-body markdown-rendered"
                            v-html="renderThinkingMarkdown(item.data.content)"
                            @click="handleMarkdownClick"
                          ></div>
                        </ThinkingProcessBlock>
                        <ToolCallCard
                          v-else-if="item.type === 'tool'"
                          :call="item.data"
                          @permission="handlePermissionDecision"
                          @command-approval="handleCommandApproval"
                          @question="handleQuestionReply"
                        />
                      </template>
                    </template>

                    <!-- Realtime Streaming Thinking -->
                    <ThinkingProcessBlock
                      v-if="showThinkingProcess && msg.thinking"
                      :content="msg._thinkingDisplay || msg.thinking"
                      :rendered-content="renderThinkingMarkdown(msg._thinkingDisplay || msg.thinking)"
                      :is-streaming="true"
                      @markdown-click="handleMarkdownClick"
                    >
                      <div
                        class="thinking-content-body markdown-rendered"
                        v-html="renderThinkingMarkdown(msg._thinkingDisplay || '')"
                        @click="handleMarkdownClick"
                      ></div>
                    </ThinkingProcessBlock>

                    <!-- Content -->
                    <div class="ai-msg-content">
                      <AgentImageAttachments
                        v-if="msg.attachments?.length"
                        :attachments="msg.attachments"
                        variant="message"
                        :show-names="false"
                        @preview="openImagePreview"
                      />
                      <ContextLimitBlockerCard
                        v-if="msg.contextLimitBlocker"
                        :blocker="msg.contextLimitBlocker"
                        :task-id="msg.taskId"
                        :retrying="msg.environmentRetrying"
                        @retry="retryEnvironmentTask(msg)"
                      />
                      <div v-else-if="msg.isStreaming && !msg.content && !msg.thinking" class="ai-loading-skeleton">
                        <div class="skeleton-line w-80"></div>
                        <div class="skeleton-line w-60"></div>
                      </div>
                      <div v-else class="ai-msg-text markdown-rendered" v-html="renderMessageMarkdown(msg)" @click="handleMarkdownClick"></div>
                      <button
                        v-if="msg.environmentBlocker && !msg.contextLimitBlocker && msg.taskId && msg.environmentBlocker.retryable !== false"
                        type="button"
                        class="ai-environment-retry"
                        :disabled="msg.environmentRetrying"
                        @click="retryEnvironmentTask(msg)"
                      >
                        {{ msg.environmentRetrying ? '正在恢复任务...' : '环境恢复后重试' }}
                      </button>
                      <div v-if="showMessageTimestamps && msg.timestamp" class="ai-msg-time">{{ formatTime(msg.timestamp) }}</div>
                    </div>

                    <!-- File Changes Summary Card -->
                    <FileChangesSummaryCard
                      v-if="msg.role === 'assistant' && (sessionChanges?.length > 0 && i === messages.length - 1)"
                      :changes="sessionChanges"
                      @review-all="selectAiTab('review')"
                      @open-file-diff="file => openFile(file.path)"
                    />

                    <CompletionEvidenceCard
                      v-if="msg.role === 'assistant' && (msg.completionEvidence || msg.completionBlockedEvidence)"
                      :evidence="msg.completionEvidence || msg.completionBlockedEvidence"
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
                    </div>
                  </div>
                </div>
              </TransitionGroup>
            </div>

            <!-- Composer Dock (Fixed at bottom) -->
            <div class="ai-composer-wrapper">
              <ComposerDock
                v-model="agentInput"
                v-model:agent-mode="agentMode"
                :current-model="currentModelName"
                :thinking-level="thinkingLevel"
                :available-models="modelConfigs"
                :loading="agentLoading"
                :supports-images="currentModelSupportsImages"
                :selected-code="selectedCode"
                :pending-images="pendingImageAttachments"
                :context-usage-status="contextUsageStatus"
                :active-path="activePath"
                @mode-change="switchMode"
                @send="sendMessage"
                @stop="stopGeneration"
                @trigger-commands="showCommandMenu"
                @trigger-at-file="atFile"
                @optimize-prompt="optimizePrompt"
                @clear-selected-code="selectedCode = ''"
                @preview-image="openImagePreview"
                @remove-image="removePendingImage"
                @image-files="handleDroppedImageFiles"
                @open-context-dialog="openContextUsageDialog"
                @change-model="handleSelectModelByName"
                @change-thinking="handleChangeThinkingLevel"
                @open-model-config="showModelConfig = true"
              />
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
                  <span class="usage-stat-value prompt">{{ formatTokenCount(allTokenStats?.totalPromptTokens ?? tokenUsage.promptTokens) }}</span>
                </div>
                <div class="usage-stat">
                  <span class="usage-stat-label">输出</span>
                  <span class="usage-stat-value completion">{{ formatTokenCount(allTokenStats?.totalCompletionTokens ?? tokenUsage.completionTokens) }}</span>
                </div>
              </div>
                <div class="usage-cache-card" :data-cache-status="cacheTelemetryView.status">
                  <div class="usage-cache-heading">
                    <span class="usage-cache-title">Prompt 缓存</span>
                    <span class="usage-cache-status">{{ cacheTelemetryView.label }}</span>
                    <select
                      v-model="selectedCacheTelemetryModel"
                      :disabled="cacheTelemetryModels.length === 0"
                      class="usage-cache-model-select"
                      aria-label="按模型查看 Prompt 缓存统计"
                    >
                      <option value="">全部模型</option>
                      <option v-for="model in cacheTelemetryModels" :key="model" :value="model">{{ model }}</option>
                    </select>
                    <strong v-if="cacheTelemetryView.showHitRate" class="usage-cache-rate">
                      {{ cacheTelemetryView.hitRate.toFixed(2) }}%（{{ cacheTelemetryScopeLabel }}）
                    </strong>
                  </div>
                  <p class="usage-cache-detail">{{ cacheTelemetryView.detail }}</p>
                  <div class="usage-cache-metrics">
                    <span>读取 {{ formatTokenCount(cacheTelemetryTotals.cachedTokens) }} tokens</span>
                    <span>写入 {{ formatTokenCount(cacheTelemetryTotals.cacheWriteTokens) }} tokens</span>
                    <span>非缓存输入 {{ formatTokenCount(cacheTelemetryTotals.nonCachedInputTokens) }} tokens</span>
                  </div>
                </div>
               <UsageHeatmap v-if="allTokenStats" :by-day="allTokenStats.byDay" :cache-by-day="allTokenStats.cacheByDay" :dark="aiDarkTheme" />
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

    <ImageLightboxModal
      v-model:visible="showImageLightbox"
      :src="imageLightboxSrc"
      :title="imageLightboxTitle"
    />

    <ModelConfigDialog :state="modelConfigDialogState" :actions="modelConfigDialogActions" />
  </div>
</template>

<script setup>
import { ref, reactive, computed, onMounted, onBeforeUnmount, nextTick, watch, defineAsyncComponent } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { projectApi, modelConfigApi, agentExtensionApi } from '@/api'
import { DEFAULT_MAX_TOKENS, modelConfigPresets } from '@/constants/modelPresets'
import { DEFAULT_AGENT_IMAGE_INPUT_POLICY, imageAcceptValue } from '@/constants/agentImageInput'
import WorkspaceTopBar from '@/components/cloud/layout/WorkspaceTopBar.vue'
import DynamicResizer from '@/components/cloud/layout/DynamicResizer.vue'
import ImageLightboxModal from '@/components/cloud/layout/ImageLightboxModal.vue'
import ComposerDock from '@/components/cloud/composer/ComposerDock.vue'
import ModeSlider from '@/components/cloud/composer/ModeSlider.vue'
import ModelSelectorPopover from '@/components/cloud/composer/ModelSelectorPopover.vue'
import ThinkingProcessBlock from '@/components/cloud/chat/ThinkingProcessBlock.vue'
import FileIcon from '@/components/icons/FileIcon.vue'
import SidebarNav from '@/components/sidebar/SidebarNav.vue'
import FileExplorerPanel from '@/components/sidebar/FileExplorerPanel.vue'
import ConversationPanel from '@/components/sidebar/ConversationPanel.vue'
import AgentImageAttachments from '@/components/cloud/AgentImageAttachments.vue'
import ToolCallCard from '@/components/cloud/ToolCallCard.vue'
import ContextLimitBlockerCard from '@/components/cloud/ContextLimitBlockerCard.vue'
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
import { normalizeCommandCatalog, resolveSlashCommand } from '@/composables/slashCommandRuntime'
import { createConversationSelectionGuard } from '@/composables/conversationSelectionGuard'
import { useAgentInteraction } from '@/composables/useAgentInteraction'
import { useAgentExtensions } from '@/composables/useAgentExtensions'
import { useWorkspaceFiles } from '@/composables/useWorkspaceFiles'
import { createWorkspaceMutationProjection } from '@/composables/workspaceMutationProjection'
import { useChangeSetState } from '@/composables/useChangeSetState'
import { reduceContextManagementEvent, reduceHistoryEvent } from '@/composables/agentHistoryReducer'
import { applyCommandExecutionResponseState, attachCommandApprovalState as attachCommandApproval, findCommandApprovalToolCall as commandApprovalToolCall, updateCommandApprovalState as updateCommandApprovalLifecycle } from '@/composables/agentCommandApprovalState'
import { attachDurableInteraction } from '@/composables/agentInteractionProjection'
import { normalizeSpecialMarkdownBlocks, stripInternalReasoningBlocks, stripInternalReasoningTags } from '@/utils/agentMarkdown'
import { resolveContextUsageStatus } from '@/composables/contextUsageStatus'
import { applyTokenUsageEvent, createTokenUsageState, resolveCacheTelemetryScope, resolveCacheTelemetryView } from '@/composables/cacheTelemetryStatus'
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
const UsageHeatmap = defineAsyncComponent(() => import('@/components/cloud/UsageHeatmap.vue'))
const AgentTimer = defineAsyncComponent(() => import('@/components/cloud/AgentTimer.vue'))
const ContextUsageIndicator = defineAsyncComponent(() => import('@/components/cloud/ContextUsageIndicator.vue'))
const ContextUsageDialog = defineAsyncComponent(() => import('@/components/cloud/ContextUsageDialog.vue'))
const ModelConfigDialog = defineAsyncComponent(() => import('@/components/cloud/ModelConfigDialog.vue'))
const CenterAiWorkspace = defineAsyncComponent(() => import('@/components/cloud/chat/CenterAiWorkspace.vue'))
const ChangesPanel = defineAsyncComponent(() => import('@/components/cloud/ChangesPanel.vue'))
const CompletionEvidenceCard = defineAsyncComponent(() => import('@/components/cloud/CompletionEvidenceCard.vue'))
const PlanDisplay = defineAsyncComponent(() => import('@/components/cloud/PlanDisplay.vue'))
const FileChangesSummaryCard = defineAsyncComponent(() => import('@/components/cloud/chat/FileChangesSummaryCard.vue'))

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
  treeRefreshKey,
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
const imageInputRef = ref(null)
const pendingImageAttachments = ref([])
const imagePreviewAttachment = ref(null)
const imageDragActive = ref(false)
const imageInputPolicy = ref(DEFAULT_AGENT_IMAGE_INPUT_POLICY)
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
const tokenUsage = ref(createTokenUsageState())
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
const selectedCacheTelemetryModel = ref('')
const cacheTelemetryModels = computed(() => Object.keys(allTokenStats.value?.cacheByModel || {}))
const selectedCacheTelemetryStats = computed(() => resolveCacheTelemetryScope(
  allTokenStats.value,
  selectedCacheTelemetryModel.value
))
const cacheTelemetryView = computed(() => resolveCacheTelemetryView(selectedCacheTelemetryStats.value, tokenUsage.value))
const cacheTelemetryScopeLabel = computed(() => selectedCacheTelemetryModel.value || '全部模型')
const cacheTelemetryTotals = computed(() => ({
  cachedTokens: cacheTelemetryView.value.ledger.cacheReadTokens,
  cacheWriteTokens: cacheTelemetryView.value.ledger.cacheWriteTokens,
  nonCachedInputTokens: cacheTelemetryView.value.ledger.nonCachedInputTokens
}))
let usagePieChart = null
let usageBarChart = null
let usageTimelineChart = null
let usageModelChart = null


// Multi-session management
const sidebarView = ref('files')

function showConversationPanel() {
  loadConversations()
  sidebarView.value = 'conversations'
}

// AI Panel UI state
const aiCollapsed = ref(false)
const explorerVisible = ref(true)
const isAgentInCenter = ref(false)
const isAgentTabActive = ref(false)
const aiPanelWidth = ref(420)
const centerDropOverlayActive = ref(false)
const isDraggingAi = ref(false)
const draggedTabIdx = ref(null)
const showImageLightbox = ref(false)
const imageLightboxSrc = ref('')
const imageLightboxTitle = ref('')
const thinkingLevel = ref('High')

async function handleSelectTreeFile(path) {
  isAgentTabActive.value = false
  await openFile(path)
}

function onSelectFileTab(idx) {
  isAgentTabActive.value = false
  switchTab(idx)
}

function onSelectAgentTab() {
  isAgentTabActive.value = true
}

function closeAgentTab() {
  isAgentInCenter.value = false
  isAgentTabActive.value = false
  aiCollapsed.value = false
  if (openFiles.value.length > 0) {
    const nextIdx = Math.min(Math.max(0, activeTabIndex.value), openFiles.value.length - 1)
    switchTab(nextIdx)
  }
  ElMessage.info('LabexAgent 已还原至右侧边栏')
}

function dockAiBackToSidebar() {
  closeAgentTab()
}

async function handleOpenFileDiff(file) {
  const path = file?.path || file?.filePath || file
  if (path) {
    isAgentTabActive.value = false
    await openFile(path)
  }
}

function handleReviewChanges(msg) {
  if (msg?.fileChanges && msg.fileChanges.length > 0) {
    handleOpenFileDiff(msg.fileChanges[0])
  } else if (sessionChanges.value && sessionChanges.value.length > 0) {
    selectAiTab('review')
  }
}

function moveAiToCenter() {
  isAgentInCenter.value = true
  isAgentTabActive.value = true
  aiCollapsed.value = true
  ElMessage.success('LabexAgent 已作为标签页在中心区域打开')
}

function onAiHeaderDragStart(e) {
  isDraggingAi.value = true
  e.dataTransfer?.setData('text/plain', 'ai-panel')
}

function onAiHeaderDragEnd() {
  isDraggingAi.value = false
  centerDropOverlayActive.value = false
}

function onCenterDragOver(e) {
  if (isDraggingAi.value) {
    centerDropOverlayActive.value = true
  }
}

function onCenterDragLeave(e) {
  if (!e.currentTarget.contains(e.relatedTarget)) {
    centerDropOverlayActive.value = false
  }
}

function onCenterDrop(e) {
  if (isDraggingAi.value) {
    isAgentInCenter.value = true
    isAgentTabActive.value = true
    centerDropOverlayActive.value = false
    isDraggingAi.value = false
    aiCollapsed.value = true
    ElMessage.success('LabexAgent 已作为标签页在中心区域打开')
  }
}

function toggleAiPanelLayout() {
  if (isAgentInCenter.value) {
    isAgentInCenter.value = false
    aiCollapsed.value = false
  } else {
    aiCollapsed.value = !aiCollapsed.value
  }
}

function onTabDragStart(e, idx) {
  draggedTabIdx.value = idx
}

function onTabDragOver(e, idx) {
  // enable drop
}

function onTabDrop(e, idx) {
  if (draggedTabIdx.value !== null && draggedTabIdx.value !== idx) {
    const item = openFiles.value.splice(draggedTabIdx.value, 1)[0]
    openFiles.value.splice(idx, 0, item)
    activeTabIndex.value = idx
  }
  draggedTabIdx.value = null
}

function handleSelectModelByName(name) {
  const found = modelConfigs.value.find(c => c.modelName === name || c.configName === name)
  if (found) {
    selectedModelConfigId.value = found.configId
    ElMessage.success(`已切换模型: ${found.configName || found.modelName}`)
  }
}

function handleChangeThinkingLevel(lvl) {
  thinkingLevel.value = lvl
  const effort = lvl === 'High' ? 'high' : (lvl === 'Low' ? 'low' : 'medium')
  mcForm.value.reasoningEffort = effort
  ElMessage.success(`思考程度已设置为: ${lvl}`)
}

async function handleDroppedImageFiles(files) {
  if (!currentModelSupportsImages.value) {
    ElMessage.warning('当前模型不支持图片输入')
    return
  }
  for (const file of files) {
    await appendPendingImageFile(file)
  }
}

function insertToEditor(text) {
  if (!text) return
  fileContent.value = (fileContent.value ? fileContent.value + '\n' : '') + text
  fileContentDirty.value = true
  ElMessage.success('已插入到当前文件')
}

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

// Slash command 目录由后端 typed metadata 投影，前端只持有可重建视图。
const commandList = ref([])
const showMessageTimestamps = ref(true)
const showThinkingProcess = ref(true)
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
  maxTokens: DEFAULT_MAX_TOKENS,
  contextWindowTokens: null,
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
  maxTokens: DEFAULT_MAX_TOKENS,
  contextWindowTokens: null,
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
const currentModelSupportsImages = computed(() => {
  const config = modelConfigs.value.find(item => item.configId === selectedModelConfigId.value)
  return config?.imageInputEnabled === 1 || config?.imageInputEnabled === true
})
const imageAccept = computed(() => imageAcceptValue(imageInputPolicy.value.allowedMimeTypes))
const imageInputTitle = computed(() => currentModelSupportsImages.value
  ? '\u6dfb\u52a0\u56fe\u7247'
  : '\u5f53\u524d\u6a21\u578b\u662f\u7eaf\u6587\u672c\u6a21\u578b\uff0c\u4e0d\u80fd\u8f93\u5165\u56fe\u7247')

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
    () => loadImageInputPolicy(),
    () => loadAgentExtensions(),
    () => loadConversations(),
    () => loadCommandCatalog()
  ])
  await loadRoot()
  void secondaryResources.then(async () => {
    const startupConversation = resolveStartupConversation()
    if (conversationSelectionGuard.isCurrent(startupConversationSelection)
      && !currentAgentSession.value
      && messages.value.length === 0
      && startupConversation) {
      await selectConversation(startupConversation, { explicit: false })
    }
  })
})

// 清理定时器，防止内存泄漏
let terminalRefreshTimer = null
function onTerminalCommandFinished() {
  // 终端命令结束（含审批执行）后合并刷新文件树，避免高频命令反复请求。
  if (terminalRefreshTimer) clearTimeout(terminalRefreshTimer)
  terminalRefreshTimer = setTimeout(() => { void loadRoot() }, 150)
}

onBeforeUnmount(() => {
  invalidateTaskRuntime()
  if (terminalRefreshTimer) {
    clearTimeout(terminalRefreshTimer)
    terminalRefreshTimer = null
  }
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
  revokeImageObjectUrls(pendingImageAttachments.value)
  messages.value.forEach(msg => revokeImageObjectUrls(msg.attachments || []))
})

async function exportProject() {
  try { const r = await projectApi.exportProject(projectId.value); const blob = r.data instanceof Blob ? r.data : new Blob([r.data], { type: 'application/zip' }); const url = window.URL.createObjectURL(blob); const a = document.createElement('a'); a.href = url; a.download = (projectName.value || 'project') + '.zip'; document.body.appendChild(a); a.click(); document.body.removeChild(a); window.URL.revokeObjectURL(url); ElMessage.success('导出成功') } catch (e) { ElMessage.error('导出失败: ' + (e?.response?.data?.message || e?.message || '未知错误')) }
}

async function loadCommandCatalog() {
  try {
    const response = await projectApi.agentCommands(projectId.value)
    commandList.value = normalizeCommandCatalog(response)
  } catch (error) {
    commandList.value = []
    ElMessage.warning('命令目录加载失败，slash command 已安全禁用')
  }
}

function buildConversationMarkdown() {
  const title = currentSessionName.value || '会话'
  const body = messages.value.map(message => {
    const role = message.role === 'user' ? '用户' : 'Agent'
    return `## ${role}\n\n${message.content || ''}`
  }).join('\n\n')
  return `# ${title}\n\n${body}`.trim() + '\n'
}

async function copyConversationTranscript() {
  if (!messages.value.length) throw new Error('当前会话没有可复制的记录')
  if (!navigator.clipboard?.writeText) throw new Error('当前浏览器不支持剪贴板写入')
  await navigator.clipboard.writeText(buildConversationMarkdown())
  ElMessage.success('会话记录已复制')
}

function exportConversationTranscript() {
  if (!messages.value.length) throw new Error('当前会话没有可导出的记录')
  const blob = new Blob([buildConversationMarkdown()], { type: 'text/markdown;charset=utf-8' })
  const url = window.URL.createObjectURL(blob)
  const anchor = document.createElement('a')
  anchor.href = url
  anchor.download = `${(currentSessionName.value || 'conversation').replace(/[\\/:*?"<>|]/g, '_')}.md`
  document.body.appendChild(anchor)
  anchor.click()
  document.body.removeChild(anchor)
  window.URL.revokeObjectURL(url)
  ElMessage.success('会话记录已导出')
}

function buildClientSlashActions() {
  return {
    SESSION_LIST: async () => {
      await loadConversations()
      sidebarView.value = 'conversations'
    },
    SESSION_NEW: async () => createNewSession(),
    CONVERSATION_COMPACT: async () => compactCurrentConversation(),
    CONVERSATION_FORK: async () => forkCurrentConversation(),
    CONVERSATION_COPY: async () => copyConversationTranscript(),
    CONVERSATION_EXPORT: async () => exportConversationTranscript(),
    TOGGLE_TIMESTAMPS: async () => {
      showMessageTimestamps.value = !showMessageTimestamps.value
      ElMessage.success(showMessageTimestamps.value ? '已显示消息时间戳' : '已隐藏消息时间戳')
    },
    TOGGLE_THINKING: async () => {
      showThinkingProcess.value = !showThinkingProcess.value
      ElMessage.success(showThinkingProcess.value ? '已显示思考过程' : '已隐藏思考过程')
    },
    MODEL_SETTINGS: async () => { showModelConfig.value = true },
    THEME_SETTINGS: async () => themeStore.openSettings(),
    CHANGES_PANEL: async () => selectAiTab('review'),
    SKILLS_PANEL: async () => {
      activeExtensionTab.value = 'skills'
      await loadAgentExtensions()
      await selectAiTab('extensions')
    },
    MCP_PANEL: async () => {
      activeExtensionTab.value = 'mcp'
      await loadAgentExtensions()
      await selectAiTab('extensions')
    },
    USAGE_PANEL: async () => selectAiTab('usage'),
    CONTEXT_USAGE: async () => openContextUsageDialog(),
    PROJECT_STATUS: async () => ElMessage.info(
      `项目：${projectName.value || '-'} · 会话：${currentSessionName.value || '-'} · 模式：${agentMode.value} · 模型：${currentModelName.value}`
    ),
    COMMAND_HELP: async () => {
      showCommandPalette.value = true
      commandSearch.value = ''
      selectedCommandIndex.value = 0
      await nextTick()
      commandSearchRef.value?.focus()
    },
    WORKSPACE_EXIT: async () => router.push({ name: 'Projects' })
  }
}
// AI methods
async function sendMessage() {
  const q = agentInput.value.trim()
  const imageAttachments = pendingImageAttachments.value.slice()
  if ((!q && imageAttachments.length === 0) || agentLoading.value) return

  let messageToSend = q
  let displayMessage = null
  if (q.startsWith('/')) {
    if (imageAttachments.length > 0) {
      ElMessage.warning('Slash command \u6682\u4e0d\u652f\u6301\u56fe\u7247\u9644\u4ef6\uff0c\u8bf7\u79fb\u9664\u56fe\u7247\u540e\u518d\u6267\u884c\u3002')
      return
    }
    // 先关闭输入时产生的旧面板，允许 /help 等客户端动作按需重新打开目标 UI。
    closeCommandPalette()
    try {
      const commandResult = await resolveSlashCommand({
        raw: q,
        runCommand: request => projectApi.runCommand(projectId.value, request),
        requestContext: {
          conversationId: currentAgentSession.value?.conversationId,
          mode: agentMode.value
        },
        clientActions: buildClientSlashActions()
      })
      if (commandResult.kind === 'handled') {
        agentInput.value = ''
        return
      }
      messageToSend = commandResult.prompt
      displayMessage = q
      if (commandResult.subtask) {
        ElMessage.info(`正在执行子任务：/${commandResult.command}`)
      }
    } catch (error) {
      console.error('命令执行失败:', error)
      ElMessage.error(error?.message || '命令执行失败')
      return
    }
  }
  messages.value.push({ role: 'user', content: q, attachments: imageAttachments, timestamp: Date.now() })
  messages.value.push({ role: 'assistant', content: '', pendingFinalContent: '', hasPendingFinalDraft: false, thinking: '', _thinkingDisplay: '', _thinkingTimer: null, thinkingBlocks: [], toolCalls: [], plan: null, isStreaming: true, error: null, _nextOrder: 0, timestamp: Date.now(), conversationId: currentAgentSession.value?.conversationId || null, timing: createMessageTiming() })
  const assistantMsg = messages.value[messages.value.length - 1]
  agentInput.value = ''
  pendingImageAttachments.value = []
  agentLoading.value = true
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
      displayMessage,
      activePath: activePath.value || '',
      modelConfigId: selectedModelConfigId.value || null
    }, {
      files: imageAttachments.map(attachment => attachment.file),
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

function attachUserQuestion(msg, data) {
  attachDurableInteraction(msg, 'question', data)
}

function continueCommandTaskProjection(call, approval) {
  const assistantMsg = messages.value.find(message => message?.toolCalls?.includes(call))
  const taskId = approval?.taskId || assistantMsg?.taskId
  if (!assistantMsg || !taskId) return
  assistantMsg.waitingForCommandApproval = false
  assistantMsg.isStreaming = true
  if (assistantMsg.timing) assistantMsg.timing.isRunning = true
  agentLoading.value = true
  void replayResumedAgent(taskId, assistantMsg, approval?.conversationId || assistantMsg.conversationId)
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
      continueCommandTaskProjection(call, approval)
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
    applyCommandExecutionResponseState(call, executionData)
    // HTTP 只描述本次命令请求；waiting_network 等非终态仍必须继续消费持久事件。
    continueCommandTaskProjection(call, approval)
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

async function replayResumedAgent(taskId, assistantMsg, conversationId) {
  try {
    logTaskRecovery('TASK_EVENT_RESUME_WAITING', { taskId, conversationId: conversationId || assistantMsg?.conversationId || currentAgentSession.value?.conversationId || null })
    await resumeTaskEventSubscription(taskId, assistantMsg, conversationId)
  } catch (error) {
    assistantMsg.error = '恢复 Agent 任务失败：' + (error?.message || '未知错误')
    if (!assistantMsg.content) assistantMsg.content = assistantMsg.error
    assistantMsg.isStreaming = false
    stopMessageTimer(assistantMsg)
    agentLoading.value = false
  }
}

async function handlePermissionDecision(payload) {
  const result = await submitPermissionDecision(payload)
  if (!result.success || (payload?.call?.status === 'error' && payload?.action !== 'reject')) return
  const call = payload.call
  const request = call?.networkRequest || call?.permissionRequest
  const assistantMsg = messages.value.find(message => message?.toolCalls?.includes(call))
  const taskId = request?.taskId || assistantMsg?.taskId
  if (assistantMsg && taskId) {
    assistantMsg.isStreaming = true
    if (assistantMsg.timing) assistantMsg.timing.isRunning = true
    agentLoading.value = true
    void replayResumedAgent(taskId, assistantMsg, request?.conversationId || assistantMsg.conversationId)
  }
}

async function handleQuestionReply(payload) {
  const result = await submitQuestionReply(payload)
  if (result.reason === 'answer_required') {
    ElMessage.warning('\u8bf7\u5148\u8f93\u5165\u56de\u7b54')
    return
  }
  if (result.reason === 'request_missing') {
    ElMessage.warning('提问请求仍在同步，请稍后重试')
    return
  }
  if (result.success) {
    const call = payload.call
    const assistantMsg = messages.value.find(message => message?.toolCalls?.includes(call))
    const taskId = call?.questionRequest?.taskId || assistantMsg?.taskId
    if (assistantMsg && taskId) {
      assistantMsg.isStreaming = true
      agentLoading.value = true
      void replayResumedAgent(taskId, assistantMsg, call?.questionRequest?.conversationId || assistantMsg.conversationId)
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
const { projectWorkspaceChange } = createWorkspaceMutationProjection({
  projectId,
  loadRoot
})

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
  onHistoryAttachments: hydrateHistoryAttachmentPreviews,
  onHistoryLoaded: initialScroll
})
const {
  conversations,
  currentSessionName,
  hasOlderMessages,
  loadingOlderMessages,
  clearConversationState,
  loadConversations,
  resolveStartupConversation,
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
  scrollDown,
  // 历史快照与任务终态可能跨事务交错；终态确认后再读取一次持久会话，补齐最后的 FINAL。
  reloadConversationHistory: conversationId => loadConversationMessagesState(conversationId)
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
  currentSessionName,
  onTokenUsageProjected: invalidateTokenStatsProjection,
  onWorkspaceChanged: event => { void projectWorkspaceChange(event) }
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

async function loadImageInputPolicy() {
  try {
    const response = await projectApi.agentImageAttachmentPolicy(projectId.value)
    const policy = response.data || {}
    imageInputPolicy.value = {
      maxFilesPerMessage: Number(policy.maxFilesPerMessage) || DEFAULT_AGENT_IMAGE_INPUT_POLICY.maxFilesPerMessage,
      maxFileSizeBytes: Number(policy.maxFileSizeBytes) || DEFAULT_AGENT_IMAGE_INPUT_POLICY.maxFileSizeBytes,
      maxTotalSizeBytes: Number(policy.maxTotalSizeBytes) || DEFAULT_AGENT_IMAGE_INPUT_POLICY.maxTotalSizeBytes,
      allowedMimeTypes: Array.isArray(policy.allowedMimeTypes) && policy.allowedMimeTypes.length > 0
        ? policy.allowedMimeTypes : DEFAULT_AGENT_IMAGE_INPUT_POLICY.allowedMimeTypes
    }
  } catch {
    imageInputPolicy.value = DEFAULT_AGENT_IMAGE_INPUT_POLICY
  }
}

function requestImageInput() {
  if (agentLoading.value) return
  if (!currentModelSupportsImages.value) {
    ElMessage.warning('\u5f53\u524d\u6a21\u578b\u662f\u7eaf\u6587\u672c\u6a21\u578b\uff0c\u4e0d\u80fd\u8f93\u5165\u56fe\u7247\uff1b\u8bf7\u5207\u6362\u5230\u5df2\u5f00\u542f\u201c\u652f\u6301\u56fe\u7247\u7406\u89e3\u201d\u7684\u6a21\u578b\u914d\u7f6e\u3002')
    return
  }
  imageInputRef.value?.click()
}

function handleImageInput(event) {
  addImageFiles(event.target?.files)
  event.target.value = ''
}

function handleImagePaste(event) {
  const files = Array.from(event.clipboardData?.files || []).filter(file => file.type.startsWith('image/'))
  if (files.length > 0) {
    event.preventDefault()
    addImageFiles(files)
  }
}

function hasImageFiles(dataTransfer) {
  return Array.from(dataTransfer?.types || []).includes('Files')
}

function handleImageDragEnter(event) {
  if (!hasImageFiles(event.dataTransfer)) return
  event.preventDefault()
  imageDragActive.value = true
}

function handleImageDragOver(event) {
  if (!hasImageFiles(event.dataTransfer)) return
  event.preventDefault()
  event.dataTransfer.dropEffect = currentModelSupportsImages.value ? 'copy' : 'none'
}

function handleImageDragLeave(event) {
  if (!hasImageFiles(event.dataTransfer)) return
  event.preventDefault()
  if (!event.currentTarget.contains(event.relatedTarget)) imageDragActive.value = false
}

function handleImageDrop(event) {
  if (!hasImageFiles(event.dataTransfer)) return
  event.preventDefault()
  imageDragActive.value = false
  addImageFiles(event.dataTransfer?.files)
}

function addImageFiles(fileList) {
  const files = Array.from(fileList || []).filter(Boolean)
  if (files.length === 0 || agentLoading.value) return
  if (!currentModelSupportsImages.value) {
    ElMessage.warning('\u5f53\u524d\u6a21\u578b\u662f\u7eaf\u6587\u672c\u6a21\u578b\uff0c\u4e0d\u80fd\u8f93\u5165\u56fe\u7247\uff1b\u8bf7\u5207\u6362\u5230\u5df2\u5f00\u542f\u201c\u652f\u6301\u56fe\u7247\u7406\u89e3\u201d\u7684\u6a21\u578b\u914d\u7f6e\u3002')
    return
  }
  const policy = imageInputPolicy.value
  const existing = pendingImageAttachments.value
  let totalBytes = existing.reduce((sum, attachment) => sum + attachment.file.size, 0)
  const accepted = []
  for (const file of files) {
    if (!policy.allowedMimeTypes.includes(file.type)) {
      ElMessage.warning(`\u4e0d\u652f\u6301 ${file.name} \u7684\u56fe\u7247\u683c\u5f0f`)
      continue
    }
    if (file.size <= 0 || file.size > policy.maxFileSizeBytes) {
      ElMessage.warning(`${file.name} \u8d85\u8fc7\u5355\u5f20\u56fe\u7247\u5927\u5c0f\u9650\u5236`)
      continue
    }
    if (existing.length + accepted.length >= policy.maxFilesPerMessage) {
      ElMessage.warning(`\u4e00\u6b21\u6700\u591a\u6dfb\u52a0 ${policy.maxFilesPerMessage} \u5f20\u56fe\u7247`)
      break
    }
    if (totalBytes + file.size > policy.maxTotalSizeBytes) {
      ElMessage.warning('\u56fe\u7247\u603b\u5927\u5c0f\u8d85\u8fc7\u5f53\u524d\u9650\u5236')
      break
    }
    const duplicate = [...existing, ...accepted].some(item => item.file.name === file.name
      && item.file.size === file.size && item.file.lastModified === file.lastModified)
    if (duplicate) continue
    accepted.push({ id: crypto.randomUUID(), file, name: file.name || 'image', previewUrl: URL.createObjectURL(file) })
    totalBytes += file.size
  }
  pendingImageAttachments.value.push(...accepted)
}

function removePendingImage(attachmentId) {
  const attachment = pendingImageAttachments.value.find(item => item.id === attachmentId)
  if (attachment?.previewUrl) URL.revokeObjectURL(attachment.previewUrl)
  pendingImageAttachments.value = pendingImageAttachments.value.filter(item => item.id !== attachmentId)
  if (imagePreviewAttachment.value?.id === attachmentId) closeImagePreview()
}

async function hydrateHistoryAttachmentPreviews() {
  const attachments = messages.value.flatMap(message => message?.attachments || [])
  await Promise.all(attachments.map(hydrateHistoryAttachmentPreview))
}

async function hydrateHistoryAttachmentPreview(attachment) {
  if (!attachment || attachment.previewUrl || attachment.expired || attachment.previewFailed || attachment._previewPromise) return
  attachment._previewPromise = projectApi.agentAttachmentPreview(projectId.value, attachment.id)
    .then(blob => {
      if (!(blob instanceof Blob) || blob.size === 0) throw new Error('图片预览为空')
      attachment.previewUrl = URL.createObjectURL(blob)
    })
    .catch(error => {
      const status = error?.response?.status
      if (status === 404 || status === 410) attachment.expired = true
      else attachment.previewFailed = true
    })
    .finally(() => { attachment._previewPromise = null })
  await attachment._previewPromise
}

function openImagePreview(attachment) {
  imagePreviewAttachment.value = attachment
  imageLightboxSrc.value = attachment.previewUrl || attachment.url || attachment.dataUrl || ''
  imageLightboxTitle.value = attachment.name || '图片预览'
  showImageLightbox.value = true
}

function closeImagePreview() {
  imagePreviewAttachment.value = null
  showImageLightbox.value = false
}

function revokeImageObjectUrls(attachments) {
  ;(attachments || []).forEach(attachment => {
    if (attachment?.previewUrl) URL.revokeObjectURL(attachment.previewUrl)
  })
}

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
    maxTokens: cfg.maxTokens || DEFAULT_MAX_TOKENS,
    contextWindowTokens: cfg.contextWindowTokens ?? null,
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
    maxTokens: tpl.maxTokens || DEFAULT_MAX_TOKENS,
    contextWindowTokens: tpl.contextWindowTokens ?? null,
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
  if (!f.baseUrl.trim() && !f.modelsUrl.trim()) {
    ElMessage.warning('\u8bf7\u586b\u5199 Base URL \u6216\u6a21\u578b\u5217\u8868 URL')
    return
  }
  mcModelsLoading.value = true
  try {
    const r = await modelConfigApi.listModels({
      configId: mcEditingId.value || null,
      baseUrl: f.baseUrl.trim(),
      modelsUrl: f.modelsUrl.trim(),
      apiKey: f.apiKey.trim()
    })
    const data = r.data || {}
    if (!data.success) {
      mcFetchedModels.value = []
      ElMessage.warning(data.error || '\u6a21\u578b\u5217\u8868\u83b7\u53d6\u5931\u8d25')
      return
    }
    mcFetchedModels.value = data.models || []
    if (data.modelsUrl && !f.modelsUrl.trim()) {
      mcForm.value.modelsUrl = data.modelsUrl
    }
    if (mcFetchedModels.value.length === 0) {
      ElMessage.warning('\u6a21\u578b\u5217\u8868\u4e3a\u7a7a')
    } else {
      applyFetchedModelLimits()
      ElMessage.success(`\u5df2\u83b7\u53d6 ${mcFetchedModels.value.length} \u4e2a\u6a21\u578b`)
    }
  } catch (e) {
    mcFetchedModels.value = []
    ElMessage.error('\u6a21\u578b\u5217\u8868\u83b7\u53d6\u5931\u8d25: ' + (e?.response?.data?.message || e?.message || '\u672a\u77e5\u9519\u8bef'))
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
  if (key === 'usage') await initUsageCharts()
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
      applyTokenUsageEvent(tokenUsage.value, usage)
      invalidateTokenStatsProjection()
    },
    onContextStatus: status => { contextUsageStatus.value = status },
    onWorkspaceChanged: event => { void projectWorkspaceChange(event) }
  })
}

async function forkConversation(conversation) {
  try {
    const result = await forkConversationState(conversation)
    if (!result.success) {
      ElMessage.error(result.message)
      return false
    }
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
  if (showThinkingProcess.value && msg.thinkingBlocks) {
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

let tokenUsageProjectionEpoch = 0

function invalidateTokenStatsProjection() {
  tokenUsageProjectionEpoch += 1
  allTokenStats.value = null
}

async function loadAllTokenStats() {
  const requestEpoch = tokenUsageProjectionEpoch
  try {
    const r = await projectApi.agentTokenSummary(projectId.value)
    if (requestEpoch === tokenUsageProjectionEpoch) {
      const stats = r.data || null
      if (selectedCacheTelemetryModel.value
        && !Object.prototype.hasOwnProperty.call(stats?.cacheByModel || {}, selectedCacheTelemetryModel.value)) {
        selectedCacheTelemetryModel.value = ''
      }
      allTokenStats.value = stats
    }
  } catch (e) {
    if (requestEpoch === tokenUsageProjectionEpoch) allTokenStats.value = null
  }
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
function renderMarkdown(text) {
  if (!text) return ''
  const rawHtml = marked.parse(normalizeSpecialMarkdownBlocks(text), { gfm: true, breaks: true, silent: true })
  return enhanceMarkdownHtml(sanitizeMarkdownHtml(String(rawHtml || '')))
}

function renderThinkingMarkdown(text) {
  return renderMarkdown(stripInternalReasoningTags(text))
}

function renderMessageMarkdown(message) {
  const source = message?.role === 'assistant'
    ? stripInternalReasoningBlocks(message?.content)
    : message?.content
  return renderMarkdown(source)
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
  if (!search) return commandList.value
  return commandList.value.filter(cmd =>
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
