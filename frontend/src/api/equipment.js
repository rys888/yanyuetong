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
