import { computed, onBeforeUnmount, ref } from 'vue'

/**
 * 工作台演示编排：把「提问 → 工具调用 → 回答」串成一条可重播的时间线。
 *
 * 抽成 composable 的理由：这段逻辑涉及多个定时器与阶段状态，
 * 放在展示组件里会与渲染混在一起；独立出来后组件只负责画，
 * 时间线可以单独推理与测试。
 *
 * 时间模型：所有步骤以「提问打完后」为原点，用 step.at 作为相对偏移，
 * 因此改打字速度不会让工具卡与回答的节奏错位。
 */
export function useWorkbenchDemo(options = {}) {
  const { scenes, order = [], typingSpeed = 38, thinkingDelay = 420, replyDelay = 900 } = options

  const sceneKey = ref(order[0] ?? '')
  const askText = ref('')
  const steps = ref([])
  const reply = ref('')
  const tokens = ref(0)
  /** idle | typing | thinking | tools | done */
  const phase = ref('idle')

  let timers = []
  function clearTimers() {
    timers.forEach(clearTimeout)
    timers = []
  }
  function later(fn, ms) {
    timers.push(setTimeout(fn, ms))
  }

  const isPlaying = computed(() => phase.value !== 'idle' && phase.value !== 'done')
  const tokenLabel = computed(() => (tokens.value ? `${(tokens.value / 1000).toFixed(1)}K` : '0'))

  function reset() {
    clearTimers()
    askText.value = ''
    steps.value = []
    reply.value = ''
    tokens.value = 0
    phase.value = 'idle'
  }

  function play(key) {
    const scene = scenes[key]
    if (!scene) return
    clearTimers()
    sceneKey.value = key
    askText.value = ''
    steps.value = []
    reply.value = ''
    tokens.value = 0
    phase.value = 'typing'

    // ① 提问逐字打出
    const chars = Array.from(scene.ask)
    chars.forEach((char, index) => {
      later(() => { askText.value += char }, index * typingSpeed)
    })
    const typedAt = chars.length * typingSpeed

    // ② 思考
    later(() => { phase.value = 'thinking' }, typedAt)

    // ③ 工具卡按各自偏移依次出现；token 随最后出现的卡片推进
    scene.steps.forEach((step, index) => {
      later(() => {
        phase.value = 'tools'
        steps.value = [...steps.value, { ...step, id: `${key}-${index}` }]
        tokens.value = step.tokens
      }, typedAt + thinkingDelay + step.at)
    })

    // ④ 回答
    const lastAt = scene.steps.length ? scene.steps[scene.steps.length - 1].at : 0
    later(() => {
      reply.value = scene.reply
      phase.value = 'done'
    }, typedAt + thinkingDelay + lastAt + replyDelay)
  }

  function replay() {
    if (sceneKey.value) play(sceneKey.value)
  }

  onBeforeUnmount(clearTimers)

  return { sceneKey, askText, steps, reply, tokens, tokenLabel, phase, isPlaying, play, replay, reset }
}
