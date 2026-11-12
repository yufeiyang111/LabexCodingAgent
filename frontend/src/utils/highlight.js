/*
 * 代码高亮的唯一入口。
 *
 * 为什么不再 `import hljs from 'highlight.js/lib/common'`：
 *   lib/common 一次性注册 36 种语言，打包成一个 205KB（66KB gzip）的 chunk，
 *   并且是 CloudWorkspace 路由 chunk 的**静态依赖** —— 进工作区就要先下载它，
 *   哪怕这条会话里一行代码都没有。而项目实际渲染的代码块语言高度集中，
 *   绝大多数会话只用到 bash / java / javascript / typescript / xml / json 这几种。
 *
 * 现在的策略分两层：
 *   - 常驻层：上面这几种高频语言同步注册，首屏渲染时直接可用，不会闪一下再变色；
 *   - 按需层：其余语言在**真正出现该语言的代码块时**才动态 import 对应的小模块
 *     （单个 1~20KB），注册完成后就地升级已经渲染出来的代码块。
 *
 * 语言集合以代码块自己声明的 ```lang 为准，不从项目文件树猜技术栈：
 *   markdown 里本来就写着语言名，猜项目语言既多一次请求又更容易判错。
 */

import hljs from 'highlight.js/lib/core'

import plaintext from 'highlight.js/lib/languages/plaintext'
import bash from 'highlight.js/lib/languages/bash'
import java from 'highlight.js/lib/languages/java'
import javascript from 'highlight.js/lib/languages/javascript'
import typescript from 'highlight.js/lib/languages/typescript'
import xml from 'highlight.js/lib/languages/xml'
import json from 'highlight.js/lib/languages/json'
import yaml from 'highlight.js/lib/languages/yaml'
import sql from 'highlight.js/lib/languages/sql'
import python from 'highlight.js/lib/languages/python'
import diff from 'highlight.js/lib/languages/diff'
import ini from 'highlight.js/lib/languages/ini'

const EAGER_LANGUAGES = {
  plaintext,
  bash,
  java,
  javascript,
  typescript,
  xml,
  json,
  yaml,
  sql,
  python,
  diff,
  ini
}

for (const [name, definition] of Object.entries(EAGER_LANGUAGES)) {
  hljs.registerLanguage(name, definition)
}

/*
 * 按需语言表。新增一种语言只需在这里加一行；没列到的语言按纯文本渲染，不会报错。
 * 表里只放 hljs 真实存在的模块名（highlight.js/lib/languages/<name>.js）。
 */
const LAZY_LOADERS = {
  c: () => import('highlight.js/lib/languages/c'),
  cpp: () => import('highlight.js/lib/languages/cpp'),
  csharp: () => import('highlight.js/lib/languages/csharp'),
  go: () => import('highlight.js/lib/languages/go'),
  rust: () => import('highlight.js/lib/languages/rust'),
  ruby: () => import('highlight.js/lib/languages/ruby'),
  php: () => import('highlight.js/lib/languages/php'),
  kotlin: () => import('highlight.js/lib/languages/kotlin'),
  swift: () => import('highlight.js/lib/languages/swift'),
  lua: () => import('highlight.js/lib/languages/lua'),
  r: () => import('highlight.js/lib/languages/r'),
  perl: () => import('highlight.js/lib/languages/perl'),
  objectivec: () => import('highlight.js/lib/languages/objectivec'),
  scala: () => import('highlight.js/lib/languages/scala'),
  dart: () => import('highlight.js/lib/languages/dart'),
  elixir: () => import('highlight.js/lib/languages/elixir'),
  erlang: () => import('highlight.js/lib/languages/erlang'),
  haskell: () => import('highlight.js/lib/languages/haskell'),
  julia: () => import('highlight.js/lib/languages/julia'),
  matlab: () => import('highlight.js/lib/languages/matlab'),
  powershell: () => import('highlight.js/lib/languages/powershell'),
  clojure: () => import('highlight.js/lib/languages/clojure'),
  fsharp: () => import('highlight.js/lib/languages/fsharp'),
  groovy: () => import('highlight.js/lib/languages/groovy'),
  gradle: () => import('highlight.js/lib/languages/gradle'),
  makefile: () => import('highlight.js/lib/languages/makefile'),
  cmake: () => import('highlight.js/lib/languages/cmake'),
  dockerfile: () => import('highlight.js/lib/languages/dockerfile'),
  nginx: () => import('highlight.js/lib/languages/nginx'),
  apache: () => import('highlight.js/lib/languages/apache'),
  properties: () => import('highlight.js/lib/languages/properties'),
  protobuf: () => import('highlight.js/lib/languages/protobuf'),
  thrift: () => import('highlight.js/lib/languages/thrift'),
  graphql: () => import('highlight.js/lib/languages/graphql'),
  http: () => import('highlight.js/lib/languages/http'),
  less: () => import('highlight.js/lib/languages/less'),
  scss: () => import('highlight.js/lib/languages/scss'),
  css: () => import('highlight.js/lib/languages/css'),
  markdown: () => import('highlight.js/lib/languages/markdown'),
  vbnet: () => import('highlight.js/lib/languages/vbnet'),
  wasm: () => import('highlight.js/lib/languages/wasm'),
  x86asm: () => import('highlight.js/lib/languages/x86asm'),
  vim: () => import('highlight.js/lib/languages/vim'),
  latex: () => import('highlight.js/lib/languages/latex'),
  mermaid: null
}

