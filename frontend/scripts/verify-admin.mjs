import assert from 'node:assert/strict'
import { readFile, readdir } from 'node:fs/promises'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

import * as ElementPlusIcons from '@element-plus/icons-vue'

import { ENUM_VALUES } from '../src/constants/enums.js'
import { statusMap } from '../src/constants/statusMap.js'
import { routes } from '../src/router/routes.js'

process.env.NO_PROXY = '*'
process.env.no_proxy = '*'

const FRONTEND_ROOT = fileURLToPath(new URL('../', import.meta.url))
const SRC_ROOT = path.join(FRONTEND_ROOT, 'src')
const groups = {
  A: { expected: 20, pass: 0, fail: 0, skip: 0 },
  B: { expected: 24, pass: 0, fail: 0, skip: 0 },
}

let createdLaboratoryId = null
let createdEquipmentId = null
const createdBatchIds = []
let closedBatchId = null

async function check(groupName, name, verification) {
  try {
    const outcome = await verification()
    if (outcome?.skip) {
      groups[groupName].skip += 1
      console.log(`SKIP: [${groupName}] ${name} — ${outcome.reason}`)
      return
    }
    groups[groupName].pass += 1
    console.log(`PASS: [${groupName}] ${name}`)
  } catch (error) {
    groups[groupName].fail += 1
    console.error(`FAIL: [${groupName}] ${name}`)
    console.error(error instanceof Error ? error.stack : error)
  }
}

function skip(reason) {
  return { skip: true, reason }
}

