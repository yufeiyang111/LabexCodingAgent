<template>
  <section id="top" class="hero wrap">
    <p class="hero__kicker">{{ HERO.kicker }}</p>

    <!--
      首字逐字符拆分：设计稿只拆第一个词（Labex），每个字符挂 hero__char--interactive，
      于是能各自响应 hover 变色与上浮；pointerenter 触发整词的 color-flash 闪烁。
      第二个词（Agent）保持整词渲染，避免无谓的节点膨胀。
    -->
    <h1 ref="titleEl" class="hero__title" @pointerenter="flashChars" @click="flashChars">
      <span
        v-for="(word, wordIndex) in titleWords"
        :key="word.text"
        class="hero__word"
        :style="{ '--word-delay': word.delay }"
      >
        <template v-if="word.chars">
          <span
            v-for="(char, charIndex) in word.chars"
            :key="charIndex"
            class="hero__char hero__char--interactive"
            :class="{ 'hero__char--replay': flashing, 'is-flashing': flashing }"
            :style="{ '--char-delay': charDelay(charIndex) }"
          >{{ char }}</span>
        </template>
        <template v-else>{{ word.text }}</template>
      </span>
    </h1>

    <p class="hero__sub">{{ HERO.sub }}</p>

    <!--
      CTA 按登录态分两套：
        未登录 → 引导注册/登录；
        已登录 → 继续展示登录按钮毫无意义（点开只会看到「已登录」），
                 改为把用户送进工作台，并提供继续阅读教程的次级入口。
      首屏是用户最可能停留的位置，留一个已经失效的按钮会让人以为登录没生效。
    -->
    <div class="hero__cta">
      <template v-if="userStore.isLoggedIn">
        <button type="button" class="btn" @click="goWorkspace">进入项目空间</button>
        <button type="button" class="btn btn--ghost" @click="goTutorials">查看开发教程</button>
      </template>
      <template v-else>
        <button type="button" class="btn" @click="openAuth('login')">登录</button>
        <!--
          注册入口互斥：开启邀请码时只给「使用邀请码」，否则只给「创建账号」。
          两者都走 mode=register —— 字段差异由 LoginForm 依据 inviteCodeEnabled 决定
          （邀请码模式下只要求邀请码 + 用户名 + 密码）。
        -->
        <button
          v-if="inviteCodeEnabled"
          type="button"
          class="btn btn--ghost"
          @click="openAuth('register')"
        >使用邀请码</button>
        <button
          v-else
          type="button"
          class="btn btn--ghost"
          @click="openAuth('register')"
        >创建账号</button>
      </template>
    </div>

    <!-- is-open 让重播按钮显形（设计稿由脚本在初始化时添加） -->
    <div id="hero-media" class="hero__media is-open">
      <span class="media-halo" aria-hidden="true"></span>

      <div class="stage">
        <!--
          工作台演示体积较大（13 个组件），用异步组件 + Suspense 拆成独立 chunk：
          落地页的标题与按钮先渲染，重活随后补齐。
          fallback 复用 .demo--ide / .ide 的尺寸，避免加载完成时页面高度跳动。
        -->
        <Suspense>
          <WorkbenchShowcase ref="workbenchRef" />
          <template #fallback>
            <div class="demo demo--ide">
              <div class="ide" aria-hidden="true"></div>
            </div>
          </template>
        </Suspense>
      </div>

      <!-- 重播按钮位于 .stage 之外：与设计稿一致，避免被工作台的层叠上下文裁剪 -->
      <button type="button" class="playback" aria-label="重播动画" @click="replayWorkbench">
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.9" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
          <path d="M21 12a9 9 0 1 1-3-6.7" />
          <path d="M3 4v5h5" />
        </svg>
        <span>重播</span>
      </button>
    </div>
  </section>
</template>

<script setup>
import { computed, defineAsyncComponent, nextTick, onBeforeUnmount, onMounted, ref } from 'vue'
import { storeToRefs } from 'pinia'
import { useRouter } from 'vue-router'
import { useLandingAuth } from '@/composables/landing/useLandingAuth.js'
import { HERO } from '@/data/landing/landingContent.js'
import { useAuthCapabilitiesStore } from '@/stores/authCapabilities'
import { useUserStore } from '@/stores/user'

const WorkbenchShowcase = defineAsyncComponent(() => import('@/components/landing/workbench/WorkbenchShowcase.vue'))

const router = useRouter()
const { openAuth } = useLandingAuth()
const userStore = useUserStore()

/* 认证能力（是否需要邀请码）决定未登录时 CTA 显示哪几个按钮 */
const authCapabilities = useAuthCapabilitiesStore()
const { inviteCodeEnabled } = storeToRefs(authCapabilities)
onMounted(() => { authCapabilities.ensureLoaded() })

function goWorkspace() {
  router.push({ name: 'Projects' })
}

function goTutorials() {
  router.push({ name: 'Tutorials' })
}

/* 仅第一个词拆成字符；其余保持整词，避免为动画制造无谓节点 */
const titleWords = computed(() =>
  HERO.titleWords.map((word, index) => ({
    ...word,
    chars: index === 0 ? Array.from(word.text) : null
  }))
)

/** 与逐词揭示对齐：同一词内每个字额外 +24ms（沿用设计稿节奏） */
function charDelay(index) {
  return `${(0.06 + index * 0.024).toFixed(3)}s`
}

/* ── 触碰时整词闪烁（color-flash）── */
const POSITIVE = '(prefers-reduced-motion: reduce)'
const titleEl = ref(null)
const flashing = ref(false)
let flashTimer = null

async function flashChars() {
  if (window.matchMedia?.(POSITIVE).matches) return
  // 先移除再添加：同一 class 连续存在时动画不会重播，中间必须让浏览器重新计算一次样式
  flashing.value = false
  await nextTick()
  if (titleEl.value) void titleEl.value.offsetWidth
  flashing.value = true
  clearTimeout(flashTimer)
  flashTimer = setTimeout(() => { flashing.value = false }, 260)
}

onBeforeUnmount(() => clearTimeout(flashTimer))

/* ── 重播工作台演示 ── */
// 异步组件的 ref 在解析完成前为 null，调用处做空值保护
const workbenchRef = ref(null)

function replayWorkbench() {
  workbenchRef.value?.replay?.()
}
</script>
