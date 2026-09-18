import { onBeforeUnmount, onMounted, ref } from 'vue'

/**
 * 媒体懒加载：让视频 / 大图在接近视口前不产生任何网络请求。
 *
 * 为什么需要它：落地页有一段产品演示视频（约 1.5MB）与两张产品截图。
 * 若直接写 `src`，浏览器会在首屏就发起请求，直接拖慢首屏。
 * 这里的策略是「三不」：
 *   1. 不写 src —— 用 data-src 占位，只有 JS 主动赋值才开始下载；
 *   2. 不预加载 —— 视频赋值时同时设置 preload='none'，避免拿到元数据即抢带宽；
 *   3. 不到视口不加载 —— IntersectionObserver 提前 rootMargin 预热，但绝不首屏加载。
 */
export function useLazyMedia(mediaRef, options = {}) {
  const { rootMargin = '200px 0px', poster = '' } = options
  const isLoaded = ref(false)
  let observer = null

  function activate() {
    const el = mediaRef?.value
    if (!el || isLoaded.value) return
    const src = el.dataset.src
    if (!src) return
    el.preload = 'none'
    el.src = src
    el.removeAttribute('data-src')
    isLoaded.value = true
  }

  onMounted(() => {
    const el = mediaRef?.value
    if (!el) return

    // 浏览器不支持 observer 时直接加载，保证功能可用（优雅降级）
    if (typeof IntersectionObserver === 'undefined') {
      activate()
      return
    }

    observer = new IntersectionObserver(
      entries => {
        entries.forEach(entry => {
          if (!entry.isIntersecting) return
          activate()
          observer?.disconnect()
          observer = null
        })
      },
      { rootMargin }
    )
    observer.observe(el)
  })

  onBeforeUnmount(() => {
    observer?.disconnect()
    observer = null
  })

  return { isLoaded, activate }
}

/**
 * 图片懒加载（原生优先）。
 *
 * 支持 `<picture>` 双格式：父级为 picture 且存在 `source[data-srcset]` 时，
 * **必须先写 source.srcset 再写 img.src** —— 浏览器只在 img 发起加载的那一刻
 * 评估 source 列表，顺序反了就会退回到回退格式（实测会把 webp 白白跳过）。
 *
 * 之所以优先原生 loading="lazy"：它由浏览器在合成线程调度，
 * 观测器版本只在浏览器不支持原生懒加载时才启用。
 */
export function useLazyImage(imageRef, options = {}) {
  const { rootMargin = '240px 0px' } = options
  const isLoaded = ref(false)
  let observer = null

  function pictureSource(el) {
    const parent = el.parentElement
    if (!parent || parent.tagName !== 'PICTURE') return null
    return parent.querySelector('source[data-srcset]')
  }

  function activate(el, source) {
    if (source) {
      source.srcset = source.dataset.srcset
      source.removeAttribute('data-srcset')
    }
    const src = el.dataset.src
    if (src) {
      el.src = src
      el.removeAttribute('data-src')
    }
  }

  onMounted(() => {
    const el = imageRef?.value
    if (!el) return
    const source = pictureSource(el)
    if (!el.dataset.src && !source) { isLoaded.value = true; return }

    if ('loading' in HTMLImageElement.prototype) {
      el.loading = 'lazy'
      el.decoding = 'async'
      activate(el, source)
      el.addEventListener('load', () => { isLoaded.value = true }, { once: true })
      // 命中缓存时不会再触发 load，做一次兜底判断
      if (el.complete) isLoaded.value = true
      return
    }

    if (typeof IntersectionObserver === 'undefined') {
      activate(el, source)
      isLoaded.value = true
      return
    }

    observer = new IntersectionObserver(
      entries => {
        entries.forEach(entry => {
          if (!entry.isIntersecting) return
          activate(el, source)
          el.addEventListener('load', () => { isLoaded.value = true }, { once: true })
          isLoaded.value = true
          observer?.disconnect()
          observer = null
        })
      },
      { rootMargin }
    )
    observer.observe(el)
  })

  onBeforeUnmount(() => {
    observer?.disconnect()
    observer = null
  })

  return { isLoaded }
}
