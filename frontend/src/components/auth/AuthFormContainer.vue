<template>
  <section class="auth-form-container" aria-labelledby="auth-heading">
    <div class="auth-form-container__header">
      <p class="auth-form-container__eyebrow">智能编程工作间 · 01</p>
      <h2 id="auth-heading">{{ title }}</h2>
      <p class="auth-form-container__subtitle">{{ subtitle }}</p>
    </div>
    <slot />
  </section>
</template>

<script setup>
defineProps({
  title: { type: String, required: true },
  subtitle: { type: String, required: true }
})
</script>

<style scoped>
.auth-form-container {
  width: min(100%, 440px);
}

.auth-form-container__header {
  margin-bottom: 30px;
}

.auth-form-container__eyebrow {
  margin: 0 0 11px;
  color: var(--theme-accent);
  font-size: 10px;
  font-weight: 600;
  letter-spacing: .18em;
}

.auth-form-container h2 {
  margin: 0;
  color: var(--theme-text);
  font-family: var(--font-auth-sans);
  letter-spacing: -0.02em;
  font-size: 36px;
  font-weight: 400;
  line-height: 1.05;
  letter-spacing: -.04em;
}

.auth-form-container__subtitle {
  max-width: 380px;
  margin: 14px 0 0;
  color: var(--theme-text-secondary);
  font-size: 14px;
  line-height: 1.8;
}

/*
 * 窄屏：标题与头部间距等比收缩。
 *
 * 36px 标题 + 30px 头部下边距是桌面弹窗的尺度，放到手机上（可用宽度约 320px）
 * 会让标题占比过大、把表单整体往下推，观感「过大且拥挤」。
 * 这里按约 0.6 的比例收一档；表单控件的高度不动，触屏可点性不降级。
 */
@media (max-width: 640px) {
  .auth-form-container__header {
    margin-bottom: 18px;
  }

  .auth-form-container__eyebrow {
    margin-bottom: 8px;
    font-size: 9.5px;
  }

  .auth-form-container h2 {
    font-size: 22px;
    line-height: 1.25;
    letter-spacing: -0.01em;
  }

  .auth-form-container__subtitle {
    margin-top: 10px;
    font-size: 13px;
    line-height: 1.6;
  }
}
</style>
