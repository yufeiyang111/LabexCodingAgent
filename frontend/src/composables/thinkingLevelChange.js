/**
 * 思考档位落库逻辑。
 *
 * 为什么单独抽成模块：组件里的同类逻辑只能靠「读源码文本做断言」来测试，
 * 抓不到作用域、控制流这类运行时错误。抽出来之后可以被真正执行验证。
 *
 * 历史事故（本模块存在的原因）：档位保存逻辑原先内联在 CloudWorkspace.vue 中，
 * `let saved` 声明在 if 块内、却在块外被引用，运行时抛
 * `ReferenceError: saved is not defined` —— 构建、单测、体积门禁全部通过，
 * 部署后才在浏览器控制台暴露；而报错行之前的代码已经改完了状态，
 * 所以界面看起来正常（滑块已移动、数据已落库），只有成功提示永远不弹出。
 */

/**
 * 保存思考档位。
 *
 * @param {object}   input
 * @param {object}   input.payload  { configId, modelName, value }
 * @param {Array}    input.configs  当前模型配置列表（用于定位目标配置）
 * @param {Function} input.update   (configId, body) => Promise<{ data }>，失败时 reject
 * @returns {Promise<{ok: boolean, reason?: string, target?: object, config?: object, value?: string}>}
 *          ok=true 时 `config` 为应写回列表的最新配置；`reason` 取值：
 *          empty-value / target-not-found / empty-response
 *          更新接口 reject 时本函数**不吞异常**，由调用方决定如何提示。
 */
export async function persistThinkingLevel({ payload, configs, update }) {
  const configId = payload?.configId
  const modelName = payload?.modelName
  // 归一：去空白 + 小写。前端不把带空白的档位值发给后端。
  const value = String(payload?.value ?? '').trim().toLowerCase()
  if (!value) return { ok: false, reason: 'empty-value' }

  const list = Array.isArray(configs) ? configs : []
  const target = list.find(c => c.configId === configId)
    || list.find(c => c.modelName === modelName || c.configName === modelName)
  if (!target) return { ok: false, reason: 'target-not-found' }

  // latest 声明在函数作用域：它要在下面的分支之外被读取。
  let latest = target
  if (String(target.reasoningEffort ?? '').trim().toLowerCase() !== value) {
    const response = await update(target.configId, { reasoningEffort: value })
    const saved = response?.data || null
    if (!saved) return { ok: false, reason: 'empty-response', target }
    latest = saved
  }

  return { ok: true, target, config: latest, value }
}

/**
 * 档位中文名。取值来自后端下发的 reasoningOptions，前端不维护第二份映射；
 * 未登记取值原样返回，不伪装成某个已知档位。
 */
export function labelOfReasoningEffort(options, value) {
  const hit = (Array.isArray(options) ? options : []).find(opt => opt.value === value)
  return hit?.label || value
}
