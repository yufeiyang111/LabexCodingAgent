<template>
  <div class="auth-module-layout" :class="{ 'auth-module-layout--inline': isInline }">
    <aside v-if="!isInline" class="auth-module-layout__brand">
      <slot name="brand" />
    </aside>
    <main class="auth-module-layout__form">
      <slot name="form" />
    </main>
  </div>
</template>

<script setup>
import { computed } from 'vue'

/**
 * 认证布局容器。
 *
 * variant:
 * - `page`（默认）：整页两栏布局，左侧品牌叙事 + 右侧表单，用于 `/login` 路由。
 * - `inline`：单栏紧凑布局，去掉全屏高度与内边距，供登录弹窗内嵌使用。
 */
const props = defineProps({
  variant: { type: String, default: 'page' }
})

const isInline = computed(() => props.variant === 'inline')
</script>

<style scoped>
.auth-module-layout {
  display: grid;
  grid-template-columns: minmax(0, 1.08fr) minmax(420px, 1fr);
  min-height: 100dvh;
  font-family: var(--font-auth-sans);
}

.auth-module-layout__brand {
  display: flex;
  padding: clamp(32px, 5vw, 80px);
  /* 与表单侧同底：靠内容与留白区分，而不是靠色块 */
  background: var(--theme-bg);
  border-right: 1px solid var(--theme-border);
}

.auth-module-layout__form {
  display: flex;
  align-items: center;
  justify-content: center;
  padding: clamp(28px, 5vw, 80px);
  background: var(--theme-surface);
}

/* 弹窗内嵌：单栏、无全屏高度、无内边距，背景与留白交给 el-dialog */
.auth-module-layout--inline {
  grid-template-columns: minmax(0, 1fr);
  min-height: 0;
}

.auth-module-layout--inline .auth-module-layout__form {
  align-items: stretch;
  padding: 0;
  background: transparent;
  /*
   * grid 子项默认 min-width: auto，会被内部内容（大字号标题、输入框固有宽度）
   * 撑到超过轨道宽度。实测窄屏下该项宽 350px 而网格容器仅 276px，
   * 内容因此溢出面板右边界。显式归零后才会真正跟随容器收缩。
   * （与历史上 .segmented、.field__row 的修复同源）
   */
  min-width: 0;
}

@media (max-width: 900px) {
  .auth-module-layout {
    grid-template-columns: 1fr;
  }

  .auth-module-layout__brand {
    min-height: 200px;
    padding: 32px 24px 32px;
  }

  .auth-module-layout__form {
    align-items: flex-start;
    padding: 32px 24px 64px;
  }

  .auth-module-layout--inline .auth-module-layout__form {
    align-items: stretch;
    padding: 0;
  }
}

@media (max-width: 640px) {
  .auth-module-layout__brand {
    min-height: auto;
    padding: 24px 16px 12px;
  }

  .auth-module-layout__form {
    padding: 16px 14px 40px;
  }

  .auth-module-layout--inline .auth-module-layout__form {
    padding: 0;
  }
}
</style>
