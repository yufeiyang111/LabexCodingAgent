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
    if (!response.config?.silent) {
      ElMessage.error(message)
    }
    return Promise.reject(new Error(message))
  },
  (error) => {
    const status = error.response?.status
    const responseData = error.response?.data
    console.error('[request] failed:', {
      url: error.config?.url,
      method: error.config?.method,
      status,
      responseData,
      message: error.message,
      cause: error.cause?.message
    })
    if (status === 401 || status === 403) {
      const userStore = useUserStore()
      userStore.logout()
      if (!error.config?.silent) {
        ElMessage.error('登录已失效，请重新登录')
      }
      router.push('/login')
    } else if (error.code === 'ECONNABORTED' || /timeout/i.test(error.message || '')) {
      if (!error.config?.silent) {
        ElMessage.error(error.config?.timeoutMessage || '请求超时')
      }
    } else {
      const detail = responseData?.message || responseData?.error || (responseData ? JSON.stringify(responseData) : '')
      const message = [detail, error.message].filter(Boolean).join('（')
      if (!error.config?.silent) {
        ElMessage.error(status ? `请求失败(${status})：${detail || error.message || '网络异常'}` : error.message || '网络异常')
      }
      const enhanced = new Error(message ? `${message}` : '请求失败')
      enhanced.name = error.name
      enhanced.status = status
      enhanced.response = error.response
      enhanced.config = error.config
      enhanced.cause = error.cause || error
      return Promise.reject(enhanced)
    }
    return Promise.reject(error)
  }
)

export default service
