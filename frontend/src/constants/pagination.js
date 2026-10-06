export const PAGE_SIZE = 5
export const DEFAULT_CURRENT = 1

export function lastPageOf(total) {
  const normalizedTotal = Number(total)
  return normalizedTotal > 0 ? Math.ceil(normalizedTotal / PAGE_SIZE) : DEFAULT_CURRENT
}

export function normalizeCurrent(raw) {
  const current = Number(raw)
  return Number.isInteger(current) && current > 0 ? current : DEFAULT_CURRENT
}
