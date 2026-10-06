import { defineStore } from 'pinia'
import { ElMessage } from 'element-plus'

import {
  fetchMe as fetchMeApi,
  login as loginApi,
  logout as logoutApi,
} from '../api/user.js'

export const TOKEN_STORAGE_KEY = 'yanyuetong.token'

function readStoredToken() {
  try {
    return globalThis.localStorage?.getItem(TOKEN_STORAGE_KEY) || null
  } catch {
    return null
  }
}

function persistToken(token) {
  try {
    globalThis.localStorage?.setItem(TOKEN_STORAGE_KEY, token)
  } catch {
    // 存储不可用时保留内存会话，刷新后重新登录即可。
  }
}

function removeStoredToken() {
  try {
    globalThis.localStorage?.removeItem(TOKEN_STORAGE_KEY)
  } catch {
    // 禁用存储不应阻止退出登录或页面渲染。
  }
}

export const useUserStore = defineStore('user', {
  state: () => ({
    token: readStoredToken(),
    profile: null,
  }),

  getters: {
    isLogin: (state) => Boolean(state.token),
    isAdmin: (state) => state.profile?.role === 'LAB_ADMIN',
  },

  actions: {
    async login({ phone, code }) {
      const result = await loginApi({ phone, code })
      if (result.kind === 'ok') {
        this.token = result.data
        persistToken(result.data)
      }
      return result
    },

    async fetchMe() {
      const result = await fetchMeApi()
      if (result.kind === 'ok') {
        this.profile = result.data
      } else {
        this.reset()
        if (result.kind === 'network') {
          ElMessage.error('网络异常，请检查网络后重试')
        }
      }
      return result
    },

    async logout() {
      let result
      try {
        // 必须先带 token 请求后端；先清本地会让 Redis 会话残留 30 分钟。
        result = await logoutApi()
      } finally {
        this.reset()
      }
      return result
    },

    reset() {
      this.token = null
      this.profile = null
      removeStoredToken()
    },
  },
})
