<template>
  <!--
    纯装饰层：aria-hidden + pointer-events:none，对读屏与点击完全透明。
    使用 position:fixed 且不做滚动联动，避免为装饰效果引入滚动监听带来的掉帧。
  -->
  <div class="aurora" aria-hidden="true">
    <div class="aurora__field">
      <span v-for="blob in BLOBS" :key="blob" :class="['aurora__blob', `aurora__blob--${blob}`]"></span>
      <span class="aurora__ribbon"></span>
    </div>
    <span class="aurora__grid"></span>
  </div>
</template>

<script setup>
/**
 * 极光背景：落地页的底层氛围。
 *
 * 之所以做成独立组件而不是写在视图里：它是 6 个实体层之外唯一的装饰层，
 * 独立后视图模板只表达「页面由哪些区块组成」，不掺入装饰细节。
 * 动画在系统「减少动态效果」下由 CSS 统一停用，这里无需分支。
 */
const BLOBS = ['a', 'b', 'c', 'd', 'e']
</script>
