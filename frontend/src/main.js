import { createApp } from 'vue'
import { createPinia } from 'pinia'
import ElementPlus from 'element-plus'
import 'element-plus/dist/index.css'
import * as ElementPlusIconsVue from '@element-plus/icons-vue'
import App from './App.vue'
import router from './router'
import { useThemeStore } from './stores/theme'
import '@/styles/tailwind.css'
import '@/styles/global.scss'
import '@/styles/wabi-sabi.scss'
import '@/styles/theme.scss'
import '@/styles/sidebar.scss'

const app = createApp(App)
const pinia = createPinia()

for (const [key, component] of Object.entries(ElementPlusIconsVue)) {
  app.component(key, component)
}

app.use(pinia)
useThemeStore(pinia).initialize()
app.use(router)
app.use(ElementPlus)
app.mount('#app')
