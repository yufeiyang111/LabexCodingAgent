<template>
  <Teleport to="body">
    <Transition name="epd" appear>
      <div v-if="visible" class="epd-overlay" @click.self="onOverlayClick">
        <div class="epd-dialog" role="dialog" aria-modal="true">
          <template v-if="phase === 'confirm'">
            <h3>导出项目压缩包</h3>
            <p class="epd-hint">默认排除 node_modules、dist、target 等依赖与构建产物目录，仅打包源码与配置，速度最快。</p>
            <label class="epd-option">
              <input v-model="includeAllModel" type="checkbox" />
              <span>包含全部文件（含依赖目录，体积大、耗时长）</span>
            </label>
            <div class="epd-actions">
              <button class="epd-btn ghost" @click="$emit('cancel')">取消</button>
              <button class="epd-btn primary" @click="$emit('start')">开始导出</button>
            </div>
          </template>

          <template v-else>
            <h3>正在导出项目…</h3>
            <div class="epd-progress-row">
              <div class="epd-bar-track">
                <div class="epd-bar-fill" :style="{ width: progressPercent + '%' }"></div>
              </div>
              <span class="epd-percent">{{ progressPercent }}%</span>
            </div>
            <p class="epd-hint">{{ packedHint }}</p>
            <div class="epd-actions">
              <button class="epd-btn ghost" :disabled="phase === 'saving'" @click="$emit('cancel')">
                {{ phase === 'saving' ? '保存中…' : '取消导出' }}
              </button>
            </div>
          </template>
        </div>
      </div>
    </Transition>
  </Teleport>
</template>

<script setup>
import { computed } from 'vue'

/**
 * 导出进度对话框：confirm 阶段选择是否包含依赖目录；running/saving 展示轮询进度。
 * 完成与失败由 composable 通过 notify/phase 变化收尾，这里只做展示与转发。
 */
const props = defineProps({
  visible: { type: Boolean, default: false },
  phase: { type: String, default: 'idle' },
  progressPercent: { type: Number, default: 0 },
  packedBytes: { type: Number, default: 0 }
})

const emit = defineEmits(['start', 'cancel'])

const includeAllModel = defineModel('includeAll', { type: Boolean, default: false })

const packedHint = computed(() => {
  if (props.phase === 'saving') return '打包完成，正在保存到本地…'
  const packedMb = (props.packedBytes / 1024 / 1024).toFixed(1)
  return `已打包 ${packedMb} MB，完成后将自动保存到本地`
})

function onOverlayClick() {
  // 运行中点击遮罩不关闭，防止误触丢任务；confirm 阶段允许取消。
  if (props.phase === 'confirm') emit('cancel')
}
</script>

<style scoped>
.epd-overlay { position: fixed; inset: 0; z-index: 9995; background: rgba(15,15,20,0.45); display: flex; align-items: center; justify-content: center; }
.epd-dialog { background: #fff; border-radius: 12px; box-shadow: 0 16px 48px rgba(0,0,0,0.2); padding: 18px 20px; width: min(420px, calc(100vw - 48px)); }
.epd-dialog h3 { margin: 0 0 8px; font-size: 15px; color: #18181b; }
.epd-hint { margin: 0 0 12px; font-size: 12.5px; color: #6b7280; line-height: 1.6; }
.epd-option { display: flex; align-items: center; gap: 8px; font-size: 13px; color: #374151; cursor: pointer; user-select: none; margin-bottom: 16px; }
.epd-option input { accent-color: #4f46e5; }
.epd-progress-row { display: flex; align-items: center; gap: 10px; margin: 6px 0 10px; }
.epd-bar-track { flex: 1; height: 8px; border-radius: 999px; background: #e5e7eb; overflow: hidden; }
.epd-bar-fill { height: 100%; border-radius: 999px; background: linear-gradient(90deg, #6366f1, #4f46e5); transition: width 0.4s ease; }
.epd-percent { font-size: 12.5px; color: #374151; min-width: 38px; text-align: right; font-variant-numeric: tabular-nums; }
.epd-actions { display: flex; justify-content: flex-end; gap: 8px; }
.epd-btn { border-radius: 8px; padding: 7px 14px; font: inherit; font-size: 13px; cursor: pointer; transition: all 0.12s; }
.epd-btn:disabled { opacity: 0.6; cursor: not-allowed; }
.epd-btn.ghost { border: none; background: transparent; color: #6b7280; }
.epd-btn.ghost:hover:not(:disabled) { background: #f3f4f6; }
.epd-btn.primary { border: none; background: #4f46e5; color: #fff; }
.epd-btn.primary:hover { background: #4338ca; }

:global(html[data-theme="dark"] .epd-dialog) { background: #1f2033; }
:global(html[data-theme="dark"] .epd-dialog h3) { color: #c0caf5; }
:global(html[data-theme="dark"] .epd-hint) { color: #787c99; }
:global(html[data-theme="dark"] .epd-option) { color: #a9b1d6; }
:global(html[data-theme="dark"] .epd-bar-track) { background: #282a3a; }
:global(html[data-theme="dark"] .epd-percent) { color: #a9b1d6; }

.epd-enter-active, .epd-leave-active { transition: opacity 0.16s ease; }
.epd-enter-from, .epd-leave-to { opacity: 0; }
</style>
