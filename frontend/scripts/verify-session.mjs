import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'

import { createRequest } from '../src/api/createRequest.js'

const DEFAULT_BACKEND_URL = 'http://127.0.0.1:18081'
const USER_PHONE = '13686869696'

let passCount = 0
let failCount = 0

async function check(name, verification) {
  try {
    await verification()
    passCount += 1
    console.log(`PASS: ${name}`)
  } catch (error) {
    failCount += 1
    console.error(`FAIL: ${name}`)
    console.error(error instanceof Error ? error.stack : error)
  }
}

function assertLocation(actual, expectedPath, expectedRedirect) {
  assert.equal(typeof actual, 'object')
  assert.notEqual(actual, null)
  assert.equal(actual.path, expectedPath)

  if (expectedRedirect === undefined) {
    assert.equal(Object.hasOwn(actual, 'query'), false)
    return
  }

  assert.equal(typeof actual.query, 'object')
  assert.notEqual(actual.query, null)
  assert.equal(actual.query.redirect, expectedRedirect)
}

async function loadGuardModule() {
  try {
    return await import('../src/router/guard.js')
  } catch (error) {
    return { loadError: error }
  }
}

async function verifyNavigationGuard(guard) {
  console.log('\nNavigation guard truth table')

  await check('1. authenticated visitor is redirected away from /login', () => {
    assert.equal(guard.loadError, undefined)
    const result = guard.resolveNavigation({
      to: { path: '/login', fullPath: '/login', meta: { public: true } },
      token: 'token',
      profile: { role: 'LAB_ADMIN' },
    })
    assertLocation(result, '/admin')
  })

  await check('2. other public routes are allowed', () => {
    assert.equal(guard.loadError, undefined)
    const result = guard.resolveNavigation({
      to: { path: '/403', fullPath: '/403', meta: { public: true } },
      token: null,
      profile: null,
    })
    assert.equal(result, true)
  })

  await check('3. missing token redirects to login with the original fullPath', () => {
    assert.equal(guard.loadError, undefined)
    const result = guard.resolveNavigation({
      to: { path: '/admin', fullPath: '/admin?current=1', meta: {} },
      token: null,
      profile: null,
    })
    assertLocation(result, '/login', '/admin?current=1')
  })

  await check('4. token without profile redirects to login', () => {
    assert.equal(guard.loadError, undefined)
    const result = guard.resolveNavigation({
      to: { path: '/home', fullPath: '/home', meta: {} },
      token: 'token',
      profile: null,
    })
    assertLocation(result, '/login', '/home')
  })

  await check('5. disallowed role redirects to /403', () => {
    assert.equal(guard.loadError, undefined)
    const result = guard.resolveNavigation({
      to: {
        path: '/admin',
        fullPath: '/admin',
        meta: { roles: ['LAB_ADMIN'] },
      },
      token: 'token',
      profile: { role: 'USER' },
    })
    assertLocation(result, '/403')
  })

  await check('6. authenticated and authorized navigation is allowed', () => {
    assert.equal(guard.loadError, undefined)
    const result = guard.resolveNavigation({
      to: { path: '/home', fullPath: '/home', meta: { roles: ['USER', 'LAB_ADMIN'] } },
      token: 'token',
      profile: { role: 'USER' },
    })
    assert.equal(result, true)
  })
}

async function verifyRedirectHelpers(guard) {
  console.log('\nRedirect sanitization and defaults')

  const redirectCases = [
    ['/home', '/home'],
    ['/admin?current=1', '/admin?current=1'],
    ['/reservations/645589569487503751', '/reservations/645589569487503751'],
    ['//evil.com', null],
    ['/\\evil.com', null],
    ['https://evil.com', null],
    ['javascript:alert(1)', null],
    ['/login', null],
    ['/login?redirect=/home', null],
  ]

  for (const [raw, expected] of redirectCases) {
    await check(`sanitizeRedirect(${JSON.stringify(raw)})`, () => {
      assert.equal(guard.loadError, undefined)
      assert.equal(guard.sanitizeRedirect(raw), expected)
    })
  }

  await check('sanitizeRedirect rejects empty, null, undefined, and number inputs', () => {
    assert.equal(guard.loadError, undefined)
    assert.equal(guard.sanitizeRedirect(''), null)
    assert.equal(guard.sanitizeRedirect(null), null)
    assert.equal(guard.sanitizeRedirect(undefined), null)
    assert.equal(guard.sanitizeRedirect(123), null)
  })

  await check('defaultRouteFor sends LAB_ADMIN to /admin', () => {
    assert.equal(guard.loadError, undefined)
    assert.equal(guard.defaultRouteFor('LAB_ADMIN'), '/admin')
  })

  await check('defaultRouteFor sends other and missing roles to /home', () => {
    assert.equal(guard.loadError, undefined)
    assert.equal(guard.defaultRouteFor('USER'), '/home')
    assert.equal(guard.defaultRouteFor(null), '/home')
    assert.equal(guard.defaultRouteFor(undefined), '/home')
  })
}

async function verifyProfileFetchPredicate(guard) {
  console.log('\nProfile fetch predicate behavior')

  await check('public /login with token and no profile requires profile fetch', () => {
    assert.equal(guard.loadError, undefined)
    assert.equal(
      guard.needsProfileFetch({
        to: { path: '/login', meta: { public: true } },
        token: 'token',
        profile: null,
      }),
      true,
    )
  })

  await check('token with an existing profile does not require profile fetch', () => {
    assert.equal(guard.loadError, undefined)
    assert.equal(
      guard.needsProfileFetch({
        to: { path: '/home', meta: {} },
        token: 'token',
        profile: { role: 'USER' },
      }),
      false,
    )
  })

  await check('missing token does not require profile fetch', () => {
    assert.equal(guard.loadError, undefined)
    assert.equal(
      guard.needsProfileFetch({
        to: { path: '/home', meta: {} },
        token: null,
        profile: null,
      }),
      false,
    )
  })

  await check('other public route with token and no profile requires profile fetch', () => {
    assert.equal(guard.loadError, undefined)
    assert.equal(
      guard.needsProfileFetch({
        to: { path: '/403', meta: { public: true } },
        token: 'token',
        profile: null,
      }),
      true,
    )
  })
}

