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
  color: #4b4c45;
}

.auth-field__label {
  font-size: 13px;
  font-weight: 600;
}

.auth-field__label small {
  color: #8b887e;
  font-size: 12px;
  font-weight: 400;
}

.auth-field input {
  box-sizing: border-box;
  width: 100%;
  height: 46px;
  border: 1px solid #cfc8ba;
  border-radius: 13px 11px 14px 12px;
  padding: 0 14px;
  outline: none;
  background: #f7f4ec;
  color: #34352f;
  font: inherit;
  transition: border-color 180ms ease, box-shadow 180ms ease, background-color 180ms ease;
}

.auth-field input::placeholder {
  color: #9b978c;
}

.auth-field input:focus {
  border-color: #5d675b;
  background: #fcfaf4;
  box-shadow: 0 0 0 4px rgb(93 103 91 / 10%);
}

.auth-field--error input {
  border-color: #b96c5c;
}

.auth-field__error {
  color: #a24e42;
  font-size: 12px;
  line-height: 1.4;
}
</style>
