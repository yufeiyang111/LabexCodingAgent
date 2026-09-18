import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import test from 'node:test'

const read = name => readFile(new URL(name, import.meta.url), 'utf8')

/**
 * 锁定「思考程度」必须真实生效：档位由后端下发、选择必须落库。
 *
 * 背景：此前 ModelSelectorPopover 把 ['Low','Medium','High'] 写死在模板里，
 * handleChangeThinkingLevel 只改本地 ref + 弹「已设置」提示，既不调接口也不落库，
 * 于是点档位是一个零效果的假控件。这些断言防止该形态回归。
 */
test('思考程度档位由后端下发，不在模板里写死', async () => {
  const popover = await read('./ModelSelectorPopover.vue')

  assert.doesNotMatch(popover, /\['Low',\s*'Medium',\s*'High'\]/, '不得写死三档数组')
  assert.doesNotMatch(popover, /supportsThinking\s*\?\?\s*true/, '不得对能力做 ?? true 的无依据兜底')
  assert.match(popover, /reasoningOptions/, '档位必须来自后端下发的 reasoningOptions')
  assert.match(popover, /effective/, '必须消费折叠/等价关系（effective），否则界面会假装每档都不同')
})

test('选择思考程度必须落库，且成功提示只能在接口成功之后', async () => {
  const workspace = await read('../../../views/CloudWorkspace.vue')

  const start = workspace.indexOf('async function handleChangeThinkingLevel')
  assert.notEqual(start, -1, 'handleChangeThinkingLevel 必须存在且为 async（要等接口）')
  const end = workspace.indexOf('\nfunction ', start)
  const body = workspace.slice(start, end === -1 ? undefined : end)

  // 落库逻辑在 composables/thinkingLevelChange.js（有真正执行它的行为测试）；
  // 组件只负责 UI 反馈，这里断言它确实委托出去、且顺序正确。
  assert.match(body, /await persistThinkingLevel\(/, '必须委托给 persistThinkingLevel 完成落库')
  assert.match(body, /modelConfigApi\.update/, '必须把保存接口注入进去')

  const apiIndex = body.indexOf('await persistThinkingLevel(')
  const successIndex = body.indexOf('ElMessage.success')
  assert.notEqual(successIndex, -1, '成功时应有明确反馈')
  assert.ok(apiIndex < successIndex, '成功提示必须在落库成功之后，不能在调用前就宣称已生效')

  // 只有 ok 为真才提示成功：失败分支必须先 return
  assert.match(body, /if \(!outcome\.ok\)[\s\S]*?return[\s\S]*?ElMessage\.success/, '失败必须先返回，不能落到成功提示')
  assert.doesNotMatch(body, /thinkingLevel\.value\s*=/, '不得再维护可写副本')

  // 落库细节（调用接口、失败不谎报）由行为测试覆盖，这里只确认它确实被注入
  const logic = await read('../../../composables/thinkingLevelChange.js')
  assert.match(logic, /reasoningEffort: value/, '保存内容必须包含归一后的 reasoningEffort')
})

test('思考程度的展示值来自所选模型配置，而不是独立的本地副本', async () => {
  const workspace = await read('../../../views/CloudWorkspace.vue')
  assert.match(
    workspace,
    /const thinkingLevel = computed\(/,
    'thinkingLevel 必须是派生自所选模型配置的 computed，避免出现第二份事实源'
  )
  assert.doesNotMatch(
    workspace,
    /thinkingLevel\.value = /,
    '不得再直接写入 thinkingLevel，档位唯一写入点是模型配置'
  )
})

/*
 * 选中行的识别必须同时兼容 modelName 与 configName。
 *
 * 缺陷形态：父组件传下来的 currentModel 是 modelName（deepseek-v4.1-flash），
 * 而列表标签优先用 configName（Deepseek go）。只比较单一字段时选中行识别不到，
 * 表现是「档位没有选中态 + 胶囊不显示当前档位」——数据全对、界面看不出选中了什么。
 * 实测断言：activePill = -1、胶囊文本退化为纯模型名。
 */
test('识别当前选中模型时必须同时兼容 modelName 与 configName', async () => {
  const popover = await read('./ModelSelectorPopover.vue')

  // 必须单独保留两个身份字段
  assert.match(popover, /configName:\s*m\.configName/, '必须保留 configName')
  assert.match(popover, /modelName:\s*m\.modelName/, '必须保留 modelName')

  // 选中行解析必须逐级匹配，不能只比单一字段
  const active = popover.slice(
    popover.indexOf('const activeModelRow = computed('),
    popover.indexOf('function labelOf')
  )
  assert.match(active, /item\.modelName === current/, '必须能按 modelName 匹配到选中行')
  assert.match(active, /item\.configName === current/, '必须能按 configName 匹配到选中行')

  // 档位选中态与胶囊文案都必须走同一个解析结果
  assert.match(popover, /function isSelectedLevel\(model, value\) \{[\s\S]*?activeModelRow\.value/, '选中态必须由 activeModelRow 判定')
  assert.match(popover, /const m = activeModelRow\.value/, '胶囊档位文案必须复用 activeModelRow')
})

/*
 * 折叠关系必须整块出现一次，不能塞进每个档位按钮里。
 *
 * 缺陷形态：每个按钮内联「→极致」角标 + 选中勾，五档分 3+2 两行排列，
 * 最长文案还会把按钮撑成两行 —— 视觉上是表单而非控件。
 * 现在改为单排分段控件，折叠关系收敛到右上角即时说明 + 底部脚注各一处。
 */
test('折叠关系整块呈现，不内联到每个档位按钮', async () => {
  const popover = await read('./ModelSelectorPopover.vue')

  // 分段控件（单排）而非网格
  assert.match(popover, /class="thinking-segmented"/, '档位必须用单排分段控件')
  assert.doesNotMatch(popover, /thinking-level-grid/, '不得回退成网格分行布局')
  assert.doesNotMatch(popover, /gridTemplateColumns/, '不得按档位数动态算列数')
  assert.doesNotMatch(popover, /class="level-alias"/, '不得在按钮内内联折叠角标')
  assert.doesNotMatch(popover, /class="check-mark"[\s\S]{0,40}thinking/, '档位按钮里不得再放对勾标记')

  // 折叠信息集中到两处：头部即时说明 + 底部脚注，且都由函数产出
  assert.match(popover, /function foldedSummary\(model\)/, '折叠摘要必须集中在 foldedSummary')
  assert.match(popover, /function activeLevelEffectiveNote\(model\)/, '选中档的生效说明必须独立成函数')
  assert.match(popover, /class="thinking-footnote"/, '必须有脚注承载折叠关系')
  assert.match(popover, /class="thinking-header-note"/, '选中折叠档时头部要给出即时说明')

  // 未折叠时不产生任何多余文案（避免噪音）
  assert.match(
    popover,
    /function activeLevelEffectiveNote\(model\)[\s\S]*?if \(!hit \|\| hit\.effective === hit\.value\) return ''/,
    '未折叠时必须返回空串，不能显示无意义的提示'
  )
  assert.match(
    popover,
    /function foldedSummary\(model\)[\s\S]*?if \(!folded\.length\) return ''/,
    '没有折叠档位时不得渲染脚注'
  )

  // 无障碍：分段控件必须声明 radiogroup / aria-checked
  assert.match(popover, /role="radiogroup"/, '分段控件需有 radiogroup 语义')
  assert.match(popover, /:aria-checked="isSelectedLevel\(model, opt\.value\)"/, '每个档位需暴露 aria-checked')
})

/*
 * 切换档位必须是"滑动"而不是"闪一下"。
 *
 * 实现要点：选中态背景从按钮上移到独立的 .thinking-thumb，靠 transform 位移产生动画。
 * 三条约束缺一不可：
 *   ① 指示块与按钮分离（否则背景随按钮重建，无法过渡）；
 *   ② 位置用 translateX(百分比) 表达，而不是重算像素；
 *   ③ 点击后不立刻关闭浮层（否则动画来不及被看见）。
 * 另外轨道必须无 gap 且内边距固定，thumbStyle() 的 (100% - 4px) / N 才成立。
 */
test('切换档位用滑动指示块，且不靠关闭浮层掩盖状态变化', async () => {
  const popover = await read('./ModelSelectorPopover.vue')

  // ① 独立的指示块
  assert.match(popover, /class="thinking-thumb"/, '必须有独立的滑动指示块')
  assert.match(popover, /function thumbStyle\(model\)/, '指示块位置必须由 thumbStyle 统一计算')
  assert.match(popover, /v-if="thumbStyle\(model\)"/, '无选中项时必须隐藏指示块')

  // ② 位移用百分比表达，且带过渡
  assert.match(popover, /transform:\s*`translateX\(\$\{index \* 100\}%\)`/, '位移必须用 translateX 百分比，不能重算像素')
  assert.match(popover, /\.thinking-thumb[\s\S]*?transition:\s*transform/, '指示块必须有 transform 过渡')
  assert.match(popover, /prefers-reduced-motion[\s\S]*?\.thinking-thumb[\s\S]*?transition:\s*none/, '必须尊重 prefers-reduced-motion')

  // ③ 选中背景不再由按钮承担
  const activeBlock = popover.slice(popover.indexOf('.thinking-segment.active'))
  assert.doesNotMatch(activeBlock.slice(0, 200), /background:\s*var\(--ai-accent/, '选中背景必须在指示块上，按钮只改文字色')

  // ④ 点击后不关闭浮层
  const selectFn = popover.slice(
    popover.indexOf('function selectThinkingLevel'),
    popover.indexOf('function openConfigDialog')
  )
  assert.doesNotMatch(selectFn, /isOpen\.value = false/, '选择档位后不得立刻关闭浮层，否则看不到滑动结果')

  // ⑤ 轨道数值与算式绑定
  assert.match(popover, /calc\(\(100% - 4px\) \/ \$\{options\.length\}\)/, '指示块宽度算式必须与轨道内边距一致')
  const trackBlock = popover.slice(popover.indexOf('.thinking-segmented {'), popover.indexOf('.thinking-thumb'))
  assert.doesNotMatch(trackBlock, /^\s*gap:/m, '轨道不得留 gap，否则位移会累积错位')
})
