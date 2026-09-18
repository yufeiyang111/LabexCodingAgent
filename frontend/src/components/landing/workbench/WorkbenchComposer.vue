<template>
  <!-- 输入区：模式胶囊 + 文本卡片（工具按钮 / 模型选择 / 发送） -->
  <div class="ide__composer">
    <!-- 模式胶囊：data-active 驱动 CSS 中滑块的位置；点击切换对应场景 -->
    <div class="ide__modes" :data-active="modeIndex">
      <button
        v-for="m in modeList"
        :key="m.key"
        type="button"
        class="ide__mode"
        :class="{ 'is-on': m.key === mode }"
        :data-mode="m.key"
        @click="$emit('mode', m.key)"
      >
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8">
          <path v-for="(d, i) in m.icon" :key="i" :d="d" />
        </svg>
        {{ m.label }}
      </button>
    </div>

    <div class="ide__card">
      <textarea
        v-model="input"
        class="ide__ta"
        rows="1"
        placeholder="输入开发需求，支持文件修改、Mermaid 图表生成与工具链自动化..."
      ></textarea>

      <div class="ide__cardfoot">
        <span class="ide__tools">
          <i title="附加图片">
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7">
              <rect x="3" y="3" width="18" height="18" rx="2" />
              <circle cx="8.5" cy="8.5" r="1.5" />
              <path d="M21 15l-5-5L5 21" />
            </svg>
          </i>
          <i title="预设指令">
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
              <path d="M4 7h16M4 12h10M4 17h13" />
            </svg>
          </i>
          <i title="引用文件">
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7">
              <rect x="4" y="3" width="16" height="18" rx="2" />
              <path d="M8 8h8M8 12h5" />
            </svg>
          </i>
          <i title="优化提示词">
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7">
              <path d="M12 3l1.9 4.6L18.5 9l-4.6 1.4L12 15l-1.9-4.6L5.5 9l4.6-1.4z" />
              <path d="M18 15l.9 2.1L21 18l-2.1.9L18 21l-.9-2.1L15 18l2.1-.9z" />
            </svg>
          </i>
        </span>

        <!-- 模型选择：点击展开演示用下拉，纯展示不接真实模型列表 -->
        <span class="ide__modelwrap">
          <span class="ide__model" @click="modelOpen = !modelOpen">
            <span class="ide__modelname">{{ selectedModel }}</span>
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
              <path d="M6 9l6 6 6-6" />
            </svg>
          </span>
          <div v-show="modelOpen" class="ide__modelmenu" :hidden="!modelOpen">
            <button
              v-for="mm in MODELS"
              :key="mm"
              type="button"
              class="ide__modelopt"
              :class="{ 'is-on': mm === selectedModel }"
              @click="selectModel(mm)"
            >
              {{ mm }}
              <svg v-if="mm === selectedModel" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                <path d="M5 12l5 5L20 6" />
              </svg>
            </button>
          </div>
        </span>

        <button
          type="button"
          class="ide__send"
          :class="{ 'is-ready': input.trim() }"
          aria-label="发送"
          @click="$emit('send')"
        >
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
            <path d="M5 12h13M12 5l7 7-7 7" />
          </svg>
        </button>
      </div>
    </div>
  </div>
</template>

<script setup>
import { computed, ref } from 'vue'

const props = defineProps({
  // 当前激活的模式 key（由场景决定）
  mode: { type: String, default: 'build' },
  // 模式在胶囊中的序号，用于 data-active 滑块定位
  modeIndex: { type: Number, default: 0 },
  // 模式定义（key + label），图标在此处按 key 补充
  modes: { type: Array, default: () => [] },
})

defineEmits(['mode', 'send'])

// 模式图标路径（数据文件只含 key/label，图标属于视觉层在此补齐）
const MODE_ICONS = {
  build: ['M12 3.5v2', 'M12 18.5v2', 'M5.6 5.6l1.4 1.4', 'M17 17l1.4 1.4', 'M3.5 12h2', 'M18.5 12h2', 'M5.6 18.4L7 17', 'M17 7l1.4-1.4', 'M12 8.8a3.2 3.2 0 1 0 0-1.6 3.2 3.2 0 0 0 0 1.6z'],
  plan: ['M8 6h12', 'M8 12h12', 'M8 18h12', 'M4 6h.01', 'M4 12h.01', 'M4 18h.01'],
  explore: ['M11 4a7 7 0 1 0 0 14 7 7 0 0 0 0-14z', 'M20 20l-3.5-3.5'],
}
const modeList = computed(() =>
  (props.modes || []).map((m) => ({ ...m, icon: MODE_ICONS[m.key] || [] }))
)

// 文本框内容仅作本地展示与发送键高亮，不向外发请求
const input = ref('')

// 模型下拉为演示文案，不对应真实模型接口
const MODELS = ['MiniMax-M2.7-highspeed', 'MiniMax-M2.7', 'MiniMax-M2.7-light']
const selectedModel = ref(MODELS[0])
const modelOpen = ref(false)

function selectModel(mm) {
  selectedModel.value = mm
  modelOpen.value = false
}
</script>
