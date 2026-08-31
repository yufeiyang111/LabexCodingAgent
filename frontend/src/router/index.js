import { createRouter, createWebHistory } from 'vue-router'
import { ElMessage } from 'element-plus'
import { useUserStore } from '@/stores/user'

const Login = () => import('@/views/Login.vue')
const CloudSpace = () => import('@/views/CloudSpace.vue')
const CloudWorkspace = () => import('@/views/CloudWorkspace.vue')
const Monitor = () => import('@/views/Monitor.vue')
const Tutorials = () => import('@/views/Tutorials.vue')

const routes = [
  { path: '/login', name: 'Login', component: Login, meta: { requiresAuth: false, title: '登录' } },
  { path: '/projects', name: 'Projects', component: CloudSpace, meta: { requiresAuth: true, title: '项目空间' } },
  { path: '/workspace/:projectId', name: 'CloudWorkspace', component: CloudWorkspace, meta: { requiresAuth: true, title: '工作空间' } },
  { path: '/ops', name: 'Monitor', component: Monitor, meta: { requiresAuth: false, title: '运维监控' } },
  { path: '/tutorials', name: 'Tutorials', component: Tutorials, meta: { requiresAuth: false, title: '开发教程' } },
  { path: '/tutorials/:id', name: 'TutorialDetail', component: Tutorials, meta: { requiresAuth: false, title: '教程详情' } },
  { path: '/', redirect: '/projects' },
  { path: '/:pathMatch(.*)*', redirect: '/projects' }
]

const router = createRouter({
  history: createWebHistory(),
  routes
})

router.beforeEach((to, from, next) => {
  document.title = to.meta.title ? `${to.meta.title} - LabexAgent` : 'LabexAgent'
  const userStore = useUserStore()
  if (!to.meta.requiresAuth) {
    if (to.name === 'Login' && userStore.isLoggedIn) {
      next('/projects')
      return
    }
    next()
    return
  }
  if (!userStore.isLoggedIn) {
    ElMessage.warning('请先登录')
    next('/login')
    return
  }
  next()
})

export default router
