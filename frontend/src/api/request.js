import { createRequest } from './createRequest.js'

const request = createRequest({
  baseURL: import.meta.env.VITE_API_BASE || '/api',
  timeout: 10000,
  // F2 会替换为 Pinia user store 的 token getter。
  getToken: () => null,
})

export default request
