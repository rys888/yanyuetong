import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import http from 'node:http'
import { fileURLToPath } from 'node:url'

import axios from 'axios'
import { createPinia, setActivePinia } from 'pinia'
import { createServer as createViteServer } from 'vite'

import * as queryModule from '../src/utils/query.js'

process.env.NO_PROXY = '*'
process.env.no_proxy = '*'

const FRONTEND_ROOT = fileURLToPath(new URL('../', import.meta.url))
const RESERVATION_API_PATH = fileURLToPath(new URL('../src/api/reservation.js', import.meta.url))
const BATCH_DETAIL_PATH = fileURLToPath(
  new URL('../src/views/batch/BatchDetailView.vue', import.meta.url),
)
const SUBMIT_RESULT_PATH = fileURLToPath(
  new URL('../src/views/batch/SubmitResult.vue', import.meta.url),
)
const RESERVATION_ID = '645589569487503751'
const TOKEN = 'fixture-reservation-token'

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

function readBody(request) {
  return new Promise((resolve, reject) => {
    let body = ''
    request.setEncoding('utf8')
    request.on('data', (chunk) => {
      body += chunk
    })
    request.on('end', () => resolve(body))
    request.on('error', reject)
  })
}

function sendJson(response, status, body) {
  response.writeHead(status, { 'content-type': 'application/json; charset=utf-8' })
  response.end(body)
}

function createFixtureServer(capturedRequests) {
  return http.createServer(async (request, response) => {
    const url = new URL(request.url, 'http://fixture.local')
    const body = await readBody(request)
    capturedRequests.push({
      method: request.method,
      url: `${url.pathname}${url.search}`,
      body,
      headers: request.headers,
    })

    if (request.method === 'POST' && url.pathname === '/reservation') {
      sendJson(response, 200, `{"success":true,"data":${RESERVATION_ID}}`)
      return
    }

    if (request.method === 'GET' && url.pathname === `/reservation/${RESERVATION_ID}`) {
      sendJson(
        response,
        200,
        `{"success":true,"data":{"id":${RESERVATION_ID},"status":"RESERVED"}}`,
      )
      return
    }

    if (request.method === 'GET' && url.pathname === '/reservation/page') {
      sendJson(response, 200, '{"success":true,"data":[],"total":0}')
      return
    }

    sendJson(response, 404, '{"status":404,"error":"Not Found"}')
  })
}

function createFixtureAdapter(capturedRequests) {
  return async (config) => {
    const resolvedUrl = new URL(axios.getUri(config), 'http://fixture.local')
    const headers = Object.fromEntries(
      Object.entries(config.headers?.toJSON?.() ?? config.headers ?? {}).map(([key, value]) => [
        key.toLowerCase(),
        value,
      ]),
    )
    capturedRequests.push({
      method: config.method?.toUpperCase(),
      url: `${resolvedUrl.pathname}${resolvedUrl.search}`,
      body: config.data ?? '',
      headers,
    })

    let data
    if (config.method === 'post' && config.url === '/reservation') {
      data = `{"success":true,"data":${RESERVATION_ID}}`
    } else if (config.method === 'get' && config.url === `/reservation/${RESERVATION_ID}`) {
      data = `{"success":true,"data":{"id":${RESERVATION_ID},"status":"RESERVED"}}`
    } else if (config.method === 'get' && config.url === '/reservation/page') {
      data = '{"success":true,"data":[],"total":0}'
    } else {
      data = '{"status":404,"error":"Not Found"}'
    }

    return {
      data,
      status: data.includes('Not Found') ? 404 : 200,
      statusText: data.includes('Not Found') ? 'Not Found' : 'OK',
      headers: { 'content-type': 'application/json; charset=utf-8' },
      config,
      request: {},
    }
  }
}

let reservationModule
let reservationImportError

async function loadReservationModule() {
  if (reservationModule || reservationImportError) return

  try {
    reservationModule = await import('../src/api/reservation.js')
  } catch (error) {
    reservationImportError = error
  }
}

function requireReservationExport(name) {
  if (reservationImportError) {
    throw new Error(
      `导入 frontend/src/api/reservation.js 失败：${reservationImportError instanceof Error ? reservationImportError.message : String(reservationImportError)}`,
      { cause: reservationImportError },
    )
  }

  const exported = reservationModule?.[name]
  if (typeof exported !== 'function') {
    throw new TypeError(`frontend/src/api/reservation.js 未导出函数 ${name}`)
  }
  return exported
}

