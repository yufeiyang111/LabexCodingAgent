import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import { authApi } from '@/api'

export const useUserStore = defineStore('user', () => {
  const token = ref(localStorage.getItem('token') || '')
  const userInfo = ref(JSON.parse(localStorage.getItem('userInfo') || 'null'))

  const isLoggedIn = computed(() => !!token.value)

  async function login(form) {
    const response = await authApi.login(form)
    setSession(response.data)
    return response.data
  }

  async function register(form) {
    const response = await authApi.register(form)
    setSession(response.data)
    return response.data
  }

  async function getUserInfo() {
    const response = await authApi.getUserInfo()
    userInfo.value = response.data
    localStorage.setItem('userInfo', JSON.stringify(response.data))
    return response.data
  }

  function setSession(data) {
    token.value = data.token
    userInfo.value = data.userInfo
    localStorage.setItem('token', data.token)
    localStorage.setItem('userInfo', JSON.stringify(data.userInfo))
  }

  function logout() {
    token.value = ''
    userInfo.value = null
    localStorage.removeItem('token')
    localStorage.removeItem('userInfo')
  }

  return {
    token,
    userInfo,
    isLoggedIn,
    login,
    register,
    getUserInfo,
    logout
  }
})
