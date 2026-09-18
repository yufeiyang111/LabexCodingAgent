<template>
  <!-- 用量面板：本轮 token 消耗 + 上下文占用进度条 + 会话累计 -->
  <div class="ide__panels">
    <div class="ide__pcard">
      <div class="ide__ptitle"><span>本轮消耗</span><span>{{ label }}</span></div>
      <div class="ide__prow"><span>输入</span><b>{{ usage.input }}</b></div>
      <div class="ide__prow"><span>输出</span><b>{{ usage.output }}</b></div>
      <div class="ide__prow"><span>合计</span><b>{{ usage.total }}</b></div>
      <div class="ide__prow ide__prow--sub"><span>缓存命中</span><b>{{ usage.cache }}</b></div>
    </div>

    <div class="ide__pcard">
      <div class="ide__ptitle"><span>上下文占用</span><span>{{ ctxPct }}%</span></div>
      <!-- 进度条宽度在进入视口时从 0 过渡到目标值，动画由 CSS transition 驱动 -->
      <div class="ide__pbar"><i :style="{ width: ctxPct + '%' }"></i></div>
      <div class="ide__prow ide__prow--sub" style="margin-top: 9px">
        <span>会话累计</span><b>{{ usage.session }}</b>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'

// 用量数字属于演示文案：落地页不接真实计费，这里给出与场景规模匹配的示例值
defineProps({
  label: { type: String, default: 'build · config.py' },
})

const usage = {
  input: '1.2K',
  output: '0.8K',
  total: '2.0K',
  cache: '64%',
  session: '12.4K · $0.18',
}

// 初始为 0，挂载后再赋值以触发 CSS 的填充过渡动画
const ctxPct = ref(0)
onMounted(() => { ctxPct.value = 38 })
</script>
