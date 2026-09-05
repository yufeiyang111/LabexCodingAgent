import { fileURLToPath } from 'node:url'
import { dirname, resolve } from 'node:path'
import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import tailwindcss from '@tailwindcss/vite'
import Icons from 'unplugin-icons/vite'
import Components from 'unplugin-vue-components/vite'
import { ElementPlusResolver } from 'unplugin-vue-components/resolvers'

const __dirname = dirname(fileURLToPath(import.meta.url))
const apiTarget = process.env.VITE_API_TARGET || 'http://localhost:8080'
const frontendPort = Number.parseInt(process.env.ACCEPTANCE_FRONTEND_PORT || '3000', 10)

export default defineConfig({
  plugins: [
    vue(),
    tailwindcss(),
    Icons({ compiler: 'vue3' }),
    // Element Plus 按需引入：只打包模板中实际用到的 el-* 组件及其样式，
    // 替代 main.js 的 app.use(ElementPlus) 全量注册（约 1MB JS + 300KB CSS 的首屏减负）
    Components({ resolvers: [ElementPlusResolver({ importStyle: 'css' })] })
  ],
  resolve: {
    alias: {
      '@': resolve(__dirname, 'src'),
      '@api': resolve(__dirname, 'src/api'),
      '@router': resolve(__dirname, 'src/router'),
      '@stores': resolve(__dirname, 'src/stores'),
      '@views': resolve(__dirname, 'src/views'),
      '@components': resolve(__dirname, 'src/components'),
      '@utils': resolve(__dirname, 'src/utils'),
      '@styles': resolve(__dirname, 'src/styles'),
      '@assets': resolve(__dirname, 'src/assets')
    }
  },
  server: {
    port: Number.isInteger(frontendPort) && frontendPort > 0 ? frontendPort : 3000,
    host: '0.0.0.0',
    proxy: {
      '/api': {
        target: apiTarget,
        changeOrigin: true,
        secure: false,
        ws: true
      },
      '/ws': {
        target: apiTarget,
        changeOrigin: true,
        secure: false,
        ws: true
      }
    }
  },
  preview: {
    host: '127.0.0.1',
    allowedHosts: ['labexagent.123845.xyz']
  },
  css: {
    preprocessorOptions: {
      scss: {
        api: 'modern-compiler'
      }
    }
  },
  build: {
    outDir: 'dist',
    assetsDir: 'assets',
    sourcemap: false,
    chunkSizeWarningLimit: 1500,
    rollupOptions: {
      output: {
        // 仅重命名「恰好被 rollup 命名为 index-* 的非入口共享 chunk」（避免与唯一入口 index-* 撞前缀，
        // 导致 chunk 预算脚本误判），其余保持语义化前缀（CloudWorkspace-*、TerminalPanel-* 等）
        chunkFileNames: (chunkInfo) => {
          const raw = chunkInfo.name || 'chunk'
          const name = !chunkInfo.isEntry && raw.startsWith('index')
            ? `shared-${raw.replace(/^index/, '').replace(/^-+/, '') || 'vendor'}`
            : raw
          return `assets/${name}-[hash].js`
        },
        manualChunks: {
          // echarts 仅被动态 import 使用，但显式声明可稳定 chunk 边界便于缓存复用
          echarts: ['echarts'],
          // xterm 本体约 350KB：独立 chunk，避免拖垮 TerminalPanel 业务 chunk 的体积预算
          // 注意：scoped 包的前缀必须包含 @（rollup 按模块 id 前缀匹配）
          xterm: ['@xterm/xterm', '@xterm/addon-fit', '@xterm/addon-search', '@xterm/addon-web-links']
          // 注意：element-plus 已改为按需引入，不再整包收集为独立 chunk；
          // 静态 import 的 ElMessage 等服务将由 tree-shaking 缩到极小
        }
      }
    }
  }
})
