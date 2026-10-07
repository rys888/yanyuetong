import request from './request.js'

export function fetchEquipmentPage(params) {
  return request({ method: 'get', url: '/equipment/page', params })
}

export function fetchEquipmentById(id) {
  return request({ method: 'get', url: `/equipment/${id}` })
}

export function fetchEquipmentCategories() {
  return request({ method: 'get', url: '/equipment-category/list' })
}

export function createEquipment(payload) {
  return request({ method: 'post', url: '/equipment', data: payload })
}

export function updateEquipment(payload) {
  return request({ method: 'put', url: '/equipment', data: payload })
}

export function updateEquipmentStatus(id, status) {
  return request({ method: 'put', url: `/equipment/${id}/status`, params: { status } })
}

export function deleteEquipment(id) {
  return request({ method: 'delete', url: `/equipment/${id}` })
}
