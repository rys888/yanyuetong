import assert from 'node:assert/strict'
import { access, readFile, readdir } from 'node:fs/promises'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

import * as ElementPlusIcons from '@element-plus/icons-vue'

import { createRequest } from '../src/api/createRequest.js'
import { ENUM_VALUES } from '../src/constants/enums.js'
import { PAGE_SIZE } from '../src/constants/pagination.js'
import { resolveStatus } from '../src/constants/statusMap.js'
import { routes } from '../src/router/routes.js'
import { formatDateTime } from '../src/utils/datetime.js'

process.env.NO_PROXY = '*'
process.env.no_proxy = '*'

const FRONTEND_ROOT = fileURLToPath(new URL('../', import.meta.url))
const SRC_ROOT = path.join(FRONTEND_ROOT, 'src')
const PUBLIC_BATCH_STATUSES = ['PUBLISHED', 'CLOSED']
const LOCAL_DATE_TIME_PATTERN = /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}$/
const DISPLAY_DATE_TIME_PATTERN = /^\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}$/

const groups = {
  A: { expected: 13, pass: 0, fail: 0, skip: 0 },
  B: { expected: 6, pass: 0, fail: 0, skip: 0 },
  C: { expected: 3, pass: 0, fail: 0, skip: 0 },
}

async function check(groupName, name, verification) {
  try {
    await verification()
    groups[groupName].pass += 1
    console.log(`PASS: [${groupName}] ${name}`)
  } catch (error) {
    groups[groupName].fail += 1
    console.error(`FAIL: [${groupName}] ${name}`)
    console.error(error instanceof Error ? error.stack : error)
  }
}

function skipGroup(groupName, reason) {
  const count = groups[groupName].expected
  groups[groupName].skip = count
  console.log(`SKIP: [${groupName}] ${reason}（跳过 ${count} 条）`)
}

async function pathExists(target) {
  try {
    await access(target)
    return true
  } catch {
    return false
  }
}

async function readSource(relativePath) {
  return readFile(path.join(FRONTEND_ROOT, relativePath), 'utf8')
}

async function collectSourceFiles(directory) {
  const entries = await readdir(directory, { withFileTypes: true })
  const files = []

  for (const entry of entries) {
    const absolutePath = path.join(directory, entry.name)
    if (entry.isDirectory()) {
      files.push(...(await collectSourceFiles(absolutePath)))
    } else if (/\.(?:js|vue)$/.test(entry.name)) {
      files.push(absolutePath)
    }
  }

  return files
}

function findChildRoute(routePath) {
  const appRoute = routes.find((route) => route.path === '/')
  return appRoute?.children?.find((route) => route.path === routePath)
}

let queryModule
let queryImportError

async function loadQueryModule() {
  if (queryModule || queryImportError) return

  try {
    queryModule = await import('../src/utils/query.js')
  } catch (error) {
    queryImportError = error
  }
}

function requireQueryExport(exportName, expectedType) {
  if (queryImportError) {
    throw new Error(
      `导入 frontend/src/utils/query.js 失败：${queryImportError instanceof Error ? queryImportError.message : String(queryImportError)}`,
      { cause: queryImportError },
    )
  }

  const exported = queryModule?.[exportName]
  if (expectedType && typeof exported !== expectedType) {
    throw new TypeError(
      `frontend/src/utils/query.js 未导出 ${exportName}（期望类型：${expectedType}）`,
    )
  }
  if (!expectedType && exported === undefined) {
    throw new TypeError(`frontend/src/utils/query.js 未导出 ${exportName}`)
  }
  return exported
}

