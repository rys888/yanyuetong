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
  A: { expected: 11, pass: 0, fail: 0, skip: 0 },
  B: { expected: 8, pass: 0, fail: 0, skip: 0 },
}

let createdLaboratoryId = null
let createdEquipmentId = null

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

  await check('A', 'admin route registers the three ordered menu entries with real icons', () => {
    const adminRoute = routes.find((route) => route.path === '/admin')
    assert.deepEqual(
      adminRoute?.children?.map((route) => [route.path, route.name]),
      [
        ['', 'admin-home'],
        ['laboratories', 'admin-laboratory-list'],
        ['equipment', 'admin-equipment-list'],
      ],
    )
    assert.equal(adminRoute.children[1].meta.menu.order, 2)
    assert.equal(adminRoute.children[2].meta.menu.order, 3)
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
  ])

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

  await check('A', 'later admin domains do not exist yet', async () => {
    const files = await collectSourceFiles(path.join(SRC_ROOT, 'views', 'admin'))
    for (const file of files) assert.doesNotMatch(path.basename(file), /batch|reservation|dlq/i)
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
