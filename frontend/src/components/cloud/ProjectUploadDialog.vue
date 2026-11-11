<template>
  <Teleport to="body">
    <Transition name="upd" appear>
      <div v-if="visible" class="upd-overlay" @click.self="onOverlayClick">
        <div class="upd-dialog" role="dialog" aria-modal="true">
          <template v-if="phase === 'confirm'">
            <h3>上传项目压缩包</h3>
            <div class="upd-file">
              <span class="upd-file-name">{{ fileName }}</span>
              <span class="upd-file-size">{{ formatBytes(fileSize) }}</span>
            </div>
            <p v-if="!validation.ok" class="upd-error">{{ validation.message }}</p>
            <p v-else-if="limitLabel" class="upd-hint">上限 {{ limitLabel }}。上传后会自动解压并创建项目。</p>

            <label v-if="skippedDirectoryNames.length" class="upd-option">
              <input
                type="checkbox"
                :checked="skipDependencies"
                @change="$emit('update:skipDependencies', $event.target.checked)"
              />
              <span>
                跳过依赖与构建产物目录
                <span class="upd-option-detail">（{{ skippedDirectoryNames.join('、') }} 等）</span>
              </span>
            </label>

            <div class="upd-actions">
              <button class="upd-btn ghost" @click="$emit('cancel')">取消</button>
              <button class="upd-btn primary" :disabled="!validation.ok" @click="$emit('start')">开始上传</button>
            </div>
          </template>

          <template v-else-if="phase === 'uploading'">
            <h3>正在上传…</h3>
            <div class="upd-progress-row">
              <div class="upd-bar-track">
                <div class="upd-bar-fill" :style="{ width: progress.percent + '%' }"></div>
              </div>
              <span class="upd-percent">{{ progress.percent }}%</span>
            </div>
            <p class="upd-hint">{{ uploadHint }}</p>
            <div class="upd-actions">
              <button class="upd-btn ghost" @click="$emit('cancel')">取消上传</button>
            </div>
          </template>

          <template v-else-if="phase === 'extracting'">
            <h3>正在解压…</h3>
            <div class="upd-progress-row">
              <div class="upd-bar-track is-indeterminate">
                <div class="upd-bar-fill indeterminate"></div>
              </div>
            </div>
            <p class="upd-hint">{{ extractingHint }}</p>
            <p class="upd-note">服务端解压无法中断，请等待完成。</p>
            <div class="upd-actions">
              <button class="upd-btn ghost" disabled>请稍候</button>
            </div>
          </template>

          <template v-else-if="phase === 'done'">
            <h3>上传完成</h3>
            <p class="upd-hint">{{ doneHint }}</p>
            <div v-if="result?.skippedCount > 0" class="upd-skipped">
              <p class="upd-note">已跳过 {{ result.skippedCount }} 个依赖或构建产物文件，未写入工作区。</p>
              <ul v-if="result.skippedSamples?.length" class="upd-samples">
                <li v-for="sample in result.skippedSamples" :key="sample">{{ sample }}</li>
              </ul>
            </div>
            <div class="upd-actions">
              <button class="upd-btn primary" @click="$emit('close')">关闭</button>
            </div>
          </template>
        </div>
      </div>
    </Transition>
  </Teleport>
</template>

<script setup>
import { computed } from 'vue'
import { formatBytes, formatDuration, previewSkippedDirectories } from '@/composables/projectUpload/uploadModel'

/**
 * 上传进度对话框：确认（含超限提示与跳过依赖选项）→ 上传（真实字节进度）→
 * 解压（不确定进度 + 已用时间）→ 完成。完成与失败由 composable 收尾，这里只展示。
 */
const props = defineProps({
  visible: { type: Boolean, default: false },
  phase: { type: String, default: 'idle' },
  file: { type: Object, default: null },
  validation: { type: Object, default: () => ({ ok: true }) },
  progress: { type: Object, default: () => ({ percent: 0, loaded: 0, total: 0, bytesPerSecond: 0, etaSeconds: null }) },
  extractingElapsed: { type: Number, default: null },
  result: { type: Object, default: null },
  skipDependencies: { type: Boolean, default: true },
  limits: { type: Object, default: () => ({}) }
})

defineEmits(['start', 'cancel', 'close', 'update:skipDependencies'])

const fileName = computed(() => props.file?.name || '')
const fileSize = computed(() => Number(props.file?.size) || 0)

const limitLabel = computed(() => {
  const max = Number(props.limits?.maxArchiveBytes)
  return Number.isFinite(max) && max > 0 ? formatBytes(max) : ''
})

const skippedDirectoryNames = computed(() => previewSkippedDirectories(props.limits?.skippedDirectoryNames, 6))

const uploadHint = computed(() => {
  const { loaded, total, bytesPerSecond, etaSeconds } = props.progress
  const parts = [`${formatBytes(loaded)} / ${formatBytes(total)}`]
  if (bytesPerSecond > 0) parts.push(`${formatBytes(bytesPerSecond)}/s`)
  if (etaSeconds != null) parts.push(`剩余 ${formatDuration(etaSeconds)}`)
  return parts.join(' · ')
})

