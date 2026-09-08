<template>
  <div class="auth-brand-panel">
    <div class="auth-brand-panel__top">
      <Logo />
      <span class="auth-brand-panel__tag">安全、透明、可验证</span>
    </div>
    <div class="auth-brand-panel__copy">
      <p class="auth-brand-panel__eyebrow">AI 编程协作</p>
      <h1>把复杂的工程，<br />交给一位安静的 Agent。</h1>
      <p>从项目上下文到代码改动，让每一步都有依据、记录与可验证的结果。</p>
      <ol>
        <li><span>01</span>理解仓库与上下文</li>
        <li><span>02</span>规划改动与工具调用</li>
        <li><span>03</span>验证结果与保留痕迹</li>
      </ol>
    </div>
    <div class="auth-brand-panel__line" aria-hidden="true" />
  </div>
</template>

<script setup>
import Logo from '@/components/Logo.vue'
</script>

<style scoped>
.auth-brand-panel {
  display: flex;
  flex: 1;
  flex-direction: column;
  justify-content: space-between;
  gap: 40px;
  color: #34352f;
}

.auth-brand-panel__top {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 20px;
}

.auth-brand-panel__tag {
  padding-top: 5px;
  color: #76756d;
  font-size: 11px;
  letter-spacing: .08em;
}

.auth-brand-panel__copy {
  max-width: 620px;
  border-left: 1px solid #cfc8ba;
  padding-left: clamp(20px, 3vw, 44px);
}

.auth-brand-panel__eyebrow {
  display: inline-flex;
  margin: 0 0 26px;
  border: 1px solid #b7b8ab;
  border-radius: 10px 13px 11px 12px;
  padding: 6px 10px;
  color: #5d675b;
  font-size: 10px;
  font-weight: 600;
  letter-spacing: .18em;
}

.auth-brand-panel h1 {
  margin: 0;
  font-family: var(--font-auth-serif);
  font-size: clamp(42px, 5vw, 76px);
  font-weight: 400;
  line-height: 1.02;
  letter-spacing: -.04em;
}

.auth-brand-panel__copy > p:not(.auth-brand-panel__eyebrow) {
  max-width: 430px;
  margin: 28px 0 0;
  color: #76756d;
  font-size: 15px;
  line-height: 1.9;
}

.auth-brand-panel ol {
  display: grid;
  gap: 14px;
  max-width: 430px;
  margin: 48px 0 0;
  border-top: 1px solid #cfc8ba;
  padding: 22px 0 0;
  list-style: none;
  color: #4c4e47;
  font-size: 14px;
}

.auth-brand-panel li {
  display: flex;
  align-items: center;
  gap: 16px;
}

.auth-brand-panel li span {
  color: #987562;
  font-family: var(--font-auth-serif);
  font-size: 18px;
}

.auth-brand-panel__line {
  width: 62px;
  height: 4px;
  border-radius: 2px;
  background: #987562;
}

@media (max-width: 900px) {
  .auth-brand-panel__copy {
    padding-left: 18px;
  }

  .auth-brand-panel h1 {
    font-size: clamp(32px, 8vw, 54px);
  }

  .auth-brand-panel ol {
    display: none;
  }
}

@media (max-width: 640px) {
  .auth-brand-panel {
    gap: 16px;
  }

  .auth-brand-panel__copy {
    border-left: none;
    padding-left: 0;
  }

  .auth-brand-panel__eyebrow {
    margin-bottom: 12px;
  }

  .auth-brand-panel h1 {
    font-size: 24px;
    line-height: 1.2;
  }

  .auth-brand-panel__copy > p:not(.auth-brand-panel__eyebrow) {
    margin-top: 8px;
    font-size: 13px;
    line-height: 1.5;
  }

  .auth-brand-panel__line {
    display: none;
  }
}
</style>
