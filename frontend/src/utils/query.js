import { ENUM_VALUES } from '../constants/enums.js'
import { normalizeCurrent } from '../constants/pagination.js'

export function isNumericId(raw) {
  return /^\d{1,18}$/.test(raw)
}

export function stripEmpty(source) {
  return Object.fromEntries(
    Object.entries(source).filter(
      ([, value]) => value !== '' && value !== undefined && value !== null,
    ),
  )
}

export function buildLaboratoryQuery(query) {
  return { current: normalizeCurrent(query?.current) }
}

export function buildEquipmentQuery(query) {
  const laboratoryId = query?.laboratoryId
  const categoryId = query?.categoryId
  const name = query?.name == null ? '' : String(query.name).trim()
  const status = query?.status

  return stripEmpty({
    current: normalizeCurrent(query?.current),
    laboratoryId:
      laboratoryId != null && isNumericId(String(laboratoryId))
        ? String(laboratoryId)
        : undefined,
    categoryId:
      categoryId != null && isNumericId(String(categoryId))
        ? String(categoryId)
        : undefined,
    name,
    status: ENUM_VALUES.EquipmentStatus.includes(status) ? status : undefined,
  })
}

export function hasActiveFilter(params) {
  return ['laboratoryId', 'categoryId', 'name', 'status'].some(
    (key) => params?.[key] !== '' && params?.[key] !== undefined && params?.[key] !== null,
  )
}
