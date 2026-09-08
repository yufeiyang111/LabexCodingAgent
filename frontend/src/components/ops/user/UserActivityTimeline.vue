<template>
  <div class="uat">
    <div class="uat-header">
      <div class="uat-title">行为轨迹与事件流</div>
      <div class="uat-filters">
        <div class="uat-categories">
          <button
            v-for="c in categories"
            :key="c.value"
            class="uat-cat-btn"
            :class="{ active: category === c.value }"
            @click="$emit('category-change', c.value)"
          >
            {{ c.label }}
          </button>
        </div>
        <label class="uat-checkbox">
          <input
            type="checkbox"
            :checked="onlyErrors"
            @change="$emit('toggle-errors', $event.target.checked)"
          />
          <span>仅看异常</span>
        </label>
        <button class="uat-refresh-btn" @click="$emit('refresh')">刷新</button>
      </div>
    </div>

    <OpsStateBlock :loading="loading" :error="error" :empty="events.length === 0" empty-text="暂无行为轨迹记录">
      <div class="uat-timeline">
        <div v-for="evt in events" :key="evt.eventId" class="uat-item" :class="evt.status?.toLowerCase()">
          <div class="uat-item-dot" :class="evt.status?.toLowerCase()"></div>
          <div class="uat-item-content">
            <div class="uat-item-top">
              <span class="uat-item-time">{{ formatTime(evt.timestamp) }}</span>
              <OpsStatusTag :text="evt.category" :type="categoryTagType(evt.category)" />
              <span class="uat-item-action">{{ evt.action }}</span>
            </div>
            <div class="uat-item-title">{{ evt.title }}</div>
            <div class="uat-item-details">{{ evt.details }}</div>
            <div v-if="hasMetadata(evt.metadata)" class="uat-item-meta">
              <span v-for="(v, k) in evt.metadata" :key="k" class="uat-meta-pill">
                {{ k }}: <strong>{{ v }}</strong>
              </span>
            </div>
          </div>
        </div>
      </div>

      <OpsPagination
        :page="page"
        :total="total"
        :page-size="pageSize"
        @change="$emit('page-change', $event)"
      />
    </OpsStateBlock>
  </div>
</template>

<script setup>
import { USER_ACTIVITY_CATEGORIES } from '@/constants/ops'
import OpsStateBlock from '@/components/ops/ui/OpsStateBlock.vue'
import OpsPagination from '@/components/ops/ui/OpsPagination.vue'
import OpsStatusTag from '@/components/ops/ui/OpsStatusTag.vue'

defineProps({
  events: { type: Array, default: () => [] },
  loading: { type: Boolean, default: false },
  error: { type: String, default: '' },
  category: { type: String, default: 'ALL' },
  onlyErrors: { type: Boolean, default: false },
  page: { type: Number, default: 1 },
  total: { type: Number, default: 0 },
  pageSize: { type: Number, default: 20 }
})

defineEmits(['category-change', 'toggle-errors', 'page-change', 'refresh'])

const categories = USER_ACTIVITY_CATEGORIES

function formatTime(val) {
  return val ? String(val).replace('T', ' ').slice(0, 19) : '-'
}

function categoryTagType(cat) {
  switch (cat) {
    case 'HTTP': return 'info'
    case 'TASK': return 'warning'
    case 'SECURITY': return 'danger'
    default: return 'info'
  }
}

function hasMetadata(meta) {
  return meta && Object.keys(meta).length > 0
}
</script>

<style scoped>
.uat { background: #fff; border: 1px solid #eef0f3; border-radius: 12px; padding: 14px 16px; }
.uat-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  flex-wrap: wrap;
  gap: 10px;
  margin-bottom: 12px;
}
.uat-title { font-size: 13px; font-weight: 600; color: #111827; }
.uat-filters { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; }
.uat-categories { display: flex; gap: 4px; }
.uat-cat-btn {
  border: 1px solid #e5e7eb;
  background: #fff;
  padding: 3px 8px;
  border-radius: 6px;
  font-size: 11px;
  color: #4b5563;
  cursor: pointer;
}
.uat-cat-btn.active { background: #4f46e5; color: #fff; border-color: #4f46e5; }
.uat-checkbox { display: flex; align-items: center; gap: 4px; font-size: 11px; color: #4b5563; cursor: pointer; }
.uat-refresh-btn {
  border: 1px solid #e5e7eb;
  background: #fff;
  padding: 3px 8px;
  border-radius: 6px;
  font-size: 11px;
  color: #374151;
  cursor: pointer;
}
.uat-refresh-btn:hover { background: #f9fafb; }

.uat-timeline { position: relative; padding-left: 18px; margin-top: 8px; }
.uat-timeline::before {
  content: '';
  position: absolute;
  top: 8px;
  bottom: 8px;
  left: 5px;
  width: 2px;
  background: #f1f5f9;
}
.uat-item { position: relative; margin-bottom: 16px; font-size: 12px; }
.uat-item-dot {
  position: absolute;
  left: -18px;
  top: 4px;
  width: 10px;
  height: 10px;
  border-radius: 50%;
  background: #cbd5e1;
  border: 2px solid #fff;
  box-shadow: 0 0 0 1px #e2e8f0;
}
.uat-item-dot.success { background: #10b981; }
.uat-item-dot.warning { background: #f59e0b; }
.uat-item-dot.danger { background: #ef4444; }

.uat-item-content { background: #f9fafb; border-radius: 8px; padding: 8px 12px; }
.uat-item-top { display: flex; align-items: center; gap: 8px; margin-bottom: 4px; }
.uat-item-time { font-size: 11px; color: #9ca3af; font-variant-numeric: tabular-nums; }
.uat-item-action { font-size: 11px; color: #64748b; font-family: monospace; }
.uat-item-title { font-size: 12px; font-weight: 500; color: #1e293b; margin-bottom: 2px; }
.uat-item-details { font-size: 11px; color: #64748b; }
.uat-item-meta { display: flex; gap: 6px; flex-wrap: wrap; margin-top: 6px; }
.uat-meta-pill { background: #fff; border: 1px solid #e2e8f0; border-radius: 4px; padding: 2px 6px; font-size: 10px; color: #64748b; }
</style>
