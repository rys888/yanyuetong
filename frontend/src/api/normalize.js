const NETWORK_MESSAGE = '网络连接失败，请检查网络后重试'

function failure(kind, message, status) {
  return {
    ok: false,
    kind,
    data: null,
    total: null,
    message: typeof message === 'string' ? message : String(message ?? ''),
    status,
  }
}

function isResultBody(body) {
  return body !== null && typeof body === 'object' && typeof body.success === 'boolean'
}

function hasErrorMessage(body) {
  return body !== null && typeof body === 'object' && Object.hasOwn(body, 'errorMsg')
}

export function normalizeAxiosResult(input) {
  try {
    const response =
      input?.response ??
      (typeof input?.status === 'number' && Object.hasOwn(input, 'data') ? input : null)

    if (!response) {
      return failure('network', NETWORK_MESSAGE, null)
    }

    const status = response.status
    const body = response.data

    if (status === 401) {
      return failure('unauthorized', '登录已失效，请重新登录', status)
    }

    if (status === 403 && hasErrorMessage(body)) {
      return failure('denied', body.errorMsg, status)
    }

    if (status === 403) {
      return failure('forbidden', '无权限执行该操作', status)
    }

    const isSuccessfulStatus = status >= 200 && status < 300

    if (!isSuccessfulStatus && isResultBody(body)) {
      return failure('error', body.errorMsg, status)
    }

    if (!isSuccessfulStatus) {
      return failure('clientError', '请求参数不合法', status)
    }

    if (isResultBody(body) && body.success === true) {
      return {
        ok: true,
        kind: 'ok',
        data: 'data' in body ? body.data : null,
        total: body.total ?? null,
        message: '',
        status,
      }
    }

    if (isResultBody(body) && body.success === false) {
      return failure('business', body.errorMsg, status)
    }

    return failure('clientError', '响应格式不符合预期', status)
  } catch {
    return failure('clientError', '响应格式不符合预期', null)
  }
}

export default normalizeAxiosResult
