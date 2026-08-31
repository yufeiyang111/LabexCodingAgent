<template>
  <div class="monaco-wrapper" :style="{ height }">
    <VueMonacoEditor
      :value="modelValue"
      :language="language"
      :theme="theme"
      :options="editorOptions"
      @update:value="$emit('update:modelValue', $event)"
      @mount="handleMount"
    />
  </div>
</template>

<script setup>
import { computed } from 'vue'
import { loader, VueMonacoEditor } from '@guolao/vue-monaco-editor'
import * as monaco from 'monaco-editor'

loader.config({ monaco })

if (typeof window !== 'undefined' && !window.MonacoEnvironment) {
  window.MonacoEnvironment = {
    getWorker(_, label) {
      return null
    }
  }
}

const props = defineProps({
  modelValue: { type: String, default: '' },
  language: { type: String, default: 'plaintext' },
  height: { type: String, default: '400px' },
  readOnly: { type: Boolean, default: false },
  theme: { type: String, default: 'vs' }
})

const emit = defineEmits(['update:modelValue', 'mount'])

const editorOptions = computed(() => ({
  minimap: { enabled: false },
  lineNumbers: 'on',
  scrollBeyondLastLine: false,
  automaticLayout: true,
  fontSize: 13,
  tabSize: 2,
  readOnly: props.readOnly,
  wordWrap: 'on',
  padding: { top: 8 }
}))

function handleMount(editor) {
  emit('mount', editor)
}
</script>

<style scoped>
.monaco-wrapper {
  width: 100%;
  position: relative;
  overflow: hidden;
}
</style>
