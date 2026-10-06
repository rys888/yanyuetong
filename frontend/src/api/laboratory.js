import request from './request.js'

export function fetchLaboratoryList(params) {
  return request({ method: 'get', url: '/laboratory/list', params })
}

export function fetchLaboratoryById(id) {
  return request({ method: 'get', url: `/laboratory/${id}` })
}
