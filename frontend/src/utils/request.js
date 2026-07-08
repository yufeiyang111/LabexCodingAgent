import axios from 'axios'
import { ElMessage } from 'element-plus'
import { useUserStore } from '@/stores/user'
import router from '@/router'

export const REQUEST_TIMEOUTS = {
  DEFAULT: 120000,
  AI_GENERATION: 10 * 60 * 1000
}

const service = axios.create({
  baseURL: '/api',
  timeout: REQUEST_TIMEOUTS.DEFAULT,
  headers: {
    'Content-Type': 'application/json'
  }
})

service.interceptors.request.use(
  (config) => {
    const userStore = useUserStore()
    if (userStore.token) {
      config.headers.Authorization = `Bearer ${userStore.token}`
    }
    return config
  },
  (error) => Promise.reject(error)
)

service.interceptors.response.use(
  (response) => {
    if (response.config?.responseType === 'blob') {
      return response.data
    }
    const body = response.data
    if (body?.code === 0) {
      return body
    }
    const message = body?.message || '请求失败'
    ElMessage.error(message)
    return Promise.reject(new Error(message))
  },
  (error) => {
    const status = error.response?.status
    if (status === 401 || status === 403) {
      const userStore = useUserStore()
      userStore.logout()
      ElMessage.error('登录已失效，请重新登录')
      router.push('/login')
    } else if (error.code === 'ECONNABORTED' || /timeout/i.test(error.message || '')) {
      ElMessage.error(error.config?.timeoutMessage || '请求超时')
    } else {
      ElMessage.error(error.response?.data?.message || error.message || '网络异常')
    }
    return Promise.reject(error)
  }
)

export default service
