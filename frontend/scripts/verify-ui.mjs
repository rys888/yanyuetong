import assert from 'node:assert/strict'
import { readFile, readdir } from 'node:fs/promises'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const FRONTEND_ROOT = fileURLToPath(new URL('../', import.meta.url))
const SRC_ROOT = path.join(FRONTEND_ROOT, 'src')
const groups = {
  A: { expected: 3, pass: 0, fail: 0 },
  B: { expected: 2, pass: 0, fail: 0 },
  C: { expected: 3, pass: 0, fail: 0 },
  D: { expected: 4, pass: 0, fail: 0 },
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

async function collectFiles(directory, extensions) {
  const entries = await readdir(directory, { withFileTypes: true })
  const files = []
  for (const entry of entries) {
    const absolutePath = path.join(directory, entry.name)
    if (entry.isDirectory()) files.push(...(await collectFiles(absolutePath, extensions)))
    else if (extensions.has(path.extname(entry.name))) files.push(absolutePath)
  }
  return files.sort()
}

function relativeToFrontend(absolutePath) {
  return path.relative(FRONTEND_ROOT, absolutePath).split(path.sep).join('/')
}

function extractTopLevelFunctions(source) {
  const starts = [...source.matchAll(/^(?:async\s+)?function\s+([A-Za-z_$][\w$]*)\s*\(/gm)]
  return starts.map((match) => {
    const bodyStart = source.indexOf('\n', match.index)
    const remainder = source.slice(bodyStart + 1)
    const closingMatch = remainder.match(/^}/m)
    assert.ok(closingMatch, `missing top-level closing brace for ${match[1]}`)
    const end = bodyStart + 1 + closingMatch.index + 1
    return { name: match[1], source: source.slice(match.index, end) }
  })
}

function extractMatchingBraceBlock(source, braceStart) {
  let depth = 0
  for (let index = braceStart; index < source.length; index += 1) {
    if (source[index] === '{') depth += 1
    if (source[index] === '}') depth -= 1
    if (depth === 0) return source.slice(braceStart + 1, index)
  }
  assert.fail('missing matching closing brace')
}

function unauthorizedBranches(functionSource) {
  const conditionPattern = /if\s*\(\s*[\w$.]+\.kind\s*===\s*['"]unauthorized['"]\s*\)\s*\{/g
  return [...functionSource.matchAll(conditionPattern)].map((match) => {
    const braceStart = match.index + match[0].lastIndexOf('{')
    return extractMatchingBraceBlock(functionSource, braceStart)
  })
}

async function verifyUnauthorizedClosures() {
  console.log('\nA group · offline loader unauthorized closure contracts')
  const viewFiles = await collectFiles(path.join(SRC_ROOT, 'views'), new Set(['.vue']))

  await check('A', '15 state loaders set state to error in every unauthorized branch', async () => {
    let branchCount = 0
    for (const file of viewFiles) {
      const source = await readFile(file, 'utf8')
      for (const fn of extractTopLevelFunctions(source)) {
        if (!/state\.value\s*=\s*['"]loading['"]/.test(fn.source)) continue
        for (const branch of unauthorizedBranches(fn.source)) {
          branchCount += 1
          assert.match(
            branch,
            /state\.value\s*=\s*['"]error['"]/,
            `${relativeToFrontend(file)}:${fn.name} unauthorized branch must set state to error`,
          )
        }
      }
    }
    assert.equal(branchCount, 15, 'expected all 15 state-loader unauthorized branches')
  })

  await check('A', 'EquipmentDetail loadBatches sets batchState to error on unauthorized', async () => {
    let branchCount = 0
    for (const file of viewFiles) {
      const source = await readFile(file, 'utf8')
      for (const fn of extractTopLevelFunctions(source)) {
        if (/state\.value\s*=\s*['"]loading['"]/.test(fn.source)) continue
        if (!/batchState\.value\s*=\s*['"]loading['"]/.test(fn.source)) continue
        for (const branch of unauthorizedBranches(fn.source)) {
          branchCount += 1
          assert.match(
            branch,
            /batchState\.value\s*=\s*['"]error['"]/,
            `${relativeToFrontend(file)}:${fn.name} unauthorized branch must set batchState to error`,
          )
        }
      }
    }
    assert.equal(branchCount, 1, 'expected the loadBatches unauthorized branch')
  })

  await check('A', 'BatchDetail pollReservation unauthorized finishes as pending without bare timer cleanup', async () => {
    const source = await readFile(path.join(SRC_ROOT, 'views/batch/BatchDetailView.vue'), 'utf8')
    const pollReservation = extractTopLevelFunctions(source).find((fn) => fn.name === 'pollReservation')
    assert.ok(pollReservation, 'missing pollReservation')
    const branches = unauthorizedBranches(pollReservation.source)
    assert.equal(branches.length, 1, 'expected one pollReservation unauthorized branch')
    assert.match(branches[0], /finishAsPending\s*\(/)
    assert.doesNotMatch(branches[0], /clearPollingTimers\s*\(\s*\)/)
  })
}

async function verifyAxiosIsolation() {
  console.log('\nB group · axios isolation')

  await check('B', 'views, layouts, and components contain no axios references', async () => {
    const roots = ['views', 'layouts', 'components'].map((directory) => path.join(SRC_ROOT, directory))
    const files = (
      await Promise.all(roots.map((root) => collectFiles(root, new Set(['.vue', '.js']))))
    ).flat()
    for (const file of files) {
      const source = await readFile(file, 'utf8')
      assert.doesNotMatch(source, /axios/i, `${relativeToFrontend(file)} must use the API layer`)
    }
  })

  await check('B', 'createRequest.js is the only src file that imports axios', async () => {
    const files = await collectFiles(SRC_ROOT, new Set(['.vue', '.js']))
    const importers = []
    for (const file of files) {
      const source = await readFile(file, 'utf8')
      if (/\bimport\s+[\s\S]*?\sfrom\s*['"]axios['"]/.test(source)) {
        importers.push(relativeToFrontend(file))
      }
    }
    assert.deepEqual(importers, ['src/api/createRequest.js'])
  })
}

async function verifyBreakpoints() {
  console.log('\nC group · responsive breakpoint contracts')
  const vueFiles = await collectFiles(SRC_ROOT, new Set(['.vue']))

  await check('C', 'all media-query max-width breakpoints use the approved whitelist', async () => {
    const allowed = new Set([1200, 992, 768, 560])
    const found = new Set()
    for (const file of vueFiles) {
      const source = await readFile(file, 'utf8')
      for (const match of source.matchAll(/@media\s*\(\s*max-width:\s*(\d+)px\s*\)/g)) {
        const breakpoint = Number(match[1])
        found.add(breakpoint)
        assert.ok(allowed.has(breakpoint), `${relativeToFrontend(file)} uses ${breakpoint}px`)
      }
    }
    assert.ok(found.size > 0, 'expected at least one responsive breakpoint')
  })

  await check('C', 'AppLayout defines both 992px compression and 768px narrow breakpoints', async () => {
    const source = await readFile(path.join(SRC_ROOT, 'layouts/AppLayout.vue'), 'utf8')
    assert.match(source, /@media\s*\(\s*max-width:\s*992px\s*\)/)
    assert.match(source, /@media\s*\(\s*max-width:\s*768px\s*\)/)
  })

  await check('C', 'AdminLayout collapse threshold remains the literal 992', async () => {
    const source = await readFile(path.join(SRC_ROOT, 'layouts/AdminLayout.vue'), 'utf8')
    const updateCollapseState = extractTopLevelFunctions(source).find(
      (fn) => fn.name === 'updateCollapseState',
    )
    assert.ok(updateCollapseState, 'missing updateCollapseState')
    assert.match(updateCollapseState.source, /window\.innerWidth\s*<=\s*992/)
  })
}

async function verifyPageStates() {
  console.log('\nD group · routed data-page state contracts')
  const routesSource = await readFile(path.join(SRC_ROOT, 'router/routes.js'), 'utf8')
  const routeImports = [...routesSource.matchAll(/component:\s*\(\)\s*=>\s*import\(['"]\.\.\/views\/([^'"]+)['"]\)/g)]
    .map((match) => `src/views/${match[1]}`)
    .filter((relativePath) => !relativePath.startsWith('src/views/error/'))

  const dataPages = []
  for (const relativePath of routeImports) {
    const source = await readFile(path.join(FRONTEND_ROOT, relativePath), 'utf8')
    if (/\bimport\s+[\s\S]*?\sfrom\s*['"][^'"]*\/api\/[^'"]+['"]/.test(source)) {
      dataPages.push({ relativePath, source })
    }
  }

  await check('D', 'router extraction finds exactly 15 API-backed data pages', () => {
    assert.equal(dataPages.length, 15)
  })

  await check('D', 'all 14 non-login data pages expose loading, error, empty, and ready states', () => {
    const pages = dataPages.filter(({ relativePath }) => !relativePath.endsWith('/login/LoginView.vue'))
    assert.equal(pages.length, 14)
    for (const { relativePath, source } of pages) {
      assert.match(source, /v-loading/, `${relativePath} missing v-loading`)
      assert.match(source, /state\s*===\s*['"]error['"]/, `${relativePath} missing error state`)
      assert.match(source, /<(?:EmptyState|el-empty)\b/, `${relativePath} missing empty state`)
      assert.match(source, /state\s*===\s*['"]ready['"]/, `${relativePath} missing ready state`)
    }
  })

  await check('D', 'LoginView keeps its sending/submitting loaders and shared result-error path', () => {
    const login = dataPages.find(({ relativePath }) => relativePath.endsWith('/login/LoginView.vue'))
    assert.ok(login, 'LoginView must remain an API-backed routed page')
    assert.match(login.source, /:loading\s*=\s*['"]sendingCode['"]/)
    assert.match(login.source, /:loading\s*=\s*['"]submitting['"]/)
    assert.match(login.source, /function\s+showResultError\s*\(/)
    assert.equal((login.source.match(/showResultError\s*\(/g) ?? []).length, 3)
    assert.match(login.source, /ElMessage\.error\s*\(\s*result\.message\s*\)/)
  })

  await check('D', 'DLQ detail breadcrumb parent is route-name metadata resolved by AdminLayout', async () => {
    assert.match(
      routesSource,
      /name:\s*['"]admin-dlq-detail['"][\s\S]*?meta:\s*\{[^}]*parent:\s*['"]admin-dlq-list['"]/,
    )
    const layoutSource = await readFile(path.join(SRC_ROOT, 'layouts/AdminLayout.vue'), 'utf8')
    assert.match(layoutSource, /route\.meta\.parent/)
    assert.match(layoutSource, /router\s*\.\s*getRoutes\s*\(\)/)
    assert.match(layoutSource, /:to\s*=\s*['"]\{\s*name:\s*breadcrumbParent\.name\s*\}['"]/)
    assert.doesNotMatch(layoutSource, /['"]\/admin\/dlq['"]/, 'layout must not hard-code the list path')
  })
}

function printSummary() {
  console.log('\nSummary')
  let totalPass = 0
  let totalFail = 0
  for (const groupName of ['A', 'B', 'C', 'D']) {
    const group = groups[groupName]
    assert.equal(group.pass + group.fail, group.expected)
    totalPass += group.pass
    totalFail += group.fail
    console.log(`${groupName} group: ${group.pass} PASS, ${group.fail} FAIL（共 ${group.expected} 条）`)
  }
  console.log(`Summary: ${totalPass} PASS, ${totalFail} FAIL`)
  if (totalFail > 0) process.exitCode = 1
}

async function main() {
  await verifyUnauthorizedClosures()
  await verifyAxiosIsolation()
  await verifyBreakpoints()
  await verifyPageStates()
  printSummary()
}

main().catch((error) => {
  console.error('FAIL: verification script crashed')
  console.error(error instanceof Error ? error.stack : error)
  process.exitCode = 1
})
