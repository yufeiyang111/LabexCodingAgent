<template>
  <Teleport to="body">
    <Transition name="fcd" appear>
      <div v-if="visible && current" class="fcd-overlay" @click.self="cancel">
        <div class="fcd-dialog" role="alertdialog" aria-modal="true">
          <h3>{{ title }}</h3>
          <p class="fcd-progress">第 {{ index + 1 }} / {{ conflicts.length }} 个冲突</p>
          <div class="fcd-target">
            <span class="fcd-type-badge" :class="{ dir: current.type === 'directory' }">
              {{ current.type === 'directory' ? '文件夹' : '文件' }}
            </span>
            <span class="fcd-name">{{ current.path }}</span>
          </div>
          <label class="fcd-apply-rest">
            <input v-model="applyToRest" type="checkbox" />
            <span>对剩余全部冲突应用相同选择</span>
          </label>
          <div class="fcd-actions">
            <button class="fcd-btn ghost" @click="cancel">取消{{ kindLabel }}</button>
            <button class="fcd-btn outline" @click="decide('skip')">跳过</button>
            <button class="fcd-btn primary" @click="decide('overwrite')">覆盖</button>
          </div>
        </div>
      </div>
    </Transition>
  </Teleport>
</template>

<script setup>
import { computed, ref, watch } from 'vue'

/**
 * 同名冲突裁决：默认逐个询问（跳过/覆盖），可勾选"应用到剩余全部"一次决策；
 * 取消则中止整个操作。decisions 结构与后端一致 { path: 'skip'|'overwrite' }。
 */
const props = defineProps({
  visible: { type: Boolean, default: false },
  conflicts: { type: Array, default: () => [] },
  kind: { type: String, default: 'copy' }
})

const emit = defineEmits(['complete'])

const index = ref(0)
const applyToRest = ref(false)
const decisions = ref({})

const KIND_LABELS = {
  copy: '复制',
  move: '移动',
  upload: '上传'
}

const title = computed(() => `${KIND_LABELS[props.kind] || '操作'}冲突`)
const kindLabel = computed(() => KIND_LABELS[props.kind] || '操作')
const current = computed(() => props.conflicts[index.value] || null)

watch(() => props.visible, visible => {
  if (visible) {
    index.value = 0
    applyToRest.value = false
    decisions.value = {}
  }
})

function decide(action) {
  if (!current.value) return finish({})
  decisions.value[current.value.path] = action
  if (applyToRest.value) {
    props.conflicts.forEach(conflict => {
      if (conflict.path !== current.value.path) decisions.value[conflict.path] = action
    })
    return finish(decisions.value)
  }
  if (index.value + 1 >= props.conflicts.length) return finish(decisions.value)
  index.value++
}

function cancel() {
  emit('complete', null)
}

function finish(result) {
  emit('complete', result)
}
</script>

<style scoped>
.fcd-overlay { position: fixed; inset: 0; z-index: 10000; background: rgba(15,15,20,0.45); display: flex; align-items: center; justify-content: center; }
.fcd-dialog { background: #fff; border-radius: 12px; box-shadow: 0 16px 48px rgba(0,0,0,0.2); padding: 18px 20px; width: min(440px, calc(100vw - 48px)); }
.fcd-dialog h3 { margin: 0 0 6px; font-size: 15px; color: #18181b; }
.fcd-progress { margin: 0 0 10px; font-size: 12px; color: #9ca3af; }
.fcd-target { display: flex; align-items: center; gap: 8px; padding: 10px 12px; border: 1px solid #e5e7eb; border-radius: 8px; margin-bottom: 12px; background: #fafafa; }
.fcd-type-badge { font-size: 11px; padding: 2px 8px; border-radius: 999px; background: #dbeafe; color: #1d4ed8; flex-shrink: 0; }
.fcd-type-badge.dir { background: #fef3c7; color: #92400e; }
.fcd-name { font-size: 13px; color: #374151; word-break: break-all; }
.fcd-apply-rest { display: flex; align-items: center; gap: 8px; font-size: 12.5px; color: #4b5563; cursor: pointer; margin-bottom: 14px; user-select: none; }
.fcd-actions { display: flex; justify-content: flex-end; gap: 8px; }
.fcd-btn { border-radius: 8px; padding: 7px 14px; font: inherit; font-size: 13px; cursor: pointer; transition: all 0.12s; }
.fcd-btn.ghost { border: none; background: transparent; color: #6b7280; }
.fcd-btn.ghost:hover { background: #f3f4f6; }
.fcd-btn.outline { border: 1px solid #d4d4d8; background: #fff; color: #374151; }
.fcd-btn.outline:hover { background: #f4f4f5; }
.fcd-btn.primary { border: none; background: #4f46e5; color: #fff; }
.fcd-btn.primary:hover { background: #4338ca; }

:global(html[data-theme="dark"] .fcd-dialog) { background: #1f2033; }
:global(html[data-theme="dark"] .fcd-dialog h3) { color: #c0caf5; }
:global(html[data-theme="dark"] .fcd-target) { background: #171827; border-color: #383a50; }
:global(html[data-theme="dark"] .fcd-name) { color: #a9b1d6; }
:global(html[data-theme="dark"] .fcd-apply-rest) { color: #a9b1d6; }
:global(html[data-theme="dark"] .fcd-btn.outline) { background: #1f2033; border-color: #383a50; color: #a9b1d6; }

.fcd-enter-active, .fcd-leave-active { transition: opacity 0.15s ease; }
.fcd-enter-from, .fcd-leave-to { opacity: 0; }
</style>
