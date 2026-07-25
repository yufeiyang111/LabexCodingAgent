<template>
  <form class="space-y-4" @submit.prevent="submit">
    <label class="grid gap-2">
      <span class="text-sm font-medium text-[#1D1D1F]">Username</span>
      <input v-model.trim="form.username" class="h-12 w-full rounded-xl border border-[#E5E5E5] bg-white px-4 text-[15px] text-[#1D1D1F] outline-none transition-[border-color,box-shadow] duration-200 placeholder:text-[#8A8A8A] focus:border-[#1D1D1F] focus:ring-4 focus:ring-black/[0.04]" type="text" autocomplete="username" placeholder="Enter your username" />
    </label>
    <label v-if="mode === 'register'" class="grid gap-2">
      <span class="text-sm font-medium text-[#1D1D1F]">Display name <span class="font-normal text-[#8A8A8A]">(optional)</span></span>
      <input v-model.trim="form.displayName" class="h-12 w-full rounded-xl border border-[#E5E5E5] bg-white px-4 text-[15px] text-[#1D1D1F] outline-none transition-[border-color,box-shadow] duration-200 placeholder:text-[#8A8A8A] focus:border-[#1D1D1F] focus:ring-4 focus:ring-black/[0.04]" type="text" autocomplete="name" placeholder="Enter your display name" />
    </label>
    <label class="grid gap-2">
      <span class="text-sm font-medium text-[#1D1D1F]">Password</span>
      <input v-model="form.password" class="h-12 w-full rounded-xl border border-[#E5E5E5] bg-white px-4 text-[15px] text-[#1D1D1F] outline-none transition-[border-color,box-shadow] duration-200 placeholder:text-[#8A8A8A] focus:border-[#1D1D1F] focus:ring-4 focus:ring-black/[0.04]" type="password" :autocomplete="mode === 'login' ? 'current-password' : 'new-password'" placeholder="Enter your password" />
    </label>
    <button class="mt-2 flex h-12 w-full items-center justify-center rounded-xl bg-[#1D1D1F] px-5 text-[15px] font-medium text-white transition-all duration-200 hover:-translate-y-px hover:bg-[#333333] hover:shadow-[0_6px_16px_rgba(0,0,0,0.12)] focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-[#1D1D1F] disabled:cursor-wait disabled:opacity-60 disabled:hover:translate-y-0" type="submit" :disabled="loading">{{ loading ? 'Please wait?' : mode === 'login' ? 'Log in' : 'Create account' }}</button>
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
