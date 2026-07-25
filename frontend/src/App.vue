<template>
  <router-view />
  <ThemeSettingsLauncher />
  <ThemeSettingsDrawer />
</template>

<script setup>
import ThemeSettingsDrawer from '@/components/theme/ThemeSettingsDrawer.vue'
import ThemeSettingsLauncher from '@/components/theme/ThemeSettingsLauncher.vue'
</script>
