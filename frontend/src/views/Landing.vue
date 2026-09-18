<template>
  <div ref="rootEl" class="landing-scope">
    <AuroraBackdrop />
    <SiteHeader />

    <main>
      <LandingHero />
      <LandingDiffSection />
      <LandingRuntimeSection />
    </main>

    <SiteFooter />
  </div>
</template>

<script setup>
import { ref } from 'vue'
import AuroraBackdrop from '@/components/landing/AuroraBackdrop.vue'
import LandingDiffSection from '@/components/landing/LandingDiffSection.vue'
import LandingHero from '@/components/landing/LandingHero.vue'
import LandingRuntimeSection from '@/components/landing/LandingRuntimeSection.vue'
import SiteFooter from '@/components/landing/SiteFooter.vue'
import SiteHeader from '@/components/landing/SiteHeader.vue'
import { useScrollReveal } from '@/composables/landing/useScrollReveal.js'
// 落地页样式：全部规则已限定在 .landing-scope 内，不会影响工作台等既有页面
import '@/styles/landing/landing.css'

/**
 * 站点首页（公开落地页）。
 *
 * 结构上是一个薄壳：只负责把装饰层、顶栏、三个区块与页脚组装起来，
 * 并在此处统一做两件跨区块的事——样式作用域引入与滚动揭示。
 *
 * 之所以把 `landing-scope` 放在这里而不是各区块：它是样式作用域的唯一入口，
 * 分散到子组件会让「哪些样式只在落地页生效」变得难以追踪。
 */
const rootEl = ref(null)

// 揭示目标分散在三个区块内；这里以根节点统一观察，避免每个区块各建一个 observer
useScrollReveal(rootEl)
</script>
