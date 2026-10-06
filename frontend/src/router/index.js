import { createRouter, createWebHistory } from 'vue-router'

import { useUserStore } from '../stores/user.js'
import { needsProfileFetch, resolveNavigation } from './guard.js'
import { routes } from './routes.js'

const router = createRouter({
  history: createWebHistory(import.meta.env.BASE_URL),
  routes,
})

// 前端权限只是 UI 控制，真正的安全边界是后端的 RoleInterceptor + 服务层归属校验；前端隐藏入口不等于禁止访问。
router.beforeEach(async (to) => {
  const store = useUserStore()
  const token = store.token
  const profile = store.profile
  if (needsProfileFetch({ to, token, profile })) {
    await store.fetchMe()
  }

  return resolveNavigation({
    to: { path: to.path, fullPath: to.fullPath, meta: to.meta },
    token: store.token,
    profile: store.profile,
  })
})

export default router
