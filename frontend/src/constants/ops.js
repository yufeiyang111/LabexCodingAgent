export const ALERT_STATUSES = {
  FIRING: { label: '触发中', type: 'danger' },
  ACKNOWLEDGED: { label: '已确认', type: 'warning' },
  SILENCED: { label: '已静默', type: 'info' },
  RESOLVED: { label: '已恢复', type: 'success' }
}

export const INCIDENT_STATUSES = {
  OPEN: { label: '开启', type: 'danger' },
  ACKNOWLEDGED: { label: '已确认', type: 'warning' },
  MITIGATING: { label: '缓解中', type: 'warning' },
  RESOLVED: { label: '已解决', type: 'success' },
  CLOSED: { label: '已关闭', type: 'info' }
}

export const SEVERITIES = {
  info: { label: '信息', type: 'info' },
  warning: { label: '警告', type: 'warning' },
  critical: { label: '严重', type: 'danger' }
}

export const OPERATION_STATUSES = {
  REQUESTED: { label: '已请求', type: 'info' },
  SUCCEEDED: { label: '成功', type: 'success' },
  FAILED: { label: '失败', type: 'danger' }
}

export const ALERT_OPERATORS = [
  { value: 'GT', label: '>' },
  { value: 'GTE', label: '>=' },
  { value: 'LT', label: '<' },
  { value: 'LTE', label: '<=' }
]

export const ALERT_METRIC_KEYS = [
  { value: 'cpu_percent', label: 'CPU 使用率' },
  { value: 'memory_percent', label: '内存使用率' },
  { value: 'disk_percent', label: '磁盘使用率' },
  { value: 'heap_used_ratio', label: 'JVM 堆占比' },
  { value: 'system_load', label: '系统负载' },
  { value: 'task_running', label: '运行中任务数' },
  { value: 'task_waiting', label: '等待任务数' },
  { value: 'task_failed', label: '失败任务数' },
  { value: 'task_total', label: '任务总数' },
  { value: 'token_total', label: 'Token 总量' }
]

export const SEVERITY_OPTIONS = [
  { value: 'info', label: '信息' },
  { value: 'warning', label: '警告' },
  { value: 'critical', label: '严重' }
]

export const EVENT_TYPES = [
  { value: 'APP_STARTED', label: '应用启动' },
  { value: 'ALERT_FIRED', label: '告警触发' },
  { value: 'ALERT_RESOLVED', label: '告警恢复' },
  { value: 'ALERT_ACKNOWLEDGED', label: '告警确认' },
  { value: 'ALERT_SILENCED', label: '告警静默' },
  { value: 'ALERT_NOTIFY_FAILED', label: '通知失败' },
  { value: 'INCIDENT_OPENED', label: '故障开启' },
  { value: 'INCIDENT_ACKNOWLEDGED', label: '故障确认' },
  { value: 'INCIDENT_MITIGATING', label: '故障缓解' },
  { value: 'INCIDENT_RESOLVED', label: '故障解决' },
  { value: 'INCIDENT_CLOSED', label: '故障关闭' },
  { value: 'OPS_OPERATION_SUCCEEDED', label: '操作成功' },
  { value: 'OPS_OPERATION_FAILED', label: '操作失败' }
]

export const OPS_ROLES = {
  OPS_VIEWER: { label: '只读', type: 'info' },
  OPS_OPERATOR: { label: '操作者', type: 'warning' },
  OPS_ADMIN: { label: '管理员', type: 'danger' },
  AUDITOR: { label: '审计', type: 'info' }
}
