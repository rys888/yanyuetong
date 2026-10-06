import { createRouter, createWebHistory } from 'vue-router'

import { useUserStore } from '../stores/user.js'
import { needsProfileFetch, resolveNavigation } from './guard.js'

const router = createRouter({
  history: createWebHistory(import.meta.env.BASE_URL),
  routes: [
    {
      path: '/login',
      name: 'login',
      component: () => import('../views/login/LoginView.vue'),
      meta: { public: true, title: '登录' },
    },
    {
      path: '/',
      component: () => import('../layouts/AppLayout.vue'),
      redirect: '/home',
      meta: { roles: ['USER', 'LAB_ADMIN'] },
      children: [
        {
          path: 'home',
          name: 'home',
          component: () => import('../views/home/HomeView.vue'),
          meta: { title: '首页', menu: { title: '首页', icon: 'HomeFilled', order: 1 } },
        },
      ],
    },
    {
      path: '/admin',
      component: () => import('../layouts/AdminLayout.vue'),
      meta: { roles: ['LAB_ADMIN'] },
      children: [
        {
          path: '',
          name: 'admin-home',
          component: () => import('../views/admin/AdminHomeView.vue'),
          meta: {
            title: '管理首页',
            menu: { title: '管理首页', icon: 'Odometer', order: 1 },
          },
        },
      ],
    },
    {
      path: '/403',
      name: 'forbidden',
      component: () => import('../views/error/ForbiddenView.vue'),
      meta: { public: true, title: '无权访问' },
    },
    {
      path: '/:pathMatch(.*)*',
      name: 'not-found',
      component: () => import('../views/error/NotFoundView.vue'),
      meta: { public: true, title: '页面不存在' },
    },
  ],
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
