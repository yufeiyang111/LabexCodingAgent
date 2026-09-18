<template>
  <svg class="brand-mark" :viewBox="BRAND_MARK_VIEWBOX" fill="none" aria-hidden="true">
    <!--
      底板单独成元素（而非并入图元循环），因为它是唯一需要随主题微调的部件：
      底板为深板岩色，在暗色背景上几乎融为一体，需加一道浅描边勾出轮廓。
    -->
    <rect
      class="brand-mark__plate"
      x="12"
      y="12"
      width="104"
      height="104"
      rx="28"
      :fill="BRAND_MARK_COLORS.plate"
    />
    <template v-for="(shape, index) in BRAND_MARK_SHAPES" :key="index">
      <circle
        v-if="shape.type === 'circle'"
        :cx="shape.cx"
        :cy="shape.cy"
        :r="shape.r"
        :fill="colorOf(shape.fill)"
      />
      <path
        v-else-if="shape.type === 'path'"
        :d="shape.d"
        :stroke="colorOf(shape.stroke)"
        :stroke-width="shape.strokeWidth"
        :stroke-linecap="shape.linecap"
        :stroke-linejoin="shape.linejoin"
      />
    </template>
  </svg>
</template>

<script setup>
import { BRAND_MARK_COLORS, BRAND_MARK_SHAPES, BRAND_MARK_VIEWBOX } from '@/assets/brand/labexMark.js'

/**
 * 品牌标记：烧瓶 + 网络节点（语义：实验环境里的 Agent 协作网络）。
 *
 * 几何与配色来自 `assets/brand/labexMark.js` 单一数据源；
 * 站点图标（favicon / PWA / 分享图）由脚本从同一份数据生成，改一处即可全线统一。
 *
 * 使用紧裁 viewBox（去掉原图四周 12 单位内边距）：实测同样尺寸下主体更大，
 * 21–26px 的小尺寸下保留更多可辨识细节。
 *
 * 不再需要 variant prop：旧实现用它区分 SVG 渐变 id（避免 id 冲突导致图标变透明块），
 * 本标记不使用渐变，该约束已消失。
 */
function colorOf(key) {
  return BRAND_MARK_COLORS[key] || key
}
</script>

<style scoped>
.brand-mark {
  display: block;
  flex: 0 0 auto;
}

/*
 * 底板描边：亮色下透明（白底上深色方块本身足够清楚），
 * 暗色下提为浅色细线 —— 底板 #0F172A 与暗色背景 #16181f 色差极小，
 * 不加描边时圆角方块会糊进背景，只剩瓶身线条，品牌轮廓丢失。
 * stroke-width 6 在 104 单位画布下，渲染到 26px 约等于 1.5px。
 */
.brand-mark__plate {
  stroke: transparent;
  stroke-width: 6;
}

html[data-theme='dark'] .brand-mark__plate {
  stroke: oklch(1 0 0 / 0.18);
}
</style>
