<template>
  <figure class="shot shot--video reveal reveal--d1">
    <video
      ref="videoEl"
      class="shot__video"
      :poster="poster"
      :data-src="src"
      width="1440"
      height="900"
      autoplay
      muted
      loop
      playsinline
      preload="none"
      :aria-label="caption"
    ></video>

    <button
      type="button"
      class="shot__play"
      :aria-label="paused ? '播放演示' : '暂停演示'"
      @click="toggle"
    >
      <svg viewBox="0 0 24 24" fill="currentColor" aria-hidden="true">
        <path class="shot__play-pause" d="M8 5h3v14H8zM13 5h3v14h-3z" />
        <path class="shot__play-go" d="M8 5l11 7-11 7z" />
      </svg>
    </button>

    <figcaption class="shot__cap">
      <span class="shot__tag">演示</span>
      {{ caption }}
    </figcaption>
  </figure>
</template>

<script setup>
import { ref } from 'vue'
import { useLazyMedia } from '@/composables/landing/useLazyMedia.js'

/**
 * 懒加载演示视频。
 *
 * 性能取舍：视频在落地页里是第二屏内容，若首屏就写 src，
 * 浏览器会立刻发起请求并预取元数据，直接抢走首屏带宽。
 * 因此 src 挂在 data-src 上，由 IntersectionObserver 在接近视口时才赋值，
 * 并显式 preload='none'；海报图先顶上，避免出现空白黑框。
 */
const props = defineProps({
  src: { type: String, required: true },
  poster: { type: String, default: '' },
  caption: { type: String, default: '' }
})

const videoEl = ref(null)
const paused = ref(false)

// 海报与 src 都走懒加载策略；poster 本身是静态图，体积远小于视频
useLazyMedia(videoEl, { rootMargin: '240px 0px', poster: props.poster })

function toggle() {
  const el = videoEl.value
  if (!el) return
  if (el.paused) {
    // play() 返回 Promise；自动播放被拦截时不做补偿，保持静默以免噪声
    el.play?.().catch(() => {})
    paused.value = false
  } else {
    el.pause()
    paused.value = true
  }
}
</script>

<style scoped>
/* 暂停态切换图标：设计稿用 .shot--paused 控制，这里改用按钮自身状态，
   避免为了一个图标切换而给 video 元素挂修饰类 */
.shot__play-go { display: none; }
.shot__play[aria-label='播放演示'] .shot__play-pause { display: none; }
.shot__play[aria-label='播放演示'] .shot__play-go { display: block; }
</style>
