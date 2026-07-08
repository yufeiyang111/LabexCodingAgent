<template>
  <span class="app-icon" :class="{ compact }" :style="{ width: size + 'px', height: size + 'px' }">
    <img src="/brand/labex-mark.svg" alt="Labex" />
  </span>
</template>

<script setup>
defineProps({
  size: { type: Number, default: 36 },
  compact: { type: Boolean, default: false }
})
</script>

<style scoped>
.app-icon {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
  border-radius: 12px;
  overflow: hidden;
  box-shadow: 0 12px 28px rgba(15, 23, 42, 0.14);
}

.app-icon.compact {
  border-radius: 10px;
  box-shadow: 0 8px 18px rgba(15, 23, 42, 0.12);
}

.app-icon img {
  width: 100%;
  height: 100%;
  display: block;
}
</style>
