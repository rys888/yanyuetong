import assert from 'node:assert/strict'
import http from 'node:http'

import { parseBigintJson } from '../src/api/bigintJson.js'
import { createRequest } from '../src/api/createRequest.js'
import { ENUM_VALUES } from '../src/constants/enums.js'
import { resolveStatus, statusMap } from '../src/constants/statusMap.js'

const BIGINT_ID = '645589569487503751'
const NETWORK_MESSAGE = '网络连接失败，请检查网络后重试'
const TAG_TYPES = new Set(['primary', 'success', 'info', 'warning', 'danger'])

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

function listen(server) {
  return new Promise((resolve, reject) => {
    server.once('error', reject)
    server.listen(0, '127.0.0.1', () => {
      server.off('error', reject)
      resolve()
    })
  })
}

function close(server) {
  return new Promise((resolve, reject) => {
    server.close((error) => (error ? reject(error) : resolve()))
  })
}

function sendJson(response, status, body) {
  response.writeHead(status, { 'content-type': 'application/json; charset=utf-8' })
  response.end(body)
}

function createFixtureServer() {
  return http.createServer((request, response) => {
    const path = new URL(request.url, 'http://fixture.local').pathname

    if (path === '/ok') {
      const authorization = JSON.stringify(request.headers.authorization ?? null)
      sendJson(
        response,
        200,
        `{"success":true,"data":{"id":${BIGINT_ID},"small":1013,"pageSize":10999,"zero":0,"authorization":${authorization},"date":"2026-10-06","nullable":null},"total":0}`,
      )
      return
    }

    if (path === '/business') {
      sendJson(response, 200, '{"success":false,"errorMsg":"手机号格式错误！"}')
      return
    }

    if (path === '/error') {
      sendJson(response, 500, '{"success":false,"errorMsg":"服务处理失败"}')
      return
    }

    if (path === '/unauthorized') {
      response.writeHead(401)
      response.end()
      return
    }

    if (path === '/forbidden') {
      response.writeHead(403)
      response.end()
      return
    }

    if (path === '/denied') {
      sendJson(response, 403, '{"success":false,"errorMsg":"该实验室不归当前管理员管理"}')
      return
    }

    if (path === '/malformed-success') {
      sendJson(response, 200, '{"message":"not a Result body"}')
      return
    }

    sendJson(
      response,
      404,
      '{"timestamp":"2026-10-06T00:00:00.000+00:00","status":404,"error":"Not Found","message":"","path":"/missing"}',
    )
  })
}

