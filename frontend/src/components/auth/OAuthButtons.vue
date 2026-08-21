<template>
  <section v-if="providers.length" class="oauth-buttons" aria-label="第三方登录">
    <div class="oauth-buttons__divider"><span />或使用第三方账号登录<span /></div>
    <div class="oauth-buttons__grid">
      <button v-for="provider in providers" :key="provider" type="button" :disabled="loading" @click="$emit('select', provider)">
        <span class="oauth-buttons__icon-wrapper" aria-hidden="true">
          <OAuthProviderIcon :provider="provider" />
        </span>
        <span>{{ provider === 'github' ? 'GitHub 登录' : provider === 'google' ? 'Google 登录' : `${provider} 登录` }}</span>
      </button>
    </div>
  </section>
</template>

<script setup>
import OAuthProviderIcon from '@/components/auth/OAuthProviderIcon.vue'
defineProps({
  providers: { type: Array, default: () => [] },
  loading: { type: Boolean, default: false }
})
defineEmits(['select'])
</script>

<style scoped>
.oauth-buttons { margin-top: 22px; }
.oauth-buttons__divider { display: flex; align-items: center; gap: 10px; color: #9a958b; font-size: 11px; white-space: nowrap; }
.oauth-buttons__divider span { height: 1px; flex: 1; background: #ded8cc; }
.oauth-buttons__grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 10px; margin-top: 14px; }
.oauth-buttons button { display: inline-flex; align-items: center; justify-content: center; gap: 8px; min-height: 42px; border: 1px solid #cfc8ba; border-radius: 12px 10px 13px 11px; background: #fbf8f0; color: #4b4c45; cursor: pointer; font: inherit; font-size: 12px; transition: background-color 180ms ease, border-color 180ms ease; }
.oauth-buttons button:hover:not(:disabled) { border-color: #899485; background: #f2eee4; }
.oauth-buttons button:disabled { cursor: wait; opacity: .6; }
.oauth-buttons__icon-wrapper { display: inline-flex; align-items: center; justify-content: center; width: 20px; height: 20px; flex-shrink: 0; }
@media (max-width: 420px) { .oauth-buttons__grid { grid-template-columns: 1fr; } }
</style>
