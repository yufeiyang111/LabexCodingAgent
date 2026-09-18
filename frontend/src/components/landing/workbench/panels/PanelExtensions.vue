<template>
  <!-- 扩展面板：一组可独立开关的能力，开关状态为本地演示状态 -->
  <div class="ide__exts">
    <div v-for="ext in exts" :key="ext.name" class="ide__extrow">
      <span class="ide__exticon">
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7">
          <path v-for="(d, i) in ext.icon" :key="i" :d="d" />
        </svg>
      </span>
      <span class="ide__extbody">
        <span class="ide__extname">{{ ext.name }}</span>
        <span class="ide__extdesc">{{ ext.desc }}</span>
      </span>
      <button
        type="button"
        class="ide__sw"
        :class="{ 'is-on': ext.on }"
        role="switch"
        :aria-checked="ext.on"
        :title="ext.on ? '已开启' : '已关闭'"
        @click="ext.on = !ext.on"
      ></button>
    </div>
  </div>
</template>

<script setup>
import { reactive } from 'vue'

// 能力清单为演示文案；开关仅切换本地视觉状态，不触发任何真实能力
const exts = reactive([
  { name: '自动执行命令', desc: '允许 Agent 运行终端命令', on: true, icon: ['M4 17l6-6-6-6', 'M12 19h8'] },
  { name: '文件读写', desc: '读写项目内任意文件', on: true, icon: ['M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z', 'M14 2v6h6'] },
  { name: '网络检索', desc: '联网获取最新文档', on: false, icon: ['M12 3a9 9 0 1 0 9 9', 'M3 12h18', 'M12 3a15 15 0 0 1 0 18', 'M12 3a15 15 0 0 0 0 18'] },
  { name: 'Mermaid 渲染', desc: '将回复中的图表渲染为图像', on: true, icon: ['M3 3h18v18H3z', 'M8.5 8.5h.01', 'M21 15l-5-5L5 21'] },
  { name: '谨慎模式', desc: '危险操作前请求确认', on: false, icon: ['M12 3l7 3v5c0 4.5-3 8-7 10-4-2-7-5.5-7-10V6z'] },
])
</script>