function skipGroup(groupName, reason) {
  groups[groupName].skip = groups[groupName].expected
  console.log(`SKIP: [${groupName}] ${reason}（跳过 ${groups[groupName].expected} 条）`)
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

function exportedFunctions(source) {
  return [...source.matchAll(/export\s+function\s+(\w+)\s*\(/g)].map((match) => match[1])
}

function extractFunctionBody(source, functionName) {
  const start = source.search(new RegExp(`async\\s+function\\s+${functionName}\\s*\\(`))
  assert.notEqual(start, -1, `missing ${functionName}`)
  const braceStart = source.indexOf('{', start)
  let depth = 0
  for (let index = braceStart; index < source.length; index += 1) {
    if (source[index] === '{') depth += 1
    if (source[index] === '}') depth -= 1
    if (depth === 0) return source.slice(braceStart + 1, index)
  }
  throw new Error(`unclosed ${functionName}`)
}

async function verifyGroupA() {
  console.log('\nA group — admin routes, API, and source contract')

  await check('A', 'admin route registers the four ordered menu entries with real icons', () => {
    const adminRoute = routes.find((route) => route.path === '/admin')
    assert.deepEqual(
      adminRoute?.children?.map((route) => [route.path, route.name]),
      [
        ['', 'admin-home'],
        ['laboratories', 'admin-laboratory-list'],
        ['equipment', 'admin-equipment-list'],
        ['batches', 'admin-batch-list'],
      ],
    )
    assert.deepEqual(adminRoute.children.map((route) => route.meta.menu.order), [1, 2, 3, 4])
    for (const route of adminRoute.children) {
      assert.equal(typeof ElementPlusIcons[route.meta.menu.icon], 'object')
    }
  })

  await check('A', 'new admin routes inherit roles from the parent', () => {
    const adminRoute = routes.find((route) => route.path === '/admin')
    assert.deepEqual(adminRoute?.meta?.roles, ['LAB_ADMIN'])
    for (const route of adminRoute.children.filter((item) => item.path)) {
      assert.equal(Object.hasOwn(route.meta, 'roles'), false)
    }
  })

  await check('A', 'laboratory API exposes the exact CRUD surface', async () => {
    const source = await readSource('src/api/laboratory.js')
    assert.deepEqual(exportedFunctions(source), [
      'fetchLaboratoryList',
      'fetchLaboratoryById',
      'createLaboratory',
      'updateLaboratory',
      'deleteLaboratory',
    ])
    assert.equal((source.match(/method\s*:\s*['"]post['"]/g) ?? []).length, 1)
    assert.equal((source.match(/method\s*:\s*['"]put['"]/g) ?? []).length, 1)
    assert.equal((source.match(/method\s*:\s*['"]delete['"]/g) ?? []).length, 1)
    assert.match(source, /method\s*:\s*['"]post['"]\s*,\s*url\s*:\s*['"]\/laboratory['"]\s*,\s*data\s*:\s*payload/s)
    assert.match(source, /method\s*:\s*['"]put['"]\s*,\s*url\s*:\s*['"]\/laboratory['"]\s*,\s*data\s*:\s*payload/s)
    assert.match(source, /method\s*:\s*['"]delete['"]\s*,\s*url\s*:\s*`\/laboratory\/\$\{id\}`/s)
  })

  await check('A', 'equipment API exposes exact CRUD and query-param status update', async () => {
    const source = await readSource('src/api/equipment.js')
    assert.deepEqual(exportedFunctions(source), [
      'fetchEquipmentPage',
      'fetchEquipmentById',
      'fetchEquipmentCategories',
      'createEquipment',
      'updateEquipment',
      'updateEquipmentStatus',
      'deleteEquipment',
    ])
    const body = source.slice(source.indexOf('export function updateEquipmentStatus'), source.indexOf('export function deleteEquipment'))
    assert.match(body, /url\s*:\s*`\/equipment\/\$\{id\}\/status`/)
    assert.match(body, /method\s*:\s*['"]put['"]/)
    assert.match(body, /params\s*:\s*\{\s*status\s*\}/)
    assert.doesNotMatch(body, /\bdata\s*:/)
  })

  await check('A', 'reservation batch API exposes the exact F8 CRUD and transition surface', async () => {
    const source = await readSource('src/api/reservationBatch.js')
    assert.deepEqual(exportedFunctions(source).sort(), [
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
    assert.match(source, /method\s*:\s*['"]post['"]\s*,\s*url\s*:\s*['"]\/reservation-batch['"]\s*,\s*data\s*:\s*payload/s)
    assert.match(source, /method\s*:\s*['"]put['"]\s*,\s*url\s*:\s*['"]\/reservation-batch['"]\s*,\s*data\s*:\s*payload/s)
    for (const action of ['publish', 'close', 'cancel']) {
      const body = source.slice(
        source.indexOf(`export function ${action}ReservationBatch`),
        source.indexOf('\n}', source.indexOf(`export function ${action}ReservationBatch`)) + 2,
      )
      assert.match(body, new RegExp(`method\\s*:\\s*['"]post['"]`))
      assert.match(body, new RegExp(`url\\s*:\\s*\`/reservation-batch/\\$\\{id\\}/${action}\``))
      assert.doesNotMatch(body, /\bdata\s*:/)
    }
  })

  await check('A', 'manage batch query accepts all four statuses and only supported parameters', async () => {
    const query = await import('../src/utils/query.js')
    assert.equal(query.MANAGE_BATCH_STATUSES, ENUM_VALUES.ReservationBatchStatus)
    for (const status of ENUM_VALUES.ReservationBatchStatus) {
      assert.deepEqual(query.buildManageBatchQuery({ current: '2', equipmentId: '12', status }), {
        current: 2,
        equipmentId: '12',
        status,
      })
    }
    assert.deepEqual(query.buildManageBatchQuery({
      current: '0',
      equipmentId: 'abc',
      status: 'BOGUS',
      laboratoryId: '1',
      name: '离心机',
      pageSize: 100,
      sort: 'id',
    }), { current: 1 })
    for (const status of ['', undefined]) {
      assert.deepEqual(query.buildManageBatchQuery({ status }), { current: 1 })
    }
  })

  await check('A', 'manage batch active-filter predicate recognizes only equipment and status', async () => {
    const { hasActiveManageBatchFilter } = await import('../src/utils/query.js')
    assert.equal(hasActiveManageBatchFilter({ current: 2, laboratoryId: '1', name: 'x' }), false)
    assert.equal(hasActiveManageBatchFilter({ equipmentId: '1' }), true)
    assert.equal(hasActiveManageBatchFilter({ status: 'DRAFT' }), true)
    for (const emptyValue of ['', undefined, null]) {
      assert.equal(hasActiveManageBatchFilter({ equipmentId: emptyValue, status: emptyValue }), false)
    }
  })

  await check('A', 'equipment actions map only to valid enum targets', async () => {
    const source = await readSource('src/views/admin/equipment/AdminEquipmentListView.vue')
    const literal = source.match(/const\s+EQUIPMENT_STATUS_ACTION_TARGETS\s*=\s*\{([^}]+)\}/s)?.[1]
    assert.ok(literal, 'missing EQUIPMENT_STATUS_ACTION_TARGETS')
    const targets = Object.fromEntries(
      [...literal.matchAll(/(\w+)\s*:\s*['"]([^'"]+)['"]/g)].map((match) => [match[1], match[2]]),
    )
    for (const value of ENUM_VALUES.EquipmentStatus) {
      for (const action of statusMap.EquipmentStatus[value].actions) {
        assert.equal(Object.hasOwn(targets, action), true, action)
      }
    }
    for (const target of Object.values(targets)) {
      assert.equal(ENUM_VALUES.EquipmentStatus.includes(target), true, target)
    }
  })

  await check('A', 'equipment status handler obtains its target only from the action map', async () => {
    const source = await readSource('src/views/admin/equipment/AdminEquipmentListView.vue')
    const body = extractFunctionBody(source, 'handleStatusChange')
    assert.match(body, /EQUIPMENT_STATUS_ACTION_TARGETS\s*\[\s*action\s*\]/)
    assert.doesNotMatch(body, /['"](?:AVAILABLE|MAINTENANCE|DISABLED)['"]/)
  })

  const adminViewSources = async () => Promise.all([
    readSource('src/views/admin/laboratory/AdminLaboratoryListView.vue'),
    readSource('src/views/admin/equipment/AdminEquipmentListView.vue'),
    readSource('src/views/admin/batch/AdminBatchListView.vue'),
  ])

  await check('A', 'batch actions come only from status metadata and have an exact handler map', async () => {
    const source = await readSource('src/views/admin/batch/AdminBatchListView.vue')
    assert.match(source, /resolveStatus\s*\(\s*['"]ReservationBatchStatus['"]\s*,\s*row\.status\s*\)\.actions/)
    assert.doesNotMatch(source, /\bstatus\s*===\s*['"](?:DRAFT|PUBLISHED|CLOSED|CANCELLED)['"]/)
    const literal = source.match(/const\s+BATCH_STATUS_ACTION_HANDLERS\s*=\s*\{([^}]+)\}/s)?.[1]
    assert.ok(literal, 'missing BATCH_STATUS_ACTION_HANDLERS')
    const handlerActions = [...literal.matchAll(/\b(\w+)\s*:/g)].map((match) => match[1]).sort()
    const statusActions = [...new Set(
      ENUM_VALUES.ReservationBatchStatus.flatMap((status) => statusMap.ReservationBatchStatus[status].actions),
    )].sort()
    assert.deepEqual(handlerActions, statusActions)
  })

  await check('A', 'admin batch params use the manage query builder', async () => {
    const source = await readSource('src/views/admin/batch/AdminBatchListView.vue')
    assert.match(
      source,
      /const\s+params\s*=\s*computed\s*\(\s*\(\)\s*=>\s*buildManageBatchQuery\s*\(\s*route\.query\s*\)\s*\)/,
    )
    assert.doesNotMatch(source, /\bbuildBatchQuery\b/)
  })

  await check('A', 'batch cancellation stops when confirmation is rejected', async () => {
    const source = await readSource('src/views/admin/batch/AdminBatchListView.vue')
    const body = extractFunctionBody(source, 'handleCancel')
    assert.match(body, /ElMessageBox\.confirm\s*\(/)
    assert.match(body, /catch\s*(?:\([^)]*\)\s*)?\{[^}]*\breturn\b[^}]*\}/s)
    const confirmIndex = body.indexOf('ElMessageBox.confirm')
    const cancelIndex = body.indexOf('cancelReservationBatch(')
    assert.notEqual(cancelIndex, -1)
    assert.equal(confirmIndex < cancelIndex, true)
    assert.match(body, /await\s+loadBatches\s*\(/)
  })

  await check('A', 'all four batch datetime pickers preserve the required T separator', async () => {
    const source = await readSource('src/views/admin/batch/AdminBatchListView.vue')
    const pickers = [...source.matchAll(/<el-date-picker\b[^>]*>/gs)].map((match) => match[0])
    assert.equal(pickers.length, 4)
    for (const picker of pickers) {
      assert.match(picker, /type\s*=\s*['"]datetime['"]/)
      assert.match(picker, /value-format\s*=\s*['"]YYYY-MM-DD\[T\]HH:mm:ss['"]/)
    }
  })

  await check('A', 'dependent equipment options discard stale laboratory responses', async () => {
    const source = await readSource('src/views/admin/batch/AdminBatchListView.vue')
    const body = extractFunctionBody(source, 'loadEquipmentOptions')
    assert.match(body, /const\s+sequence\s*=\s*\+\+equipmentRequestSequence/)
    assert.match(body, /sequence\s*!==\s*equipmentRequestSequence/)
    assert.match(source, /equipmentRequestSequence\s*\+=\s*1/)
  })

  await check('A', 'batch writes reload, surface backend errors, and never update records optimistically', async () => {
    const source = await readSource('src/views/admin/batch/AdminBatchListView.vue')
    assert.doesNotMatch(extractFunctionBody(source, 'loadBatches'), /showError\s*\(/)
    for (const name of ['handleSave', 'handlePublish', 'handleClose', 'handleCancel']) {
      const body = extractFunctionBody(source, name)
      assert.match(body, /await\s+loadBatches\s*\(/)
      assert.match(body, /showError\s*\(/)
    }
    assert.doesNotMatch(source, /records\.value\.(?:push|splice)\s*\(/)
    assert.doesNotMatch(source, /records\.value\s*=\s*[^\n;]*\.filter\s*\(/)
  })

  await check('A', 'admin views never infer laboratory ownership', async () => {
    for (const source of await adminViewSources()) assert.doesNotMatch(source, /adminUserId/)
  })

  await check('A', 'admin views rely on route roles instead of local role checks', async () => {
    for (const source of await adminViewSources()) {
      assert.doesNotMatch(source, /role\s*(?:===|!==)/)
      assert.doesNotMatch(source, /roles\s*\.\s*includes/)
    }
  })

  await check('A', 'admin views use the API layer instead of axios', async () => {
    for (const source of await adminViewSources()) assert.doesNotMatch(source, /axios/i)
  })

  await check('A', 'every write handler reloads and no list is updated optimistically', async () => {
    const [laboratorySource, equipmentSource] = await adminViewSources()
    for (const name of ['handleSave', 'handleDelete']) {
      assert.match(extractFunctionBody(laboratorySource, name), /await\s+loadLaboratories\s*\(/)
      assert.match(extractFunctionBody(equipmentSource, name), /await\s+loadEquipment\s*\(/)
    }
    assert.match(extractFunctionBody(equipmentSource, 'handleStatusChange'), /await\s+loadEquipment\s*\(/)
    assert.doesNotMatch(extractFunctionBody(laboratorySource, 'loadLaboratories'), /showError\s*\(/)
    assert.doesNotMatch(extractFunctionBody(equipmentSource, 'loadEquipment'), /showError\s*\(/)
    for (const name of ['handleSave', 'handleDelete']) {
      assert.match(extractFunctionBody(laboratorySource, name), /showError\s*\(/)
      assert.match(extractFunctionBody(equipmentSource, name), /showError\s*\(/)
    }
    assert.match(extractFunctionBody(equipmentSource, 'handleStatusChange'), /showError\s*\(/)
    for (const source of [laboratorySource, equipmentSource]) {
      assert.doesNotMatch(source, /records\.value\.(?:push|splice)\s*\(/)
      assert.doesNotMatch(source, /records\.value\s*=\s*[^\n;]*\.filter\s*\(/)
    }
  })

  await check('A', 'later admin domains after F8 do not exist yet', async () => {
    const files = await collectSourceFiles(path.join(SRC_ROOT, 'views', 'admin'))
    for (const file of files) assert.doesNotMatch(path.basename(file), /reservation|dlq/i)
  })
}

async function rawRequest(backendURL, token, requestPath, options = {}) {
  const response = await fetch(`${backendURL}${requestPath}`, {
    ...options,
    headers: {
      authorization: token,
      ...(options.body ? { 'content-type': 'application/json' } : {}),
      ...options.headers,
    },
    signal: AbortSignal.timeout(5000),
  })
  const text = await response.text()
  const body = text ? JSON.parse(text) : null
  console.log(`  measured: ${options.method ?? 'GET'} ${requestPath} -> ${response.status} ${text}`)
  return { status: response.status, body }
}

async function cleanupCreatedLaboratoryFixture(backendURL, token) {
  if (!createdLaboratoryId) return
  const laboratoryId = createdLaboratoryId
  const result = await rawRequest(backendURL, token, `/laboratory/${laboratoryId}`, {
    method: 'DELETE',
  })
  assert.equal(result.status, 200)
  assert.equal(result.body.success, true)
  createdLaboratoryId = null
}

function localDateTime(daysFromNow, hourOffset = 0) {
  const value = new Date()
  value.setDate(value.getDate() + daysFromNow)
  value.setHours(value.getHours() + hourOffset)
  value.setMilliseconds(0)
  return value.toISOString().slice(0, 19)
}

function validBatchPayload(equipmentId = 1, dayOffset = 60, overrides = {}) {
  return {
    equipmentId,
    startTime: localDateTime(dayOffset),
    endTime: localDateTime(dayOffset, 2),
    bookingStartTime: localDateTime(1),
    bookingEndTime: localDateTime(dayOffset - 1),
    totalQuota: 5,
    ...overrides,
  }
}

function validBatchPayloadAt(equipmentId, startDate, overrides = {}) {
  const endDate = new Date(startDate)
  endDate.setHours(endDate.getHours() + 2)
  const bookingEndDate = new Date(startDate)
  bookingEndDate.setDate(bookingEndDate.getDate() - 1)
  return {
    equipmentId,
    startTime: startDate.toISOString().slice(0, 19),
    endTime: endDate.toISOString().slice(0, 19),
    bookingStartTime: localDateTime(1),
    bookingEndTime: bookingEndDate.toISOString().slice(0, 19),
    totalQuota: 5,
    ...overrides,
  }
}

async function createBatchFixture(backendURL, token, payload) {
  const result = await rawRequest(backendURL, token, '/reservation-batch', {
    method: 'POST',
    body: JSON.stringify(payload),
  })
  assert.equal(result.status, 200)
  assert.equal(result.body.success, true)
  assert.match(String(result.body.data), /^\d+$/)
  assert.equal(Number(result.body.data) > 0, true)
  createdBatchIds.push(result.body.data)
  return result.body.data
}

async function fetchBatchFixture(backendURL, token, id) {
  const result = await rawRequest(backendURL, token, `/reservation-batch/${id}`)
  assert.equal(result.status, 200)
  assert.equal(result.body.success, true)
  return result.body.data
}

async function manageBatchTotal(backendURL, token, equipmentId) {
  const result = await rawRequest(
    backendURL,
    token,
    `/reservation-batch/manage/page?equipmentId=${equipmentId}&current=1`,
  )
  assert.equal(result.status, 200)
  assert.equal(result.body.success, true)
  return Number(result.body.total)
}

async function nextAvailablePrimaryPayload(backendURL, token, equipmentId = 1) {
  const candidate = new Date()
  candidate.setDate(candidate.getDate() + 60)
  candidate.setMilliseconds(0)
  for (const status of ['DRAFT', 'PUBLISHED', 'CLOSED']) {
    let current = 1
    let total = 0
    do {
      const result = await rawRequest(
        backendURL,
        token,
        `/reservation-batch/manage/page?equipmentId=${equipmentId}&status=${status}&current=${current}`,
      )
      assert.equal(result.status, 200)
      assert.equal(result.body.success, true)
      const rows = Array.isArray(result.body.data) ? result.body.data : []
      total = Number(result.body.total) || 0
      for (const row of rows) {
        const endTime = new Date(row.endTime)
        if (endTime >= candidate) {
          candidate.setTime(endTime.getTime())
          candidate.setDate(candidate.getDate() + 1)
        }
      }
      current += 1
    } while ((current - 1) * 5 < total)
  }
  return validBatchPayloadAt(equipmentId, candidate)
}

async function cleanupCreatedBatchFixtures(backendURL, token) {
  for (const id of createdBatchIds) {
    const batch = await fetchBatchFixture(backendURL, token, id)
    if (['DRAFT', 'PUBLISHED'].includes(batch.status)) {
      const cancelled = await rawRequest(backendURL, token, `/reservation-batch/${id}/cancel`, {
        method: 'POST',
      })
      assert.equal(cancelled.status, 200)
      assert.equal(cancelled.body.success, true)
    }
  }
}

async function verifyGroupB() {
  console.log('\nB group — real backend admin contract')
  const backend = process.env.VERIFY_BACKEND
  const token = process.env.VERIFY_ADMIN_TOKEN
  if (!backend || !token) {
    const missing = [!backend ? 'VERIFY_BACKEND' : null, !token ? 'VERIFY_ADMIN_TOKEN' : null].filter(Boolean).join('、')
    skipGroup('B', `${missing} 未提供，真机管理端断言未运行`)
    return
  }
  const backendURL = backend.replace(/\/$/, '')
  const uniqueName = `F7验证实验室-${Date.now()}`

  try {
    await check('B', 'create laboratory returns a positive auto-increment id, not a snowflake id', async () => {
      const result = await rawRequest(backendURL, token, '/laboratory', {
        method: 'POST',
        body: JSON.stringify({ name: uniqueName, campus: '验证校区', status: 'OPEN' }),
      })
      assert.equal(result.status, 200)
      assert.equal(result.body.success, true)
      // 实验室与设备 id 由后端数据库自增生成，是小整数，不是雪花 id。
      createdLaboratoryId = result.body.data
      assert.equal(['number', 'string'].includes(typeof createdLaboratoryId), true)
      assert.match(String(createdLaboratoryId), /^\d+$/)
      assert.equal(Number(createdLaboratoryId) > 0, true)
    })

    await check('B', 'creator update succeeds as indirect ownership evidence because LaboratoryVO omits the owner', async () => {
      if (!createdLaboratoryId) return skip('创建实验室未取得 id')
      // LaboratoryVO 不含负责人字段；创建后 PUT 通过 assertManageable 是创建者成为负责人的间接证据。
      const result = await rawRequest(backendURL, token, '/laboratory', {
        method: 'PUT',
        body: JSON.stringify({ id: createdLaboratoryId, name: uniqueName, description: '已验证可管理', status: 'OPEN' }),
      })
      assert.equal(result.status, 200)
      assert.equal(result.body.success, true)
    })

    await check('B', 'deleted laboratory is returned without a data key', async () => {
      if (!createdLaboratoryId) return skip('创建实验室未取得 id')
      const laboratoryId = createdLaboratoryId
      const deleted = await rawRequest(backendURL, token, `/laboratory/${laboratoryId}`, { method: 'DELETE' })
      assert.equal(deleted.status, 200)
      assert.equal(deleted.body.success, true)
      createdLaboratoryId = null
      const fetched = await rawRequest(backendURL, token, `/laboratory/${laboratoryId}`)
      assert.equal(fetched.status, 200)
      assert.equal(Object.hasOwn(fetched.body, 'data'), false)
    })
  } finally {
    // B1 或 B2 的后续断言即使失败，已保存的 id 仍会在这里被删除。
    await cleanupCreatedLaboratoryFixture(backendURL, token)
  }

  await check('B', 'laboratory with equipment rejects deletion with measured 400 message', async () => {
    const result = await rawRequest(backendURL, token, '/laboratory/1', { method: 'DELETE' })
    assert.equal(result.status, 400)
    assert.match(result.body.errorMsg, /台设备/)
  })

  await check('B', 'other laboratory equipment status update is denied', async () => {
    const page = await rawRequest(backendURL, token, '/equipment/page?laboratoryId=4&current=1')
    assert.equal(page.status, 200)
    assert.equal(page.body.success, true)
    const equipment = Array.isArray(page.body?.data) ? page.body.data[0] : null
    if (!equipment?.id) return skip('实验室 4 未探测到设备')
    const result = await rawRequest(backendURL, token, `/equipment/${equipment.id}/status?status=MAINTENANCE`, { method: 'PUT' })
    assert.equal(result.status, 403)
    assert.equal(['无权管理该设备', '无权管理该实验室'].includes(result.body.errorMsg), true)
  })

  await check('B', 'own equipment can be created, transitioned, fetched, and cleaned up', async () => {
    const laboratory = await rawRequest(backendURL, token, '/laboratory', {
      method: 'POST',
      body: JSON.stringify({ name: `${uniqueName}-设备`, campus: '验证校区', status: 'OPEN' }),
    })
    assert.equal(laboratory.status, 200)
    createdLaboratoryId = laboratory.body.data
    try {
      const emptyPage = await rawRequest(backendURL, token, `/equipment/page?laboratoryId=${createdLaboratoryId}&current=1`)
      assert.equal(emptyPage.status, 200)
      assert.equal(emptyPage.body.success, true)
      assert.equal(Array.isArray(emptyPage.body.data), true)
      assert.equal(emptyPage.body.data.length, 0)
      const categories = await rawRequest(backendURL, token, '/equipment-category/list')
      assert.equal(categories.status, 200)
      assert.equal(categories.body.success, true)
      const categoryId = categories.body?.data?.[0]?.id
      if (!categoryId) return skip('未探测到设备分类')
      const created = await rawRequest(backendURL, token, '/equipment', {
        method: 'POST',
        body: JSON.stringify({ laboratoryId: createdLaboratoryId, categoryId: String(categoryId), name: `F7验证设备-${Date.now()}`, status: 'AVAILABLE', bookingEnabled: true }),
      })
      assert.equal(created.status, 200)
      assert.equal(created.body.success, true)
      createdEquipmentId = created.body.data
      const updated = await rawRequest(backendURL, token, `/equipment/${createdEquipmentId}/status?status=MAINTENANCE`, { method: 'PUT' })
      assert.equal(updated.status, 200)
      const fetched = await rawRequest(backendURL, token, `/equipment/${createdEquipmentId}`)
      assert.equal(fetched.body.data.status, 'MAINTENANCE')
    } finally {
      if (createdEquipmentId) {
        const deletedEquipment = await rawRequest(backendURL, token, `/equipment/${createdEquipmentId}`, { method: 'DELETE' })
        assert.equal(deletedEquipment.status, 200)
        createdEquipmentId = null
      }
      const deletedLaboratory = await rawRequest(backendURL, token, `/laboratory/${createdLaboratoryId}`, { method: 'DELETE' })
      assert.equal(deletedLaboratory.status, 200)
      createdLaboratoryId = null
    }
  })

  await check('B', 'invalid status uses the measured business channel', async () => {
    const laboratory = await rawRequest(backendURL, token, '/laboratory', {
      method: 'POST',
      body: JSON.stringify({ name: `${uniqueName}-非法状态`, campus: '验证校区', status: 'OPEN' }),
    })
    assert.equal(laboratory.status, 200)
    const laboratoryId = laboratory.body.data
    let equipmentId = null
    try {
      const categories = await rawRequest(backendURL, token, '/equipment-category/list')
      assert.equal(categories.status, 200)
      assert.equal(categories.body.success, true)
      const categoryId = categories.body?.data?.[0]?.id
      if (!categoryId) return skip('未探测到设备分类')
      const equipment = await rawRequest(backendURL, token, '/equipment', {
        method: 'POST',
        body: JSON.stringify({ laboratoryId, categoryId: String(categoryId), name: `F7非法状态设备-${Date.now()}`, status: 'AVAILABLE', bookingEnabled: true }),
      })
      assert.equal(equipment.status, 200)
      equipmentId = equipment.body.data
      const result = await rawRequest(backendURL, token, `/equipment/${equipmentId}/status?status=BOGUS`, { method: 'PUT' })
      assert.equal(result.status, 200)
      assert.equal(result.body.success, false)
      assert.equal(result.body.errorMsg, '设备状态不合法')
    } finally {
      if (equipmentId) {
        const deletedEquipment = await rawRequest(backendURL, token, `/equipment/${equipmentId}`, { method: 'DELETE' })
        assert.equal(deletedEquipment.status, 200)
      }
      const deletedLaboratory = await rawRequest(backendURL, token, `/laboratory/${laboratoryId}`, { method: 'DELETE' })
      assert.equal(deletedLaboratory.status, 200)
    }
  })

  await check('B', 'equipment creation in a missing laboratory is denied without side effects', async () => {
    const result = await rawRequest(backendURL, token, '/equipment', {
      method: 'POST',
      body: JSON.stringify({ laboratoryId: '999999', categoryId: '1', name: 'F7无效实验室设备', status: 'AVAILABLE', bookingEnabled: true }),
    })
    assert.equal(result.status, 403)
    assert.equal(result.body.errorMsg, '无权管理该实验室')
  })

  let primaryBatchId = null
  let cancelBatchId = null
  const primaryPayload = await nextAvailablePrimaryPayload(backendURL, token)
  try {
    await check('B', 'space-separated batch datetimes are rejected without creating a fixture', async () => {
      const before = await manageBatchTotal(backendURL, token, 1)
      const payload = Object.fromEntries(
        Object.entries(primaryPayload).map(([key, value]) => [
          key,
          typeof value === 'string' ? value.replace('T', ' ') : value,
        ]),
      )
      const result = await rawRequest(backendURL, token, '/reservation-batch', {
        method: 'POST',
        body: JSON.stringify(payload),
      })
      assert.equal(result.status, 400)
      assert.equal(result.body.errorMsg, '请求体格式错误')
      assert.equal(await manageBatchTotal(backendURL, token, 1), before)
    })

    await check('B', 'T-separated batch datetimes reach required-field validation', async () => {
      const before = await manageBatchTotal(backendURL, token, 1)
      const payload = { ...primaryPayload }
      delete payload.totalQuota
      const result = await rawRequest(backendURL, token, '/reservation-batch', {
        method: 'POST',
        body: JSON.stringify(payload),
      })
      assert.equal(result.status, 400)
      assert.equal(result.body.errorMsg, '批次必填字段不能为空')
      assert.equal(await manageBatchTotal(backendURL, token, 1), before)
    })

    await check('B', 'created draft is owned by the admin and absent from the public page', async () => {
      primaryBatchId = await createBatchFixture(backendURL, token, primaryPayload)
      const managed = await rawRequest(
        backendURL,
        token,
        `/reservation-batch/manage/page?equipmentId=1&status=DRAFT&current=1`,
      )
      assert.equal(managed.status, 200)
      assert.equal(managed.body.success, true)
      assert.equal(managed.body.data.some((row) => String(row.id) === String(primaryBatchId)), true)
      const publicPage = await rawRequest(
        backendURL,
        token,
        '/reservation-batch/page?equipmentId=1&current=1',
      )
      assert.equal(publicPage.status, 200)
      assert.equal(publicPage.body.success, true)
      assert.equal(publicPage.body.data.some((row) => String(row.id) === String(primaryBatchId)), false)
    })

    await check('B', 'creating a batch for another laboratory is denied', async () => {
      const result = await rawRequest(backendURL, token, '/reservation-batch', {
        method: 'POST',
        body: JSON.stringify(validBatchPayload(12, 62)),
      })
      assert.equal(result.status, 403)
      assert.equal(result.body.errorMsg, '无权管理该实验室')
    })

    await check('B', 'draft total quota can be updated', async () => {
      if (!primaryBatchId) return skip('主草稿未创建')
      const result = await rawRequest(backendURL, token, '/reservation-batch', {
        method: 'PUT',
        body: JSON.stringify({ ...primaryPayload, id: primaryBatchId, totalQuota: 6 }),
      })
      assert.equal(result.status, 200)
      assert.equal(result.body.success, true)
      const batch = await fetchBatchFixture(backendURL, token, primaryBatchId)
      assert.equal(batch.totalQuota, 6)
    })

    await check('B', 'publishing initializes remaining quota and status from the saved draft', async () => {
      if (!primaryBatchId) return skip('主草稿未创建')
      const result = await rawRequest(backendURL, token, `/reservation-batch/${primaryBatchId}/publish`, {
        method: 'POST',
      })
      assert.equal(result.status, 200)
      assert.equal(result.body.success, true)
      const batch = await fetchBatchFixture(backendURL, token, primaryBatchId)
      assert.equal(batch.status, 'PUBLISHED')
      assert.equal(batch.totalQuota, 6)
      assert.equal(batch.remainingQuota, batch.totalQuota)
    })

    await check('B', 'published batch rejects further edits with the exact backend message', async () => {
      if (!primaryBatchId) return skip('主批次未创建')
      const result = await rawRequest(backendURL, token, '/reservation-batch', {
        method: 'PUT',
        body: JSON.stringify({ ...primaryPayload, id: primaryBatchId, totalQuota: 7 }),
      })
      assert.equal(result.status, 400)
      assert.equal(result.body.errorMsg, '仅草稿批次可修改')
    })

    await check('B', 'overlapping use time is rejected with the exact backend message', async () => {
      const id = await createBatchFixture(backendURL, token, { ...primaryPayload, totalQuota: 5 })
      const result = await rawRequest(backendURL, token, `/reservation-batch/${id}/publish`, {
        method: 'POST',
      })
      assert.equal(result.status, 400)
      assert.equal(result.body.errorMsg, '同一设备的使用时段不能重叠')
    })

    await check('B', 'booking deadline after use start is rejected with the exact backend message', async () => {
      const startTime = localDateTime(63)
      const id = await createBatchFixture(backendURL, token, validBatchPayload(3, 63, {
        startTime,
        endTime: localDateTime(63, 2),
        bookingEndTime: localDateTime(63, 1),
      }))
      const result = await rawRequest(backendURL, token, `/reservation-batch/${id}/publish`, {
        method: 'POST',
      })
      assert.equal(result.status, 400)
      assert.equal(result.body.errorMsg, '预约截止时间不能晚于使用开始时间')
    })

    await check('B', 'unavailable equipment is rejected with the exact backend message', async () => {
      const id = await createBatchFixture(backendURL, token, validBatchPayload(2, 64))
      const result = await rawRequest(backendURL, token, `/reservation-batch/${id}/publish`, {
        method: 'POST',
      })
      assert.equal(result.status, 400)
      assert.equal(result.body.errorMsg, '设备当前不可预约')
    })

    await check('B', 'quota below one is rejected with the exact backend message', async () => {
      const id = await createBatchFixture(backendURL, token, validBatchPayload(4, 65, { totalQuota: 0 }))
      const result = await rawRequest(backendURL, token, `/reservation-batch/${id}/publish`, {
        method: 'POST',
      })
      assert.equal(result.status, 400)
      assert.equal(result.body.errorMsg, '总名额必须大于等于1')
    })

    await check('B', 'closing a published batch closes booking and rejects a repeated close', async () => {
      if (!primaryBatchId) return skip('主批次未创建')
      const result = await rawRequest(backendURL, token, `/reservation-batch/${primaryBatchId}/close`, {
        method: 'POST',
      })
      assert.equal(result.status, 200)
      assert.equal(result.body.success, true)
      closedBatchId = primaryBatchId
      const batch = await fetchBatchFixture(backendURL, token, primaryBatchId)
      assert.equal(batch.status, 'CLOSED')
      assert.equal(batch.bookingState, 'CLOSED')
      const repeated = await rawRequest(backendURL, token, `/reservation-batch/${primaryBatchId}/close`, {
        method: 'POST',
      })
      assert.equal(repeated.status, 400)
      assert.equal(repeated.body.errorMsg, '仅已发布批次可关闭')
    })

    await check('B', 'published batches can cancel while closed batches cannot', async () => {
      const cancelPayload = await nextAvailablePrimaryPayload(backendURL, token, 9)
      cancelBatchId = await createBatchFixture(backendURL, token, cancelPayload)
      const published = await rawRequest(backendURL, token, `/reservation-batch/${cancelBatchId}/publish`, {
        method: 'POST',
      })
      assert.equal(published.status, 200)
      const cancelled = await rawRequest(backendURL, token, `/reservation-batch/${cancelBatchId}/cancel`, {
        method: 'POST',
      })
      assert.equal(cancelled.status, 200)
      assert.equal((await fetchBatchFixture(backendURL, token, cancelBatchId)).status, 'CANCELLED')
      if (!closedBatchId) return skip('关闭夹具未创建')
      const closedCancel = await rawRequest(backendURL, token, `/reservation-batch/${closedBatchId}/cancel`, {
        method: 'POST',
      })
      assert.equal(closedCancel.status, 400)
      assert.equal(closedCancel.body.errorMsg, '仅草稿或已发布批次可取消')
    })
  } finally {
    await cleanupCreatedBatchFixtures(backendURL, token)
  }

  const finalBatchStates = new Map()
  for (const id of createdBatchIds) {
    finalBatchStates.set(String(id), (await fetchBatchFixture(backendURL, token, id)).status)
  }

  await check('B', 'exactly the close-semantics fixture remains CLOSED', () => {
    const closedIds = [...finalBatchStates].filter(([, status]) => status === 'CLOSED').map(([id]) => id)
    assert.deepEqual(closedIds, [String(closedBatchId)])
  })

  await check('B', 'every other created batch is cleaned to CANCELLED', () => {
    for (const [id, status] of finalBatchStates) {
      if (id !== String(closedBatchId)) assert.equal(status, 'CANCELLED', `batch ${id}`)
    }
  })

  await check('B', 'no created batch remains DRAFT or PUBLISHED', () => {
    for (const [id, status] of finalBatchStates) {
      assert.equal(['DRAFT', 'PUBLISHED'].includes(status), false, `batch ${id}`)
    }
  })
}

function printSummary() {
  console.log('\nSummary')
  for (const groupName of ['A', 'B']) {
    const group = groups[groupName]
    assert.equal(group.pass + group.fail + group.skip, group.expected)
    console.log(`${groupName} group: ${group.pass} PASS, ${group.fail} FAIL, ${group.skip} SKIP（共 ${group.expected} 条）`)
  }
  const pass = groups.A.pass + groups.B.pass
  const fail = groups.A.fail + groups.B.fail
  const skipped = groups.A.skip + groups.B.skip
  console.log(`Summary: ${pass} PASS, ${fail} FAIL, ${skipped} SKIP`)
  if (fail > 0) process.exitCode = 1
}

async function main() {
  await verifyGroupA()
  await verifyGroupB()
  printSummary()
}

main().catch((error) => {
  console.error('FAIL: verification script crashed')
  console.error(error instanceof Error ? error.stack : error)
  process.exitCode = 1
})
