import axios from 'axios'

import { parseBigintJson } from './bigintJson.js'
import { normalizeAxiosResult } from './normalize.js'

export function createRequest({ baseURL, timeout = 10000, getToken = () => null } = {}) {
  const client = axios.create({
    baseURL,
    timeout,
    transformResponse: [parseBigintJson],
  })

  return async function request(config = {}) {
    try {
      const requestConfig = config && typeof config === 'object' ? config : {}
      const token = getToken()
      const headers = token
        ? { ...(requestConfig.headers ?? {}), authorization: token }
        : requestConfig.headers
      const response = await client.request({
        ...requestConfig,
        headers,
        transformResponse: [parseBigintJson],
      })

      return normalizeAxiosResult(response)
    } catch (error) {
      return normalizeAxiosResult(error)
    }
  }
}

export default createRequest
