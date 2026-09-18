<template>
  <figure class="shot" :class="delayClass">
    <!--
      webp 优先、png 回退：source 的 srcset 挂在 data-srcset 上由 composable 在进入视口时赋值，
      顺序上先写 source 再写 img.src，否则浏览器会退回回退格式。
    -->
    <picture>
      <source type="image/webp" :data-srcset="src" />
      <img
        ref="imageEl"
        class="shot__img"
        :class="{ 'is-loaded': isLoaded }"
        :data-src="fallback || src"
        :alt="alt"
        width="1600"
        height="1000"
        decoding="async"
      />
    </picture>
    <figcaption class="shot__cap">
      <span class="shot__tag">{{ tag }}</span>
      {{ caption }}
    </figcaption>
  </figure>
</template>

<script setup>
import { computed, ref } from 'vue'
import { useLazyImage } from '@/composables/landing/useLazyMedia.js'

/**
 * 单张产品截图（延迟揭示）。
 *
 * 独立成组件的理由有两个：
 * 1. 每张图需要各自独立的观测目标，循环里的 ref 无法简单共享；
 * 2. 宽高属性已显式写在标签上，浏览器可在图片到达前就占好位，避免布局偏移（CLS）。
 */
const props = defineProps({
  /** 首选源（webp） */
  src: { type: String, required: true },
  /** 回退源（png / jpg），留空则只用首选源 */
  fallback: { type: String, default: '' },
  alt: { type: String, default: '' },
  tag: { type: String, default: '' },
  caption: { type: String, default: '' },
  /** 揭示延迟档位（reveal--d2 / reveal--d3），由父级决定错峰顺序 */
  delay: { type: String, default: '' }
})

const imageEl = ref(null)
const { isLoaded } = useLazyImage(imageEl)

const delayClass = computed(() => (props.delay ? `reveal ${props.delay}` : 'reveal'))
</script>

<style scoped>
/* display:contents 让 picture 不产生盒子，避免 img 的 width:100% 相对 picture 自身形成循环 */
picture { display: contents; }
/* 图片解码完成前轻微降低不透明度，避免逐行扫描的观感 */
.shot__img { opacity: 0; transition: opacity 0.4s ease; }
.shot__img.is-loaded { opacity: 1; }
@media (prefers-reduced-motion: reduce) {
  .shot__img { transition: none; }
}
</style>
