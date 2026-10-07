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

// 学生端可见的批次状态子集，镜像后端 ReservationBatchServiceImpl.queryBatchPage 的闸门
// （非 PUBLISHED/CLOSED 直接 400）与 ReservationBatchMapper.xml 的 publicOnly 条件。
export const BATCH_PUBLIC_STATUSES = ['PUBLISHED', 'CLOSED']

export function buildBatchQuery(query) {
  const laboratoryId = query?.laboratoryId
  const equipmentId = query?.equipmentId
  const status = query?.status

  return stripEmpty({
    current: normalizeCurrent(query?.current),
    laboratoryId:
      laboratoryId != null && isNumericId(String(laboratoryId))
        ? String(laboratoryId)
        : undefined,
    equipmentId:
      equipmentId != null && isNumericId(String(equipmentId))
        ? String(equipmentId)
        : undefined,
    status: BATCH_PUBLIC_STATUSES.includes(status) ? status : undefined,
  })
}

export function hasActiveBatchFilter(params) {
  return ['laboratoryId', 'equipmentId', 'status'].some(
    (key) => params?.[key] !== '' && params?.[key] !== undefined && params?.[key] !== null,
  )
}

export const MANAGE_BATCH_STATUSES = ENUM_VALUES.ReservationBatchStatus

export function buildManageBatchQuery(query) {
  const equipmentId = query?.equipmentId
  const status = query?.status

  return stripEmpty({
    current: normalizeCurrent(query?.current),
    equipmentId:
      equipmentId != null && isNumericId(String(equipmentId))
        ? String(equipmentId)
        : undefined,
    status: MANAGE_BATCH_STATUSES.includes(status) ? status : undefined,
  })
}

export function hasActiveManageBatchFilter(params) {
  return ['equipmentId', 'status'].some(
    (key) => params?.[key] !== '' && params?.[key] !== undefined && params?.[key] !== null,
  )
}

export const RESERVATION_STATUSES = ENUM_VALUES.ReservationStatus

export function buildReservationQuery(query) {
  const status = query?.status

  return stripEmpty({
    current: normalizeCurrent(query?.current),
    status: RESERVATION_STATUSES.includes(status) ? status : undefined,
  })
}

export function buildManageReservationQuery(query) {
  const batchId = query?.batchId
  const status = query?.status

  return stripEmpty({
    current: normalizeCurrent(query?.current),
    batchId:
      batchId != null && isNumericId(String(batchId))
        ? String(batchId)
        : undefined,
    status: RESERVATION_STATUSES.includes(status) ? status : undefined,
  })
}

export function hasActiveManageReservationFilter(params) {
  return ['batchId', 'status'].some(
    (key) => params?.[key] !== '' && params?.[key] !== undefined && params?.[key] !== null,
  )
}

export function buildDlqQuery(query) {
  const status = query?.status

  return stripEmpty({
    current: normalizeCurrent(query?.current),
    status: ENUM_VALUES.ReservationDlqStatus.includes(status) ? status : undefined,
  })
}

export function hasActiveDlqFilter(params) {
  return params?.status !== '' && params?.status !== undefined && params?.status !== null
}
