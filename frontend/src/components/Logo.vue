<template>
  <div class="flex items-center gap-2.5" aria-label="LabexAgent">
    <svg class="size-8 text-[#1D1D1F]" viewBox="0 0 32 32" fill="none" aria-hidden="true">
      <path d="M7 6v20h18" stroke="currentColor" stroke-width="2.6" stroke-linecap="round" stroke-linejoin="round" />
      <path d="M13 11h12M13 16h8M13 21h14" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" />
      <circle cx="24" cy="11" r="1.7" fill="currentColor" />
    </svg>
    <span class="text-[15px] font-semibold tracking-[-0.02em] text-[#1D1D1F]">{{ productName }}</span>
  </div>
</template>

<script setup lang="ts">
withDefaults(defineProps<{ productName?: string }>(), { productName: 'LabexAgent' })
</script>