async function verifyRouterWiringText() {
  console.log('\nRouter wiring — STATIC TEXT CHECK (not behavior verification)')

  await check('beforeEach delegates profile-fetch policy without a public-route gate', async () => {
    const routerSource = await readFile(new URL('../src/router/index.js', import.meta.url), 'utf8')
    const beforeEachStart = routerSource.indexOf('router.beforeEach(')
    const beforeEachEnd = routerSource.indexOf('\n\nexport default router', beforeEachStart)

    assert.notEqual(beforeEachStart, -1)
    assert.notEqual(beforeEachEnd, -1)

    // router/index.js imports .vue files, so this only locks the wiring text;
    // needsProfileFetch behavior is verified separately against the real pure function above.
    const beforeEachSource = routerSource.slice(beforeEachStart, beforeEachEnd)
    assert.match(beforeEachSource, /needsProfileFetch\s*\(/)
    assert.doesNotMatch(beforeEachSource, /to\.meta\.public/)
  })
}

async function verifyRequestImport() {
  console.log('\nStatic smoke')

  await check('api/request.js imports in plain Node without throwing', async () => {
    const module = await import('../src/api/request.js')
    assert.equal(typeof module.default, 'function')
    assert.equal(typeof module.setUnauthorizedHandler, 'function')
  })
}

async function verifyBackendSession() {
  console.log('\nReal backend session contract')

  const configuredBackend = process.env.VERIFY_BACKEND
  const verifyCode = process.env.VERIFY_CODE
  const backendURL = (configuredBackend || DEFAULT_BACKEND_URL).replace(/\/$/, '')
  if (!configuredBackend) {
    console.log('SKIP: VERIFY_BACKEND is not set — real backend session checks not run')
    if (!verifyCode) {
      console.log('SKIP: 3 code-required checks not run — set VERIFY_CODE to enable')
    }
    return
  }

  const publicRequest = createRequest({ baseURL: backendURL, timeout: 3000 })
  const probe = await publicRequest({ method: 'get', url: '/user/me' })
  if (probe.kind === 'network') {
    console.log(`SKIP: backend unreachable at ${backendURL}`)
    if (!verifyCode) {
      console.log('SKIP: 3 code-required checks not run — set VERIFY_CODE to enable')
    }
    return
  }

  if (!verifyCode) {
    console.log('SKIP: 3 code-required checks not run — set VERIFY_CODE to enable')
    return
  }

  let token = null

  await check('backend login returns a bare token string', async () => {
    const result = await publicRequest({
      method: 'post',
      url: '/user/login',
      data: { phone: USER_PHONE, code: verifyCode },
    })
    assert.equal(result.kind, 'ok')
    assert.equal(result.ok, true)
    assert.equal(result.status, 200)
    assert.equal(typeof result.data, 'string')
    assert.match(result.data, /^[0-9a-f]{32}$/)
    token = result.data
  })

  const tokenRequest = createRequest({
    baseURL: backendURL,
    timeout: 3000,
    getToken: () => token,
  })

  await check('backend /user/me returns the authenticated USER profile', async () => {
    assert.equal(typeof token, 'string')
    const result = await tokenRequest({ method: 'get', url: '/user/me' })
    assert.equal(result.kind, 'ok')
    assert.equal(result.ok, true)
    assert.equal(result.status, 200)
    // These identity assertions intentionally depend on the existing USER seed row.
    assert.equal(result.data.id, 1)
    assert.equal(result.data.nickName, '小鱼同学')
    // D-12: the API returns this non-empty legacy path, but it is not a usable image resource;
    // the F2 UI deliberately renders a text avatar from nickName instead.
    assert.equal(typeof result.data.icon, 'string')
    assert.notEqual(result.data.icon, '')
    assert.equal(result.data.icon, '/imgs/blogs/blog1.jpg')
    assert.equal(result.data.role, 'USER')
  })

  await check('backend logout invalidates the old token immediately', async () => {
    assert.equal(typeof token, 'string')
    const logoutResult = await tokenRequest({ method: 'post', url: '/user/logout' })
    assert.equal(logoutResult.kind, 'ok')
    assert.equal(logoutResult.ok, true)
    assert.equal(logoutResult.status, 200)

    const staleTokenResult = await tokenRequest({ method: 'get', url: '/user/me' })
    assert.equal(staleTokenResult.kind, 'unauthorized')
    assert.equal(staleTokenResult.ok, false)
    assert.equal(staleTokenResult.status, 401)
  })
}

async function main() {
  const guard = await loadGuardModule()
  await verifyNavigationGuard(guard)
  await verifyRedirectHelpers(guard)
  await verifyProfileFetchPredicate(guard)
  await verifyRouterWiringText()
  await verifyRequestImport()
  await verifyBackendSession()

  console.log(`\nSummary: ${passCount} PASS, ${failCount} FAIL`)
  if (failCount > 0) {
    process.exitCode = 1
  }
}

main().catch((error) => {
  console.error('FAIL: verification script crashed')
  console.error(error instanceof Error ? error.stack : error)
  process.exitCode = 1
})
