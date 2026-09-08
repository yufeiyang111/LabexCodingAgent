import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import test from 'node:test'

test('FileExplorerPanel and Activity Rail completeness and dark theme integrity', async () => {
  const explorerPanel = await readFile(new URL('./FileExplorerPanel.vue', import.meta.url), 'utf8')
  const workspace = await readFile(new URL('../../views/CloudWorkspace.vue', import.meta.url), 'utf8')
  const fileTreeNode = await readFile(new URL('../cloud/FileTreeNode.vue', import.meta.url), 'utf8')
  const contextMenu = await readFile(new URL('../cloud/FileContextMenu.vue', import.meta.url), 'utf8')
  const scopedScss = await readFile(new URL('../../styles/cloud-workspace.scoped.scss', import.meta.url), 'utf8')
  const globalScss = await readFile(new URL('../../styles/cloud-workspace.scss', import.meta.url), 'utf8')

  // 1. 验证 FileExplorerPanel 具备完整的五大常用动作按钮
  assert.match(explorerPanel, /title="新建文件"/, 'Must provide create-file button')
  assert.match(explorerPanel, /title="新建文件夹"/, 'Must provide create-dir button')
  assert.match(explorerPanel, /title="刷新"/, 'Must provide refresh button')
  assert.match(explorerPanel, /title="全部折叠"/, 'Must provide collapse-all button')
  assert.match(explorerPanel, /title="收起资源管理器"/, 'Must provide collapse-sidebar button')

  // 2. 验证全部折叠与递归节点的联动逻辑
  assert.match(explorerPanel, /collapseKey/, 'Must manage collapseKey for directory tree')
  assert.match(explorerPanel, /:collapse-key="collapseKey"/, 'Must pass collapseKey to FileTreeNode')
  assert.match(fileTreeNode, /collapseKey/, 'FileTreeNode must accept collapseKey prop')
  assert.match(fileTreeNode, /watch\(\(\) => props\.collapseKey/, 'FileTreeNode must collapse directory on collapseKey update')

  // 3. 验证 Activity Rail 完整覆盖 files / search / conversations
  assert.match(workspace, /toggleActivityView\('files'\)/, 'Activity rail must support files view')
  assert.match(workspace, /toggleActivityView\('search'\)/, 'Activity rail must support search view')
  assert.match(workspace, /toggleActivityView\('conversations'\)/, 'Activity rail must support conversations view')

  // 4. 验证去除彩色文件图标的强制单色覆盖，恢复专属颜色辨识度
  assert.doesNotMatch(fileTreeNode, /stroke:\s*#a9b1d6\s*!important/, 'Must NOT force gray stroke on colored file icons')
  assert.match(fileTreeNode, /rgba\(99,\s*102,\s*241,\s*0\.25\)/, 'Selected file row must have clear dark contrast')

  // 5. 验证 FileContextMenu 使用纯正 SVG 图标并具备暗色规范
  assert.match(contextMenu, /<svg width="13"/, 'FileContextMenu must use SVG icons instead of raw emojis')
  assert.match(contextMenu, /html\[data-theme="dark"\]\s*\.file-context-menu/, 'FileContextMenu must have dark theme styles')

  // 6. 验证活动栏与工作区外壳不再硬编码纯白背景
  assert.match(scopedScss, /html\[data-theme="dark"\]\s*\.rail-btn\.active/, 'Active rail button must have specialized dark style')
  assert.doesNotMatch(scopedScss, /background:\s*#ffffff\s*!important/, 'Must not force pure white background on rail')
  assert.match(globalScss, /html\[data-theme='dark'\] body\.ws-page-active \.ws-shell/, 'Must set dark background for ws-shell under dark theme')

  // 7. 验证 FileContextMenu 使用 Teleport 挂载到 body，彻底摆脱父容器 contain:paint 与 overflow 裁剪
  assert.match(contextMenu, /<Teleport to="body">[\s\S]*class="file-context-menu"/, 'FileContextMenu must be teleported to body')

  // 8. 验证侧边栏初始宽度持久化与最小防挤压
  assert.match(workspace, /resolveInitialSidebarWidth/, 'Must resolve initial sidebar width from storage')
  assert.match(workspace, /DEFAULT_SIDEBAR_WIDTH = 260/, 'Must use 260px as default sidebar width')
  assert.match(workspace, /localStorage\.setItem\('labex_sidebar_width'/, 'Must persist sidebar width to localStorage')
  assert.match(scopedScss, /flex:\s*0\s*0\s*var\(--ws-sidebar-w,\s*260px\)/, 'Sidebar must be rigid against flex shrink')
  assert.match(scopedScss, /min-width:\s*var\(--ws-sidebar-w,\s*260px\)/, 'Sidebar must maintain min-width')

  // 9. 验证标题和工具栏按钮样式计算防御，防止文本竖排折叠
  assert.match(explorerPanel, /white-space:\s*nowrap/, 'Header title text must never wrap vertically')
  assert.match(explorerPanel, /\.ws-sidebar-actions\s*\.ws-btn[\s\S]*width:\s*24px/, 'Action buttons must have compact 24px square size')

  // 10. 验证暗色模式下侧边栏面板背景为深色并非白色硬编码
  assert.match(explorerPanel, /html\[data-theme="dark"\]\s*\.ws-sidebar-panel[\s\S]*background:\s*#181b24/, 'Sidebar panel must be dark in dark mode')
  assert.match(explorerPanel, /html\[data-theme="dark"\]\s*\.ws-sidebar-header[\s\S]*background:\s*#202430/, 'Sidebar header must be dark in dark mode')
  assert.match(explorerPanel, /html\[data-theme="dark"\]\s*\.ws-tree-container[\s\S]*background:\s*#181b24/, 'Tree container must be dark in dark mode')
})
