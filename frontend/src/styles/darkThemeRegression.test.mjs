import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import test from 'node:test'

test('Dark theme styling integrity for all requested components', async () => {
  const toolCallCard = await readFile(new URL('../components/cloud/ToolCallCard.vue', import.meta.url), 'utf8')
  const globalScss = await readFile(new URL('./cloud-workspace.scss', import.meta.url), 'utf8')
  const usagePanel = await readFile(new URL('../components/cloud/UsagePanel.vue', import.meta.url), 'utf8')
  const themeScss = await readFile(new URL('./theme.scss', import.meta.url), 'utf8')
  const cloudSpace = await readFile(new URL('../views/CloudSpace.vue', import.meta.url), 'utf8')
  const userDetail = await readFile(new URL('../components/cloud/UserDetailButton.vue', import.meta.url), 'utf8')
  const tutorialsScss = await readFile(new URL('./tutorials.scss', import.meta.url), 'utf8')
  const tutorialsVue = await readFile(new URL('../views/Tutorials.vue', import.meta.url), 'utf8')

  // 1. 验证 ToolCallCard 工具调用卡片支持暗黑模式
  assert.match(toolCallCard, /\[data-theme='dark'\] \.tc-card/, 'ToolCallCard must style .tc-card in dark theme')
  assert.match(toolCallCard, /\[data-theme='dark'\] \.tc-header/, 'ToolCallCard must style .tc-header in dark theme')
  assert.match(toolCallCard, /\[data-theme='dark'\] \.tc-status-pill-badge/, 'ToolCallCard must style .tc-status-pill-badge in dark theme')
  assert.match(toolCallCard, /\[data-theme='dark'\] \.tc-subagent-header-open/, 'ToolCallCard must style subagent header button in dark theme')

  // 2. 验证 Markdown 表格在暗黑模式下具备高对比度和深色背景
  assert.match(globalScss, /html\[data-theme='dark'\] \.markdown-rendered \.msg-table-wrap[\s\S]*background:\s*#181825/, 'Markdown table must be dark in dark theme')
  assert.match(globalScss, /html\[data-theme='dark'\] \.markdown-rendered \.msg-table th[\s\S]*color:\s*#edf1fb/, 'Markdown table th must have high contrast')
  assert.match(globalScss, /html\[data-theme='dark'\] \.markdown-rendered \.msg-table td[\s\S]*color:\s*#cdd6f4/, 'Markdown table td must be readable in dark theme')

  // 3. 验证 UsagePanel 用量面板卡片与图表在暗黑模式下自适应
  assert.match(usagePanel, /html\[data-theme='dark'\] \.kpi-card[\s\S]*background:\s*#1e1e2e/, 'KPI cards must be dark in dark theme')
  assert.match(usagePanel, /html\[data-theme='dark'\] \.cache-telemetry-card[\s\S]*background:\s*#181825/, 'Telemetry cards must be dark in dark theme')
  assert.match(usagePanel, /html\[data-theme='dark'\] \.chart-box[\s\S]*background:\s*#1e1e2e/, 'Chart boxes must be dark in dark theme')
  assert.match(usagePanel, /isDarkTheme \? '#818cf8' : '#09090b'/, 'Bar chart must adjust color in dark mode')

  // 4. 验证项目列表页主按钮对比度保障
  assert.match(themeScss, /--theme-accent-contrast/, 'Theme must define --theme-accent-contrast')
  assert.match(themeScss, /html\[data-theme\] \.cs-btn-primary[\s\S]*color:\s*var\(--theme-accent-contrast/, 'cs-btn-primary must use accent contrast text')
  assert.match(cloudSpace, /html\[data-theme="dark"\] \.cs-btn-primary[\s\S]*color:\s*var\(--theme-accent-contrast/, 'CloudSpace must set primary button dark contrast')

  // 5. 验证用户设置详情弹窗深色模式无白块且文本高对比
  assert.match(userDetail, /html\[data-theme="dark"\] \.ud-value[\s\S]*color:\s*#edf1fb/, 'User detail value must be high contrast light color')
  assert.match(userDetail, /html\[data-theme="dark"] \.oauth-item[\s\S]*background:\s*#181b24/, 'OAuth provider card must be dark background')
  assert.match(userDetail, /html\[data-theme="dark"\] \.el-dialog \.el-button/, 'Dialog buttons must be styled in dark theme')

  // 6. 验证教程页支持暗黑模式并响应全局主题
  assert.match(tutorialsScss, /html\[data-theme='dark'\] \.tutorial-page[\s\S]*background:\s*#11131a/, 'Tutorials page must have dark background in dark theme')
  assert.match(tutorialsScss, /html\[data-theme='dark'\] \.tutorial-sidebar[\s\S]*background:\s*#181b24/, 'Tutorials sidebar must have dark background')
  assert.match(tutorialsVue, /themeStore\.openSettings\(\)/, 'Tutorials page must allow opening theme settings')

  // 7. 验证用户信息区域在暗黑模式下的深色背景与高对比文字
  const userPanel = await readFile(new URL('../components/cloud/UserPanel.vue', import.meta.url), 'utf8')
  assert.match(userPanel, /html\[data-theme="dark"\] \.user-panel[\s\S]*background:\s*#181b24/, 'User panel must have dark background')
  assert.match(userPanel, /html\[data-theme="dark"\] \.user-name[\s\S]*color:\s*#edf1fb/, 'User name must be high contrast light color')
  assert.match(themeScss, /\.user-panel\s*\{[\s\S]*background:\s*#181b24/, 'Theme scss must secure user panel dark background')
  assert.match(cloudSpace, /html\[data-theme="dark"\] \.cs-left[\s\S]*background:\s*#181b24/, 'CloudSpace left panel must be dark')

  // 8. 验证模型配置页文本与背景对比度保障
  assert.match(globalScss, /html\[data-theme='dark'\] \.mc-card-model[\s\S]*color:\s*#89b4fa/, 'Model name in config card must have vibrant contrast')
  assert.match(globalScss, /html\[data-theme='dark'\] \.mc-config-card[\s\S]*background:\s*#1e1e2e/, 'Config card must have dark background')
  assert.match(globalScss, /html\[data-theme='dark'\] \.mc-field label[\s\S]*color:\s*#edf1fb/, 'Field labels must be high contrast')
  assert.match(globalScss, /html\[data-theme='dark'\] \.mc-input[\s\S]*background:\s*#11131a/, 'Input must have dark background')

  // 9. 验证选择模型页容器与卡片在暗黑模式下无白底且层级分明
  const modelSelector = await readFile(new URL('../components/cloud/composer/ModelSelectorPopover.vue', import.meta.url), 'utf8')
  assert.match(modelSelector, /html\[data-theme="dark"\] \.model-popover-menu[\s\S]*background:\s*#181b24/, 'Model popover menu must have dark background')
  assert.match(modelSelector, /html\[data-theme="dark"\] \.thinking-levels-accordion[\s\S]*background:\s*#141720/, 'Thinking accordion must be dark')
  assert.match(modelSelector, /html\[data-theme="dark"\] \.thinking-pill-btn[\s\S]*background:\s*#202430/, 'Thinking pill buttons must be dark')
  assert.match(globalScss, /html\[data-theme='dark'\] \.mc-template-card[\s\S]*background:\s*#1e1e2e/, 'Template card in dialog must have dark background')
  assert.match(globalScss, /html\[data-theme='dark'\] \.mc-template-model[\s\S]*color:\s*#89b4fa/, 'Template model name must have high contrast')

  // 10. 验证输入框右下角触发胶囊（已配置模型名称）在暗黑模式下的高对比度
  assert.match(modelSelector, /html\[data-theme="dark"\] \.model-trigger-pill[\s\S]*color:\s*#cdd6f4/, 'Active model name in input box pill must have bright contrast')
  assert.match(modelSelector, /html\[data-theme="dark"\] \.model-trigger-pill:hover[\s\S]*color:\s*#ffffff/, 'Model trigger pill must be white on hover')
})
