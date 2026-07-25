<template>
  <main class="auth-page min-h-[100dvh] bg-auth-background font-auth-sans text-auth-ink">
    <header class="auth-topbar absolute inset-x-0 top-0 z-10 flex h-20 items-center justify-between px-6 sm:px-10">
      <Logo />
      <button class="hidden rounded-lg border border-[#E5E5E5] bg-white px-4 py-2 text-sm font-medium text-[#1D1D1F] transition hover:border-[#CFCFCF] hover:bg-[#FCFCFC] focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-[#1D1D1F] sm:inline-flex" type="button" @click="toggleMode">
        {{ mode === 'login' ? 'Create account' : 'Log in' }}
      </button>
    </header>

    <section class="auth-content animate-auth-enter mx-auto flex min-h-[100dvh] w-full max-w-[400px] flex-col justify-center px-6 pb-20 pt-28 sm:px-0" aria-labelledby="auth-heading">
      <header class="mb-8 text-center">
        <h1 id="auth-heading" class="text-[32px] font-semibold tracking-[-0.04em] text-[#1D1D1F]">{{ mode === 'login' ? 'Log in to LabexAgent' : 'Create your LabexAgent account' }}</h1>
        <p class="mt-3 text-[15px] text-[#6B6B6B]">{{ mode === 'login' ? 'Continue to your workspace.' : 'Start with a workspace for your projects and Agent sessions.' }}</p>
      </header>

      <LoginForm :mode="mode" :loading="loading" @submit="submit" @validation-error="showValidationError" />

      <p class="mt-8 text-center text-sm text-[#6B6B6B] sm:hidden">
        <template v-if="mode === 'login'">Don't have an account? <button class="border-0 bg-transparent p-0 font-medium text-[#1D1D1F] hover:underline focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-[#1D1D1F]" type="button" @click="toggleMode">Create account</button></template>
        <template v-else>Already have an account? <button class="border-0 bg-transparent p-0 font-medium text-[#1D1D1F] hover:underline focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-[#1D1D1F]" type="button" @click="toggleMode">Log in</button></template>
      </p>

      <p v-if="mode === 'register'" class="mt-6 text-center text-xs leading-5 text-[#8A8A8A]">By creating an account, you agree to the Terms of Service and Privacy Policy.</p>
    </section>

    <footer class="auth-footer absolute inset-x-0 bottom-7 flex justify-center gap-5 text-xs text-[#8A8A8A]"><a href="#" @click.prevent>Terms of Service</a><a href="#" @click.prevent>Privacy Policy</a></footer>
  </main>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import Logo from '@/components/Logo.vue'
import LoginForm from '@/components/LoginForm.vue'
import { useUserStore } from '@/stores/user'

type AuthMode = 'login' | 'register'
interface Credentials { username: string; displayName: string; password: string }
const router = useRouter(); const userStore = useUserStore(); const mode = ref<AuthMode>('login'); const loading = ref(false)
function toggleMode() { mode.value = mode.value === 'login' ? 'register' : 'login' }
function showValidationError() { ElMessage.warning('Please enter your username and password.') }
async function submit(credentials: Credentials) { loading.value = true; try { if (mode.value === 'register') { await userStore.register(credentials); ElMessage.success('Account created successfully.') } else { await userStore.login(credentials); ElMessage.success('Signed in successfully.') }; router.replace('/projects') } catch (error) { ElMessage.error(error instanceof Error ? error.message : 'Authentication failed.') } finally { loading.value = false } }
</script>

<style>
.auth-page ~ .theme-settings-launcher { display: none; }
</style>
