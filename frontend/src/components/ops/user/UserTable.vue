<template>
  <div class="utb">
    <!-- 筛选工具栏 -->
    <div class="utb-toolbar">
      <input
        v-model="filters.keyword"
        class="utb-input"
        placeholder="搜索 ID / 用户名 / 邮箱"
        @keyup.enter="onFilter"
      />
      <select v-model="filters.role" class="utb-select" @change="onFilter">
        <option value="">全部角色</option>
        <option value="STUDENT">学生 (STUDENT)</option>
        <option value="ADMIN">管理员 (ADMIN)</option>
      </select>
      <select v-model="filters.status" class="utb-select" @change="onFilter">
        <option value="">全部状态</option>
        <option :value="1">正常</option>
        <option :value="0">已冻结</option>
      </select>
      <select v-model="filters.sortBy" class="utb-select" @change="onFilter">
        <option value="lastActiveTime">按最后活跃排序</option>
        <option value="totalTokens">按 Token 消耗排序</option>
        <option value="taskCount">按任务量排序</option>
      </select>
      <label class="utb-online-toggle">
        <input
          type="checkbox"
          v-model="filters.isOnline"
          @change="onFilter"
        />
        <span>仅看在线</span>
      </label>
      <button class="utb-btn" @click="onFilter">查询</button>
      <button class="utb-btn" @click="onReset">重置</button>
      <button class="utb-btn utb-btn-refresh" @click="$emit('refresh')">刷新列表</button>
    </div>

    <!-- 用户数据表格 -->
    <OpsStateBlock :loading="loading" :error="error" :empty="users.length === 0" empty-text="未查询到匹配的用户数据">
      <div class="utb-table-wrap">
        <table class="utb-table">
          <thead>
            <tr>
              <th>用户</th>
              <th>角色</th>
              <th>在线状态</th>
              <th>账号状态</th>
              <th class="text-right">项目数</th>
              <th class="text-right">Agent 任务 (成功率)</th>
              <th class="text-right">累计 Token</th>
              <th>最后活跃时间</th>
              <th>操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="u in users" :key="u.userId" class="utb-row" @click="$emit('select', u)">
              <td>
                <div class="utb-user-cell">
                  <span class="utb-uid">#{{ u.userId }}</span>
                  <div class="utb-u-meta">
                    <span class="utb-uname">{{ u.username }}</span>
                    <span v-if="u.displayName" class="utb-dname">{{ u.displayName }}</span>
                  </div>
                </div>
              </td>
              <td>
                <OpsStatusTag :text="u.role" :type="u.role === 'ADMIN' ? 'danger' : 'info'" />
              </td>
              <td>
                <div class="utb-online-cell">
                  <span class="utb-dot" :class="{ online: u.online }"></span>
                  <span>{{ u.online ? '在线' : '离线' }}</span>
                </div>
              </td>
              <td>
                <OpsStatusTag :text="u.status === 1 ? '正常' : '已冻结'" :type="u.status === 1 ? 'success' : 'danger'" />
              </td>
              <td class="text-right font-num">{{ u.projectCount }}</td>
              <td class="text-right font-num">
                {{ u.taskCount }}
                <span class="utb-rate" :class="u.taskSuccessRate >= 0.8 ? 'rate-good' : 'rate-bad'">
                  ({{ Math.round(u.taskSuccessRate * 100) }}%)
                </span>
              </td>
              <td class="text-right font-num">{{ fmtNum(u.totalTokens) }}</td>
              <td class="utb-time">{{ formatTime(u.lastActiveTime) }}</td>
              <td>
                <button class="utb-action-link" @click.stop="$emit('select', u)">查看画像</button>
              </td>
            </tr>
          </tbody>
        </table>
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
import OpsStateBlock from '@/components/ops/ui/OpsStateBlock.vue'
import OpsPagination from '@/components/ops/ui/OpsPagination.vue'
import OpsStatusTag from '@/components/ops/ui/OpsStatusTag.vue'

