<template>
  <main class="auth-canvas auth-paper-grain min-h-[100dvh] overflow-hidden bg-auth-background px-5 py-6 font-auth-sans text-auth-ink sm:px-8 lg:px-12">
    <header class="mx-auto flex w-full max-w-[1180px] items-center justify-between py-3">
      <Logo />
      <button class="rounded-[10px_12px_9px_11px] border border-[#BDB7A9] bg-[#F6F2E9]/80 px-3 py-1.5 text-xs tracking-[0.08em] text-[#5D675B] transition-[border-color,background-color] duration-200 hover:border-[#899485] hover:bg-[#FBF8F0] focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-[#5D675B]" type="button" @click="toggleMode">{{ mode === 'login' ? '创建工作间' : '返回登录' }}</button>
    </header>

    <section class="auth-layout animate-auth-enter mx-auto grid min-h-[calc(100dvh-122px)] w-full max-w-[1180px] items-center gap-16 py-10 lg:grid-cols-[minmax(0,1fr)_460px] lg:gap-24" aria-labelledby="auth-heading">
      <aside class="auth-narrative relative hidden min-h-[520px] flex-col justify-center border-l border-[#CFC8BA] pl-10 lg:flex">
        <p class="mb-8 inline-flex w-fit rounded-[10px_13px_11px_12px] border border-[#B7B8AB] px-3 py-1.5 text-[10px] font-semibold tracking-[0.18em] text-[#5D675B]">AI 编程协作</p>
        <h1 class="max-w-[570px] font-auth-serif text-[clamp(44px,5vw,74px)] font-normal leading-[1.02] tracking-[-0.06em] text-[#34352F]">把复杂的工程，<br />交给一位安静的 Agent。</h1>
        <p class="mt-7 max-w-[420px] text-[15px] leading-7 text-[#76756D]">从项目上下文到代码改动，让每一步都有依据、记录与可验证的结果。</p>

        <ol class="mt-14 grid max-w-[420px] gap-4 border-t border-[#CFC8BA] pt-6">
          <li class="flex items-center gap-4"><span class="font-auth-serif text-[#987562]">01</span><span class="text-sm text-[#4C4E47]">理解仓库与上下文</span></li>
          <li class="flex items-center gap-4"><span class="font-auth-serif text-[#987562]">02</span><span class="text-sm text-[#4C4E47]">规划改动与工具调用</span></li>
          <li class="flex items-center gap-4"><span class="font-auth-serif text-[#987562]">03</span><span class="text-sm text-[#4C4E47]">验证结果与保留痕迹</span></li>
        </ol>

        <svg class="pointer-events-none absolute bottom-2 right-6 h-36 w-72 text-[#788273]/40" viewBox="0 0 288 144" fill="none" aria-hidden="true"><path d="M10 118c44-8 53-56 96-53 35 3 27 48 70 39 30-6 50-43 102-72" stroke="currentColor" stroke-width="1.3" stroke-linecap="round" /><path d="M106 65c-7-17-1-35 14-48" stroke="currentColor" stroke-width="1" stroke-linecap="round" /><path d="M177 103c-1-15 8-29 23-36" stroke="currentColor" stroke-width="1" stroke-linecap="round" /></svg>
      </aside>

      <article class="auth-card w-full max-w-[460px] justify-self-center rounded-[26px_22px_28px_24px] border border-[#CFC8BA] bg-[#FBF8F0]/92 p-7 sm:p-10" :class="{ 'auth-card--active': isCardActive, 'auth-card--expanded': cardExpanded }" :style="cardStyle" @pointermove="handleCardPointerMove" @pointerenter="isCardActive = true" @pointerleave="handleCardPointerLeave">
        <div class="flex items-start justify-between gap-6">
          <div><p class="text-[10px] font-medium tracking-[0.18em] text-[#987562]">智能编程工作间 · 01</p><h2 id="auth-heading" class="mt-3 font-auth-serif text-[34px] font-normal leading-none tracking-[-0.05em] text-[#34352F]">{{ mode === 'login' ? '进入你的工作间' : '创建一处工作间' }}</h2></div>
          <button class="shrink-0 rounded-[9px_11px_8px_10px] border border-[#BDB7A9] bg-transparent px-2.5 py-1.5 text-xs text-[#5D675B] transition-[border-color,color] duration-200 hover:border-[#788273] hover:text-[#454F43] focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-[#5D675B]" type="button" :aria-expanded="cardExpanded" @click.stop="toggleCardExpansion">{{ cardExpanded ? '收起' : '展开' }}</button>
        </div>
        <p class="mt-5 max-w-[360px] text-[15px] leading-7 text-[#76756D]">{{ mode === 'login' ? '让 Agent 帮你理解仓库、规划改动，并把验证结果留在清楚的工作流里。' : '从项目、模型配置到 Agent 会话，逐步建立属于你的开发工作流。' }}</p>
        <Transition name="auth-note"><div v-if="cardExpanded" class="mt-6 border-l border-[#AEB6A9] pl-4 text-[13px] leading-6 text-[#68685F]">Agent 会先理解代码上下文，再按计划调用工具、生成改动并完成验证。你始终可以查看过程，决定下一步。</div></Transition>
        <div class="mt-8"><LoginForm :mode="mode" :loading="loading" @submit="submit" @validation-error="showValidationError" /></div>
        <p class="mt-8 text-center text-sm leading-6 text-[#76756D]"><template v-if="mode === 'login'">还没有工作间？ <button class="border-0 bg-transparent p-0 font-medium text-[#4E584D] hover:underline focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-[#5D675B]" type="button" @click="toggleMode">创建一个</button></template><template v-else>已经有工作间？ <button class="border-0 bg-transparent p-0 font-medium text-[#4E584D] hover:underline focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-[#5D675B]" type="button" @click="toggleMode">返回登录</button></template></p>
        <p v-if="mode === 'register'" class="mt-5 border-t border-[#DED8CC] pt-5 text-center text-xs leading-5 text-[#8B887E]">创建即表示你同意使用说明与隐私说明。</p>
      </article>
    </section>

    <footer class="mx-auto flex w-full max-w-[1180px] items-center justify-between border-t border-[#D9D3C7] py-4 text-xs text-[#8B887E]"><span>代码、上下文与改动，安静地流动。</span><span class="flex gap-4"><a href="#" @click.prevent>使用说明</a><a href="#" @click.prevent>隐私说明</a></span></footer>
  </main>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import Logo from '@/components/Logo.vue'
