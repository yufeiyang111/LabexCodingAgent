<template>
  <component
    :is="iconComponent"
    class="ops-icon"
    :width="size"
    :height="size"
    :style="{ '--ops-icon-stroke-width': strokeWidth }"
    aria-hidden="true"
  />
</template>

<script setup>
/**
 * 运维 / 监控通用图标组件。
 *
 * 来源：Lucide（ISC License，Copyright (c) 2022 Lucide Contributors）
 *       https://github.com/lucide-icons/lucide
 * 通过 unplugin-icons 以 `~icons/lucide/<name>` 按需引入：每个图标在构建期编译为
 * 单个内联 SVG 组件，只有模板真正引用到的图标才会进入产物，不引入整包图标库。
 *
 * 维护约束：本组件只允许引用上游官方图标集合，不得在此内联手绘或复制的路径数据；
 * 新增图标直接添加对应的 `~icons/lucide/*` 导入即可，来源与许可由上游包保证。
 * 回归测试见 OpsIconProvenance.test.mjs。
 */
import { computed } from 'vue'

import IconActivity from '~icons/lucide/activity'
import IconArrowLeft from '~icons/lucide/arrow-left'
import IconArrowRight from '~icons/lucide/arrow-right'
import IconBell from '~icons/lucide/bell'
import IconBellOff from '~icons/lucide/bell-off'
import IconBox from '~icons/lucide/box'
import IconCheck from '~icons/lucide/check'
import IconChevronDown from '~icons/lucide/chevron-down'
import IconChevronLeft from '~icons/lucide/chevron-left'
import IconChevronRight from '~icons/lucide/chevron-right'
import IconChevronUp from '~icons/lucide/chevron-up'
import IconCircle from '~icons/lucide/circle'
import IconCircleAlert from '~icons/lucide/circle-alert'
import IconCircleCheckBig from '~icons/lucide/circle-check-big'
import IconClock from '~icons/lucide/clock'
import IconCoffee from '~icons/lucide/coffee'
import IconCpu from '~icons/lucide/cpu'
import IconDatabase from '~icons/lucide/database'
import IconEye from '~icons/lucide/eye'
import IconFileText from '~icons/lucide/file-text'
import IconFilter from '~icons/lucide/filter'
import IconGlobe from '~icons/lucide/globe'
import IconHardDrive from '~icons/lucide/hard-drive'
import IconHash from '~icons/lucide/hash'
import IconInfo from '~icons/lucide/info'
import IconKey from '~icons/lucide/key'
import IconLayers from '~icons/lucide/layers'
import IconLayoutDashboard from '~icons/lucide/layout-dashboard'
import IconLock from '~icons/lucide/lock'
import IconLogOut from '~icons/lucide/log-out'
import IconMonitor from '~icons/lucide/monitor'
import IconNetwork from '~icons/lucide/network'
import IconPieChart from '~icons/lucide/pie-chart'
import IconPlus from '~icons/lucide/plus'
import IconPower from '~icons/lucide/power'
import IconRotateCw from '~icons/lucide/rotate-cw'
import IconSearch from '~icons/lucide/search'
import IconServer from '~icons/lucide/server'
import IconShield from '~icons/lucide/shield'
import IconShieldAlert from '~icons/lucide/shield-alert'
import IconSparkles from '~icons/lucide/sparkles'
import IconSquarePen from '~icons/lucide/square-pen'
import IconTerminal from '~icons/lucide/terminal'
import IconTrash from '~icons/lucide/trash'
import IconTrendingUp from '~icons/lucide/trending-up'
import IconTriangleAlert from '~icons/lucide/triangle-alert'
import IconUser from '~icons/lucide/user'
import IconUsers from '~icons/lucide/users'
import IconX from '~icons/lucide/x'
import IconZap from '~icons/lucide/zap'

/** 名称 → 图标组件。同时保留历史上的别名，避免调用方改动。 */
const OPS_ICONS = {
  activity: IconActivity,
  'alert-circle': IconCircleAlert,
  'alert-triangle': IconTriangleAlert,
  'arrow-left': IconArrowLeft,
  'arrow-right': IconArrowRight,
  bell: IconBell,
  'bell-off': IconBellOff,
  box: IconBox,
  check: IconCheck,
  'check-circle': IconCircleCheckBig,
  'chevron-down': IconChevronDown,
  'chevron-left': IconChevronLeft,
  'chevron-right': IconChevronRight,
  'chevron-up': IconChevronUp,
  clock: IconClock,
  coffee: IconCoffee,
  cpu: IconCpu,
  database: IconDatabase,
  disk: IconHardDrive,
  'hard-drive': IconHardDrive,
  edit: IconSquarePen,
  eye: IconEye,
  'file-text': IconFileText,
  filter: IconFilter,
  globe: IconGlobe,
  hash: IconHash,
  info: IconInfo,
  key: IconKey,
  layers: IconLayers,
  memory: IconLayers,
  'layout-dashboard': IconLayoutDashboard,
  dashboard: IconLayoutDashboard,
  lock: IconLock,
  'log-out': IconLogOut,
  monitor: IconMonitor,
  network: IconNetwork,
  'pie-chart': IconPieChart,
  plus: IconPlus,
  power: IconPower,
  refresh: IconRotateCw,
  'rotate-cw': IconRotateCw,
  search: IconSearch,
  server: IconServer,
  shield: IconShield,
  'shield-alert': IconShieldAlert,
  sparkles: IconSparkles,
  terminal: IconTerminal,
  trash: IconTrash,
  'trending-up': IconTrendingUp,
  user: IconUser,
  users: IconUsers,
  x: IconX,
  zap: IconZap
}

const props = defineProps({
  name: { type: String, required: true },
  size: { type: [Number, String], default: 16 },
  strokeWidth: { type: [Number, String], default: 1.8 }
})

/** 未识别的名称渲染为空圆，与组件历史行为一致 */
const iconComponent = computed(() => OPS_ICONS[props.name] || IconCircle)
</script>

<style scoped>
.ops-icon {
  display: inline-block;
  vertical-align: -0.125em;
  flex-shrink: 0;
  transition: transform 0.2s ease, stroke 0.2s ease;
}

/* 上游默认 stroke-width=2；表现属性优先级低于 CSS，据此保持既有 1.8 的视觉权重 */
.ops-icon,
.ops-icon :deep(*) {
  stroke-width: var(--ops-icon-stroke-width, 1.8);
}
</style>
