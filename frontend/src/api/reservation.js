import request from './request.js'

// 与后端 ReservationServiceImpl 的 503 抛出点耦合；文案变化时需同步核对提交结果语义。
export const RETRYABLE_503_MESSAGE = '预约失败，请重试'
export const PENDING_503_MESSAGE = '预约结果待确认，请稍后查询'

export function submitReservation(batchId) {
  return request({ method: 'post', url: '/reservation', data: { batchId } })
}

export function fetchReservationById(id) {
  return request({ method: 'get', url: `/reservation/${id}` })
}

export function fetchReservationPage(params) {
  return request({ method: 'get', url: '/reservation/page', params })
}

export function cancelReservation(id) {
  return request({ method: 'post', url: `/reservation/${id}/cancel` })
}

export function classifySubmitFailure(result) {
  if (result.kind === 'ok') {
    return null
  }

  if (result.kind === 'error') {
    if (result.status === 400) {
      return { title: '预约失败', message: result.message, action: 'none' }
    }
    if (result.status === 503 && result.message === RETRYABLE_503_MESSAGE) {
      return { title: '预约失败', message: result.message, action: 'retry' }
    }
    if (result.status === 503 && result.message === PENDING_503_MESSAGE) {
      return { title: '预约结果待确认', message: result.message, action: 'mine' }
    }
    if (result.status === 503) {
      return { title: '预约结果待确认', message: result.message, action: 'mine' }
    }
    return { title: '预约失败', message: result.message, action: 'none' }
  }

  if (result.kind === 'network') {
    return { title: '网络异常', message: result.message, action: 'retry' }
  }
  if (result.kind === 'denied' || result.kind === 'forbidden') {
    return { title: '无权提交预约', message: result.message, action: 'none' }
  }
  if (result.kind === 'business') {
    return { title: '预约失败', message: result.message, action: 'none' }
  }
  if (result.kind === 'clientError') {
    return { title: '请求异常', message: result.message, action: 'none' }
  }

  return null
}

export function shouldContinuePolling(result) {
  return result.kind === 'denied' || result.kind === 'network'
}
