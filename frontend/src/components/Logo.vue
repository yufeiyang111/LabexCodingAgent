<template>
  <div class="logo" aria-label="LabexAgent 智能代码工作间">
    <BrandMark />
    <div class="logo__text">
      <span class="logo__name">{{ productName }}</span>
      <span class="logo__sub">智能代码工作间</span>
    </div>
  </div>
</template>

<script setup lang="ts">
import BrandMark from '@/components/landing/BrandMark.vue'

/**
 * 品牌标识（登录页与弹窗共用）。
 *
 * 复用统一的 BrandMark 而非内联自绘图标：品牌标记只应有一处定义，
 * 否则登录页、落地页、站点图标各画一套，改品牌时必然漏改。
 */

withDefaults(defineProps<{ productName?: string }>(), { productName: 'LabexAgent' })
</script>

<style scoped>
.logo {
  display: flex;
  align-items: center;
  gap: 12px;
}

/* 26px：与落地页顶栏、页脚的品牌标记尺寸一致 */
.logo :deep(svg) {
  width: 26px;
  height: 26px;
  flex: 0 0 auto;
}

.logo__text {
  display: grid;
  gap: 2px;
}

.logo__name {
  color: var(--theme-text);
  font-size: 19px;
  font-weight: 600;
  letter-spacing: -0.03em;
}

.logo__sub {
  color: var(--theme-text-secondary);
  font-size: 10px;
  letter-spacing: 0.16em;
}
</style>
