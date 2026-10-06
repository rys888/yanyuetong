import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'

import * as ElementPlusIcons from '@element-plus/icons-vue'

import { createRequest } from '../src/api/createRequest.js'
import { ENUM_VALUES } from '../src/constants/enums.js'
import {
  DEFAULT_CURRENT,
  PAGE_SIZE,
  lastPageOf,
  normalizeCurrent,
} from '../src/constants/pagination.js'
import { routes } from '../src/router/routes.js'
import { formatDateTime } from '../src/utils/datetime.js'
import {
  buildEquipmentQuery,
  buildLaboratoryQuery,
  hasActiveFilter,
  isNumericId,
  stripEmpty,
} from '../src/utils/query.js'

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

function findChildRoute(path) {
  const appRoute = routes.find((route) => route.path === '/')
  return appRoute?.children?.find((route) => route.path === path)
}

async function verifyPureFunctions() {
  console.log('\nA group — pure function behavior')

  await check('pagination constants match the fixed backend contract', () => {
    assert.equal(PAGE_SIZE, 5)
    assert.equal(DEFAULT_CURRENT, 1)
  })

  await check('lastPageOf computes the last fixed-size page', () => {
    assert.equal(lastPageOf(0), 1)
    assert.equal(lastPageOf(1), 1)
    assert.equal(lastPageOf(5), 1)
    assert.equal(lastPageOf(6), 2)
    assert.equal(lastPageOf(12), 3)
    assert.equal(lastPageOf(4), 1)
  })

  await check('normalizeCurrent accepts positive integers and rejects other values', () => {
    assert.equal(normalizeCurrent('1'), 1)
    assert.equal(normalizeCurrent('2'), 2)
    assert.equal(normalizeCurrent('999'), 999)
    assert.equal(normalizeCurrent('0'), 1)
    assert.equal(normalizeCurrent('-1'), 1)
    assert.equal(normalizeCurrent('2.5'), 1)
    assert.equal(normalizeCurrent('abc'), 1)
    assert.equal(normalizeCurrent(undefined), 1)
    assert.equal(normalizeCurrent(null), 1)
  })

  await check('isNumericId accepts only 1–18 ASCII digits', () => {
    for (const value of ['1', '999999', '01']) {
      assert.equal(isNumericId(value), true)
    }
    for (const value of [
      'abc',
      '1.5',
      '-1',
      '',
      null,
      undefined,
      '99999999999999999999',
    ]) {
      assert.equal(isNumericId(value), false)
    }
  })

  await check('stripEmpty removes only empty, undefined, and null values', () => {
    assert.deepEqual(
      stripEmpty({ a: '', b: undefined, c: null, d: 'x', e: 0, f: false }),
      { d: 'x', e: 0, f: false },
    )
  })

  await check('buildLaboratoryQuery normalizes current and ignores campus', () => {
    assert.deepEqual(buildLaboratoryQuery({ current: '2' }), { current: 2 })
    assert.deepEqual(buildLaboratoryQuery({ current: '1', campus: '主校区' }), {
      current: 1,
    })
  })

  await check('buildEquipmentQuery emits no empty keys', () => {
    assert.deepEqual(buildEquipmentQuery({ current: '1' }), { current: 1 })
  })

  await check('buildEquipmentQuery keeps only EquipmentStatus values', () => {
    assert.deepEqual(buildEquipmentQuery({ current: '1', status: 'MAINTENANCE' }), {
      current: 1,
      status: 'MAINTENANCE',
    })
    assert.deepEqual(buildEquipmentQuery({ current: '1', status: 'OPEN' }), {
      current: 1,
    })
    assert.deepEqual(buildEquipmentQuery({ current: '1', status: 'BOGUS' }), {
      current: 1,
    })
  })

  await check('buildEquipmentQuery trims name and drops blank name', () => {
    assert.deepEqual(buildEquipmentQuery({ current: '1', name: '  机  ' }), {
      current: 1,
      name: '机',
    })
    assert.deepEqual(buildEquipmentQuery({ current: '1', name: '   ' }), {
      current: 1,
    })
  })

  await check('buildEquipmentQuery validates laboratoryId', () => {
    assert.deepEqual(buildEquipmentQuery({ current: '1', laboratoryId: '4' }), {
      current: 1,
      laboratoryId: '4',
    })
    for (const laboratoryId of ['abc', '1.5', '']) {
      assert.deepEqual(buildEquipmentQuery({ current: '1', laboratoryId }), {
        current: 1,
      })
    }
  })

  await check('buildEquipmentQuery validates categoryId', () => {
    assert.deepEqual(buildEquipmentQuery({ current: '2', categoryId: '5' }), {
      current: 2,
      categoryId: '5',
    })
    assert.deepEqual(buildEquipmentQuery({ current: '2', categoryId: 'abc' }), {
      current: 2,
    })
  })

  await check('buildEquipmentQuery does not forward out-of-scope parameters', () => {
    assert.deepEqual(
      buildEquipmentQuery({
        current: '1',
        pageSize: '100',
        campus: '东校区',
        bookingEnabled: 'false',
      }),
      { current: 1 },
    )
  })

  await check('hasActiveFilter recognizes the four supported filters', () => {
    assert.equal(hasActiveFilter({ current: 1 }), false)
    for (const params of [
      { current: 1, name: '机' },
      { current: 1, status: 'AVAILABLE' },
      { current: 1, categoryId: '5' },
      { current: 1, laboratoryId: '4' },
    ]) {
      assert.equal(hasActiveFilter(params), true)
    }
  })

  await check('formatDateTime formats backend values and rejects invalid values', () => {
    assert.equal(formatDateTime('2026-10-03T18:00:48'), '2026-10-03 18:00:48')
    assert.equal(formatDateTime(null), '—')
    assert.equal(formatDateTime(''), '—')
    assert.equal(formatDateTime('not-a-date'), '—')
  })
}