const extractingHint = computed(() => {
  if (props.extractingElapsed == null) return '正在写入工作区…'
  return `已用时 ${formatDuration(props.extractingElapsed)}`
})

const doneHint = computed(() => {
  const fileCount = props.result?.fileCount ?? 0
  const totalSize = props.result?.totalSize ?? 0
  return `${fileCount} 个文件 · ${formatBytes(totalSize)}`
})

function onOverlayClick() {
  // 上传中与解压中点击遮罩不关闭，防止误触丢任务。
  if (props.phase === 'confirm') {
    // 确认阶段允许取消
  }
}
</script>

<style scoped>
.upd-overlay { position: fixed; inset: 0; z-index: 9995; background: rgba(15,15,20,0.45); display: flex; align-items: center; justify-content: center; }
.upd-dialog { background: #fff; border-radius: 12px; box-shadow: 0 16px 48px rgba(0,0,0,0.2); padding: 18px 20px; width: min(460px, calc(100vw - 48px)); }
.upd-dialog h3 { margin: 0 0 10px; font-size: 15px; color: #18181b; }
.upd-file { display: flex; align-items: baseline; justify-content: space-between; gap: 12px; padding: 8px 10px; background: #f8f9fb; border-radius: 8px; margin-bottom: 10px; }
.upd-file-name { font-size: 13px; color: #18181b; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.upd-file-size { font-size: 12.5px; color: #6b7280; flex-shrink: 0; font-variant-numeric: tabular-nums; }
.upd-hint { margin: 0 0 12px; font-size: 12.5px; color: #6b7280; line-height: 1.6; }
.upd-note { margin: 0 0 12px; font-size: 12px; color: #9ca3af; line-height: 1.5; }
.upd-skipped { margin-bottom: 12px; }
.upd-skipped .upd-note { margin-bottom: 4px; }
.upd-samples { margin: 0; padding-left: 18px; font-size: 11.5px; color: #9ca3af; line-height: 1.6; }
.upd-samples li { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.upd-error { margin: 0 0 12px; font-size: 12.5px; color: #dc2626; line-height: 1.6; }
.upd-option { display: flex; align-items: flex-start; gap: 8px; font-size: 13px; color: #374151; cursor: pointer; user-select: none; margin-bottom: 16px; line-height: 1.5; }
.upd-option input { accent-color: #4f46e5; margin-top: 2px; }
.upd-option-detail { color: #9ca3af; font-size: 12px; }
.upd-progress-row { display: flex; align-items: center; gap: 10px; margin: 6px 0 10px; }
.upd-bar-track { flex: 1; height: 8px; border-radius: 999px; background: #e5e7eb; overflow: hidden; }
.upd-bar-fill { height: 100%; border-radius: 999px; background: linear-gradient(90deg, #6366f1, #4f46e5); transition: width 0.3s ease; }
.upd-bar-track.is-indeterminate { position: relative; }
.upd-bar-fill.indeterminate { width: 35%; animation: upd-slide 1.4s ease-in-out infinite; }
@keyframes upd-slide { 0% { transform: translateX(-100%); } 100% { transform: translateX(320%); } }
.upd-percent { font-size: 12.5px; color: #374151; min-width: 38px; text-align: right; font-variant-numeric: tabular-nums; }
.upd-actions { display: flex; justify-content: flex-end; gap: 8px; }
.upd-btn { border-radius: 8px; padding: 7px 14px; font: inherit; font-size: 13px; cursor: pointer; transition: all 0.12s; }
.upd-btn:disabled { opacity: 0.55; cursor: not-allowed; }
.upd-btn.ghost { border: none; background: transparent; color: #6b7280; }
.upd-btn.ghost:hover:not(:disabled) { background: #f3f4f6; }
.upd-btn.primary { border: none; background: #4f46e5; color: #fff; }
.upd-btn.primary:hover:not(:disabled) { background: #4338ca; }

:global(html[data-theme="dark"] .upd-dialog) { background: #1f2033; }
:global(html[data-theme="dark"] .upd-dialog h3) { color: #c0caf5; }
:global(html[data-theme="dark"] .upd-file) { background: #252b3d; }
:global(html[data-theme="dark"] .upd-file-name) { color: #c0caf5; }
:global(html[data-theme="dark"] .upd-file-size) { color: #787c99; }
:global(html[data-theme="dark"] .upd-hint) { color: #787c99; }
:global(html[data-theme="dark"] .upd-note) { color: #6b7280; }
:global(html[data-theme="dark"] .upd-option) { color: #a9b1d6; }
:global(html[data-theme="dark"] .upd-bar-track) { background: #282a3a; }
:global(html[data-theme="dark"] .upd-percent) { color: #a9b1d6; }

.upd-enter-active, .upd-leave-active { transition: opacity 0.16s ease; }
.upd-enter-from, .upd-leave-to { opacity: 0; }
</style>
