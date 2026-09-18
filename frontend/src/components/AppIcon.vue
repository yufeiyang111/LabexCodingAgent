<template>
  <span class="app-icon" :class="{ compact }" :style="{ width: size + 'px', height: size + 'px' }">
    <BrandMark />
  </span>
</template>

<script setup>
import BrandMark from '@/components/landing/BrandMark.vue'

/**
 * 应用图标容器：给品牌标记加一个固定尺寸与投影的方形容器。
 *
 * 与 BrandMark 的分工：BrandMark 只画标记本身，AppIcon 负责「在某个尺寸下作为一个图标块」出现。
 *
 * 为什么不再用 `<img src="/brand/labex-mark.svg">`：
 * img 里的 SVG 是独立文档，**拿不到页面主题**。标记底板是深板岩色，
 * 暗色主题下会与背景糊在一起，而 img 无法像组件那样按主题补描边。
 * 改用组件后自动获得主题感知，且与顶栏 / 页脚 / AI 面板共用同一份几何数据。
 */
defineProps({
  size: { type: Number, default: 36 },
  compact: { type: Boolean, default: false }
})
</script>

<style scoped>
.app-icon {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
  /* 圆角比例与标记底板一致（28/104 ≈ 26.9%）：
     两者不一致时，容器会裁掉底板四角，暗色下连描边一起剪掉，轮廓又变模糊。
     改为一致后不再需要 overflow: hidden。 */
  border-radius: 27%;
  box-shadow: 0 12px 28px rgba(15, 23, 42, 0.14);
}

.app-icon.compact {
  box-shadow: 0 8px 18px rgba(15, 23, 42, 0.12);
}

.app-icon :deep(.brand-mark) {
  width: 100%;
  height: 100%;
}
</style>
