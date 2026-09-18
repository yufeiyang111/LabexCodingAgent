<template>
  <section :id="SECTIONS.runtime.id" class="runtime">
    <div class="wrap">
      <div class="seclabel seclabel--chapter reveal">
        <h2 class="seclabel__title">{{ SECTIONS.runtime.title }}</h2>
        <p class="seclabel__intro">{{ SECTIONS.runtime.intro }}</p>
      </div>

      <LazyVideo
        :src="RUNTIME_MEDIA.video.src"
        :poster="RUNTIME_MEDIA.video.poster"
        :caption="RUNTIME_MEDIA.video.caption"
      />

      <div class="shot-grid">
        <LazyShot
          v-for="(shot, index) in RUNTIME_MEDIA.shots"
          :key="shot.src"
          :src="shot.src"
          :fallback="shot.fallback"
          :alt="shot.alt"
          :tag="shot.tag"
          :caption="shot.caption"
          :delay="index === 0 ? 'reveal--d2' : 'reveal--d3'"
        />
      </div>
    </div>
  </section>
</template>

<script setup>
import LazyShot from '@/components/landing/LazyShot.vue'
import LazyVideo from '@/components/landing/LazyVideo.vue'
import { RUNTIME_MEDIA, SECTIONS } from '@/data/landing/landingContent.js'

/**
 * 真实运行记录区块。
 *
 * 该区块本身不含状态，只负责把媒体数据分发给两个懒加载子组件；
 * 懒加载与播放控制各自封闭在子组件内，主区块保持可读。
 */
</script>
