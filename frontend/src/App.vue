<template>
  <router-view />
  <!--
    登录 / 注册弹窗。
    外壳常驻（关闭时 Element Plus 的 overlay 为 display:none，不产生布局与绘制开销），
    但内部真正的认证组件由 AuthModal 以 v-if 控制，仅在用户点击「登录」后挂载、关闭即销毁，
    因此登录 / 注册表单不会随页面常驻渲染。
  -->
  <AuthModal />
  <template v-if="isThemeControlsAvailable">
    <ThemeSettingsLauncher />
    <ThemeSettingsDrawer />
    <TutorialsLauncher />
  </template>
</template>

<script setup>
import { computed, watch } from 'vue'
import { useRoute } from 'vue-router'
import AuthModal from '@/components/auth/AuthModal.vue'
import ThemeSettingsDrawer from '@/components/theme/ThemeSettingsDrawer.vue'
import ThemeSettingsLauncher from '@/components/theme/ThemeSettingsLauncher.vue'
import TutorialsLauncher from '@/components/tutorial/TutorialsLauncher.vue'
import { useThemeStore } from '@/stores/theme'

const route = useRoute()
const themeStore = useThemeStore()
const isThemeControlsAvailable = computed(() => route.name !== 'Login')

watch(isThemeControlsAvailable, available => {
  if (!available) themeStore.closeSettings()
}, { immediate: true })
</script>
