import { onBeforeUnmount, onMounted } from 'vue'

/**
 * 滚动揭示：元素进入视口后加上 `is-in`，由 CSS 负责上浮与淡入。
 *
 * 抽成 composable 而不是写在组件里，是因为落地页有 6 个区块共用同一套揭示逻辑；
 * 各区块只声明自己的根元素，无需重复实现 observer 的创建与回收。
 *
 * 无障碍：用户开启「减少动态效果」时直接落到终态，不做任何位移动画。
 */
export function useScrollReveal(rootRef, options = {}) {
  const { threshold = 0.12, rootMargin = '0px 0px -8% 0px' } = options
  let observer = null

  onMounted(() => {
    const root = rootRef?.value
    if (!root || typeof IntersectionObserver === 'undefined') return

    const targets = Array.from(root.querySelectorAll('.reveal'))
    if (!targets.length) return

    const reduceMotion = window.matchMedia?.('(prefers-reduced-motion: reduce)').matches
    if (reduceMotion) {
      targets.forEach(el => el.classList.add('is-in'))
      return
    }

    observer = new IntersectionObserver(
      entries => {
        entries.forEach(entry => {
          if (!entry.isIntersecting) return
          entry.target.classList.add('is-in')
          // 揭示只需一次，立刻取消观察，避免滚动时反复触发
          observer?.unobserve(entry.target)
        })
      },
      { threshold, rootMargin }
    )

    targets.forEach(el => observer.observe(el))
  })

  onBeforeUnmount(() => {
    observer?.disconnect()
    observer = null
  })
}
