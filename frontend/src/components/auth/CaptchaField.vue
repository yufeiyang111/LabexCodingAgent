<template>
  <div v-if="required" class="captcha-field" :class="{ 'captcha-field--error': error }">
    <label class="captcha-field__label" :for="inputId">图形验证码</label>
    <div class="captcha-field__row">
      <input
        :id="inputId"
        :name="inputId"
        :value="modelValue"
        type="text"
        inputmode="text"
        autocomplete="off"
        maxlength="8"
        placeholder="请输入图中字符"
        :aria-invalid="Boolean(error)"
        @input="$emit('update:modelValue', $event.target.value.toUpperCase())"
      />
      <button type="button" class="captcha-field__image" :disabled="loading" aria-label="刷新图形验证码" @click="$emit('refresh')">
        <img v-if="image" :src="image" alt="图形验证码" />
        <span v-else>{{ loading ? '加载中' : '点击刷新' }}</span>
      </button>
    </div>
    <span v-if="error" class="captcha-field__error" role="alert">{{ error }}</span>
  </div>
</template>

<script setup>
import { computed } from 'vue'

const props = defineProps({
  modelValue: { type: String, default: '' },
  required: { type: Boolean, default: false },
  image: { type: String, default: '' },
  loading: { type: Boolean, default: false },
  error: { type: String, default: '' }
})
defineEmits(['update:modelValue', 'refresh'])
const inputId = computed(() => `auth-captcha-${Math.random().toString(36).slice(2, 9)}`)
</script>

<style scoped>
.captcha-field { display: grid; gap: 8px; }
.captcha-field__label { color: #4b4c45; font-size: 13px; font-weight: 600; }
.captcha-field__row { display: flex; gap: 10px; }
.captcha-field input { min-width: 0; flex: 1; height: 46px; box-sizing: border-box; border: 1px solid #cfc8ba; border-radius: 13px 11px 14px 12px; padding: 0 14px; outline: none; background: #f7f4ec; color: #34352f; font: inherit; letter-spacing: .12em; }
.captcha-field input:focus { border-color: #5d675b; box-shadow: 0 0 0 4px rgb(93 103 91 / 10%); }
.captcha-field__image { display: grid; place-items: center; width: 128px; min-width: 96px; height: 46px; overflow: hidden; border: 1px solid #cfc8ba; border-radius: 10px; padding: 0; background: #f7f4ec; color: #5d675b; cursor: pointer; font-size: 11px; }
.captcha-field__image:disabled { cursor: wait; opacity: .65; }
.captcha-field__image img { display: block; width: 100%; height: 100%; object-fit: cover; }
.captcha-field__error { color: #a24e42; font-size: 12px; }
</style>
