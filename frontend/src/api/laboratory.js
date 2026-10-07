import request from './request.js'

export function fetchLaboratoryList(params) {
  return request({ method: 'get', url: '/laboratory/list', params })
}

export function fetchLaboratoryById(id) {
  return request({ method: 'get', url: `/laboratory/${id}` })
}

export function createLaboratory(payload) {
  return request({ method: 'post', url: '/laboratory', data: payload })
}

export function updateLaboratory(payload) {
  return request({ method: 'put', url: '/laboratory', data: payload })
}

export function deleteLaboratory(id) {
  return request({ method: 'delete', url: `/laboratory/${id}` })
}
