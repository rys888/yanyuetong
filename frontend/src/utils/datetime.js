import dayjs from 'dayjs'

export function formatDateTime(value) {
  if (value === null || value === undefined || value === '') {
    return '—'
  }

  const date = dayjs(value)
  return date.isValid() ? date.format('YYYY-MM-DD HH:mm:ss') : '—'
}
