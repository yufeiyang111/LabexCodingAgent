#!/usr/bin/env node
/**
 * 未定义引用检查（等价于 ESLint 的 no-undef，但不需要引入 ESLint）。
 *
 * 为什么需要它：
 *   项目没有 ESLint/TS 检查，Vite 构建也不会报「变量在作用域外被引用」——
 *   这类错误只在运行时以 ReferenceError 出现，而它恰恰能在生产环境静默存活：
 *   代码在报错行之前已经改完了状态，界面看起来是好的，只有控制台在刷错误
 *   （真实案例：`let saved` 声明在 if 块内、块外引用，导致档位已落库但成功提示永不弹出，
 *     构建、单测、体积门禁全部通过，部署后才在浏览器控制台暴露）。
 *
 * 实现：用 acorn 解析源码，建立作用域链，检查每个标识符引用能否在链上解析。
 *   - 支持 var 提升到最近的函数/模块作用域，let/const 限定在块作用域；
 *   - 区分「引用」与「非引用」位置（属性名、对象字面量键、标签、声明名等）；
 *   - 用 globals 白名单排除浏览器/Node/ES 内置与 Vue 编译宏。
 *
 * 用法：node scripts/check-undefined-refs.mjs [--dir <源码目录>] [--verbose]
 *   退出码非 0 表示发现未定义引用。
 *
 * 已知覆盖边界（务必知情，不要当成"全量检查"）：
 *   acorn 无法解析 TypeScript 的类型语法（如 `ref<Record<string, string>>({})`、
 *   `defineProps<{ x?: string }>()`）。这类 script 块会被跳过并在输出中列出，
 *   覆盖率会在结尾汇总里显示 —— 缺口是可见的，不是静默的。
 */
