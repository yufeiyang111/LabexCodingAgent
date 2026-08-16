import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import test from 'node:test'

const source = await readFile(new URL('./CloudWorkspace.vue', import.meta.url), 'utf8')
const runtimeSource = await readFile(new URL('../composables/useAgentTaskRuntime.js', import.meta.url), 'utf8')
const timelineSource = await readFile(new URL('../composables/useAgentEventTimeline.js', import.meta.url), 'utf8')
const completionEvidenceSource = await readFile(new URL('../components/cloud/CompletionEvidenceCard.vue', import.meta.url), 'utf8')
const terminalPanelSource = await readFile(new URL('../components/terminal/TerminalPanel.vue', import.meta.url), 'utf8')

test('terminal command completion reloads the file tree without manual refresh', () => {
  assert.match(source, /@command-finished="onTerminalCommandFinished"/)
  assert.match(source, /function onTerminalCommandFinished\(\) \{[\s\S]*setTimeout\(\(\) => \{ void loadRoot\(\) \}, 150\)/)
  assert.match(terminalPanelSource, /defineEmits\(\[[^\]]*'command-finished'/)
  assert.match(terminalPanelSource, /emit\('command-finished'\)/)
})

test('CloudWorkspace delegates file ownership to useWorkspaceFiles', () => {
  assert.match(source, /import \{ useWorkspaceFiles \} from '@\/composables\/useWorkspaceFiles'/)
  assert.match(source, /useWorkspaceFiles\(\{[\s\S]*api: projectApi/)
  assert.doesNotMatch(source, /async function openFile\(/)
  assert.doesNotMatch(source, /watch\(fileContent,/)
})

test('CloudWorkspace delegates extension state to useAgentExtensions', () => {
  assert.match(source, /import \{ useAgentExtensions \} from '@\/composables\/useAgentExtensions'/)
  assert.match(source, /useAgentExtensions\(\{[\s\S]*api: agentExtensionApi/)
  assert.doesNotMatch(source, /function parseSkillMd\(/)
  assert.doesNotMatch(source, /TODO:.*MCP/)
})

test('CloudWorkspace delegates event dispatch to useAgentEventTimeline', () => {
  assert.match(source, /import \{ useAgentEventTimeline \} from '@\/composables\/useAgentEventTimeline'/)
  assert.match(source, /useAgentEventTimeline\(\{[\s\S]*recordTaskEventCursor[\s\S]*reduceContextManagementEvent/)
  assert.doesNotMatch(source, /function handleAgentEvent\(/)
})

test('CloudWorkspace delegates SSE lifecycle to useAgentStream', () => {
  assert.match(source, /import \{ useAgentStream \} from '@\/composables\/useAgentStream'/)
  assert.match(source, /const \{ stream: streamAgent, replay: replayAgent, subscribe: subscribeAgent, disconnect: disconnectAgentStream, disconnectSubscription, stop: stopAgent \} = useAgentStream\(\)/)
  assert.match(source, /await streamAgent\(projectId\.value,/)
  assert.match(source, /await stopAgent\(projectId\.value,/)
  assert.doesNotMatch(source, /response\.body\.getReader\(\)/)
})

test('CloudWorkspace starts secondary initial requests without blocking the file tree', () => {
  assert.match(source, /import \{ loadWorkspaceResources \} from '@\/composables\/workspaceInitialization'/)
  assert.match(source, /const secondaryResources = loadWorkspaceResources\(\[/)
  assert.match(source, /await loadRoot\(\)/)
})

test('CloudWorkspace lazy-loads heavy editor, terminal, and chart components', () => {
  assert.match(source, /defineAsyncComponent\(\(\) => import\('@\/components\/MonacoEditor\.vue'\)\)/)
  assert.match(source, /defineAsyncComponent\(\(\) => import\('@\/components\/terminal\/TerminalPanel\.vue'\)\)/)
  assert.match(source, /defineAsyncComponent\(\(\) => import\('@\/components\/cloud\/TokenChart\.vue'\)\)/)
})

test('CloudWorkspace renders provider failures instead of leaving a loading skeleton', () => {
  assert.match(timelineSource, /case 'ERROR':[\s\S]*assistantMsg\.content = projectVisibleAgentError\(assistantMsg\.content, message\)[\s\S]*assistantMsg\.isStreaming = false/)
})

test('CloudWorkspace keeps the actual provider error when a stop final event follows', () => {
  assert.match(timelineSource, /case 'FINAL':[\s\S]*!isRecoverableAgentRunState\(assistantMsg\.runState\) && data\.content && !assistantMsg\.error[\s\S]*assistantMsg\.content = stripInternalReasoningBlocks\(data\.content\)/)
})

test('CloudWorkspace renders live thinking and answer deltas immediately', () => {
  assert.match(timelineSource, /case 'THINK_DELTA':[\s\S]*assistantMsg\._thinkingDisplay = assistantMsg\.thinking/)
  assert.match(timelineSource, /case 'FINAL_DELTA':[\s\S]*assistantMsg\._finalReasoningFilter \?\?= createInternalReasoningBlockStreamFilter\(\)[\s\S]*assistantMsg\.content \+= assistantMsg\._finalReasoningFilter\.push\(data\.delta\)[\s\S]*scheduleAgentRender\(\)/)
  assert.doesNotMatch(source, /function startThinkingReveal\([\s\S]*?setInterval\(/)
})

test('CloudWorkspace applies the internal-reasoning boundary again at render time', () => {
  assert.match(source, /v-html="renderThinkingMarkdown\(item\.data\.content\)"/)
  assert.match(source, /v-html="renderMessageMarkdown\(msg\)"/)
  assert.match(source, /function renderThinkingMarkdown\(text\) \{[\s\S]*stripInternalReasoningTags\(text\)/)
  assert.match(source, /function renderMessageMarkdown\(message\) \{[\s\S]*message\?\.role === 'assistant'[\s\S]*stripInternalReasoningBlocks\(message\?\.content\)/)
})


test('direct SSE events persist cursors through the extracted task runtime', () => {
  assert.match(timelineSource, /recordTaskEventCursor\(data\.taskId \|\| assistantMsg\?\.taskId, event\.eventId\)/)
  assert.match(source, /recordTaskEventCursor,[\s\S]*?syncTaskTiming/)
  assert.doesNotMatch(source, /\bsaveTaskEventCursor\(/)
  assert.match(runtimeSource, /function recordTaskEventCursor\(taskId, eventId\)/)
  assert.match(runtimeSource, /logTaskRecovery: log/)
})

test('question replies reconnect the same durable task after the backend resumes it', () => {
  assert.match(source, /async function handleQuestionReply\(payload\) \{[\s\S]*?if \(result\.success\)[\s\S]*?replayResumedAgent\(taskId, assistantMsg(?:, [^)]+)?\)/)
  assert.match(source, /const taskId = call\?\.questionRequest\?\.taskId \|\| assistantMsg\?\.taskId/)
})

test('command approval continuation reconnects through the extracted durable task runtime', () => {
  assert.match(source, /async function replayResumedAgent\(taskId, assistantMsg(?:, conversationId)?\) \{[\s\S]*?resumeTaskEventSubscription\(taskId, assistantMsg(?:, conversationId)?\)/)
  assert.match(runtimeSource, /async function subscribeToTaskEvents\(initialTask, assistantMsg\) \{[\s\S]*?await subscribeAgent\(projectId\.value, task\.taskId,/)
  assert.match(runtimeSource, /const cursor = storedCursor == null[\s\S]*?sequenceNumber\(task\.lastEventSequence\)/)
  assert.match(runtimeSource, /Number\(active\.taskId\) !== Number\(initialTask\.taskId\)/)
  assert.doesNotMatch(runtimeSource, /for \(let attempt = 0; attempt < 40 && assistantMsg\.isStreaming; attempt\+\+\)/)
})


test('conversation ownership changes hard-reset the rendered message timeline', () => {
  assert.match(source, /const conversationRenderEpoch = ref\(0\)/)
  assert.match(source, /<TransitionGroup\s+:key="conversationRenderEpoch"/)
  assert.match(source, /function resetRenderedConversation\(\) \{[\s\S]*?conversationRenderEpoch\.value \+= 1/)
  assert.match(source, /function createNewSession\(\) \{[\s\S]*?resetRenderedConversation\(\)[\s\S]*?resetConversation\(\)/)
  assert.match(source, /async function selectConversation\([\s\S]*?resetRenderedConversation\(\)[\s\S]*?selectConversationState\(conversation\)/)
})

test('new conversation invalidates delayed selection and the extracted task runtime', () => {
  assert.match(source, /const startupConversationSelection = conversationSelectionGuard\.capture\(\)/)
  assert.match(source, /conversationSelectionGuard\.isCurrent\(startupConversationSelection\)/)
  assert.match(source, /function createNewSession\(\) \{[\s\S]*?conversationSelectionGuard\.invalidate\(\)[\s\S]*?invalidateTaskRuntime\(\)[\s\S]*?disconnectAgentStream\(\)[\s\S]*?contextUsageStatus\.value = null[\s\S]*?resetConversation\(\)/)
  assert.match(source, /async function selectConversation\(conversation, \{ explicit = true \} = \{\}\)/)
})
test('direct streams cannot mutate loading or session ownership after a conversation transition', () => {
  assert.match(source, /const streamConversationGeneration = conversationSelectionGuard\.capture\(\)/)
  assert.match(source, /onEvent: event => \{[\s\S]*?conversationSelectionGuard\.isCurrent\(streamConversationGeneration\)[\s\S]*?handleAgentEvent\(event, assistantMsg\)/)
  assert.match(source, /const stillOwnsConversation = conversationSelectionGuard\.isCurrent\(streamConversationGeneration\)[\s\S]*?if \(stillOwnsConversation\) agentLoading\.value = false/)
  assert.match(source, /async function selectConversation\([\s\S]*?invalidateTaskRuntime\(\)[\s\S]*?disconnectAgentStream\(\)/)
})

test('CloudWorkspace keeps styles external without the broken scoped src compilation path', () => {
  assert.match(source, /<style scoped lang="scss">\s*@use ['"]@\/styles\/cloud-workspace\.scoped\.scss['"];\s*<\/style>/)
  assert.doesNotMatch(source, /<style scoped[^>]*\ssrc=/)
  assert.match(source, /<style lang="scss" src="@\/styles\/cloud-workspace\.scss"><\/style>/)
  assert.doesNotMatch(source, /<style(?:\s[^>]*)?>[\s\S]{500,}<\/style>/)
})

test('all resolved user interactions reconnect the existing task event stream', () => {
  assert.match(source, /async function handlePermissionDecision\(payload\)[\s\S]*?const result = await submitPermissionDecision\(payload\)[\s\S]*?replayResumedAgent\(taskId, assistantMsg(?:, [^)]+)?\)/)
  assert.doesNotMatch(source, /!payload\?\.call\?\.networkRequest\) return/)
  assert.match(source, /async function handleQuestionReply\(payload\)[\s\S]*?if \(result\.success[\s\S]*?replayResumedAgent\(taskId, assistantMsg(?:, [^)]+)?\)/)
})

test('command execution keeps the durable event subscription alive while waiting for network approval', () => {
  assert.match(source, /function continueCommandTaskProjection\(call, approval\)[\s\S]*?replayResumedAgent\(taskId, assistantMsg(?:, [^)]+)?\)/)
  assert.match(source, /const execution = await projectApi\.agentExecuteCommandApproval[\s\S]*?continueCommandTaskProjection\(call, approval\)/)
  assert.doesNotMatch(source, /if \(executionData\.resumeAgentLoop\) \{[\s\S]*?replayResumedAgent/)
})

test('recoverable workspace pause hands the initial stream off to durable task subscription', () => {
  assert.match(timelineSource, /resumeTaskEventsAfterStream = data\.resumeAgentLoop === true/)
  assert.match(source, /const shouldResumeTaskEvents = stillOwnsConversation[\s\S]*?assistantMsg\.resumeTaskEventsAfterStream === true/)
  assert.match(source, /shouldResumeTaskEvents[\s\S]*?replayResumedAgent\(assistantMsg\.taskId, assistantMsg(?:, [^)]+)?\)/)
})

test('CloudWorkspace projects durable cache telemetry instead of treating absent data as a miss', () => {
  assert.match(source, /import \{ applyTokenUsageEvent, createTokenUsageState, resolveCacheTelemetryScope, resolveCacheTelemetryView \} from '@\/composables\/cacheTelemetryStatus'/)
  assert.match(source, /const selectedCacheTelemetryStats = computed\(\(\) => resolveCacheTelemetryScope\([\s\S]*allTokenStats\.value,[\s\S]*selectedCacheTelemetryModel\.value/)
  assert.match(timelineSource, /case 'TOKEN_USAGE': \{[\s\S]*applyTokenUsageEvent\(tokenUsage\.value, data\)/)
  assert.match(source, /onTokenUsage: usage => \{[\s\S]*applyTokenUsageEvent\(tokenUsage\.value, usage\)/)
  assert.match(source, /class="usage-cache-card"[\s\S]*cacheTelemetryView\.label[\s\S]*cacheTelemetryView\.detail/)
  assert.match(source, /activeAiTab\.value = key[\s\S]*if \(key === 'usage'\) await initUsageCharts\(\)/)
  assert.match(source, /onTokenUsageProjected: invalidateTokenStatsProjection/)
  assert.match(source, /const requestEpoch = tokenUsageProjectionEpoch[\s\S]*requestEpoch === tokenUsageProjectionEpoch/)
})

test('provider candidate final text stays provisional until the run finalizes', () => {
  assert.match(timelineSource, /case 'FINAL_CANDIDATE_DELTA':[\s\S]*assistantMsg\.pendingFinalContent = \(assistantMsg\.pendingFinalContent \|\| ''\) \+ \(data\.delta \|\| ''\)[\s\S]*assistantMsg\.hasPendingFinalDraft = Boolean\(assistantMsg\.pendingFinalContent\)/)
  assert.match(timelineSource, /case 'COMPLETION_EVIDENCE':[\s\S]*data\.satisfied === true[\s\S]*assistantMsg\.completionBlockedEvidence = data[\s\S]*assistantMsg\.pendingFinalContent = ''/)
  assert.match(timelineSource, /case 'FINAL':[\s\S]*assistantMsg\.pendingFinalContent = ''[\s\S]*assistantMsg\.content = stripInternalReasoningBlocks\(data\.content\)/)
})

test('question and approval pauses do not reconnect before an explicit user decision', () => {
  assert.match(timelineSource, /case 'TASK_PAUSED':[\s\S]*!\['command_approval', 'permission', 'network', 'question'\]\.includes\(data\.reason\)/)
  assert.match(source, /async function handleQuestionReply\(payload\) \{[\s\S]*?replayResumedAgent\(taskId, assistantMsg(?:, [^)]+)?\)/)
})

test('CloudWorkspace renders a durable finalization blocker instead of silently truncating the conversation', () => {
  assert.match(source, /msg\.completionEvidence \|\| msg\.completionBlockedEvidence/)
  assert.match(source, /:evidence="msg\.completionEvidence \|\| msg\.completionBlockedEvidence"/)
})

test('CloudWorkspace labels a visible native final with unsatisfied evidence without calling it a rejected reply', () => {
  assert.match(completionEvidenceSource, /finalResponseVisible[\s\S]*服务器验证未满足（已展示模型答复）/)
})
