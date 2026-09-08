import { ref } from 'vue'
import { monitorApi } from '@/api'

export function useMonitorUsers() {
  const overview = ref(null)
  const overviewLoading = ref(false)
  const overviewError = ref('')
  const overviewRange = ref('7d')

  const users = ref([])
  const total = ref(0)
  const page = ref(1)
  const pageSize = ref(20)
  const loading = ref(false)
  const error = ref('')
  const filters = ref({
    keyword: '',
    role: '',
    status: '',
    isOnline: false,
    sortBy: 'lastActiveTime'
  })

  const detailVisible = ref(false)
  const selectedUserId = ref(null)
  const detail = ref(null)
  const detailLoading = ref(false)
  const detailError = ref('')

  const activities = ref([])
  const activitiesTotal = ref(0)
  const activitiesPage = ref(1)
  const activitiesPageSize = ref(20)
  const activitiesLoading = ref(false)
  const activitiesError = ref('')
  const activitiesCategory = ref('ALL')
  const activitiesOnlyErrors = ref(false)

  const acting = ref(false)
  const actionError = ref('')

  async function loadOverview(range) {
    if (range) overviewRange.value = range
    overviewLoading.value = true
    overviewError.value = ''
    try {
      overview.value = await monitorApi.userOverview(overviewRange.value)
    } catch (e) {
      overviewError.value = e?.message || '概况数据加载失败'
      if (e?.status === 401) throw e
    } finally {
      overviewLoading.value = false
    }
  }

  async function loadUsers(overrides = {}) {
    if (typeof overrides.page === 'number') page.value = overrides.page
    if (typeof overrides.pageSize === 'number') pageSize.value = overrides.pageSize
    if (overrides.filters) Object.assign(filters.value, overrides.filters)

    loading.value = true
    error.value = ''
    try {
      const params = {
        page: page.value,
        pageSize: pageSize.value,
        keyword: filters.value.keyword || undefined,
        role: filters.value.role || undefined,
        status: filters.value.status !== '' && filters.value.status != null ? filters.value.status : undefined,
        isOnline: filters.value.isOnline ? true : undefined,
        sortBy: filters.value.sortBy || undefined
      }
      const data = await monitorApi.users(params)
      users.value = data?.list || []
      total.value = data?.total || 0
    } catch (e) {
      error.value = e?.message || '用户列表加载失败'
      if (e?.status === 401) throw e
    } finally {
      loading.value = false
    }
  }

  async function selectUser(userOrId) {
    const id = typeof userOrId === 'object' ? userOrId?.userId : userOrId
    if (!id) return
    selectedUserId.value = id
    detailVisible.value = true
    activitiesPage.value = 1
    activitiesCategory.value = 'ALL'
    activitiesOnlyErrors.value = false
    await Promise.all([loadDetail(id), loadActivities(id)])
  }

  function closeDetail() {
    detailVisible.value = false
    selectedUserId.value = null
    detail.value = null
    activities.value = []
  }

  async function loadDetail(userId = selectedUserId.value) {
    if (!userId) return
    detailLoading.value = true
    detailError.value = ''
    try {
      detail.value = await monitorApi.userDetail(userId)
    } catch (e) {
      detailError.value = e?.message || '用户详情加载失败'
      if (e?.status === 401) throw e
    } finally {
      detailLoading.value = false
    }
  }

  async function loadActivities(userId = selectedUserId.value, overrides = {}) {
    if (!userId) return
    if (typeof overrides.page === 'number') activitiesPage.value = overrides.page
    if (typeof overrides.category === 'string') activitiesCategory.value = overrides.category
    if (typeof overrides.onlyErrors === 'boolean') activitiesOnlyErrors.value = overrides.onlyErrors

    activitiesLoading.value = true
    activitiesError.value = ''
    try {
      const params = {
        page: activitiesPage.value,
        pageSize: activitiesPageSize.value,
        category: activitiesCategory.value === 'ALL' ? undefined : activitiesCategory.value,
        onlyErrors: activitiesOnlyErrors.value
      }
      const data = await monitorApi.userActivities(userId, params)
      activities.value = data?.list || []
      activitiesTotal.value = data?.total || 0
    } catch (e) {
      activitiesError.value = e?.message || '行为轨迹加载失败'
      if (e?.status === 401) throw e
    } finally {
      activitiesLoading.value = false
    }
  }

  async function executeAction(action, reason = '') {
    if (!selectedUserId.value) return false
    acting.value = true
    actionError.value = ''
    try {
      await monitorApi.userAction(selectedUserId.value, action, { reason })
      await Promise.all([
        loadDetail(selectedUserId.value),
        loadUsers(),
        loadActivities(selectedUserId.value)
      ])
      return true
    } catch (e) {
      actionError.value = e?.message || '操作失败'
      if (e?.status === 401) throw e
      return false
    } finally {
      acting.value = false
    }
  }

  return {
    overview,
    overviewLoading,
    overviewError,
    overviewRange,
    users,
    total,
    page,
    pageSize,
    loading,
    error,
    filters,
    detailVisible,
    selectedUserId,
    detail,
    detailLoading,
    detailError,
    activities,
    activitiesTotal,
    activitiesPage,
    activitiesPageSize,
    activitiesLoading,
    activitiesError,
    activitiesCategory,
    activitiesOnlyErrors,
    acting,
    actionError,
    loadOverview,
    loadUsers,
    selectUser,
    closeDetail,
    loadDetail,
    loadActivities,
    executeAction
  }
}