async function verifySubmitFailureClassification() {
  console.log('\nSubmit failure classification')
  await loadReservationModule()

  await check('503 messages have one exported API source of truth', async () => {
    assert.equal(reservationModule?.RETRYABLE_503_MESSAGE, '预约失败，请重试')
    assert.equal(reservationModule?.PENDING_503_MESSAGE, '预约结果待确认，请稍后查询')

    const apiSource = await readFile(RESERVATION_API_PATH, 'utf8')
    const detailSource = await readFile(BATCH_DETAIL_PATH, 'utf8')
    assert.equal((apiSource.match(/预约失败，请重试/g) ?? []).length, 1)
    assert.equal((apiSource.match(/预约结果待确认，请稍后查询/g) ?? []).length, 1)
    assert.doesNotMatch(detailSource, /预约失败，请重试|预约结果待确认，请稍后查询/)
  })

  const cases = [
    {
      name: '400 preserves the backend Chinese message',
      result: { kind: 'error', status: 400, message: '你已预约过该批次' },
      expected: { title: '预约失败', message: '你已预约过该批次', action: 'none' },
    },
    {
      name: 'known retryable 503 exposes retry',
      result: { kind: 'error', status: 503, message: '预约失败，请重试' },
      expected: { title: '预约失败', message: '预约失败，请重试', action: 'retry' },
    },
    {
      name: 'known uncertain 503 directs to my reservations',
      result: { kind: 'error', status: 503, message: '预约结果待确认，请稍后查询' },
      expected: {
        title: '预约结果待确认',
        message: '预约结果待确认，请稍后查询',
        action: 'mine',
      },
    },
    {
      name: 'unknown 503 conservatively directs to my reservations',
      result: { kind: 'error', status: 503, message: '上游返回了新的提示' },
      expected: { title: '预约结果待确认', message: '上游返回了新的提示', action: 'mine' },
    },
    {
      name: 'other error status has no action',
      result: { kind: 'error', status: 500, message: '服务处理失败' },
      expected: { title: '预约失败', message: '服务处理失败', action: 'none' },
    },
    {
      name: 'network failure exposes retry',
      result: { kind: 'network', status: null, message: '网络连接失败，请检查网络后重试' },
      expected: {
        title: '网络异常',
        message: '网络连接失败，请检查网络后重试',
        action: 'retry',
      },
    },
    {
      name: 'denied has no action',
      result: { kind: 'denied', status: 403, message: '无权提交预约' },
      expected: { title: '无权提交预约', message: '无权提交预约', action: 'none' },
    },
    {
      name: 'forbidden has no action',
      result: { kind: 'forbidden', status: 403, message: '无权限执行该操作' },
      expected: { title: '无权提交预约', message: '无权限执行该操作', action: 'none' },
    },
    {
      name: 'business failure has no action',
      result: { kind: 'business', status: 200, message: '预约失败' },
      expected: { title: '预约失败', message: '预约失败', action: 'none' },
    },
    {
      name: 'client error has no action',
      result: { kind: 'clientError', status: 400, message: '请求参数不合法' },
      expected: { title: '请求异常', message: '请求参数不合法', action: 'none' },
    },
    {
      name: 'ok is not a failure',
      result: { kind: 'ok', status: 200, message: '' },
      expected: null,
    },
  ]

  for (const testCase of cases) {
    await check(testCase.name, () => {
      const classifySubmitFailure = requireReservationExport('classifySubmitFailure')
      assert.deepEqual(classifySubmitFailure(testCase.result), testCase.expected)
    })
  }
}