async function verifyGroupA() {
  console.log('\nA group — pure functions, routes, and source contract')
  await loadQueryModule()

  await check('A', 'public batch status whitelist is exact and covered by the enum', () => {
    const statuses = requireQueryExport('BATCH_PUBLIC_STATUSES')
    assert.deepEqual(statuses, PUBLIC_BATCH_STATUSES)
    for (const status of statuses) {
      assert.equal(ENUM_VALUES.ReservationBatchStatus.includes(status), true)
    }
  })

  await check('A', 'buildBatchQuery preserves the two allowed statuses', () => {
    const buildBatchQuery = requireQueryExport('buildBatchQuery', 'function')
    for (const status of PUBLIC_BATCH_STATUSES) {
      assert.deepEqual(buildBatchQuery({ current: '2', status }), {
        current: 2,
        status,
      })
    }
  })

  await check('A', 'buildBatchQuery drops every disallowed or empty status', () => {
    const buildBatchQuery = requireQueryExport('buildBatchQuery', 'function')
    for (const status of ['DRAFT', 'CANCELLED', 'BOGUS', '', undefined]) {
      const result = buildBatchQuery({ current: '1', status })
      assert.equal(Object.hasOwn(result, 'status'), false, `status=${String(status)}`)
      assert.deepEqual(result, { current: 1 })
    }
  })

  await check('A', 'buildBatchQuery validates ids and never forwards unsupported parameters', () => {
    const buildBatchQuery = requireQueryExport('buildBatchQuery', 'function')
    assert.deepEqual(
      buildBatchQuery({
        current: '3',
        laboratoryId: '4',
        equipmentId: '12',
        status: 'PUBLISHED',
        pageSize: '100',
        sort: 'id',
        name: '离心机',
      }),
      {
        current: 3,
        laboratoryId: '4',
        equipmentId: '12',
        status: 'PUBLISHED',
      },
    )
    for (const invalidId of ['abc', '1.5', '-1', '', undefined]) {
      assert.deepEqual(
        buildBatchQuery({ current: '0', laboratoryId: invalidId, equipmentId: invalidId }),
        { current: 1 },
      )
    }
  })

  await check('A', 'hasActiveBatchFilter ignores current and recognizes its three filters', () => {
    const hasActiveBatchFilter = requireQueryExport('hasActiveBatchFilter', 'function')
    assert.equal(hasActiveBatchFilter({ current: 99 }), false)
    for (const params of [
      { current: 1, laboratoryId: '4' },
      { current: 1, equipmentId: '12' },
      { current: 1, status: 'CLOSED' },
    ]) {
      assert.equal(hasActiveBatchFilter(params), true)
    }
    for (const emptyValue of ['', undefined, null]) {
      assert.equal(
        hasActiveBatchFilter({
          current: 1,
          laboratoryId: emptyValue,
          equipmentId: emptyValue,
          status: emptyValue,
        }),
        false,
      )
    }
  })

  await check('A', 'batch routes have exact paths, metadata, order, and no local roles', () => {
    const appRoute = routes.find((route) => route.path === '/')
    const listRoute = findChildRoute('batches')
    const detailRoute = findChildRoute('batches/:id')
    const equipmentDetailIndex = appRoute?.children?.findIndex(
      (route) => route.path === 'equipment/:id',
    )
    const batchListIndex = appRoute?.children?.findIndex((route) => route.path === 'batches')

    assert.deepEqual(appRoute?.meta?.roles, ['USER', 'LAB_ADMIN'])
    assert.equal(listRoute?.name, 'batch-list')
    assert.equal(detailRoute?.name, 'batch-detail')
    assert.equal(typeof listRoute?.component, 'function')
    assert.equal(typeof detailRoute?.component, 'function')
    assert.deepEqual(listRoute?.meta, {
      title: '预约批次',
      menu: { title: '预约批次', icon: 'Calendar', order: 4 },
    })
    assert.deepEqual(detailRoute?.meta, { title: '批次详情' })
    assert.equal(Object.hasOwn(listRoute ?? {}, 'roles'), false)
    assert.equal(Object.hasOwn(listRoute?.meta ?? {}, 'roles'), false)
    assert.equal(Object.hasOwn(detailRoute?.meta ?? {}, 'roles'), false)
    assert.equal(batchListIndex, equipmentDetailIndex + 1)
    assert.equal(typeof ElementPlusIcons.Calendar, 'object')
  })

  await check('A', 'batch list status options derive only from BATCH_PUBLIC_STATUSES', async () => {
    const source = await readSource('src/views/batch/BatchListView.vue')
    assert.match(source, /BATCH_PUBLIC_STATUSES\s*\.\s*map\s*\(/)
    assert.match(source, /resolveStatus\s*\(\s*['"]ReservationBatchStatus['"]/)
    assert.doesNotMatch(source, /ENUM_VALUES\s*\.\s*ReservationBatchStatus/)
  })

  await check('A', 'batch detail submit availability comes only from BookingState actions', async () => {
    const source = await readSource('src/views/batch/BatchDetailView.vue')
    assert.match(
      source,
      /const\s+bookingState\s*=\s*computed\s*\(\s*\(\)\s*=>\s*batch\.value\?\.bookingState\s*\)/,
    )
    assert.match(
      source,
      /resolveStatus\s*\(\s*['"]BookingState['"]\s*,\s*bookingState\.value\s*\)/,
    )
    assert.match(source, /actions\s*\.\s*includes\s*\(\s*['"]submit['"]\s*\)/)
    const submitButton = [...source.matchAll(/<el-button\b[^>]*>/gs)]
      .map((match) => match[0])
      .find((tag) => /@click\s*=\s*['"]handleSubmit['"]/.test(tag))
    const disabledExpression = submitButton?.match(/:disabled\s*=\s*['"]([^'"]+)['"]/)?.[1]
    assert.match(disabledExpression ?? '', /^\s*!canSubmit\s*\|\|\s*submitting\s*$/)
    assert.deepEqual(disabledExpression?.match(/!?[A-Za-z]\w*/g), [
      '!canSubmit',
      'submitting',
    ])
  })

  await check('A', 'batch detail contains no status literal comparisons', async () => {
    const source = await readSource('src/views/batch/BatchDetailView.vue')
    assert.doesNotMatch(source, /\b(?:bookingState|status)\s*===/)
    assert.doesNotMatch(
      source,
      /['"](?:DRAFT|PUBLISHED|CLOSED|CANCELLED|NOT_STARTED|OPEN|EXPIRED)['"]/,
    )
  })

  await check('A', 'batch detail never recomputes bookingState with the client clock', async () => {
    const source = await readSource('src/views/batch/BatchDetailView.vue')
    assert.doesNotMatch(source, /new\s+Date\s*\(/)
    assert.doesNotMatch(source, /\bdayjs\s*\(/)
    assert.doesNotMatch(source, /Date\s*\.\s*now\s*\(/)
  })

  await check('A', 'reservation submit API is exact and later-phase calls remain absent', async () => {
    const reservationApiPath = path.join(SRC_ROOT, 'api', 'reservation.js')
    assert.equal(await pathExists(reservationApiPath), true)
    const reservationApiSource = await readFile(reservationApiPath, 'utf8')
    assert.match(
      reservationApiSource,
      /export\s+function\s+submitReservation\s*\(\s*batchId\s*\)\s*\{\s*return\s+request\s*\(\s*\{\s*method\s*:\s*['"]post['"]\s*,\s*url\s*:\s*['"]\/reservation['"]\s*,\s*data\s*:\s*\{\s*batchId\s*\}\s*\}\s*\)\s*\}/s,
    )
    assert.equal(
      (reservationApiSource.match(/url\s*:\s*['"]\/reservation['"]/g) ?? []).length,
      1,
    )

    const sourceFiles = await collectSourceFiles(SRC_ROOT)
    const cancelLocations = []
    const managePageLocations = []
    const requestOrVerifyLocations = []
    for (const file of sourceFiles) {
      const source = await readFile(file, 'utf8')
      const relativePath = path.relative(FRONTEND_ROOT, file)
      const managePageMatches = source.match(/\/reservation\/manage\/page/g)
      if (managePageMatches) {
        managePageLocations.push(...managePageMatches.map(() => relativePath))
      }
      const requestOrVerifyMatches = source.match(
        /\/reservation\/(?:\$\{[^}]+\}|[^/'"`]+)\/(?:request|verify)\b/g,
      )
      if (requestOrVerifyMatches) {
        requestOrVerifyLocations.push(...requestOrVerifyMatches.map(() => relativePath))
      }
      const cancelMatches = source.match(
        /\/reservation\/(?:\$\{[^}]+\}|[^/'"`]+)\/cancel\b/g,
      )
      if (cancelMatches) {
        cancelLocations.push(...cancelMatches.map(() => relativePath))
      }
    }
    assert.deepEqual(managePageLocations, ['src/api/reservation.js'])
    assert.deepEqual(requestOrVerifyLocations, ['src/api/reservation.js'])
    assert.match('/reservation/${id}/verify', /\/reservation\/(?:\$\{[^}]+\}|[^/'"`]+)\/(?:request|verify)\b/)
    assert.deepEqual(cancelLocations, ['src/api/reservation.js'])

    const reservationNamedViews = (await collectSourceFiles(path.join(SRC_ROOT, 'views')))
      .filter((file) => /reservation/i.test(path.basename(file)))
      .map((file) => path.relative(FRONTEND_ROOT, file))
      .sort()
    assert.deepEqual(reservationNamedViews, [
      'src/views/admin/reservation/AdminReservationListView.vue',
      'src/views/reservation/ReservationDetailView.vue',
      'src/views/reservation/ReservationListView.vue',
    ])
  })

  await check('A', 'admin routes and domains are exactly the ones through F10', async () => {
    const adminRoute = routes.find((route) => route.path === '/admin')
    // 白名单而非"非空即可"：F9 加预约记录、F10 加死信时必须显式更新这里。
    assert.deepEqual(
      adminRoute?.children?.map((route) => [route.path, route.name]).sort(),
      [
        ['', 'admin-home'],
        ['batches', 'admin-batch-list'],
        ['equipment', 'admin-equipment-list'],
        ['laboratories', 'admin-laboratory-list'],
        ['reservations', 'admin-reservation-list'],
        ['dlq', 'admin-dlq-list'],
        ['dlq/:id', 'admin-dlq-detail'],
      ].sort(),
    )
    assert.deepEqual(adminRoute?.meta?.roles, ['LAB_ADMIN'])

    // 精确锁定 F10 已交付的管理端域，后续 Phase 必须显式更新白名单。
    const adminEntries = await readdir(path.join(SRC_ROOT, 'views', 'admin'))
    assert.deepEqual(adminEntries.sort(), [
      'AdminHomeView.vue',
      'batch',
      'dlq',
      'equipment',
      'laboratory',
      'reservation',
    ])
  })

  await check('A', 'reservationBatch API exposes the exact F8 read and write surface', async () => {
    const source = await readSource('src/api/reservationBatch.js')
    const exports = [...source.matchAll(/export\s+function\s+(\w+)\s*\(/g)].map(
      (match) => match[1],
    )
    assert.deepEqual(exports.sort(), [
      'cancelReservationBatch',
      'closeReservationBatch',
      'createReservationBatch',
      'fetchManageBatchPage',
      'fetchReservationBatchById',
      'fetchReservationBatchPage',
      'publishReservationBatch',
      'updateReservationBatch',
    ])
    assert.equal((source.match(/method\s*:\s*['"]get['"]/g) ?? []).length, 3)
    assert.equal((source.match(/method\s*:\s*['"]post['"]/g) ?? []).length, 4)
    assert.equal((source.match(/method\s*:\s*['"]put['"]/g) ?? []).length, 1)
    const urls = [...new Set(
      [...source.matchAll(/url\s*:\s*([`'"])(.*?)\1/g)].map((match) => match[2]),
    )]
    assert.deepEqual(urls.sort(), [
      '/reservation-batch',
      '/reservation-batch/${id}',
      '/reservation-batch/${id}/cancel',
      '/reservation-batch/${id}/close',
      '/reservation-batch/${id}/publish',
      '/reservation-batch/manage/page',
      '/reservation-batch/page',
    ].sort())

    const reservationVerifyPathPattern = /\/reservation\/[^'"`]*\/verify/
    assert.match('url: `/reservation/${id}/verify`', reservationVerifyPathPattern)

    const sourceFiles = await collectSourceFiles(SRC_ROOT)
    for (const file of sourceFiles) {
      const fileSource = await readFile(file, 'utf8')
      const relativePath = path.relative(FRONTEND_ROOT, file)
      if (relativePath === 'src/api/reservation.js') {
        assert.match(fileSource, /\/reservation\/manage\/page/)
        assert.match(fileSource, reservationVerifyPathPattern)
      } else {
        assert.doesNotMatch(fileSource, /\/reservation\/manage\/page/, relativePath)
        assert.doesNotMatch(fileSource, reservationVerifyPathPattern, relativePath)
      }
      if (relativePath === 'src/api/dlq.js') {
        assert.match(fileSource, /\/reservation-dlq/)
      } else {
        assert.doesNotMatch(fileSource, /\/reservation-dlq/, relativePath)
      }
    }
  })
}

async function rawGet(backendURL, requestPath, token) {
  const headers = token ? { authorization: token } : undefined
  const response = await fetch(`${backendURL}${requestPath}`, {
    method: 'GET',
    headers,
    signal: AbortSignal.timeout(5000),
  })
  const text = await response.text()
  let body = null
  if (text) {
    try {
      body = JSON.parse(text)
    } catch {
      body = text
    }
  }
  return { status: response.status, body, text }
}

function createBackendRequest(backendURL, token) {
  return createRequest({
    baseURL: backendURL,
    timeout: 5000,
    getToken: () => token,
  })
}

async function verifyGroupB() {
  console.log('\nB group — real backend contract')
  const token = process.env.VERIFY_TOKEN
  if (!token) {
    skipGroup('B', 'VERIFY_TOKEN 未提供，真后端断言未运行')
    return
  }

  const backendURL = (process.env.VERIFY_BACKEND || 'http://127.0.0.1:18081').replace(
    /\/$/,
    '',
  )
  const request = createBackendRequest(backendURL, token)

  await check('B', 'public batch page returns a self-consistent fixed-size page', async () => {
    const result = await request({
      method: 'get',
      url: '/reservation-batch/page',
      params: { current: 1 },
    })

    assert.equal(result.status, 200)
    assert.equal(result.kind, 'ok')
    assert.equal(Array.isArray(result.data), true)
    assert.equal(typeof result.total, 'number')
    assert.equal(result.total >= 0, true)
    assert.equal(result.data.length <= PAGE_SIZE, true)
    if (result.total <= PAGE_SIZE) {
      assert.equal(result.data.length, result.total)
    }

    for (const row of result.data) {
      assert.equal(PUBLIC_BATCH_STATUSES.includes(row.status), true)
      assert.equal(typeof row.bookingState, 'string')
      assert.notEqual(row.bookingState, '')
      assert.equal(ENUM_VALUES.BookingState.includes(row.bookingState), true)
      assert.match(row.startTime, LOCAL_DATE_TIME_PATTERN)
      assert.equal(typeof row.equipmentName, 'string')
      assert.notEqual(row.equipmentName.trim(), '')
      assert.equal(typeof row.laboratoryName, 'string')
      assert.notEqual(row.laboratoryName.trim(), '')
    }
  })

  await check('B', 'DRAFT status is rejected through the measured Result error channel', async () => {
    const result = await request({
      method: 'get',
      url: '/reservation-batch/page',
      params: { status: 'DRAFT' },
    })
    assert.equal(result.kind, 'error')
    assert.equal(result.status, 400)
    assert.equal(result.message, '批次状态不合法')
  })

  await check('B', 'BOGUS status returns Spring 400 body without a success key', async () => {
    const raw = await rawGet(
      backendURL,
      '/reservation-batch/page?status=BOGUS',
      token,
    )
    assert.equal(raw.status, 400)
    assert.equal(raw.body !== null && typeof raw.body === 'object', true)
    assert.equal(Object.hasOwn(raw.body, 'success'), false)

    const normalized = await request({
      method: 'get',
      url: '/reservation-batch/page',
      params: { status: 'BOGUS' },
    })
    assert.equal(normalized.kind, 'clientError')
    assert.equal(normalized.status, 400)
    assert.equal(normalized.message, '请求参数不合法')
  })

  await check('B', 'missing batch uses the business channel and backend message', async () => {
    const result = await request({ method: 'get', url: '/reservation-batch/999999' })
    assert.equal(result.status, 200)
    assert.equal(result.kind, 'business')
    assert.equal(result.message, '预约批次不存在')
  })

  await check('B', 'non-numeric batch id uses the measured 500 error channel', async () => {
    const result = await request({ method: 'get', url: '/reservation-batch/abc' })
    assert.equal(result.status, 500)
    assert.equal(result.kind, 'error')
    assert.equal(result.message, '服务器异常')
  })

  await check('B', 'batch page without token uses the unauthorized channel', async () => {
    const publicRequest = createBackendRequest(backendURL, null)
    const result = await publicRequest({
      method: 'get',
      url: '/reservation-batch/page',
      params: { current: 1 },
    })
    assert.equal(result.status, 401)
    assert.equal(result.kind, 'unauthorized')
  })
}

async function verifyGroupC() {
  console.log('\nC group — published OPEN fixture shape')
  const token = process.env.VERIFY_TOKEN
  const batchId = process.env.VERIFY_BATCH_ID
  if (!token || !batchId) {
    const missing = [!token ? 'VERIFY_TOKEN' : null, !batchId ? 'VERIFY_BATCH_ID' : null]
      .filter(Boolean)
      .join('、')
    skipGroup('C', `${missing} 未提供，fixture 断言未运行`)
    return
  }

  const backendURL = (process.env.VERIFY_BACKEND || 'http://127.0.0.1:18081').replace(
    /\/$/,
    '',
  )
  const request = createBackendRequest(backendURL, token)
  let fixturePromise
  const getFixture = () => {
    fixturePromise ??= request({ method: 'get', url: `/reservation-batch/${batchId}` })
    return fixturePromise
  }

  await check('C', 'fixture detail exposes the required public batch fields', async () => {
    const result = await getFixture()
    assert.equal(result.status, 200)
    assert.equal(result.kind, 'ok')
    assert.equal(String(result.data.id), String(batchId))
    assert.equal(PUBLIC_BATCH_STATUSES.includes(result.data.status), true)
    assert.equal(ENUM_VALUES.BookingState.includes(result.data.bookingState), true)
    assert.equal(typeof result.data.equipmentName, 'string')
    assert.notEqual(result.data.equipmentName.trim(), '')
    assert.equal(typeof result.data.laboratoryName, 'string')
    assert.notEqual(result.data.laboratoryName.trim(), '')
    assert.equal(typeof result.data.totalQuota, 'number')
    assert.equal(typeof result.data.remainingQuota, 'number')
    assert.equal(result.data.totalQuota >= 1, true)
    assert.equal(result.data.remainingQuota >= 0, true)
  })

  await check('C', 'fixture usage and booking-window times have backend and display shapes', async () => {
    const result = await getFixture()
    assert.equal(result.kind, 'ok')
    for (const field of ['startTime', 'endTime', 'bookingStartTime', 'bookingEndTime']) {
      assert.equal(typeof result.data[field], 'string')
      assert.match(result.data[field], LOCAL_DATE_TIME_PATTERN)
      const displayed = formatDateTime(result.data[field])
      assert.notEqual(displayed, '—')
      assert.match(displayed, DISPLAY_DATE_TIME_PATTERN)
    }
  })

  await check('C', 'OPEN fixture maps to the submit action without quota inference', async () => {
    const result = await getFixture()
    assert.equal(result.kind, 'ok')
    assert.equal(result.data.bookingState, 'OPEN')
    const meta = resolveStatus('BookingState', result.data.bookingState)
    assert.equal(meta.actions.includes('submit'), true)
    assert.equal(meta.label, '可预约')
  })
}

function printSummary() {
  console.log('\nSummary')
  for (const groupName of ['A', 'B', 'C']) {
    const group = groups[groupName]
    assert.equal(group.pass + group.fail + group.skip, group.expected)
    console.log(
      `${groupName} group: ${group.pass} PASS, ${group.fail} FAIL, ${group.skip} SKIP（共 ${group.expected} 条）`,
    )
  }

  const totals = Object.values(groups).reduce(
    (sum, group) => ({
      pass: sum.pass + group.pass,
      fail: sum.fail + group.fail,
      skip: sum.skip + group.skip,
      expected: sum.expected + group.expected,
    }),
    { pass: 0, fail: 0, skip: 0, expected: 0 },
  )
  console.log(
    `Total: ${totals.pass} PASS, ${totals.fail} FAIL, ${totals.skip} SKIP（共 ${totals.expected} 条）`,
  )
  if (totals.fail > 0) process.exitCode = 1
}

async function main() {
  await verifyGroupA()
  await verifyGroupB()
  await verifyGroupC()
  printSummary()
}

main().catch((error) => {
  console.error('FAIL: verification script crashed')
  console.error(error instanceof Error ? error.stack : error)
  process.exitCode = 1
})