async function verifyRoutes() {
  console.log('\nA group — route table behavior')

  await check('four browse routes expose the required path, name, and lazy component', () => {
    const expected = [
      ['laboratories', 'laboratory-list'],
      ['laboratories/:id', 'laboratory-detail'],
      ['equipment', 'equipment-list'],
      ['equipment/:id', 'equipment-detail'],
    ]

    for (const [path, name] of expected) {
      const route = findChildRoute(path)
      assert.equal(route?.path, path)
      assert.equal(route?.name, name)
      assert.equal(typeof route?.component, 'function')
    }
  })

  await check('list routes expose ordered menu metadata', () => {
    const laboratoryRoute = findChildRoute('laboratories')
    const equipmentRoute = findChildRoute('equipment')
    assert.deepEqual(laboratoryRoute.meta.menu, {
      title: '实验室',
      icon: 'School',
      order: 2,
    })
    assert.deepEqual(equipmentRoute.meta.menu, {
      title: '设备',
      icon: 'Cpu',
      order: 3,
    })
  })

  await check('detail routes do not expose menu metadata', () => {
    assert.equal(Object.hasOwn(findChildRoute('laboratories/:id').meta, 'menu'), false)
    assert.equal(Object.hasOwn(findChildRoute('equipment/:id').meta, 'menu'), false)
  })

  await check('menu icon names are real Element Plus icon exports', () => {
    for (const path of ['laboratories', 'equipment']) {
      const iconName = findChildRoute(path).meta.menu.icon
      assert.equal(typeof ElementPlusIcons[iconName], 'object')
    }
  })
}

