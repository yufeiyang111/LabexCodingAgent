<template>
  <label class="agreement-field" :class="{ 'agreement-field--error': error }">
    <input :checked="modelValue" type="checkbox" @change="$emit('update:modelValue', $event.target.checked)" />
    <span>
      我已阅读并同意
      <!--
        两个注意点：
        1. 链接位于 <label> 内，点击会冒泡到 label 而连带切换复选框 —— 必须 @click.stop，
           否则「点协议看看内容」会顺手把自己勾上（或取消勾选）。
        2. 用 target="_blank" 新开标签：注册表单已填写的内容不会因跳走而丢失，
           看完协议回到原标签即可继续提交。rel="noopener" 防止新页面通过 window.opener 反向操作本页。
      -->
      <RouterLink :to="legalPath('terms')" target="_blank" rel="noopener" @click.stop>《使用说明》</RouterLink>
      和
      <RouterLink :to="legalPath('privacy')" target="_blank" rel="noopener" @click.stop>《隐私说明》</RouterLink>
    </span>
    <span v-if="error" class="agreement-field__error" role="alert">{{ error }}</span>
  </label>
</template>

<script setup>
import { RouterLink } from 'vue-router'
import { legalPath } from '@/data/legal/legalDocuments.js'

defineProps({
  modelValue: { type: Boolean, default: false },
  error: { type: String, default: '' }
})
defineEmits(['update:modelValue'])
</script>

<style scoped>
.agreement-field { display: flex; align-items: flex-start; gap: 9px; margin-top: 2px; color: var(--theme-text-secondary); font-size: 12px; line-height: 1.6; cursor: pointer; }
.agreement-field input { width: 16px; height: 16px; margin: 2px 0 0; accent-color: var(--theme-accent); }
.agreement-field a { color: var(--theme-accent); text-decoration: underline; text-underline-offset: 2px; }
.agreement-field--error { color: var(--theme-danger); }
.agreement-field__error { flex-basis: 100%; margin-left: 25px; font-size: 12px; }
</style>
