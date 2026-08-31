<template>
  <router-view />
  <template v-if="isThemeControlsAvailable">
    <ThemeSettingsLauncher />
    <ThemeSettingsDrawer />
    <TutorialsLauncher />
  </template>
</template>

<script setup>
import { computed, watch } from 'vue'
import { useRoute } from 'vue-router'
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
