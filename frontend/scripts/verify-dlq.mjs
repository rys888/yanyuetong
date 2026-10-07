// B 组运行契约：这是一组有状态的真机断言。B7、B8、B9 会把夹具推进到终态，
// 每次运行前必须使用控制面提供的命令重建夹具；本脚本不会连接数据库或自行重置数据。
// 夹具未重建时，破坏性条目会记为 SKIP 并提示先重建夹具，而不是误报代码失败。
// 所有夹具 id 都只从环境变量读取，不在脚本中硬编码；拒绝断言钉死具体 errorMsg。
import assert from 'node:assert/strict'
import { readFile, readdir } from 'node:fs/promises'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

import { parseBigintJson } from '../src/api/bigintJson.js'
import { routes } from '../src/router/routes.js'

const FRONTEND_ROOT = fileURLToPath(new URL('../', import.meta.url))
const groups = {
  A: { expected: 13, pass: 0, fail: 0, skip: 0 },
  B: { expected: 12, pass: 0, fail: 0, skip: 0 },
}

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

async function readSource(relativePath) {
  return readFile(path.join(FRONTEND_ROOT, relativePath), 'utf8')
}

async function collectSourceFiles(directory) {
  const entries = await readdir(directory, { withFileTypes: true })
  const files = []
  for (const entry of entries) {
    const absolutePath = path.join(directory, entry.name)
    if (entry.isDirectory()) files.push(...(await collectSourceFiles(absolutePath)))
    else if (/\.(?:js|vue)$/.test(entry.name)) files.push(absolutePath)
  }
  return files
}

