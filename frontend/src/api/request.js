import { createRequest } from './createRequest.js'
import { useUserStore } from '../stores/user.js'

let unauthorizedHandler = () => {}

export function setUnauthorizedHandler(fn) {
  unauthorizedHandler = typeof fn === 'function' ? fn : () => {}
}

const baseRequest = createRequest({
  baseURL: import.meta.env?.VITE_API_BASE || '/api',
  timeout: 10000,
  getToken: () => useUserStore().token,
})

async function request(config) {
  const result = await baseRequest(config)

  if (result.kind === 'unauthorized') {
    try {
      unauthorizedHandler()
    } catch {
      // 401 处理器不能改变请求层“永不抛异常”的契约。
    }
  }

  return result
}

export default request
