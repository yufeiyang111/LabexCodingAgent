<template>
  <div v-if="visible" class="udd-overlay" @click.self="$emit('close')">
    <div class="udd-drawer">
      <div class="udd-header">
        <div class="udd-title-row">
          <div class="udd-title">
            <span>用户画像全景</span>
            <span v-if="detail?.profile" class="udd-uid">#{{ detail.profile.userId }}</span>
          </div>
          <button class="udd-close-btn" @click="$emit('close')">✕</button>
        </div>
      </div>

      <div class="udd-body">
        <OpsStateBlock :loading="loading && !detail" :error="error" :empty="!detail" empty-text="暂无该用户详情">
          <div v-if="detail" class="udd-content">
            <!-- 基础档案卡 -->
            <div class="udd-section">
              <div class="udd-sec-title">基本档案</div>
              <div class="udd-profile-grid">
                <div class="udd-p-item"><span class="udd-p-key">用户名:</span> {{ detail.profile.username }}</div>
                <div class="udd-p-item"><span class="udd-p-key">昵称:</span> {{ detail.profile.displayName || '-' }}</div>
                <div class="udd-p-item"><span class="udd-p-key">邮箱:</span> {{ detail.profile.email || '-' }}</div>
                <div class="udd-p-item">
                  <span class="udd-p-key">角色:</span>
                  <OpsStatusTag :text="detail.profile.role" :type="detail.profile.role === 'ADMIN' ? 'danger' : 'info'" />
                </div>
                <div class="udd-p-item">
                  <span class="udd-p-key">状态:</span>
                  <OpsStatusTag :text="detail.profile.status === 1 ? '正常' : '已冻结'" :type="detail.profile.status === 1 ? 'success' : 'danger'" />
                </div>
                <div class="udd-p-item"><span class="udd-p-key">注册时间:</span> {{ formatTime(detail.profile.createTime) }}</div>
                <div class="udd-p-item"><span class="udd-p-key">最后登录 IP:</span> {{ detail.profile.lastLoginIp || '-' }}</div>
                <div class="udd-p-item"><span class="udd-p-key">地理归属:</span> {{ detail.profile.lastLoginLocation || '-' }}</div>
              </div>
            </div>

            <!-- 运维管控动作条 -->
            <div class="udd-section udd-actions-section">
              <div class="udd-sec-title">运维管控干预</div>
              <div class="udd-actions-bar">
                <OpsConfirmButton
                  v-if="detail.profile.status === 1"
                  text="冻结用户"
                  confirm-text="确认冻结此用户？"
                  type="danger"
                  :loading="acting"
                  @confirm="onFreeze"
                />
                <OpsConfirmButton
                  v-else
                  text="解冻用户"
                  confirm-text="确认解冻该用户？"
                  type="primary"
                  :loading="acting"
                  @confirm="onUnfreeze"
                />
                <OpsConfirmButton
                  text="中断进行中任务"
                  confirm-text="确认终止该用户当前全部运行中任务？"
                  type="danger"
                  :loading="acting"
                  @confirm="onTerminateTasks"
                />
              </div>
              <div v-if="actionError" class="udd-action-err">{{ actionError }}</div>
            </div>

            <!-- 业务资产与指标统计 -->
            <div class="udd-section">
              <div class="udd-sec-title">Agent 负载与资产</div>
              <div class="udd-stat-row">
                <div class="udd-stat-box">
                  <div class="udd-sb-label">总发起任务</div>
                  <div class="udd-sb-val">{{ detail.taskMetrics?.totalTasks || 0 }}</div>
                </div>
                <div class="udd-stat-box">
                  <div class="udd-sb-label">已完成任务</div>
                  <div class="udd-sb-val text-success">{{ detail.taskMetrics?.completedTasks || 0 }}</div>
                </div>
                <div class="udd-stat-box">
                  <div class="udd-sb-label">异常/失败</div>
                  <div class="udd-sb-val text-danger">{{ detail.taskMetrics?.failedTasks || 0 }}</div>
                </div>
                <div class="udd-stat-box">
                  <div class="udd-sb-label">当前运行中</div>
                  <div class="udd-sb-val text-warning">{{ detail.taskMetrics?.runningTasks || 0 }}</div>
                </div>
              </div>

              <!-- Token 偏好模型分布 -->
              <div class="udd-chart-box">
                <UserTokenPieChart :token-breakdown="detail.tokenBreakdown" />
              </div>

              <!-- 关联项目列表 -->
              <div class="udd-projects-box">
                <div class="udd-sub-title">关联项目（前 {{ detail.projects?.length || 0 }} 个）</div>
                <div class="udd-proj-list">
                  <div v-for="p in detail.projects" :key="p.projectId" class="udd-proj-item">
                    <span class="udd-proj-name">{{ p.projectName }}</span>
                    <span class="udd-proj-time">{{ formatTime(p.updateTime) }}</span>
                  </div>
                  <div v-if="!detail.projects || detail.projects.length === 0" class="udd-empty-hint">暂无项目</div>
                </div>
              </div>
            </div>

            <!-- 用户行为轨迹流 -->
            <div class="udd-section">
              <UserActivityTimeline
                :events="activities"
                :loading="activitiesLoading"
                :error="activitiesError"
                :category="activitiesCategory"
                :only-errors="activitiesOnlyErrors"
                :page="activitiesPage"
                :total="activitiesTotal"
                :page-size="activitiesPageSize"
                @category-change="$emit('activity-category-change', $event)"
                @toggle-errors="$emit('activity-toggle-errors', $event)"
                @page-change="$emit('activity-page-change', $event)"
                @refresh="$emit('activity-refresh')"
              />
            </div>
          </div>
        </OpsStateBlock>
      </div>
    </div>
  </div>
