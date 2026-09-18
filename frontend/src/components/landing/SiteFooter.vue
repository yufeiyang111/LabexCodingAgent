<template>
  <footer class="footer">
    <div class="wrap footer__inner">
      <div class="footer__grid">
        <div class="footer__brand">
          <span class="footer__logo">
            <BrandMark />
            <b>LabexAgent</b>
          </span>
          <p class="footer__tagline">{{ FOOTER.tagline }}</p>
        </div>

        <nav v-for="column in FOOTER.columns" :key="column.label" class="footer__col" :aria-label="column.label">
          <h4>{{ column.label }}</h4>
          <!--
            三类目标分别处理：
            站内路由（以 / 开头）走 RouterLink 做前端跳转；页内锚点（#xxx）用原生 a；
            没有任何目标的条目渲染成纯文本 —— 不生成 href="#" 的空链接，
            因为「看起来能点却点不动」比不显示链接更容易让人以为站点坏了。
          -->
          <template v-for="link in column.links" :key="link.label">
            <RouterLink v-if="link.to && link.to.startsWith('/')" :to="link.to">{{ link.label }}</RouterLink>
            <a v-else-if="link.to" :href="link.to">{{ link.label }}</a>
            <span v-else class="footer__plain">{{ link.label }}</span>
          </template>
        </nav>
      </div>

      <div class="footer__bottom">
        <span class="footer__copy">{{ FOOTER.copy }}</span>
        <span class="footer__links">
          <template v-for="(link, index) in FOOTER.bottomLinks" :key="link.label">
            <span v-if="index > 0" class="footer__dot" aria-hidden="true">·</span>
            <a v-if="link.top" href="#top" @click.prevent="scrollToTop">{{ link.label }}</a>
            <RouterLink v-else-if="link.to && link.to.startsWith('/')" :to="link.to">{{ link.label }}</RouterLink>
            <a v-else-if="link.to" :href="link.to">{{ link.label }}</a>
            <span v-else class="footer__plain">{{ link.label }}</span>
          </template>
        </span>
      </div>
    </div>
  </footer>
</template>

<script setup>
import { RouterLink } from 'vue-router'
import BrandMark from '@/components/landing/BrandMark.vue'
import { FOOTER } from '@/data/landing/landingContent.js'

/**
 * 站点页脚。
 *
 * 「回到顶部」用平滑滚动而非 #top 锚点：锚点会改地址栏 hash 并在部分浏览器里
 * 产生一次生硬跳转；同时尊重「减少动态效果」偏好。
 */
function scrollToTop() {
  const reduce = window.matchMedia?.('(prefers-reduced-motion: reduce)').matches
  window.scrollTo({ top: 0, behavior: reduce ? 'auto' : 'smooth' })
}
</script>

<style scoped>
/* 无目标条目的显示样式：与相邻链接保持同样的字色与字号，但视觉上不呈现为可点击 */
.footer__plain {
  color: var(--theme-text-secondary);
  font-size: 13px;
}
</style>
