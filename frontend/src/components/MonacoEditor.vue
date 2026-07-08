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
import { VueMonacoEditor } from '@guolao/vue-monaco-editor'

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
  border: 0;
  overflow: hidden;
}
</style>