async function verifyPollingClassification() {
  console.log('\nPolling classification')
  await loadReservationModule()

  await check('denied continues polling', () => {
    const shouldContinuePolling = requireReservationExport('shouldContinuePolling')
    assert.equal(shouldContinuePolling({ kind: 'denied' }), true)
  })

  await check('network continues polling', () => {
    const shouldContinuePolling = requireReservationExport('shouldContinuePolling')
    assert.equal(shouldContinuePolling({ kind: 'network' }), true)
  })

  await check('every other result stops polling', () => {
    const shouldContinuePolling = requireReservationExport('shouldContinuePolling')
    for (const kind of [
      'ok',
      'error',
      'business',
      'clientError',
      'forbidden',
      'unauthorized',
      undefined,
    ]) {
      assert.equal(shouldContinuePolling({ kind }), false, `kind=${String(kind)}`)
    }
  })

  await check('an ok polling result without data uses the pending exit', async () => {
    const source = await readFile(BATCH_DETAIL_PATH, 'utf8')
    assert.match(
      source,
      /if\s*\(\s*result\.kind\s*===\s*['"]ok['"]\s*\)\s*\{\s*if\s*\(\s*!result\.data\s*\)\s*\{\s*finishAsPending\(sequence\)/s,
    )
  })

  await check('done result keeps the exact reservation id visible', async () => {
    const source = await readFile(SUBMIT_RESULT_PATH, 'utf8')
    const doneBranch = source.match(
      /<template\s+v-else-if="phase === 'done' && reservation">([\s\S]*?)<template\s+v-else-if="phase === 'pending'">/,
    )?.[1]
    assert.match(
      doneBranch ?? '',
      /<el-descriptions\s+:column="1"\s+border>\s*<el-descriptions-item\s+label="预约编号">\s*\{\{\s*reservationId\s*\}\}\s*<\/el-descriptions-item>/,
    )
  })
}

async function verifyReservationQuery() {
  console.log('\nReservation query')
  const buildReservationQuery = queryModule.buildReservationQuery

  await check('invalid current normalizes to page 1', () => {
    assert.equal(typeof buildReservationQuery, 'function')
    assert.deepEqual(buildReservationQuery({ current: 'invalid' }), { current: 1 })
  })

  await check('invalid status is removed', () => {
    assert.equal(typeof buildReservationQuery, 'function')
    assert.deepEqual(buildReservationQuery({ current: '2', status: 'WAITING' }), { current: 2 })
  })

  await check('every legal reservation status is preserved', () => {
    assert.equal(typeof buildReservationQuery, 'function')
    for (const status of ['RESERVED', 'CANCELLED', 'COMPLETED', 'NO_SHOW']) {
      assert.deepEqual(buildReservationQuery({ current: '3', status }), { current: 3, status })
    }
  })
}

async function verifyReservationRequests() {
  console.log('\nReservation request shapes')
  const capturedRequests = []
  const server = createFixtureServer(capturedRequests)
  let serverListening = false
  let originalAdapter
  let baseURL
  try {
    await listen(server)
    serverListening = true
    const address = server.address()
    baseURL = `http://127.0.0.1:${address.port}`
  } catch (error) {
    if (!['EPERM', 'EACCES'].includes(error?.code)) {
      throw error
    }
    console.log('INFO: local listen unavailable; using in-memory Axios fixture adapter')
    baseURL = 'http://fixture.local'
    originalAdapter = axios.defaults.adapter
    axios.defaults.adapter = createFixtureAdapter(capturedRequests)
  }
  process.env.VITE_API_BASE = baseURL
  globalThis.localStorage = {
    getItem: () => TOKEN,
    setItem: () => {},
    removeItem: () => {},
  }
  setActivePinia(createPinia())

  const viteServer = await createViteServer({
    root: FRONTEND_ROOT,
    appType: 'custom',
    logLevel: 'silent',
    server: { middlewareMode: true },
  })

  try {
    const api = await viteServer.ssrLoadModule('/src/api/reservation.js')
    const submitResult = await api.submitReservation(1013)
    const fetchResult = await api.fetchReservationById(RESERVATION_ID)
    const pageResult = await api.fetchReservationPage({ current: 2 })

    await check('submitReservation sends POST, JSON body, and lowercase authorization', () => {
      assert.equal(submitResult.kind, 'ok')
      assert.equal(submitResult.data, RESERVATION_ID)
      assert.equal(typeof submitResult.data, 'string')
      assert.equal(capturedRequests[0].method, 'POST')
      assert.equal(capturedRequests[0].url, '/reservation')
      assert.deepEqual(JSON.parse(capturedRequests[0].body), { batchId: 1013 })
      assert.equal(capturedRequests[0].headers.authorization, TOKEN)
      assert.match(capturedRequests[0].headers['content-type'] ?? '', /^application\/json\b/)
    })

    await check('fetchReservationById preserves the id string in the GET URL', () => {
      assert.equal(fetchResult.kind, 'ok')
      assert.equal(fetchResult.data.id, RESERVATION_ID)
      assert.equal(capturedRequests[1].method, 'GET')
      assert.equal(capturedRequests[1].url, `/reservation/${RESERVATION_ID}`)
      assert.equal(capturedRequests[1].body, '')
      assert.equal(capturedRequests[1].headers.authorization, TOKEN)
    })

    await check('fetchReservationPage sends only current in the GET query', () => {
      assert.equal(pageResult.kind, 'ok')
      assert.equal(capturedRequests[2].method, 'GET')
      assert.equal(capturedRequests[2].url, '/reservation/page?current=2')
      assert.equal(capturedRequests[2].body, '')
      assert.equal(capturedRequests[2].headers.authorization, TOKEN)
    })
  } finally {
    await viteServer.close()
    if (serverListening) {
      await close(server)
    }
    if (originalAdapter !== undefined) {
      axios.defaults.adapter = originalAdapter
    }
    delete process.env.VITE_API_BASE
    delete globalThis.localStorage
  }
}

async function main() {
  await verifySubmitFailureClassification()
  await verifyPollingClassification()
  await verifyReservationQuery()
  await verifyReservationRequests()

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
