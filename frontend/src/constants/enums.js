export const ENUM_VALUES = {
  EquipmentStatus: ['AVAILABLE', 'MAINTENANCE', 'DISABLED'],
  LaboratoryStatus: ['OPEN', 'CLOSED', 'MAINTENANCE'],
  ReservationBatchStatus: ['DRAFT', 'PUBLISHED', 'CLOSED', 'CANCELLED'],
  BookingState: ['DRAFT', 'NOT_STARTED', 'OPEN', 'EXPIRED', 'CLOSED', 'CANCELLED'],
  ReservationDlqStatus: ['NEW', 'REPLAYED', 'DISCARDED'],
  ReservationStatus: ['RESERVED', 'CANCELLED', 'COMPLETED', 'NO_SHOW'],
  UserRole: ['USER', 'LAB_ADMIN'],
}
