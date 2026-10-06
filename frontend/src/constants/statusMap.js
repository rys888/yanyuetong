export const statusMap = {
  ReservationStatus: {
    RESERVED: { label: '已预约', tag: 'success', actions: ['cancel'] },
    CANCELLED: { label: '已取消', tag: 'info', actions: [] },
    COMPLETED: { label: '已核验', tag: 'primary', actions: [] },
    NO_SHOW: { label: '未到场', tag: 'warning', actions: [] },
  },
  ReservationBatchStatus: {
    DRAFT: { label: '草稿', tag: 'info', actions: ['edit', 'publish', 'cancel'] },
    PUBLISHED: { label: '已发布', tag: 'success', actions: ['close', 'cancel'] },
    CLOSED: { label: '已关闭', tag: 'warning', actions: [] },
    CANCELLED: { label: '已取消', tag: 'info', actions: [] },
  },
  BookingState: {
    DRAFT: { label: '未发布', tag: 'info', actions: [] },
    NOT_STARTED: { label: '未开始预约', tag: 'warning', actions: [] },
    OPEN: { label: '可预约', tag: 'success', actions: ['submit'] },
    EXPIRED: { label: '已截止', tag: 'info', actions: [] },
    CLOSED: { label: '已关闭', tag: 'warning', actions: [] },
    CANCELLED: { label: '已取消', tag: 'info', actions: [] },
  },
  EquipmentStatus: {
    AVAILABLE: { label: '可用', tag: 'success', actions: ['maintain', 'disable'] },
    MAINTENANCE: { label: '维护中', tag: 'warning', actions: ['enable', 'disable'] },
    DISABLED: { label: '已停用', tag: 'info', actions: ['enable'] },
  },
  LaboratoryStatus: {
    OPEN: { label: '开放', tag: 'success', actions: [] },
    CLOSED: { label: '关闭', tag: 'info', actions: [] },
    MAINTENANCE: { label: '维护中', tag: 'warning', actions: [] },
  },
  ReservationDlqStatus: {
    NEW: { label: '待处理', tag: 'danger', actions: ['replay'] },
    REPLAYED: { label: '已重放', tag: 'success', actions: [] },
    DISCARDED: { label: '已丢弃', tag: 'info', actions: [] },
  },
  UserRole: {
    USER: { label: '学生', tag: 'info', actions: [] },
    LAB_ADMIN: { label: '实验室管理员', tag: 'warning', actions: [] },
  },
}

export function resolveStatus(enumName, value) {
  const enumMap = Object.hasOwn(statusMap, enumName) ? statusMap[enumName] : null
  if (enumMap && Object.hasOwn(enumMap, value)) {
    return enumMap[value]
  }

  const originalValue = value == null ? '' : String(value)
  return {
    label: originalValue ? `未知（${originalValue}）` : '未知',
    tag: 'info',
    actions: [],
  }
}
