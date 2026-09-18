<template>
  <section :id="SECTIONS.changes.id" class="section wrap">
    <div class="seclabel reveal">
      <h2 class="seclabel__title">{{ SECTIONS.changes.title }}</h2>
      <p class="seclabel__intro">{{ SECTIONS.changes.intro }}</p>
    </div>

    <figure class="diffcard reveal reveal--d1">
      <div class="diffcard__tabs">
        <button
          v-for="(pane, index) in DIFF_PANES"
          :key="pane.file"
          type="button"
          class="diffcard__tab"
          :class="{ 'is-active': index === activeIndex }"
          @click="activeIndex = index"
        >{{ pane.file }}</button>
      </div>

      <div
        v-for="(pane, index) in DIFF_PANES"
        :key="pane.path"
        class="diffcard__pane"
        :class="{ 'is-active': index === activeIndex }"
      >
        <div class="diffcard__head">
          <span class="diffcard__path">{{ pane.path }}</span>
          <span class="diffcard__stat"><b>+{{ pane.add }}</b><s>−{{ pane.del }}</s></span>
        </div>
        <!--
          逐行渲染而非整块 innerHTML：行内容来自结构化数据，
          既避免了 v-html，也让「增 / 删 / 上下文」三种行样式可由 class 直接表达。
        -->
        <pre class="diffcard__code"><span
          v-for="(line, lineIndex) in pane.lines"
          :key="lineIndex"
          class="dl"
          :class="line.k"
        ><i>{{ MARK[line.k] ?? ' ' }}</i>{{ line.t }}</span></pre>
      </div>

      <figcaption class="diffcard__foot">
        共 <b>+{{ DIFF_TOTAL.add }}</b> <s>−{{ DIFF_TOTAL.del }}</s>
      </figcaption>
    </figure>
  </section>
</template>

<script setup>
import { ref } from 'vue'
import { DIFF_PANES, DIFF_TOTAL, SECTIONS } from '@/data/landing/landingContent.js'

/**
 * 改动审查区块：一个三标签的 diff 查看器。
 *
 * 状态只有「当前标签索引」一项，因此就地持有——
 * 抽 store 或 composable 会让这份局部 UI 状态被动共享。
 */
const activeIndex = ref(0)

/** 行首标记栏；未匹配到的行（上下文）留一个空格，保持行号栏宽度一致 */
const MARK = { 'is-add': '+', 'is-del': '−' }
</script>
