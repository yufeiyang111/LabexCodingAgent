import { createRouter, createWebHistory } from 'vue-router'
import { ElMessage } from 'element-plus'
import { useUserStore } from '@/stores/user'

const Landing = () => import('@/views/Landing.vue')
const Login = () => import('@/views/Login.vue')
const Legal = () => import('@/views/Legal.vue')
const CloudSpace = () => import('@/views/CloudSpace.vue')
const CloudWorkspace = () => import('@/views/CloudWorkspace.vue')
const Monitor = () => import('@/views/Monitor.vue')
const Tutorials = () => import('@/views/Tutorials.vue')

const routes = [
  { path: '/', name: 'Landing', component: Landing, meta: { requiresAuth: false, title: '首页' } },
  { path: '/login', name: 'Login', component: Login, meta: { requiresAuth: false, title: '登录' } },
  // 法律文档：注册前的协议勾选与页脚链接都要能落到真实页面，故必须公开可访问
  { path: '/legal/:slug', name: 'Legal', component: Legal, meta: { requiresAuth: false, title: '法律条款' } },
  { path: '/projects', name: 'Projects', component: CloudSpace, meta: { requiresAuth: true, title: '项目空间' } },
  { path: '/workspace/:projectId', name: 'CloudWorkspace', component: CloudWorkspace, meta: { requiresAuth: true, title: '工作空间' } },
  { path: '/ops', name: 'Monitor', component: Monitor, meta: { requiresAuth: false, title: '运维监控' } },
  { path: '/tutorials', name: 'Tutorials', component: Tutorials, meta: { requiresAuth: false, title: '开发教程' } },
  { path: '/tutorials/:slug', name: 'TutorialDetail', component: Tutorials, meta: { requiresAuth: false, title: '教程详情' } },
  { path: '/:pathMatch(.*)*', redirect: '/projects' }
]

/**
 * 已登录时不再展示的公开页。
 *
 * 落地页与登录页对已登录用户都没有意义：前者是给未登录访客看的介绍，
 * 后者会立刻把用户送回项目空间。统一在此收口，避免两处各写一遍判断。
 */
const PUBLIC_ENTRY_PAGES = new Set(['Landing', 'Login'])

const router = createRouter({
  history: createWebHistory(),
  routes
})

router.beforeEach((to, from, next) => {
  document.title = to.meta.title ? `${to.meta.title} - LabexAgent` : 'LabexAgent'
  const userStore = useUserStore()
  if (!to.meta.requiresAuth) {
    if (PUBLIC_ENTRY_PAGES.has(to.name) && userStore.isLoggedIn) {
      next('/projects')
      return
    }
    next()
    return
  }
  if (!userStore.isLoggedIn) {
    ElMessage.warning('请先登录')
    next('/')
    return
  }
  next()
})

export default router
