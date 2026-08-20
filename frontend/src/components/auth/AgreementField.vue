<template>
  <label class="agreement-field" :class="{ 'agreement-field--error': error }">
    <input :checked="modelValue" type="checkbox" @change="$emit('update:modelValue', $event.target.checked)" />
    <span>我已阅读并同意 <a href="#" @click.prevent>《使用说明》</a> 和 <a href="#" @click.prevent>《隐私说明》</a></span>
    <span v-if="error" class="agreement-field__error" role="alert">{{ error }}</span>
  </label>
</template>

<script setup>
defineProps({
  modelValue: { type: Boolean, default: false },
  error: { type: String, default: '' }
})
defineEmits(['update:modelValue'])
</script>

<style scoped>
.agreement-field { display: flex; align-items: flex-start; gap: 9px; margin-top: 2px; color: #76756d; font-size: 12px; line-height: 1.6; cursor: pointer; }
.agreement-field input { width: 16px; height: 16px; margin: 2px 0 0; accent-color: #5d675b; }
.agreement-field a { color: #5d675b; text-decoration: underline; text-underline-offset: 2px; }
.agreement-field--error { color: #a24e42; }
.agreement-field__error { flex-basis: 100%; margin-left: 25px; font-size: 12px; }
</style>
