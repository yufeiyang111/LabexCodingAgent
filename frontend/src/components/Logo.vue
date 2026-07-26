<template>
  <div class="flex items-center gap-3" aria-label="LabexAgent 智能代码工作间">
    <svg class="size-9 text-[#4E584D]" viewBox="0 0 32 32" fill="none" aria-hidden="true">
      <path d="M7 6v20h18" stroke="currentColor" stroke-width="2.25" stroke-linecap="round" stroke-linejoin="round" />
      <path d="M12 11h12M12 16h8M12 21h14" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" />
      <circle cx="24" cy="11" r="1.6" fill="currentColor" />
    </svg>
    <div class="grid gap-0.5">
      <span class="font-auth-serif text-[19px] tracking-[-0.03em] text-[#34352F]">{{ productName }}</span>
      <span class="text-[10px] tracking-[0.16em] text-[#76756D]">智能代码工作间</span>
    </div>
  </div>
</template>

<script setup lang="ts">
withDefaults(defineProps<{ productName?: string }>(), { productName: 'LabexAgent' })
</script>
