import request from './request.js'

export function fetchReservationBatchPage(params) {
  return request({ method: 'get', url: '/reservation-batch/page', params })
}

export function fetchReservationBatchById(id) {
  return request({ method: 'get', url: `/reservation-batch/${id}` })
}
