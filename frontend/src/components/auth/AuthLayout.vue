<template>
  <div class="auth-module-layout">
    <aside class="auth-module-layout__brand">
      <slot name="brand" />
    </aside>
    <main class="auth-module-layout__form">
      <slot name="form" />
    </main>
  </div>
</template>

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
  background: #f6f2e9;
}

.auth-module-layout__form {
  display: flex;
  align-items: center;
  justify-content: center;
  padding: clamp(28px, 5vw, 80px);
  background: #fbf8f0;
}

@media (max-width: 900px), (pointer: coarse) and (hover: none) {
  .auth-module-layout {
    grid-template-columns: 1fr;
  }

  .auth-module-layout__brand {
    min-height: 280px;
    padding: 32px 24px 44px;
  }

  .auth-module-layout__form {
    align-items: flex-start;
    padding: 40px 24px 64px;
  }
}
</style>
