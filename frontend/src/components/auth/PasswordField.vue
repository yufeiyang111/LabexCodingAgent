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
  color: #4b4c45;
}

.password-field__label {
  font-size: 13px;
  font-weight: 600;
}

.password-field__control {
  display: flex;
  align-items: center;
  height: 46px;
  border: 1px solid #cfc8ba;
  border-radius: 13px 11px 14px 12px;
  background: #f7f4ec;
  transition: border-color 180ms ease, box-shadow 180ms ease, background-color 180ms ease;
}

.password-field__control:focus-within {
  border-color: #5d675b;
  background: #fcfaf4;
  box-shadow: 0 0 0 4px rgb(93 103 91 / 10%);
}

.password-field input {
  min-width: 0;
  flex: 1;
  height: 100%;
  border: 0;
  outline: 0;
  padding: 0 14px;
  background: transparent;
  color: #34352f;
  font: inherit;
}

.password-field input::placeholder {
  color: #9b978c;
}

.password-field button {
  flex: 0 0 auto;
  border: 0;
  padding: 0 13px;
  background: transparent;
  color: #5d675b;
  cursor: pointer;
  font-size: 12px;
}

.password-field--error .password-field__control {
  border-color: #b96c5c;
}

.password-field__error {
  color: #a24e42;
  font-size: 12px;
}
</style>
