<template>
  <form class="space-y-5" @submit.prevent="submit">
    <label class="grid gap-2">
      <span class="text-sm font-medium text-[#4B4C45]">用户名</span>
      <input v-model.trim="form.username" class="h-12 w-full rounded-[13px_11px_14px_12px] border border-[#CFC8BA] bg-[#F7F4EC] px-4 text-[15px] text-[#34352F] outline-none transition-[border-color,background-color,box-shadow] duration-200 placeholder:text-[#9B978C] focus:border-[#5D675B] focus:bg-[#FCFAF4] focus:ring-4 focus:ring-[#5D675B]/10" type="text" autocomplete="username" placeholder="请输入用户名" />
    </label>
    <label v-if="mode === 'register'" class="grid gap-2">
      <span class="text-sm font-medium text-[#4B4C45]">显示名称 <span class="font-normal text-[#8B887E]">（可选）</span></span>
      <input v-model.trim="form.displayName" class="h-12 w-full rounded-[13px_11px_14px_12px] border border-[#CFC8BA] bg-[#F7F4EC] px-4 text-[15px] text-[#34352F] outline-none transition-[border-color,background-color,box-shadow] duration-200 placeholder:text-[#9B978C] focus:border-[#5D675B] focus:bg-[#FCFAF4] focus:ring-4 focus:ring-[#5D675B]/10" type="text" autocomplete="name" placeholder="为工作间留下名字" />
    </label>
    <label class="grid gap-2">
      <span class="text-sm font-medium text-[#4B4C45]">密码</span>
      <input v-model="form.password" class="h-12 w-full rounded-[13px_11px_14px_12px] border border-[#CFC8BA] bg-[#F7F4EC] px-4 text-[15px] text-[#34352F] outline-none transition-[border-color,background-color,box-shadow] duration-200 placeholder:text-[#9B978C] focus:border-[#5D675B] focus:bg-[#FCFAF4] focus:ring-4 focus:ring-[#5D675B]/10" type="password" :autocomplete="mode === 'login' ? 'current-password' : 'new-password'" placeholder="请输入密码" />
    </label>
    <button class="mt-2 flex h-12 w-full items-center justify-center rounded-[14px_12px_15px_11px] border border-[#4E584D] bg-[#4E584D] px-5 text-[15px] font-medium text-[#FBF8F0] transition-[background-color,border-color,filter] duration-200 hover:border-[#596557] hover:bg-[#596557] hover:brightness-[1.02] focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-[#5D675B] disabled:cursor-wait disabled:opacity-60" type="submit" :disabled="loading">{{ loading ? '正在处理…' : mode === 'login' ? '进入工作间' : '创建工作间' }}</button>
  </form>
</template>

<script setup lang="ts">
import { reactive, watch } from 'vue'
type AuthMode = 'login' | 'register'
interface Credentials { username: string; displayName: string; password: string }
const props = withDefaults(defineProps<{ mode: AuthMode; loading?: boolean }>(), { loading: false })
const emit = defineEmits<{ submit: [credentials: Credentials]; validationError: [] }>()
const form = reactive<Credentials>({ username: '', displayName: '', password: '' })
watch(() => props.mode, mode => { if (mode === 'login') form.displayName = '' })
function submit() { if (!form.username || !form.password) { emit('validationError'); return }; emit('submit', { ...form }) }
</script>