async function importRequired(relativePath) {
  try {
    return await import(new URL(`../${relativePath}`, import.meta.url))
  } catch (error) {
    throw new Error(`无法导入 ${relativePath}：${error instanceof Error ? error.message : String(error)}`, {
      cause: error,
    })
  }
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
  console.log('\nA group — DLQ read-only frontend contract')

  await check('A', 'DLQ API exposes the exact transport surface', async () => {
    const source = await readSource('src/api/dlq.js')
    const exports = [...source.matchAll(/export\s+function\s+(\w+)\s*\(/g)].map(
      (match) => match[1],
    )
    assert.deepEqual(exports.sort(), [
      'fetchReservationDlqById',
      'fetchReservationDlqPage',
      'replayReservationDlq',
    ])
    assert.equal((source.match(/method\s*:\s*['"]get['"]/g) ?? []).length, 2)
    assert.equal((source.match(/method\s*:\s*['"]post['"]/g) ?? []).length, 1)
    const urls = [...source.matchAll(/url\s*:\s*([`'"])(.*?)\1/g)].map((match) => match[2])
    assert.deepEqual(urls.sort(), [
      '/reservation-dlq/${id}',
      '/reservation-dlq/${id}/replay',
      '/reservation-dlq/page',
    ].sort())
    assert.doesNotMatch(source, /axios/i)
  })

  await check('A', 'known replay reason codes map to exact Chinese text and retain code', async () => {
    const { describeDlqReplayReason } = await importRequired('src/constants/messages.js')
    const cases = [
      ['REPLAY_CONFIRM_NACK', '消息队列未确认（NACK），台账未做任何变更'],
      ['REPLAY_CONFIRM_RETURN', '消息被交换机退回，台账未做任何变更'],
      ['REPLAY_PUBLISH_UNKNOWN', '投递结果不确定，台账未做任何变更，请刷新确认'],
    ]
    for (const [code, text] of cases) {
      assert.deepEqual(describeDlqReplayReason(code), { text, code })
    }
    assert.deepEqual(
      describeDlqReplayReason('消息发布失败：REPLAY_PUBLISH_UNKNOWN，请刷新后确认'),
      {
        text: '投递结果不确定，台账未做任何变更，请刷新确认',
        code: 'REPLAY_PUBLISH_UNKNOWN',
      },
    )
  })

  await check('A', 'Chinese and unknown replay reasons pass through while null falls back', async () => {
    const { describeDlqReplayReason } = await importRequired('src/constants/messages.js')
    for (const reason of ['死信记录不存在', '已达到最大重放次数', 'FUTURE_REASON']) {
      assert.deepEqual(describeDlqReplayReason(reason), { text: reason, code: null })
    }
    assert.deepEqual(describeDlqReplayReason(null), { text: '重放失败', code: null })
    assert.deepEqual(describeDlqReplayReason(''), { text: '重放失败', code: null })
  })

  await check('A', 'JSON text formatting preserves an 18-digit id with a lossy positive control', async () => {
    const { formatJsonText } = await importRequired('src/utils/jsonText.js')
    const raw = '{"reservationId":190123456789012345,"message":"ok"}'
    const formatted = formatJsonText(raw)
    assert.match(formatted, /190123456789012345/)
    assert.notEqual(JSON.stringify(JSON.parse(raw)), raw)
    assert.notEqual(formatted, JSON.stringify(JSON.parse(raw), null, 2))
  })

  await check('A', 'JSON text formatting indents structures and keeps empty containers compact', async () => {
    const { formatJsonText } = await importRequired('src/utils/jsonText.js')
    assert.equal(
      formatJsonText('{"items":[1,{"empty":{},"list":[]}],"active":true}'),
      '{\n  "items": [\n    1,\n    {\n      "empty": {},\n      "list": []\n    }\n  ],\n  "active": true\n}',
    )
  })

  await check('A', 'JSON text formatting returns invalid and empty inputs unchanged', async () => {
    const { formatJsonText } = await importRequired('src/utils/jsonText.js')
    for (const raw of ['{"broken":}', '{\u00a0"invalid":true}', '', null]) {
      assert.equal(formatJsonText(raw), raw)
    }
  })

  await check('A', 'JSON text formatter source contains no native parse or stringify calls', async () => {
    const source = await readSource('src/utils/jsonText.js')
    assert.doesNotMatch(source, /JSON\.(?:parse|stringify)/)
  })

  await check('A', 'DLQ query normalization enforces status whitelist and ignores current as a filter', async () => {
    const { buildDlqQuery, hasActiveDlqFilter } = await importRequired('src/utils/query.js')
    assert.deepEqual(buildDlqQuery({ current: '3', status: 'NEW' }), { current: 3, status: 'NEW' })
    assert.deepEqual(buildDlqQuery({ current: '-2', status: 'INVALID' }), { current: 1 })
    assert.equal(hasActiveDlqFilter({ current: 4 }), false)
    assert.equal(hasActiveDlqFilter({ current: 4, status: 'DISCARDED' }), true)
  })

  await check('A', 'DLQ routes have exact paths, names, and menu metadata', () => {
    const adminRoute = routes.find((route) => route.path === '/admin')
    const listRoute = adminRoute?.children?.find((route) => route.name === 'admin-dlq-list')
    const detailRoute = adminRoute?.children?.find((route) => route.name === 'admin-dlq-detail')
    assert.equal(listRoute?.path, 'dlq')
    assert.deepEqual(listRoute?.meta?.menu, { title: '死信治理', icon: 'Warning', order: 6 })
    assert.equal(detailRoute?.path, 'dlq/:id')
    assert.equal(Object.hasOwn(detailRoute?.meta ?? {}, 'menu'), false)
  })

  await check('A', 'DLQ views preserve read-only and access-control boundaries', async () => {
    const listSource = await readSource('src/views/admin/dlq/DlqListView.vue')
    const detailSource = await readSource('src/views/admin/dlq/DlqDetailView.vue')
    assert.doesNotMatch(listSource, /payload/i)
    assert.match(detailSource, /import\s*\{\s*formatJsonText\s*\}/)
    assert.doesNotMatch(detailSource, /JSON\.parse/)
    const files = await collectSourceFiles(path.join(FRONTEND_ROOT, 'src', 'views', 'admin', 'dlq'))
    for (const file of files) {
      const source = await readFile(file, 'utf8')
      assert.doesNotMatch(source, /axios/i, file)
      assert.doesNotMatch(source, /role\s*===/, file)
    }
  })

  await check('A', 'replay confirmation precedes the request and cancellation sends no request', async () => {
    const source = await readSource('src/views/admin/dlq/DlqDetailView.vue')
    const body = extractFunctionBody(source, 'handleReplay')
    const confirmIndex = body.indexOf('ElMessageBox.confirm(')
    const replayIndex = body.indexOf('replayReservationDlq(')
    assert.notEqual(confirmIndex, -1)
    assert.notEqual(replayIndex, -1)
    assert.equal(confirmIndex < replayIndex, true)
    const catchBody = body.match(/catch\s*(?:\([^)]*\)\s*)?\{([^}]*)\}/s)?.[1]
    assert.notEqual(catchBody, undefined)
    assert.doesNotMatch(catchBody, /replayReservationDlq\s*\(/)
    assert.match(catchBody, /\breturn\b/)
  })

  await check('A', 'replay failure uses a persistent alert without destroying detail state', async () => {
    const source = await readSource('src/views/admin/dlq/DlqDetailView.vue')
    const body = extractFunctionBody(source, 'handleReplay')
    assert.doesNotMatch(body, /state\.value\s*=\s*['"]error['"]/)
    assert.match(source, /<el-alert\b/)
  })

  await check('A', 'replay confirmation is driven by DLQ status actions without a NEW literal branch', async () => {
    const source = await readSource('src/views/admin/dlq/DlqDetailView.vue')
    assert.match(
      source,
      /resolveStatus\s*\(\s*['"]ReservationDlqStatus['"]/,
    )
    assert.match(source, /\.actions\b/)
    assert.doesNotMatch(source, /\bstatus\s*===\s*['"]NEW['"]/)
  })
}

async function rawRequest(backendURL, token, requestPath, options = {}) {
  const response = await fetch(`${backendURL}${requestPath}`, {
    ...options,
    headers: {
      ...(token ? { authorization: token } : {}),
      ...options.headers,
    },
    signal: AbortSignal.timeout(5000),
  })
  const text = await response.text()
  const body = text ? parseBigintJson(text) : null
  console.log(`  measured: ${options.method ?? 'GET'} ${requestPath} -> ${response.status}`)
  return { status: response.status, body, text }
}

function missingEnvironment(...names) {
  const missing = names.filter((name) => !process.env[name])
  return missing.length > 0 ? skip(`${missing.join('、')} 未提供，真机断言未运行`) : null
}

async function fetchDlqDetail(backendURL, token, id) {
  return rawRequest(backendURL, token, `/reservation-dlq/${id}`)
}

async function verifyGroupB() {
  console.log('\nB group — stateful real-backend DLQ contract')
  console.log('B 组有状态：运行前必须先重建夹具；未重建的破坏性条目记 SKIP。')

  const backend = process.env.VERIFY_BACKEND?.replace(/\/$/, '')
  const adminToken = process.env.VERIFY_ADMIN_TOKEN
  let replayOkAdvanced = false

  await check('B', 'list payload is always null or absent', async () => {
    const unavailable = missingEnvironment('VERIFY_BACKEND', 'VERIFY_ADMIN_TOKEN')
    if (unavailable) return unavailable
    const result = await rawRequest(backend, adminToken, '/reservation-dlq/page?current=1')
    assert.equal(result.status, 200)
    assert.equal(result.body?.success, true)
    assert.equal(Array.isArray(result.body?.data), true)
    assert.equal(result.body.data.length > 0, true)
    for (const record of result.body.data) assert.equal(record.payload == null, true)
  })

  await check('B', 'detail payload retains its 18-digit reservation id without rewriting', async () => {
    const unavailable = missingEnvironment(
      'VERIFY_BACKEND',
      'VERIFY_ADMIN_TOKEN',
      'VERIFY_DLQ_REPLAY_OK_ID',
    )
    if (unavailable) return unavailable
    const result = await fetchDlqDetail(
      backend,
      adminToken,
      process.env.VERIFY_DLQ_REPLAY_OK_ID,
    )
    assert.equal(result.status, 200)
    assert.equal(result.body?.success, true)
    const record = result.body?.data
    assert.equal(typeof record?.payload, 'string')
    assert.notEqual(record.payload, '')
    assert.match(String(record.reservationId), /^\d{18}$/)
    assert.equal(record.payload.includes(String(record.reservationId)), true)
    const nativeRoundTrip = JSON.stringify(JSON.parse(record.payload))
    assert.notEqual(nativeRoundTrip, record.payload)
    assert.equal(nativeRoundTrip.includes(String(record.reservationId)), false)
  })

  await check('B', 'max replay count is rejected with the exact reason', async () => {
    const unavailable = missingEnvironment(
      'VERIFY_BACKEND',
      'VERIFY_ADMIN_TOKEN',
      'VERIFY_DLQ_MAX_REPLAY_ID',
    )
    if (unavailable) return unavailable
    const result = await rawRequest(
      backend,
      adminToken,
      `/reservation-dlq/${process.env.VERIFY_DLQ_MAX_REPLAY_ID}/replay`,
      { method: 'POST' },
    )
    assert.equal(result.status, 200)
    assert.equal(result.body?.success, false)
    assert.equal(result.body?.errorMsg, '已达到最大重放次数')
  })

  await check('B', 'max replay rejection leaves the NEW record unchanged', async () => {
    const unavailable = missingEnvironment(
      'VERIFY_BACKEND',
      'VERIFY_ADMIN_TOKEN',
      'VERIFY_DLQ_MAX_REPLAY_ID',
    )
    if (unavailable) return unavailable
    const result = await fetchDlqDetail(
      backend,
      adminToken,
      process.env.VERIFY_DLQ_MAX_REPLAY_ID,
    )
    assert.equal(result.status, 200)
    assert.equal(result.body?.success, true)
    assert.equal(result.body?.data?.status, 'NEW')
    assert.equal(result.body?.data?.replayCount, 3)
  })

  await check('B', 'REPLAYED record is rejected with the exact reason', async () => {
    const unavailable = missingEnvironment(
      'VERIFY_BACKEND',
      'VERIFY_ADMIN_TOKEN',
      'VERIFY_DLQ_REPLAYED_ID',
    )
    if (unavailable) return unavailable
    const result = await rawRequest(
      backend,
      adminToken,
      `/reservation-dlq/${process.env.VERIFY_DLQ_REPLAYED_ID}/replay`,
      { method: 'POST' },
    )
    assert.equal(result.status, 200)
    assert.equal(result.body?.success, false)
    assert.equal(result.body?.errorMsg, '死信记录已处理，不能重复重放')
  })

  await check('B', 'DISCARDED record is rejected with the exact reason', async () => {
    const unavailable = missingEnvironment(
      'VERIFY_BACKEND',
      'VERIFY_ADMIN_TOKEN',
      'VERIFY_DLQ_DISCARDED_ID',
    )
    if (unavailable) return unavailable
    const result = await rawRequest(
      backend,
      adminToken,
      `/reservation-dlq/${process.env.VERIFY_DLQ_DISCARDED_ID}/replay`,
      { method: 'POST' },
    )
    assert.equal(result.status, 200)
    assert.equal(result.body?.success, false)
    assert.equal(result.body?.errorMsg, '死信记录已处理，不能重复重放')
  })

  await check('B', 'unreplayable payload is rejected and becomes DISCARDED', async () => {
    const unavailable = missingEnvironment(
      'VERIFY_BACKEND',
      'VERIFY_ADMIN_TOKEN',
      'VERIFY_DLQ_UNREPLAYABLE_ID',
    )
    if (unavailable) return unavailable
    const id = process.env.VERIFY_DLQ_UNREPLAYABLE_ID
    const before = await fetchDlqDetail(backend, adminToken, id)
    assert.equal(before.status, 200)
    assert.equal(before.body?.success, true)
    if (before.body?.data?.status !== 'NEW') {
      return skip('夹具未重建，请先跑夹具 reset')
    }
    const replay = await rawRequest(backend, adminToken, `/reservation-dlq/${id}/replay`, {
      method: 'POST',
    })
    assert.equal(replay.status, 200)
    assert.equal(replay.body?.success, false)
    assert.equal(replay.body?.errorMsg, 'payload 无法重放')
    const after = await fetchDlqDetail(backend, adminToken, id)
    assert.equal(after.status, 200)
    assert.equal(after.body?.success, true)
    assert.equal(after.body?.data?.status, 'DISCARDED')
  })

  await check('B', 'already-persisted payload is rejected and becomes DISCARDED', async () => {
    const unavailable = missingEnvironment(
      'VERIFY_BACKEND',
      'VERIFY_ADMIN_TOKEN',
      'VERIFY_DLQ_ALREADY_PERSISTED_ID',
    )
    if (unavailable) return unavailable
    const id = process.env.VERIFY_DLQ_ALREADY_PERSISTED_ID
    const before = await fetchDlqDetail(backend, adminToken, id)
    assert.equal(before.status, 200)
    assert.equal(before.body?.success, true)
    if (before.body?.data?.status !== 'NEW') {
      return skip('夹具未重建，请先跑夹具 reset')
    }
    const replay = await rawRequest(backend, adminToken, `/reservation-dlq/${id}/replay`, {
      method: 'POST',
    })
    assert.equal(replay.status, 200)
    assert.equal(replay.body?.success, false)
    assert.equal(replay.body?.errorMsg, '已落库，无需重放')
    const after = await fetchDlqDetail(backend, adminToken, id)
    assert.equal(after.status, 200)
    assert.equal(after.body?.success, true)
    assert.equal(after.body?.data?.status, 'DISCARDED')
  })

  await check('B', 'valid NEW record replays and reloads as REPLAYED with count one', async () => {
    const unavailable = missingEnvironment(
      'VERIFY_BACKEND',
      'VERIFY_ADMIN_TOKEN',
      'VERIFY_DLQ_REPLAY_OK_ID',
    )
    if (unavailable) return unavailable
    const id = process.env.VERIFY_DLQ_REPLAY_OK_ID
    const before = await fetchDlqDetail(backend, adminToken, id)
    assert.equal(before.status, 200)
    assert.equal(before.body?.success, true)
    if (before.body?.data?.status !== 'NEW') {
      return skip('夹具未重建，请先跑夹具 reset')
    }
    const replay = await rawRequest(backend, adminToken, `/reservation-dlq/${id}/replay`, {
      method: 'POST',
    })
    assert.equal(replay.status, 200)
    assert.equal(replay.body?.success, true)
    const after = await fetchDlqDetail(backend, adminToken, id)
    assert.equal(after.status, 200)
    assert.equal(after.body?.success, true)
    assert.equal(after.body?.data?.status, 'REPLAYED')
    assert.equal(after.body?.data?.replayCount, 1)
    replayOkAdvanced = true
  })

  await check('B', 'successfully replayed record rejects a second replay', async () => {
    const unavailable = missingEnvironment(
      'VERIFY_BACKEND',
      'VERIFY_ADMIN_TOKEN',
      'VERIFY_DLQ_REPLAY_OK_ID',
    )
    if (unavailable) return unavailable
    if (!replayOkAdvanced) return skip('依赖的 B9 未成功推进夹具，本条未运行')
    const result = await rawRequest(
      backend,
      adminToken,
      `/reservation-dlq/${process.env.VERIFY_DLQ_REPLAY_OK_ID}/replay`,
      { method: 'POST' },
    )
    assert.equal(result.status, 200)
    assert.equal(result.body?.success, false)
    assert.equal(result.body?.errorMsg, '死信记录已处理，不能重复重放')
  })

  await check('B', 'missing id returns the exact business failure for detail and replay', async () => {
    const unavailable = missingEnvironment(
      'VERIFY_BACKEND',
      'VERIFY_ADMIN_TOKEN',
      'VERIFY_DLQ_MISSING_ID',
    )
    if (unavailable) return unavailable
    const id = process.env.VERIFY_DLQ_MISSING_ID
    const detailResult = await fetchDlqDetail(backend, adminToken, id)
    assert.equal(detailResult.status, 200)
    assert.equal(detailResult.body?.success, false)
    assert.equal(detailResult.body?.errorMsg, '死信记录不存在')
    const replayResult = await rawRequest(
      backend,
      adminToken,
      `/reservation-dlq/${id}/replay`,
      { method: 'POST' },
    )
    assert.equal(replayResult.status, 200)
    assert.equal(replayResult.body?.success, false)
    assert.equal(replayResult.body?.errorMsg, '死信记录不存在')
  })

  await check('B', 'DLQ page enforces unauthenticated and USER permission boundaries', async () => {
    const unavailable = missingEnvironment('VERIFY_BACKEND', 'VERIFY_STUDENT_TOKEN')
    if (unavailable) return unavailable
    const unauthenticated = await rawRequest(backend, '', '/reservation-dlq/page')
    assert.equal(unauthenticated.status, 401)
    const student = await rawRequest(
      backend,
      process.env.VERIFY_STUDENT_TOKEN,
      '/reservation-dlq/page',
    )
    assert.equal(student.status, 403)
  })
}

function printSummary() {
  console.log('\nSummary')
  console.log('B group contract: stateful; rebuild fixtures before each run; stale destructive fixtures SKIP.')
  console.log('B group fixtures: ids come only from env; rejection checks require exact errorMsg text.')
  for (const groupName of ['A', 'B']) {
    const group = groups[groupName]
    assert.equal(group.pass + group.fail + group.skip, group.expected)
    console.log(`${groupName} group: ${group.pass} PASS, ${group.fail} FAIL, ${group.skip} SKIP（共 ${group.expected} 条）`)
  }
  if (groups.A.fail + groups.B.fail > 0) process.exitCode = 1
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
