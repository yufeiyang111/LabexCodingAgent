import { ref, computed } from 'vue'

const MOBILE_BREAKPOINT = 768
const TABLET_BREAKPOINT = 1024

const windowWidth = ref(typeof window !== 'undefined' ? window.innerWidth : 1200)
const windowHeight = ref(typeof window !== 'undefined' ? window.innerHeight : 800)
const isTouch = ref(
  typeof window !== 'undefined' &&
  ('ontouchstart' in window || navigator.maxTouchPoints > 0)
)

let isListenerAttached = false
let resizeTimer = null

function updateDimensions() {
  if (typeof window === 'undefined') return
  windowWidth.value = window.innerWidth
  windowHeight.value = window.innerHeight
}

function handleResize() {
  if (resizeTimer) return
  resizeTimer = requestAnimationFrame(() => {
    resizeTimer = null
    updateDimensions()
  })
}

function ensureListener() {
  if (typeof window === 'undefined' || isListenerAttached) return
  window.addEventListener('resize', handleResize, { passive: true })
  window.addEventListener('orientationchange', handleResize, { passive: true })
  isListenerAttached = true
  updateDimensions()
}

export function useResponsive() {
  ensureListener()

  const isMobile = computed(() => windowWidth.value < MOBILE_BREAKPOINT)
  const isTablet = computed(
    () => windowWidth.value >= MOBILE_BREAKPOINT && windowWidth.value < TABLET_BREAKPOINT
  )
  const isDesktop = computed(() => windowWidth.value >= TABLET_BREAKPOINT)

  return {
    windowWidth,
    windowHeight,
    isMobile,
    isTablet,
    isDesktop,
    isTouch,
  }
}