import { readFileSync, readdirSync, statSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { dirname, join, relative, resolve } from 'node:path'
import * as acorn from 'acorn'

const here = dirname(fileURLToPath(import.meta.url))
const dirArgIndex = process.argv.indexOf('--dir')
const srcRoot = dirArgIndex >= 0 && process.argv[dirArgIndex + 1]
  ? resolve(process.cwd(), process.argv[dirArgIndex + 1])
  : resolve(here, '../src')
const verbose = process.argv.includes('--verbose')

/** 语言内置 */
const BUILTIN = [
  'Object', 'Array', 'String', 'Number', 'Boolean', 'Date', 'RegExp', 'Error', 'TypeError',
  'RangeError', 'SyntaxError', 'ReferenceError', 'EvalError', 'URIError', 'AggregateError',
  'Promise', 'Map', 'Set', 'WeakMap', 'WeakSet', 'WeakRef', 'Symbol', 'Proxy', 'Reflect',
  'JSON', 'Math', 'Intl', 'BigInt', 'Function', 'ArrayBuffer', 'SharedArrayBuffer', 'DataView',
  'Int8Array', 'Uint8Array', 'Uint8ClampedArray', 'Int16Array', 'Uint16Array', 'Int32Array',
  'Uint32Array', 'Float32Array', 'Float64Array', 'BigInt64Array', 'BigUint64Array',
  'parseInt', 'parseFloat', 'isNaN', 'isFinite', 'encodeURIComponent', 'decodeURIComponent',
  'encodeURI', 'decodeURI', 'eval', 'structuredClone', 'queueMicrotask', 'globalThis',
  'undefined', 'NaN', 'Infinity', 'arguments', 'this'
]

/** 浏览器 / Web API */
const BROWSER = [
  'window', 'document', 'navigator', 'location', 'history', 'screen', 'console', 'localStorage',
  'sessionStorage', 'fetch', 'Headers', 'Request', 'Response', 'AbortController', 'AbortSignal',
  'setTimeout', 'clearTimeout', 'setInterval', 'clearInterval', 'requestAnimationFrame',
  'cancelAnimationFrame', 'requestIdleCallback', 'cancelIdleCallback', 'queueMicrotask',
  'URL', 'URLSearchParams', 'Blob', 'File', 'FileReader', 'FormData', 'Image', 'Audio',
  'Event', 'CustomEvent', 'MouseEvent', 'KeyboardEvent', 'PointerEvent', 'TouchEvent',
  'DragEvent', 'ClipboardEvent', 'WheelEvent', 'FocusEvent', 'InputEvent', 'DOMException',
  'HTMLElement', 'HTMLInputElement', 'HTMLCanvasElement', 'HTMLImageElement', 'HTMLVideoElement',
  'HTMLMediaElement', 'HTMLTextAreaElement', 'HTMLSelectElement', 'HTMLButtonElement',
  'HTMLAnchorElement', 'HTMLDivElement', 'HTMLSpanElement', 'HTMLLabelElement', 'HTMLFormElement',
  'Element', 'Node', 'NodeList', 'NodeFilter', 'TreeWalker', 'DOMRect', 'DOMRectReadOnly',
  'CSSStyleDeclaration', 'CSSRule', 'CSSRuleList', 'DataTransfer', 'FileList', 'NamedNodeMap',
  'MutationObserver', 'IntersectionObserver', 'ResizeObserver', 'PerformanceObserver',
  'getComputedStyle', 'matchMedia', 'scrollTo', 'scrollBy', 'alert', 'confirm', 'prompt',
  'atob', 'btoa', 'crypto', 'performance', 'TextEncoder', 'TextDecoder', 'DOMParser',
  'XMLHttpRequest', 'WebSocket', 'Worker', 'MessageChannel', 'BroadcastChannel',
  'CSS', 'CSSStyleSheet', 'Notification', 'Selection', 'Range', 'DocumentFragment',
  'MediaQueryList', 'XMLSerializer', 'SpeechSynthesisUtterance', 'TouchList', 'AudioContext',
  'OffscreenCanvas', 'ImageData', 'Path2D', 'EventTarget', 'AbortSignal', 'Headers',
  'addEventListener', 'removeEventListener', 'dispatchEvent', 'speechSynthesis', 'indexedDB',
  'caches', 'process', 'global', 'Buffer', 'require', 'module', 'exports', '__dirname', '__filename'
]

/** Vue 编译宏：<script setup> 中无需导入即可使用 */
const VUE_MACROS = [
  'defineProps', 'defineEmits', 'defineExpose', 'defineOptions', 'defineSlots',
  'defineModel', 'withDefaults', 'defineComponent', 'useSlots', 'useAttrs'
]

const GLOBALS = new Set([...BUILTIN, ...BROWSER, ...VUE_MACROS])

class Scope {
  constructor(parent, isFunctionScope) {
    this.parent = parent
    this.isFunctionScope = isFunctionScope
    this.names = new Set()
  }
  declare(name) { if (name) this.names.add(name) }
  has(name) { return this.names.has(name) }
  resolve(name) {
    for (let s = this; s; s = s.parent) if (s.has(name)) return true
    return false
  }
  nearestFunctionScope() {
    for (let s = this; s; s = s.parent) if (s.isFunctionScope) return s
    return this
  }
}

/** 从各种绑定模式里取出被声明的名字 */
function declarePattern(node, scope, kind) {
  if (!node) return
  const target = kind === 'var' ? scope.nearestFunctionScope() : scope
  switch (node.type) {
    case 'Identifier':
      target.declare(node.name)
      break
    case 'ObjectPattern':
      for (const prop of node.properties) {
        if (prop.type === 'RestElement') declarePattern(prop.argument, scope, kind)
        else declarePattern(prop.value, scope, kind)
      }
      break
    case 'ArrayPattern':
      for (const el of node.elements) if (el) declarePattern(el, scope, kind)
      break
    case 'AssignmentPattern':
      declarePattern(node.left, scope, kind)
      break
    case 'RestElement':
      declarePattern(node.argument, scope, kind)
      break
    default:
      break
  }
}

/** 遍历子节点（通用，避免为每个节点类型手写） */
function childKeys(node) {
  return Object.keys(node).filter(k => {
    if (k === 'type' || k === 'start' || k === 'end' || k === 'loc' || k === 'range') return false
    const v = node[k]
    return v && (Array.isArray(v) ? v.some(x => x && x.type) : v.type)
  })
}

/**
 * 遍历子节点。
 *
 * ⚠️ 这里的 if/else 必须显式加花括号。写成
 *     if (Array.isArray(v)) for (const c of v) if (c && c.type) fn(c, key)
 *     else if (v && v.type) fn(v, key)
 * 时，`else` 会按 dangling-else 规则绑定到内层 `if (c && c.type)`，
 * 于是只有「数组型字段」会被遍历（body / params / arguments），
 * 所有直接子节点（test / expression / consequent / init / left / right …）全部漏掉 ——
 * 检查器会因此「永远不报错」而看起来一切正常。
 */
function forEachChild(node, fn) {
  for (const key of childKeys(node)) {
    const v = node[key]
    if (Array.isArray(v)) {
      for (const c of v) {
        if (c && c.type) fn(c, key)
      }
    } else if (v && v.type) {
      fn(v, key)
    }
  }
}

/**
 * 收集声明：建立「节点 → 作用域」映射，供引用解析阶段复用，保证两阶段作用域完全一致。
 */
function collect(node, scope, scopeOf) {
  scopeOf.set(node, scope)

  switch (node.type) {
    case 'Program':
      for (const stmt of node.body) collect(stmt, scope, scopeOf)
      return
    case 'ImportDeclaration':
      for (const spec of node.specifiers) scope.declare(spec.local.name)
      return
    case 'FunctionDeclaration':
      scope.declare(node.id && node.id.name)
      break
    case 'ClassDeclaration':
      scope.declare(node.id && node.id.name)
      break
    case 'VariableDeclaration': {
      for (const decl of node.declarations) declarePattern(decl.id, scope, node.kind)
      // 除初始化表达式外，还要遍历「解构模式本身」：默认值里可能内嵌函数
      // （如 `const { wait = ms => new Promise(resolve => …) } = opts`），
      // 不遍历就会遗漏这些函数的作用域，导致其参数被误报为未定义。
      for (const decl of node.declarations) collect(decl.id, scope, scopeOf)
      for (const decl of node.declarations) if (decl.init) collect(decl.init, scope, scopeOf)
      return
    }
    default:
      break
  }

  // 函数：自身名字在父作用域已声明，参数与函数体在新函数作用域
  if (node.type === 'FunctionDeclaration' || node.type === 'FunctionExpression'
      || node.type === 'ArrowFunctionExpression') {
    const fnScope = new Scope(scope, true)
    scopeOf.set(node, fnScope)
    if (node.type === 'FunctionExpression' && node.id) fnScope.declare(node.id.name)
    for (const p of node.params) declarePattern(p, fnScope, 'var')
    // 同 VariableDeclaration：参数默认值里内嵌的函数也要建作用域
    for (const p of node.params) collect(p, fnScope, scopeOf)
    if (node.body.type === 'BlockStatement') {
      const bodyScope = new Scope(fnScope, false)
      scopeOf.set(node.body, bodyScope)
      for (const stmt of node.body.body) collect(stmt, bodyScope, scopeOf)
    } else {
      collect(node.body, fnScope, scopeOf)
    }
    return
  }

  // 块级作用域
  if (node.type === 'BlockStatement' || node.type === 'StaticBlock') {
    const blockScope = new Scope(scope, false)
    scopeOf.set(node, blockScope)
    for (const stmt of node.body) collect(stmt, blockScope, scopeOf)
    return
  }

  if (node.type === 'SwitchStatement') {
    collect(node.discriminant, scope, scopeOf)
    const caseScope = new Scope(scope, false)
    scopeOf.set(node, caseScope)
    for (const c of node.cases) for (const stmt of c.consequent) collect(stmt, caseScope, scopeOf)
    return
  }

  if (node.type === 'CatchClause') {
    const catchScope = new Scope(scope, false)
    scopeOf.set(node, catchScope)
    if (node.param) declarePattern(node.param, catchScope, 'let')
    collect(node.body, catchScope, scopeOf)
    return
  }

  // for 循环头部声明的作用域
  if (node.type === 'ForStatement' || node.type === 'ForInStatement' || node.type === 'ForOfStatement') {
    const forScope = new Scope(scope, false)
    scopeOf.set(node, forScope)
    forEachChild(node, child => collect(child, forScope, scopeOf))
    return
  }

  forEachChild(node, child => collect(child, scope, scopeOf))
}

/** 判断该 Identifier 是否处于「引用」位置 */
function isReference(node, parent, key) {
  if (!parent) return true
  switch (parent.type) {
    case 'MemberExpression':
    case 'OptionalMemberExpression':
      return key === 'object' || parent.computed
    case 'Property':
      if (parent.shorthand) return key === 'value' || key === 'key'
      return parent.computed ? true : key !== 'key'
    case 'MethodDefinition':
    case 'PropertyDefinition':
      return parent.computed ? key !== 'key' : false
    case 'LabeledStatement':
    case 'BreakStatement':
    case 'ContinueStatement':
      return false
    case 'ImportSpecifier':
    case 'ImportDefaultSpecifier':
    case 'ImportNamespaceSpecifier':
      return false
    case 'ExportSpecifier':
      return key === 'local'
    case 'VariableDeclarator':
      return key !== 'id'
    case 'FunctionDeclaration':
    case 'FunctionExpression':
    case 'ClassDeclaration':
    case 'ClassExpression':
      return false
    default:
      return true
  }
}

function resolveReferences(node, scope, scopeOf, parent, key, out) {
  visitedNodes++
  if (walkTrace.length < 300) walkTrace.push(walkDepth + ' ' + node.type + (parent ? ` <-${key}<- ${parent.type}` : '') + '  childKeys=[' + childKeys(node).join('|') + ']')
  if (node.type === 'Identifier') {
    identifiersSeen++
    const asRef = isReference(node, parent, key)
    if (DEBUG_REFS) {
      debugRefs.push({
        name: node.name,
        line: node.loc ? node.loc.start.line : 0,
        isRef: asRef,
        resolved: asRef ? (scope.resolve(node.name) || GLOBALS.has(node.name)) : null,
        parent: parent && parent.type,
        key
      })
    }
    if (asRef) {
      const resolved = scope.resolve(node.name) || GLOBALS.has(node.name)
      if (!resolved) {
        out.push({ name: node.name, line: node.loc ? node.loc.start.line : 0 })
      }
    }
  }
  const nextScope = scopeOf.get(node) || scope
  walkDepth++
  forEachChild(node, (child, childKey) => resolveReferences(child, nextScope, scopeOf, node, childKey, out))
  walkDepth--
}

let visitedNodes = 0
let identifiersSeen = 0
let walkDepth = 0
const walkTrace = []
const DEBUG_REFS = process.argv.includes('--debug-refs')
const DEBUG_WALK = process.argv.includes('--debug-walk')
if (DEBUG_WALK) walkTrace.length = 0
const debugRefs = []

function checkSource(code, label) {
  let ast
  try {
    ast = acorn.parse(code, {
      ecmaVersion: 'latest',
      sourceType: 'module',
      allowAwaitOutsideFunction: true,
      allowReturnOutsideFunction: true,
      locations: true
    })
  } catch (e) {
    return { parseError: `${label}: ${e.message}` }
  }
  const moduleScope = new Scope(null, true)
  const scopeOf = new Map()
  collect(ast, moduleScope, scopeOf)
  const out = []
  resolveReferences(ast, moduleScope, scopeOf, null, null, out)
  return { refs: out }
}

function walkFiles(dir) {
  const out = []
  for (const name of readdirSync(dir)) {
    const full = join(dir, name)
    const st = statSync(full)
    if (st.isDirectory()) out.push(...walkFiles(full))
    else if (/\.(js|mjs|vue)$/.test(name)) out.push(full)
  }
  return out
}

const files = walkFiles(srcRoot).filter(f => !/\.test\.mjs$/.test(f))
const findings = []
const parseErrors = []
let blocksChecked = 0
let blocksSkipped = 0

for (const file of files) {
  const rel = relative(resolve(here, '..'), file).replace(/\\/g, '/')
  const raw = readFileSync(file, 'utf8')

  if (file.endsWith('.vue')) {
    const blocks = [...raw.matchAll(/<script\b([^>]*)>([\s\S]*?)<\/script>/g)]
      .filter(m => !/\bsrc\s*=/.test(m[1]))
    // 每个 script 块单独解析；块内行号按其在文件中的偏移换算
    for (const m of blocks) {
      const before = raw.slice(0, m.index + m[0].indexOf(m[2]))
      const offset = before.split('\n').length - 1
      const r = checkSource(m[2], rel)
      if (r.parseError) {
        blocksSkipped++
        parseErrors.push(`${rel}: ${String(r.parseError).replace(/^[^:]*:\s*/, '')}`)
        continue
      }
      blocksChecked++
      for (const f of r.refs) findings.push({ file: rel, name: f.name, line: f.line + offset })
    }
  } else {
    const r = checkSource(raw, rel)
    if (r.parseError) {
      blocksSkipped++
      parseErrors.push(`${rel}: ${String(r.parseError).replace(/^[^:]*:\s*/, '')}`)
      continue
    }
    blocksChecked++
    for (const f of r.refs) findings.push({ file: rel, name: f.name, line: f.line })
  }
}

console.log(`=== 未定义引用检查 ===`)
console.log(`  扫描文件 ${files.length} 个；已解析 ${blocksChecked} 个 script 块，跳过 ${blocksSkipped} 个`)

if (blocksSkipped > 0) {
  console.log('')
  console.log('⚠️  以下 script 块含 acorn 无法解析的语法（多为 TypeScript 类型参数），未纳入检查：')
  const seen = new Set()
  for (const e of parseErrors) {
    if (seen.has(e)) continue
    seen.add(e)
    console.log('    ' + e)
  }
}

if (findings.length) {
  console.log('')
  console.log('❌ 发现未定义引用：')
  for (const f of findings) console.log(`  ${f.file}:${f.line}  ${f.name}`)
  console.log('')
  console.log('这类错误运行时抛 ReferenceError：报错行之前的代码已经执行（状态已改），')
  console.log('界面看起来正常、只有控制台在刷错误 —— 必须在发布前修掉。')
  process.exit(1)
}

console.log('')
if (blocksSkipped > 0) {
  console.log(`✅ 已检查的 ${blocksChecked} 个 script 块中未发现未定义引用。`)
  console.log(`   （另有 ${blocksSkipped} 个块因语法未覆盖，见上方清单）`)
} else {
  console.log('✅ 未发现未定义引用。')
}
if (DEBUG_REFS || DEBUG_WALK) {
  console.log('')
  console.log('   globals 白名单 ' + GLOBALS.size + ' 项')
}
if (DEBUG_REFS) {
  console.log('')
  console.log('=== 引用解析明细（--debug-refs）===')
  console.log(`  遍历节点 ${visitedNodes} 个，其中 Identifier ${identifiersSeen} 个，判定为引用 ${debugRefs.length} 个`)
  for (const r of debugRefs) {
    console.log(`  line ${String(r.line).padStart(4)}  ref=${r.isRef}  resolved=${r.resolved}  ${r.name}  (parent=${r.parent}, key=${r.key})`)
  }
}
// --debug-walk：按访问顺序打印节点、父子关系与该节点的 childKeys。
// 排查「检查器不报错但也没检查到东西」时用它：若只沿数组字段下降、
// 且某个节点的 childKeys 明明包含子节点却没被继续访问，就说明遍历逻辑坏了。
if (DEBUG_WALK) {
  console.log('')
  console.log('=== 遍历轨迹（--debug-walk）===')
  for (const t of walkTrace) console.log('  ' + t)
}
