import request from './request.js'

export function fetchReservationBatchPage(params) {
  return request({ method: 'get', url: '/reservation-batch/page', params })
}

export function fetchReservationBatchById(id) {
  return request({ method: 'get', url: `/reservation-batch/${id}` })
}

export function fetchManageBatchPage(params) {
  return request({ method: 'get', url: '/reservation-batch/manage/page', params })
}

export function createReservationBatch(payload) {
  return request({ method: 'post', url: '/reservation-batch', data: payload })
}

export function updateReservationBatch(payload) {
  return request({ method: 'put', url: '/reservation-batch', data: payload })
}

export function publishReservationBatch(id) {
  return request({ method: 'post', url: `/reservation-batch/${id}/publish` })
}

export function closeReservationBatch(id) {
  return request({ method: 'post', url: `/reservation-batch/${id}/close` })
}

export function cancelReservationBatch(id) {
  return request({ method: 'post', url: `/reservation-batch/${id}/cancel` })
}