const props = defineProps({
  users: { type: Array, default: () => [] },
  loading: { type: Boolean, default: false },
  error: { type: String, default: '' },
  page: { type: Number, default: 1 },
  total: { type: Number, default: 0 },
  pageSize: { type: Number, default: 20 },
  filters: {
    type: Object,
    default: () => ({
      keyword: '',
      role: '',
      status: '',
      isOnline: false,
      sortBy: 'lastActiveTime'
    })
  }
})

const emit = defineEmits(['select', 'filter', 'reset', 'page-change', 'refresh'])

function onFilter() {
  emit('filter')
}

function onReset() {
  props.filters.keyword = ''
  props.filters.role = ''
  props.filters.status = ''
  props.filters.isOnline = false
  props.filters.sortBy = 'lastActiveTime'
  emit('reset')
}

function fmtNum(n) {
  if (n == null) return '-'
  return Number(n).toLocaleString()
}

function formatTime(val) {
  return val ? String(val).replace('T', ' ').slice(0, 19) : '-'
}
</script>

<style scoped>
.utb {
  background: #fff;
  border: 1px solid #eef0f3;
  border-radius: 12px;
  padding: 14px 16px;
}
.utb-toolbar {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
  margin-bottom: 12px;
}
.utb-input, .utb-select {
  padding: 6px 10px;
  border: 1px solid #e5e7eb;
  border-radius: 8px;
  font-size: 12px;
  font-family: inherit;
  color: #374151;
  background: #fff;
}
.utb-input { width: 190px; }
.utb-online-toggle {
  display: flex;
  align-items: center;
  gap: 4px;
  font-size: 12px;
  color: #4b5563;
  cursor: pointer;
  padding: 0 4px;
}
.utb-btn {
  border: 1px solid #e5e7eb;
  background: #fff;
  padding: 6px 12px;
  border-radius: 8px;
  font-size: 12px;
  font-family: inherit;
  color: #374151;
  cursor: pointer;
}
.utb-btn:hover { background: #f9fafb; }
.utb-btn-refresh { margin-left: auto; }

.utb-table-wrap { overflow-x: auto; }
.utb-table {
  width: 100%;
  border-collapse: collapse;
  font-size: 12px;
}
.utb-table th {
  text-align: left;
  padding: 8px 10px;
  color: #6b7280;
  font-weight: 500;
  background: #f9fafb;
  border-bottom: 1px solid #f0f2f5;
  white-space: nowrap;
}
.utb-table td {
  padding: 10px 10px;
  border-bottom: 1px solid #f6f8fa;
  color: #374151;
  white-space: nowrap;
}
.utb-row { cursor: pointer; }
.utb-row:hover td { background: #f8fafc; }

.utb-user-cell { display: flex; align-items: center; gap: 8px; }
.utb-uid { font-size: 11px; color: #94a3b8; font-family: monospace; }
.utb-u-meta { display: flex; flex-direction: column; }
.utb-uname { font-weight: 500; color: #111827; }
.utb-dname { font-size: 11px; color: #9ca3af; }

.utb-online-cell { display: flex; align-items: center; gap: 6px; font-size: 11px; }
.utb-dot {
  width: 7px;
  height: 7px;
  border-radius: 50%;
  background: #cbd5e1;
}
.utb-dot.online { background: #10b981; }

.text-right { text-align: right; }
.font-num { font-variant-numeric: tabular-nums; }
.utb-rate { font-size: 11px; margin-left: 3px; }
.rate-good { color: #10b981; }
.rate-bad { color: #ef4444; }

.utb-time { color: #6b7280; font-variant-numeric: tabular-nums; font-size: 11px; }
.utb-action-link {
  border: none;
  background: none;
  color: #4f46e5;
  font-size: 12px;
  cursor: pointer;
  padding: 0;
}
.utb-action-link:hover { text-decoration: underline; }
</style>