import LoginForm from '@/components/LoginForm.vue'
import { useUserStore } from '@/stores/user'

type AuthMode = 'login' | 'register'
interface Credentials { username: string; displayName: string; password: string }
const router = useRouter(); const userStore = useUserStore(); const mode = ref<AuthMode>('login'); const loading = ref(false); const cardExpanded = ref(false); const isCardActive = ref(false); const pointer = ref({ x: 50, y: 50 })
const cardStyle = computed(() => ({ '--card-shift-x': `${(pointer.value.x - 50) * .045}px`, '--card-shift-y': `${(pointer.value.y - 50) * .045}px`, '--ink-x': `${pointer.value.x}%`, '--ink-y': `${pointer.value.y}%` }))
function toggleMode() { mode.value = mode.value === 'login' ? 'register' : 'login'; cardExpanded.value = false }
function toggleCardExpansion() { cardExpanded.value = !cardExpanded.value }
function handleCardPointerMove(event: PointerEvent) { const rect = (event.currentTarget as HTMLElement).getBoundingClientRect(); pointer.value = { x: ((event.clientX - rect.left) / rect.width) * 100, y: ((event.clientY - rect.top) / rect.height) * 100 } }
function handleCardPointerLeave() { isCardActive.value = false; pointer.value = { x: 50, y: 50 } }
function showValidationError() { ElMessage.warning('请输入用户名和密码。') }
async function submit(credentials: Credentials) { loading.value = true; try { if (mode.value === 'register') { await userStore.register(credentials); ElMessage.success('工作间创建成功。') } else { await userStore.login(credentials); ElMessage.success('欢迎回来。') }; router.replace('/projects') } catch (error) { ElMessage.error(error instanceof Error ? error.message : '操作失败。') } finally { loading.value = false } }
</script>

<style>
.auth-canvas ~ .theme-settings-launcher { display: none; }
</style>
