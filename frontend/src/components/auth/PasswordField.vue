<template>
  <label class="password-field" :class="{ 'password-field--error': error }">
    <span class="password-field__label">{{ label }}</span>
    <span class="password-field__control">
      <input
        :id="inputId"
        :name="inputId"
        :value="modelValue"
        :type="visible ? 'text' : 'password'"
        :autocomplete="autocomplete"
        :placeholder="placeholder"
        maxlength="72"
        :aria-invalid="Boolean(error)"
        @input="$emit('update:modelValue', $event.target.value)"
      />
      <button type="button" :aria-label="visible ? '隐藏密码' : '显示密码'" @click="visible = !visible">
        {{ visible ? '隐藏' : '显示' }}
      </button>
    </span>
    <span v-if="error" class="password-field__error" role="alert">{{ error }}</span>
  </label>
</template>

<script setup>
import { computed, ref } from 'vue'

const props = defineProps({
  modelValue: { type: String, default: '' },
  label: { type: String, required: true },
  autocomplete: { type: String, default: 'current-password' },
  placeholder: { type: String, default: '' },
  error: { type: String, default: '' }
})
defineEmits(['update:modelValue'])
const visible = ref(false)
const inputId = computed(() => `auth-password-${props.label.replace(/[^a-zA-Z0-9\u4e00-\u9fff]/g, '-')}`)
</script>

<style scoped>
.password-field {
  display: grid;
  gap: 8px;
  color: var(--theme-text);
}

.password-field__label {
  font-size: 13px;
  font-weight: 600;
}

.password-field__control {
  display: flex;
  align-items: center;
  height: 46px;
  border: 1px solid var(--theme-border);
  border-radius: 8px;
  background: var(--theme-surface);
  transition: border-color 180ms ease, box-shadow 180ms ease, background-color 180ms ease;
}

.password-field__control:focus-within {
  border-color: var(--theme-accent);
  background: transparent;
  box-shadow: 0 0 0 3px color-mix(in srgb, var(--theme-accent) 18%, transparent);
}

.password-field input {
  min-width: 0;
  flex: 1;
  height: 100%;
  border: 0;
  outline: 0;
  padding: 0 14px;
  background: transparent;
  color: var(--theme-text);
  font: inherit;
}

.password-field input::placeholder {
  color: #6b7280;
}

.password-field button {
  flex: 0 0 auto;
  border: 0;
  padding: 0 13px;
  background: transparent;
  color: var(--theme-accent);
  cursor: pointer;
  font-size: 12px;
}

.password-field--error .password-field__control {
  border-color: var(--theme-danger);
}

.password-field__error {
  color: var(--theme-danger);
  font-size: 12px;
}
</style>
