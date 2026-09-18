<template>
  <label class="auth-field" :class="{ 'auth-field--error': error }">
    <span class="auth-field__label">{{ label }}<small v-if="optional">（可选）</small></span>
    <input
      :id="id"
      :name="id"
      :value="modelValue"
      :type="type"
      :autocomplete="autocomplete"
      :placeholder="placeholder"
      :maxlength="maxlength"
      :aria-invalid="Boolean(error)"
      :aria-describedby="error ? `${id}-error` : undefined"
      @input="$emit('update:modelValue', $event.target.value)"
      @blur="$emit('blur')"
    />
    <span v-if="error" :id="`${id}-error`" class="auth-field__error" role="alert">{{ error }}</span>
  </label>
</template>

<script setup>
import { computed } from 'vue'

const props = defineProps({
  modelValue: { type: String, default: '' },
  label: { type: String, required: true },
  type: { type: String, default: 'text' },
  autocomplete: { type: String, default: 'off' },
  placeholder: { type: String, default: '' },
  error: { type: String, default: '' },
  optional: { type: Boolean, default: false },
  maxlength: { type: [String, Number], default: 255 }
})

defineEmits(['update:modelValue', 'blur'])
const id = computed(() => `auth-field-${props.label.replace(/[^a-zA-Z0-9\u4e00-\u9fff]/g, '-')}`)
</script>

<style scoped>
.auth-field {
  display: grid;
  gap: 8px;
  color: var(--theme-text);
}

.auth-field__label {
  font-size: 13px;
  font-weight: 600;
}

.auth-field__label small {
  color: #5f6b7c;
  font-size: 12px;
  font-weight: 400;
}

.auth-field input {
  box-sizing: border-box;
  width: 100%;
  height: 46px;
  border: 1px solid var(--theme-border);
  border-radius: 8px;
  padding: 0 14px;
  outline: none;
  background: var(--theme-surface);
  color: var(--theme-text);
  font: inherit;
  transition: border-color 180ms ease, box-shadow 180ms ease, background-color 180ms ease;
}

.auth-field input::placeholder {
  color: #6b7280;
}

.auth-field input:focus {
  border-color: var(--theme-accent);
  background: transparent;
  box-shadow: 0 0 0 3px color-mix(in srgb, var(--theme-accent) 18%, transparent);
}

.auth-field--error input {
  border-color: var(--theme-danger);
}

.auth-field__error {
  color: var(--theme-danger);
  font-size: 12px;
  line-height: 1.4;
}
</style>
