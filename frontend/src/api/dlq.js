import request from './request.js'

export function fetchReservationDlqPage(params) {
  return request({ method: 'get', url: '/reservation-dlq/page', params })
}

export function fetchReservationDlqById(id) {
  return request({ method: 'get', url: `/reservation-dlq/${id}` })
}

export function replayReservationDlq(id) {
  return request({ method: 'post', url: `/reservation-dlq/${id}/replay` })
}
