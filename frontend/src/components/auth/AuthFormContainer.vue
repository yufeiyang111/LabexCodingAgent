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
  color: #987562;
  font-size: 10px;
  font-weight: 600;
  letter-spacing: .18em;
}

.auth-form-container h2 {
  margin: 0;
  color: #34352f;
  font-family: var(--font-auth-serif);
  font-size: 36px;
  font-weight: 400;
  line-height: 1.05;
  letter-spacing: -.04em;
}

.auth-form-container__subtitle {
  max-width: 380px;
  margin: 14px 0 0;
  color: #76756d;
  font-size: 14px;
  line-height: 1.8;
}
</style>