const EXPECTED_STATUS_MAP = {
  ReservationStatus: {
    RESERVED: { label: '已预约', tag: 'success', actions: ['cancel'], manageActions: ['verify'] },
    CANCELLED: { label: '已取消', tag: 'info', actions: [], manageActions: [] },
    COMPLETED: { label: '已核验', tag: 'primary', actions: [], manageActions: [] },
    NO_SHOW: { label: '未到场', tag: 'warning', actions: [], manageActions: [] },
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

async function verifyBigintParser() {
  console.log('\nBigint parser')

  await check('18-digit id remains byte-for-byte identical as a string', () => {
    const parsed = parseBigintJson(`{"id":${BIGINT_ID}}`)
    assert.equal(parsed.id, BIGINT_ID)
    assert.equal(typeof parsed.id, 'string')
  })

  await check('native JSON.parse control loses precision', () => {
    const parsed = JSON.parse(`{"id":${BIGINT_ID}}`)
    assert.notEqual(String(parsed.id), BIGINT_ID)
  })

  await check('small numbers, date strings, null, and Chinese messages retain their types', () => {
    const parsed = parseBigintJson(
      '{"small":1013,"pageSize":10999,"zero":0,"total":0,"date":"2026-10-06","nullable":null,"errorMsg":"手机号格式错误！"}',
    )
    assert.equal(parsed.small, 1013)
    assert.equal(typeof parsed.small, 'number')
    assert.equal(parsed.pageSize, 10999)
    assert.equal(typeof parsed.pageSize, 'number')
    assert.equal(parsed.zero, 0)
    assert.equal(parsed.total, 0)
    assert.equal(parsed.date, '2026-10-06')
    assert.equal(parsed.nullable, null)
    assert.equal(parsed.errorMsg, '手机号格式错误！')
  })

  await check('16-digit MAX_SAFE_INTEGER follows json-bigint digit threshold', () => {
    const parsed = parseBigintJson('{"id":9007199254740991}')
    assert.equal(parsed.id, '9007199254740991')
  })

  await check('invalid and empty JSON fall back to the original string', () => {
    assert.equal(parseBigintJson(''), '')
    assert.equal(parseBigintJson('{invalid'), '{invalid')
  })
}

async function verifyFixtureChannels() {
  console.log('\nFixture request channels')
  const server = createFixtureServer()
  await listen(server)
  const address = server.address()
  const baseURL = `http://127.0.0.1:${address.port}`
  const request = createRequest({
    baseURL,
    timeout: 1000,
    getToken: () => 'fixture-token',
  })

  try {
    await check('ok', async () => {
      const result = await request({ method: 'get', url: '/ok' })
      assert.equal(result.kind, 'ok')
      assert.equal(result.ok, true)
      assert.equal(result.message, '')
      assert.equal(result.status, 200)
      assert.equal(result.data.id, BIGINT_ID)
      assert.equal(typeof result.data.id, 'string')
      assert.equal(result.data.small, 1013)
      assert.equal(result.data.pageSize, 10999)
      assert.equal(result.data.zero, 0)
      assert.equal(result.data.authorization, 'fixture-token')
      assert.equal(result.data.date, '2026-10-06')
      assert.equal(result.data.nullable, null)
      assert.equal(result.total, 0)
    })

    await check('business', async () => {
      const result = await request({ method: 'get', url: '/business' })
      assert.deepEqual(result, {
        ok: false,
        kind: 'business',
        data: null,
        total: null,
        message: '手机号格式错误！',
        status: 200,
      })
    })

    await check('error', async () => {
      const result = await request({ method: 'get', url: '/error' })
      assert.deepEqual(result, {
        ok: false,
        kind: 'error',
        data: null,
        total: null,
        message: '服务处理失败',
        status: 500,
      })
    })

    await check('unauthorized', async () => {
      const result = await request({ method: 'get', url: '/unauthorized' })
      assert.deepEqual(result, {
        ok: false,
        kind: 'unauthorized',
        data: null,
        total: null,
        message: '登录已失效，请重新登录',
        status: 401,
      })
    })

    await check('forbidden', async () => {
      const result = await request({ method: 'get', url: '/forbidden' })
      assert.deepEqual(result, {
        ok: false,
        kind: 'forbidden',
        data: null,
        total: null,
        message: '无权限执行该操作',
        status: 403,
      })
    })

    await check('denied', async () => {
      const result = await request({ method: 'get', url: '/denied' })
      assert.deepEqual(result, {
        ok: false,
        kind: 'denied',
        data: null,
        total: null,
        message: '该实验室不归当前管理员管理',
        status: 403,
      })
    })

    await check('clientError', async () => {
      const result = await request({ method: 'get', url: '/missing' })
      assert.deepEqual(result, {
        ok: false,
        kind: 'clientError',
        data: null,
        total: null,
        message: '请求参数不合法',
        status: 404,
      })
    })

    await check('malformed 2xx response is clientError', async () => {
      const result = await request({ method: 'get', url: '/malformed-success' })
      assert.deepEqual(result, {
        ok: false,
        kind: 'clientError',
        data: null,
        total: null,
        message: '响应格式不符合预期',
        status: 200,
      })
    })
  } finally {
    await close(server)
  }

  await check('network', async () => {
    const result = await request({ method: 'get', url: '/after-close' })
    assert.deepEqual(result, {
      ok: false,
      kind: 'network',
      data: null,
      total: null,
      message: NETWORK_MESSAGE,
      status: null,
    })
  })
}

async function verifyStatusMap() {
  console.log('\nStatus map')

  await check('statusMap exactly matches the approved labels, tags, and actions', () => {
    assert.deepEqual(statusMap, EXPECTED_STATUS_MAP)
  })

  await check('all 7 enums and all 25 values have complete render metadata', () => {
    assert.equal(Object.keys(ENUM_VALUES).length, 7)
    assert.equal(
      Object.values(ENUM_VALUES).reduce((count, values) => count + values.length, 0),
      25,
    )

    for (const [enumName, values] of Object.entries(ENUM_VALUES)) {
      for (const value of values) {
        const resolved = resolveStatus(enumName, value)
        assert.equal(typeof resolved.label, 'string')
        assert.notEqual(resolved.label, '')
        assert.equal(TAG_TYPES.has(resolved.tag), true)
        assert.equal(Array.isArray(resolved.actions), true)
      }
    }
  })

  await check('unknown enum and value fall back without rendering blank text', () => {
    assert.deepEqual(resolveStatus('ReservationStatus', 'FUTURE_STATE'), {
      label: '未知（FUTURE_STATE）',
      tag: 'info',
      actions: [],
    })
    assert.deepEqual(resolveStatus('FutureEnum', 'FUTURE_STATE'), {
      label: '未知（FUTURE_STATE）',
      tag: 'info',
      actions: [],
    })
  })

  await check('empty status falls back to 未知', () => {
    assert.deepEqual(resolveStatus('ReservationStatus', ''), {
      label: '未知',
      tag: 'info',
      actions: [],
    })
  })
}

async function verifyBackend() {
  const backendURL = (process.env.VERIFY_BACKEND || 'http://127.0.0.1:18081').replace(/\/$/, '')
  const request = createRequest({ baseURL: backendURL, timeout: 3000 })
  const verifyToken = process.env.VERIFY_TOKEN

  console.log('\nReal backend')
  if (!verifyToken) {
    console.log(
      'SKIP: 3 token-required checks (forbidden / denied / clientError) not run — set VERIFY_TOKEN to enable',
    )
  }

  const unauthorizedProbe = await request({ method: 'get', url: '/user/me' })
  if (unauthorizedProbe.kind === 'network') {
    console.log(`SKIP: backend unreachable at ${backendURL}`)
    return
  }

  await check('backend ok: POST /user/code with a valid phone', async () => {
    const result = await request({
      method: 'post',
      url: '/user/code',
      params: { phone: '13686869696' },
    })
    assert.equal(result.kind, 'ok')
    assert.equal(result.message, '')
    assert.equal(result.status, 200)
  })

  await check('backend business: POST /user/code with an invalid phone', async () => {
    const result = await request({ method: 'post', url: '/user/code', params: { phone: 'abc' } })
    assert.equal(result.kind, 'business')
    assert.equal(result.message, '手机号格式错误！')
    assert.equal(result.status, 200)
  })

  await check('backend unauthorized: GET /user/me without token', () => {
    assert.equal(unauthorizedProbe.kind, 'unauthorized')
    assert.equal(unauthorizedProbe.message, '登录已失效，请重新登录')
    assert.equal(unauthorizedProbe.status, 401)
  })

  await check(
    'backend unauthorized: unmapped path without token hits the login interceptor first',
    async () => {
      const result = await request({ method: 'get', url: '/no-such-endpoint-xyz' })
      // LoginInterceptor 注册在 /**，对兜到 ResourceHttpRequestHandler 的未映射路径同样生效；
      // 未登录时先返回 401 空体，404 无法到达，只有带有效 token 才会得到 T5 的 404。
      assert.equal(result.kind, 'unauthorized')
      assert.equal(result.status, 401)
    },
  )

  if (!verifyToken) {
    return
  }

  const tokenRequest = createRequest({
    baseURL: backendURL,
    timeout: 3000,
    getToken: () => process.env.VERIFY_TOKEN,
  })

  // Token-required checks must remain read-only GET requests because the token may be a real admin token.
  await check('backend token: GET /user/me proves the token is valid', async () => {
    const result = await tokenRequest({ method: 'get', url: '/user/me' })
    assert.equal(result.kind, 'ok')
  })

  await check('backend forbidden: GET /reservation-dlq/page with token', async () => {
    const result = await tokenRequest({ method: 'get', url: '/reservation-dlq/page' })
    assert.equal(result.kind, 'forbidden')
    assert.equal(result.status, 403)
    assert.equal(result.message, '无权限执行该操作')
  })

  await check('backend denied: GET /reservation/999999 with token', async () => {
    const result = await tokenRequest({ method: 'get', url: '/reservation/999999' })
    assert.equal(result.kind, 'denied')
    assert.equal(result.status, 403)
    assert.equal(result.message, '无权查看该预约')
  })

  await check('backend clientError: GET an unmapped path with token', async () => {
    const result = await tokenRequest({ method: 'get', url: '/no-such-endpoint-xyz' })
    assert.equal(result.kind, 'clientError')
    assert.equal(result.message, '请求参数不合法')
    assert.equal(result.status, 404)
  })
}

async function main() {
  await verifyBigintParser()
  await verifyFixtureChannels()
  await verifyStatusMap()
  await verifyBackend()

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
