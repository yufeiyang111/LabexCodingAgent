<template>
  <!-- 审查面板：列出本轮改动的若干文件，含 +/− 统计与状态徽章 -->
  <div class="ide__review">
    <button
      v-for="row in diffs"
      :key="row.name"
      type="button"
      class="ide__diffrow"
      :title="`查看 ${row.name} 的改动`"
    >
      <svg class="ide__difficon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.6">
        <path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z" />
        <path d="M14 2v6h6" />
      </svg>
      <span class="ide__diffname">{{ row.name }}</span>
      <span class="ide__diffstat">
        <span class="add">+{{ row.add }}</span> <span class="del">-{{ row.del }}</span>
      </span>
      <span class="ide__diffbadge">{{ row.badge }}</span>
    </button>

    <div class="ide__pact">
      <button type="button" class="ide__pbtn ide__pbtn--primary">全部接受</button>
      <button type="button" class="ide__pbtn">全部回退</button>
    </div>
  </div>
</template>

<script setup>
// 改动清单为演示文案：与「给 config.py 加 APP_VERSION」「加 /health 接口」等场景对应
const diffs = [
  { name: 'config.py', add: 2, del: 0, badge: '已应用' },
  { name: 'app.py', add: 3, del: 0, badge: '已应用' },
  { name: 'models.py', add: 1, del: 1, badge: '待确认' },
]
</script>

<style scoped>
/* 仅在组件级补充图标尺寸，复用设计稿已提取的 .ide__diffrow 等布局类 */
.ide__difficon {
  flex: 0 0 auto;
  width: 14px;
  height: 14px;
  color: #6b7280;
}
</style>