const pendingLoads = new Map()
const languageLoadedListeners = new Set()

/** 已注册语言定义；未注册且不在按需表里返回 undefined。 */
export function getLanguage(name) {
  return hljs.getLanguage(name)
}

/** 语言是否已经可以同步高亮。 */
export function isLanguageReady(name) {
  return Boolean(name && hljs.getLanguage(name))
}

/**
 * 订阅「某个按需语言加载完成」。渲染层用它清掉未高亮的缓存，
 * 让下一次渲染拿到带高亮的 HTML。
 */
export function onLanguageLoaded(listener) {
  languageLoadedListeners.add(listener)
  return () => languageLoadedListeners.delete(listener)
}

/**
 * 触发按需加载。同一个语言只发一次请求；加载失败静默降级为纯文本。
 * @returns {Promise<boolean>} 该语言最终是否可用
 */
export function ensureLanguage(name) {
  if (!name || hljs.getLanguage(name)) return Promise.resolve(true)
  const loader = LAZY_LOADERS[name]
  if (!loader) return Promise.resolve(false)
  if (pendingLoads.has(name)) return pendingLoads.get(name)

  const task = loader()
    .then((mod) => {
      hljs.registerLanguage(name, mod.default || mod)
      upgradeRenderedBlocks(name)
      languageLoadedListeners.forEach((listener) => listener(name))
      return true
    })
    .catch(() => false)
    .finally(() => pendingLoads.delete(name))

  pendingLoads.set(name, task)
  return task
}

/**
 * 就地升级已经渲染出来的、等待该语言的代码块。
 *
 * 高亮发生在同步的 HTML 字符串拼装阶段，语言没到位时只能先出纯文本。
 * 模块到达后直接改这几处 DOM，比让整个消息列表重渲染便宜得多，
 * 也不会打断用户正在进行的滚动或选中。
 */
function upgradeRenderedBlocks(name) {
  if (typeof document === 'undefined') return
  document.querySelectorAll(`code[data-hljs-lang="${name}"]`).forEach((code) => {
    const raw = code.textContent || ''
    if (!raw) return
    try {
      code.innerHTML = hljs.highlight(raw, { language: name }).value
      code.classList.add('hljs')
      delete code.dataset.hljsLang
    } catch {
      // 高亮失败保留纯文本，不影响内容可读性
    }
  })
}

/**
 * 同步高亮。语言未就绪时返回 null，并顺手发起按需加载；
 * 调用方应回退成纯文本渲染，等 onLanguageLoaded 触发后再升级。
 */
export function highlight(code, language) {
  const raw = String(code ?? '')
  if (!raw) return ''
  if (!language) return null
  if (!hljs.getLanguage(language)) {
    void ensureLanguage(language)
    return null
  }
  try {
    return hljs.highlight(raw, { language }).value
  } catch {
    return null
  }
}

/** 自动识别语言并高亮；只在调用方明确拿不到语言名时使用。 */
export function highlightAuto(code) {
  try {
    const result = hljs.highlightAuto(String(code ?? ''))
    return { value: result.value || '', language: result.language || '' }
  } catch {
    return { value: '', language: '' }
  }
}

/** 该语言是否需要（且能够）按需加载。用于渲染时决定是否打等待标记。 */
export function canLoadLanguage(name) {
  return Boolean(name && !hljs.getLanguage(name) && LAZY_LOADERS[name])
}

export default hljs