</template>

<script setup>
import OpsStateBlock from '@/components/ops/ui/OpsStateBlock.vue'
import OpsStatusTag from '@/components/ops/ui/OpsStatusTag.vue'
import OpsConfirmButton from '@/components/ops/ui/OpsConfirmButton.vue'
import UserTokenPieChart from './UserTokenPieChart.vue'
import UserActivityTimeline from './UserActivityTimeline.vue'

const props = defineProps({
  visible: { type: Boolean, default: false },
  detail: { type: Object, default: () => null },
  loading: { type: Boolean, default: false },
  error: { type: String, default: '' },
  activities: { type: Array, default: () => [] },
  activitiesLoading: { type: Boolean, default: false },
  activitiesError: { type: String, default: '' },
  activitiesCategory: { type: String, default: 'ALL' },
  activitiesOnlyErrors: { type: Boolean, default: false },
  activitiesPage: { type: Number, default: 1 },
  activitiesTotal: { type: Number, default: 0 },
  activitiesPageSize: { type: Number, default: 20 },
  acting: { type: Boolean, default: false },
  actionError: { type: String, default: '' }
})

const emit = defineEmits([
  'close',
  'action',
  'activity-category-change',
  'activity-toggle-errors',
  'activity-page-change',
  'activity-refresh'
])

function formatTime(val) {
  return val ? String(val).replace('T', ' ').slice(0, 19) : '-'
}

function onFreeze() {
  emit('action', 'FREEZE')
}

function onUnfreeze() {
  emit('action', 'UNFREEZE')
}

function onTerminateTasks() {
  emit('action', 'TERMINATE_ACTIVE_TASKS')
}
</script>

<style scoped>
.udd-overlay {
  position: fixed;
  inset: 0;
  background: rgba(0, 0, 0, 0.35);
  z-index: 1000;
  display: flex;
  justify-content: flex-end;
}
.udd-drawer {
  width: 680px;
  max-width: 90vw;
  height: 100%;
  background: #fff;
  box-shadow: -4px 0 20px rgba(0, 0, 0, 0.1);
  display: flex;
  flex-direction: column;
}
.udd-header {
  padding: 16px 20px;
  border-bottom: 1px solid #f1f5f9;
}
.udd-title-row {
  display: flex;
  justify-content: space-between;
  align-items: center;
}
.udd-title { font-size: 15px; font-weight: 600; color: #0f172a; display: flex; align-items: center; gap: 8px; }
.udd-uid { font-size: 12px; color: #64748b; font-weight: normal; }
.udd-close-btn { border: none; background: none; font-size: 16px; color: #94a3b8; cursor: pointer; padding: 4px; }
.udd-close-btn:hover { color: #334155; }

.udd-body { flex: 1; overflow-y: auto; padding: 18px 20px; }
.udd-section { margin-bottom: 20px; }
.udd-sec-title { font-size: 12px; font-weight: 600; color: #334155; margin-bottom: 10px; border-left: 3px solid #6366f1; padding-left: 8px; }

.udd-profile-grid {
  display: grid;
  grid-template-columns: repeat(2, 1fr);
  gap: 8px 16px;
  background: #f8fafc;
  border-radius: 8px;
  padding: 12px 14px;
  font-size: 12px;
}
.udd-p-item { display: flex; align-items: center; gap: 6px; color: #1e293b; }
.udd-p-key { color: #64748b; font-size: 11px; width: 80px; flex-shrink: 0; }

.udd-actions-bar { display: flex; gap: 10px; }
.udd-action-err { margin-top: 6px; font-size: 11px; color: #ef4444; }

.udd-stat-row { display: grid; grid-template-columns: repeat(4, 1fr); gap: 10px; margin-bottom: 12px; }
.udd-stat-box { background: #f8fafc; border-radius: 8px; padding: 10px; text-align: center; }
.udd-sb-label { font-size: 11px; color: #64748b; }
.udd-sb-val { font-size: 18px; font-weight: 600; margin-top: 4px; }
.text-success { color: #10b981; }
.text-danger { color: #ef4444; }
.text-warning { color: #f59e0b; }

.udd-chart-box { margin-bottom: 14px; }

.udd-sub-title { font-size: 11px; font-weight: 500; color: #475569; margin-bottom: 6px; }
.udd-proj-list { background: #f8fafc; border-radius: 8px; padding: 8px 12px; max-height: 120px; overflow-y: auto; font-size: 11px; }
.udd-proj-item { display: flex; justify-content: space-between; padding: 4px 0; border-bottom: 1px solid #f1f5f9; }
.udd-proj-item:last-child { border-bottom: none; }
.udd-proj-name { color: #1e293b; font-weight: 500; }
.udd-proj-time { color: #94a3b8; font-variant-numeric: tabular-nums; }
.udd-empty-hint { color: #94a3b8; text-align: center; padding: 8px; }
</style>