async function verifyRouterWiringText() {
  console.log('\nA group — 静态文本检查，不是行为验证')

  await check('静态文本检查，不是行为验证：router/index.js imports ./routes.js', async () => {
    const source = await readFile(new URL('../src/router/index.js', import.meta.url), 'utf8')
    assert.match(source, /import\s+\{\s*routes\s*\}\s+from\s+['"]\.\/routes\.js['"]/)
  })
}

async function verifyBackend() {
  console.log('\nB group — real backend contract')

  const configuredBackend = process.env.VERIFY_BACKEND
  if (!configuredBackend) {
    console.log('SKIP: VERIFY_BACKEND is not set — real backend browse checks not run')
    return
  }

  const backendURL = configuredBackend.replace(/\/$/, '')
  const publicRequest = createRequest({ baseURL: backendURL, timeout: 3000 })
  const unauthorizedProbe = await publicRequest({
    method: 'get',
    url: '/laboratory/list',
    params: { current: 1 },
  })

  if (unauthorizedProbe.kind === 'network') {
    console.log(`SKIP: backend unreachable at ${backendURL}`)
    return
  }

  await check('backend unauthorized: laboratory list without token is 401', () => {
    assert.equal(unauthorizedProbe.kind, 'unauthorized')
    assert.equal(unauthorizedProbe.status, 401)
  })

  if (!process.env.VERIFY_TOKEN) {
    console.log('SKIP: VERIFY_TOKEN is not set — token-required browse checks not run')
    return
  }

  const request = createRequest({
    baseURL: backendURL,
    timeout: 3000,
    getToken: () => process.env.VERIFY_TOKEN,
  })

  await check('laboratory list returns four id-desc seed rows', async () => {
    const result = await request({
      method: 'get',
      url: '/laboratory/list',
      params: { current: 1 },
    })
    assert.equal(result.kind, 'ok')
    assert.equal(Array.isArray(result.data), true)
    assert.equal(result.total, 4)
    assert.equal(result.data.length, 4)
    assert.equal(result.data[0].id, 4)
    assert.equal(typeof result.data[0].id, 'number')
    for (const field of [
      'name',
      'campus',
      'building',
      'room',
      'description',
      'status',
      'createTime',
      'updateTime',
    ]) {
      assert.equal(Object.hasOwn(result.data[0], field), true)
    }
    assert.equal(ENUM_VALUES.LaboratoryStatus.includes(result.data[0].status), true)
  })

  await check('laboratory list ignores pageSize and keeps fixed page size', async () => {
    const result = await request({
      method: 'get',
      url: '/laboratory/list',
      params: { current: 1, pageSize: 2 },
    })
    assert.equal(result.data.length, 4)
  })

  await check('laboratory list campus miss returns an empty array', async () => {
    const result = await request({
      method: 'get',
      url: '/laboratory/list',
      params: { campus: 'nowhere' },
    })
    assert.equal(result.kind, 'ok')
    assert.equal(result.total, 0)
    assert.equal(result.data.length, 0)
  })

  await check('laboratory detail returns the seeded laboratory', async () => {
    const result = await request({ method: 'get', url: '/laboratory/1' })
    assert.equal(result.kind, 'ok')
    assert.equal(result.data.id, 1)
    // This assertion intentionally depends on the approved seed data.
    assert.equal(result.data.name, '材料科学实验中心')
  })

  await check('missing laboratory normalizes success-without-data to nulls', async () => {
    const result = await request({ method: 'get', url: '/laboratory/999999' })
    assert.equal(result.kind, 'ok')
    assert.equal(result.ok, true)
    assert.equal(result.data, null)
    assert.equal(result.total, null)
  })

  await check('non-numeric laboratory id produces the measured 500 channel', async () => {
    const result = await request({ method: 'get', url: '/laboratory/abc' })
    assert.equal(result.kind, 'error')
    assert.equal(result.status, 500)
  })

  await check('equipment first page returns five joined seed rows', async () => {
    const result = await request({
      method: 'get',
      url: '/equipment/page',
      params: { current: 1 },
    })
    assert.equal(result.kind, 'ok')
    assert.equal(result.total, 12)
    assert.equal(result.data.length, 5)
    assert.equal(result.data[0].id, 12)
    for (const field of [
      'laboratoryName',
      'campus',
      'building',
      'room',
      'categoryName',
      'bookingEnabled',
      'location',
      'usageRequirement',
    ]) {
      assert.equal(Object.hasOwn(result.data[0], field), true)
    }
    assert.equal(typeof result.data[0].bookingEnabled, 'boolean')
  })

  await check('equipment pages two through four preserve fixed pagination', async () => {
    const page2 = await request({
      method: 'get',
      url: '/equipment/page',
      params: { current: 2 },
    })
    const page3 = await request({
      method: 'get',
      url: '/equipment/page',
      params: { current: 3 },
    })
    const page4 = await request({
      method: 'get',
      url: '/equipment/page',
      params: { current: 4 },
    })
    assert.equal(page2.data.length, 5)
    assert.equal(page3.data.length, 2)
    assert.equal(page4.total, 12)
    assert.equal(page4.data.length, 0)
  })

  const filterCases = [
    ['laboratoryId', '4', 4],
    ['categoryId', '5', 2],
    ['status', 'MAINTENANCE', 3],
    ['name', '离心', 1],
  ]
  for (const [key, value, expectedTotal] of filterCases) {
    await check(`equipment ${key} filter changes total to ${expectedTotal}`, async () => {
      const result = await request({
        method: 'get',
        url: '/equipment/page',
        params: { current: 1, [key]: value },
      })
      assert.equal(result.total, expectedTotal)
      assert.notEqual(result.total, 12)
    })
  }

  await check('invalid equipment status produces the measured 400 channel', async () => {
    const result = await request({
      method: 'get',
      url: '/equipment/page',
      params: { current: 1, status: 'BOGUS' },
    })
    assert.equal(result.kind, 'clientError')
    assert.equal(result.status, 400)
  })

  await check('equipment detail returns the seeded equipment', async () => {
    const result = await request({ method: 'get', url: '/equipment/1' })
    assert.equal(result.kind, 'ok')
    assert.equal(result.data.id, 1)
    assert.equal(result.data.name, '扫描电子显微镜')
  })

  await check('missing equipment uses the business failure channel', async () => {
    const result = await request({ method: 'get', url: '/equipment/999999' })
    assert.equal(result.kind, 'business')
    assert.equal(result.ok, false)
    assert.equal(result.message, '设备不存在')
  })

  await check('non-numeric equipment id produces the measured 500 channel', async () => {
    const result = await request({ method: 'get', url: '/equipment/abc' })
    assert.equal(result.kind, 'error')
    assert.equal(result.status, 500)
  })

  await check('equipment categories return six sorted rows without total', async () => {
    const result = await request({ method: 'get', url: '/equipment-category/list' })
    assert.equal(result.kind, 'ok')
    assert.equal(Array.isArray(result.data), true)
    assert.equal(result.data.length, 6)
    assert.equal(result.total, null)
    assert.deepEqual(
      {
        id: result.data[0].id,
        name: result.data[0].name,
        sort: result.data[0].sort,
      },
      { id: 1, name: '显微成像', sort: 1 },
    )
  })
}

async function main() {
  await verifyPureFunctions()
  await verifyRoutes()
  await verifyRouterWiringText()
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
