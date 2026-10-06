export function defaultRouteFor(role) {
  return role === 'LAB_ADMIN' ? '/admin' : '/home'
}

export function sanitizeRedirect(raw) {
  if (typeof raw !== 'string' || !raw.startsWith('/')) {
    return null
  }

  if (raw.startsWith('//') || raw.startsWith('/\\')) {
    return null
  }

  const path = raw.split('?')[0]
  if (path === '/login') {
    return null
  }

  return raw
}

export function needsProfileFetch({ to, token, profile }) {
  return Boolean(token) && !profile
}

export function resolveNavigation({ to, token, profile }) {
  const meta = to.meta ?? {}

  if (meta.public === true && to.path === '/login' && token && profile) {
    return { path: defaultRouteFor(profile.role) }
  }

  if (meta.public === true) {
    return true
  }

  if (!token) {
    return { path: '/login', query: { redirect: to.fullPath } }
  }

  if (!profile) {
    return { path: '/login', query: { redirect: to.fullPath } }
  }

  if (meta.roles && !meta.roles.includes(profile.role)) {
    return { path: '/403' }
  }

  return true
}
