<template>
  <!-- 终端面板：展示已执行命令的输出，下方按钮点击追加演示输出 -->
  <div class="ide__terminals">
    <div class="ide__term">
      <div
        v-for="(line, i) in lines"
        :key="i"
        class="ide__termline"
        :class="line.cls"
      >{{ line.text }}</div>
    </div>
    <div class="ide__termcmds">
      <button
        v-for="cmd in commands"
        :key="cmd"
        type="button"
        @click="run(cmd)"
      >{{ cmd }}</button>
    </div>
  </div>
</template>

<script setup>
import { ref } from 'vue'

// 初始输出为演示文案，模拟「语法检查 + 启动服务」的结果
const lines = ref([
  { text: '$ py_compile config.py', cls: '' },
  { text: '✓ 语法检查通过 (0.56s)', cls: 'ide__termline--ok' },
  { text: '', cls: '' },
  { text: '$ python app.py', cls: '' },
  { text: '* Running on http://127.0.0.1:5000', cls: 'ide__termline--dim' },
])

const commands = ['py_compile config.py', 'pytest -q', 'python app.py']

// 点击命令按钮追加输出：纯演示，不发起真实进程调用
const outputs = {
  'py_compile config.py': [{ text: '✓ 语法检查通过 (0.56s)', cls: 'ide__termline--ok' }],
  'pytest -q': [{ text: '3 passed in 0.41s', cls: 'ide__termline--ok' }],
  'python app.py': [{ text: '* Running on http://127.0.0.1:5000', cls: 'ide__termline--dim' }],
}

function run(cmd) {
  lines.value.push({ text: '$ ' + cmd, cls: '' })
  outputs[cmd]?.forEach((o) => lines.value.push({ ...o }))
  lines.value.push({ text: '', cls: '' })
}
</script>
