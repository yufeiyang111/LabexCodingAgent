import { createApp } from 'vue'
import { createPinia } from 'pinia'
import App from './App.vue'
import router from './router'
import { useThemeStore } from './stores/theme'
import '@/styles/tailwind.css'
import '@/styles/global.scss'
import '@/styles/wabi-sabi.scss'
import '@/styles/theme.scss'
import '@/styles/sidebar.scss'

/* ===== Element Plus 按需引入（首屏体积优化）=====
 * - 模板中的 el-* 组件：由 unplugin-vue-components + ElementPlusResolver 在构建期自动按需解析并注入样式；
 * - 命令式 API（ElMessage/ElMessageBox/ElNotification/ElLoading）：仍为静态 import（tree-shaking 后极小），
 *   其样式在此按模块显式引入，避免全量 element-plus/dist/index.css（约 300KB）；
 * - 图标不再全量注册：各组件内显式 import 用到的图标组件。
 */
import 'element-plus/es/components/message/style/css'
import 'element-plus/es/components/message-box/style/css'
import 'element-plus/es/components/notification/style/css'
import 'element-plus/es/components/loading/style/css'

const app = createApp(App)
const pinia = createPinia()

app.use(pinia)
useThemeStore(pinia).initialize()
app.use(router)
app.mount('#app')