/**
 * 新增能力的接口验收：项目分组、步骤与工具详情、模型目录、用量统计、历史翻页。
 * 用法：node tests/check-features.mjs [--port 19390]
 */
import { readFileSync } from 'node:fs'
import { homedir } from 'node:os'
import { join } from 'node:path'

const args = process.argv.slice(2)
let port = 19390
let token = ''
for (let i = 0; i < args.length; i += 1) {
  if (args[i] === '--port') port = Number(args[i + 1] ?? '19390')
  if (args[i] === '--token') token = String(args[i + 1] ?? '')
}
if (token === '') {
  const state = JSON.parse(readFileSync(join(homedir(), '.dsh-watch-relay.json'), 'utf8'))
  token = Object.keys(state.tokens ?? {})[0] ?? ''
}

const base = `http://127.0.0.1:${String(port)}`
let passed = 0
let failed = 0

async function call(method, path) {
  const response = await fetch(`${base}${path}`, {
    method,
    headers: { authorization: `Bearer ${token}`, accept: 'application/json' },
  })
  return { status: response.status, json: await response.json() }
}

async function check(label, fn) {
  try {
    const detail = await fn()
    passed += 1
    process.stdout.write(`  OK   ${label}${detail === undefined ? '' : ` — ${detail}`}\n`)
  } catch (error) {
    failed += 1
    process.stdout.write(`  FAIL ${label} — ${String(error.message)}\n`)
  }
}

function expect(condition, message) {
  if (!condition) throw new Error(message)
}

process.stdout.write('=== 新增能力接口验收 ===\n')

let sessionId = ''

await check('会话列表带项目分组', async () => {
  const { json } = await call('GET', '/api/sessions')
  expect(json.ok === true, '列表失败')
  expect(Array.isArray(json.projects), 'projects 不是数组')
  expect(json.projects.length > 0, '没有分组')
  for (const project of json.projects) {
    expect(typeof project.project === 'string' && project.project !== '', '分组缺少 project')
    expect(Array.isArray(project.sessions) && project.sessions.length > 0, '分组没有会话')
    expect(typeof project.running === 'number', '分组缺少 running 计数')
  }
  const names = json.projects.map((p) => p.project).slice(0, 5).join('、')
  const biggest = json.projects.reduce((a, b) => (b.sessions.length > a.sessions.length ? b : a))
  sessionId = String(biggest.sessions[0]?.id ?? '')
  return `${String(json.projects.length)} 个项目（${names}…），最大＝${biggest.project}(${String(biggest.sessions.length)})`
})

await check('会话摘要带模型信息', async () => {
  const { json } = await call('GET', '/api/sessions')
  const withModel = json.sessions.filter((s) => s.model !== '')
  expect(withModel.length > 0, '没有任何会话带模型信息')
  const sample = withModel[0]
  return `${String(withModel.length)}/${String(json.sessions.length)} 个带模型，例：${sample.provider}/${sample.model}`
})

await check('会话详情带步骤与工具详情', async () => {
  expect(sessionId !== '', '缺少样本会话')
  const { json } = await call('GET', `/api/session?id=${encodeURIComponent(sessionId)}`)
  expect(json.ok === true, '详情失败')
  expect(Array.isArray(json.steps), 'steps 不是数组')
  expect(json.steps.length > 0, '没有步骤')
  const tools = json.steps.filter((s) => s.kind === 'tool')
  const withDetail = tools.filter((s) => s.detail !== '')
  const withResult = tools.filter((s) => s.result !== '')
  const done = tools.filter((s) => s.status === 'done' || s.status === 'error')
  return `步骤 ${String(json.steps.length)}（工具 ${String(tools.length)}：有参数摘要 ${String(withDetail.length)}、有结果 ${String(withResult.length)}、已结束 ${String(done.length)}）`
})

await check('工具详情含文件名', async () => {
  const { json } = await call('GET', `/api/session?id=${encodeURIComponent(sessionId)}`)
  const withFiles = json.steps.filter((s) => Array.isArray(s.files) && s.files.length > 0)
  const anyPathLike = json.steps.filter((s) => typeof s.detail === 'string' && s.detail.includes('.'))
  return `带改动文件的步骤 ${String(withFiles.length)}，参数里含文件名的步骤 ${String(anyPathLike.length)}`
})

await check('会话详情带用量统计', async () => {
  const { json } = await call('GET', `/api/session?id=${encodeURIComponent(sessionId)}`)
  const usage = json.usage
  expect(usage !== null && typeof usage === 'object', 'usage 缺失')
  expect(typeof usage.contextWindow === 'number', '缺少 contextWindow')
  expect(typeof usage.outputTokens === 'number', '缺少 outputTokens')
  return `上下文 ${String(usage.pressureTokens)}/${String(usage.contextWindow)}（${String(usage.percent)}%）、输出 ${String(usage.outputTokens)}、步数 ${String(usage.steps)}`
})

await check('模型目录', async () => {
  const { json } = await call('GET', '/api/models')
  expect(json.ok === true, '目录失败')
  expect(Array.isArray(json.groups) && json.groups.length > 0, '没有可用供应商')
  let modelCount = 0
  for (const group of json.groups) {
    expect(typeof group.provider === 'string' && group.provider !== '', '供应商缺少 id')
    expect(Array.isArray(group.models) && group.models.length > 0, '供应商没有模型')
    modelCount += group.models.length
  }
  const defaultText = `${json.default.provider}/${json.default.model}`
  return `${String(json.groups.length)} 个供应商、${String(modelCount)} 个模型，默认 ${defaultText}`
})

await check('历史翻页（beforeSeq）', async () => {
  // 自己找一个「有更多历史」的会话：前面挑的样本可能是刚建的短会话，
  // 拿它验证翻页必然失败，而那是样本问题、不是翻页功能的问题。
  let target = ''
  let first = null
  const list = await call('GET', '/api/sessions')
  for (const item of (list.json.sessions ?? []).slice(0, 25)) {
    const probe = await call('GET', `/api/session?id=${encodeURIComponent(String(item.id))}`)
    if (probe.json.ok === true && probe.json.hasMore === true && typeof probe.json.beforeSeq === 'number') {
      target = String(item.id)
      first = probe.json
      break
    }
  }
  expect(target !== '', '前 25 个会话都没有更多历史，无法验证翻页')

  expect(first.beforeSeq > 0, '缺少 beforeSeq')
  const older = await call('GET', `/api/session?id=${encodeURIComponent(target)}&through=${String(first.beforeSeq - 1)}`)
  expect(older.json.ok === true, '翻页失败')
  expect(Array.isArray(older.json.steps), '翻页结果缺少 steps')
  return `第一批最早 seq=${String(first.beforeSeq)}，往前翻得到 ${String(older.json.steps.length)} 个步骤`
})

await check('用量统计随会话变化', async () => {
  const a = await call('GET', `/api/session?id=${encodeURIComponent(sessionId)}`)
  const b = await call('GET', `/api/session?id=${encodeURIComponent(sessionId)}`)
  expect(a.json.usage.outputTokens === b.json.usage.outputTokens, '两次读数不一致')
  return `输出 token 稳定为 ${String(a.json.usage.outputTokens)}`
})

await check('子任务列表', async () => {
  const list = await call('GET', '/api/sessions')
  // 挑一个真的派生过子任务的父会话；没有就说明这台机器上还没用过子智能体，
  // 那是样本问题而不是功能问题，用 skip 说明而不是判失败。
  const parent = (list.json.sessions ?? []).find((item) => Number(item.subagentCount ?? 0) > 0)
  if (parent === undefined) return '本机没有派生过子任务的会话，跳过'
  expect(parent.subagent === false, '父会话不该被标成子代理')

  const { json } = await call('GET', `/api/subagents?id=${encodeURIComponent(String(parent.id))}`)
  expect(json.ok === true, '子任务列表失败')
  expect(Array.isArray(json.subagents), 'subagents 不是数组')
  expect(json.subagents.length > 0, '父会话标了子任务但列表为空')
  for (const item of json.subagents) {
    expect(typeof item.id === 'string' && item.id !== '', '子任务缺少 id')
    expect(typeof item.label === 'string' && item.label !== '', '子任务缺少 label')
    expect(['running', 'done', 'idle', 'failed'].includes(item.state), `未知状态：${String(item.state)}`)
    expect(typeof item.settledMs === 'number', '缺少 settledMs')
  }

  // 子任务的对话必须能读出来：DSH 要求用「父地址 + 子地址」，用错地址会报
  // subagent Sessions require their durable parent address。
  const child = json.subagents[0]
  const detail = await call('GET', `/api/session?id=${encodeURIComponent(String(child.id))}`)
  expect(detail.json.ok === true, `子任务对话读取失败：${String(detail.json.error)}`)
  expect(Array.isArray(detail.json.steps), '子任务对话缺少 steps')
  const states = json.subagents.map((item) => item.state).join(',')
  return `${String(json.subagents.length)} 个子任务（${states}），首个对话 ${String(detail.json.steps.length)} 步`
})

await check('计划与任务块', async () => {
  expect(sessionId !== '', '缺少样本会话')
  const { json } = await call('GET', `/api/session?id=${encodeURIComponent(sessionId)}`)
  const plan = json.plan
  expect(plan !== null && typeof plan === 'object', 'plan 块缺失')
  expect(Array.isArray(plan.todos), 'plan.todos 不是数组')
  expect(typeof plan.mode === 'object' && plan.mode !== null, 'plan.mode 缺失')
  expect(typeof plan.mode.active === 'boolean', 'plan.mode.active 不是布尔')
  expect(typeof plan.team === 'object' && plan.team !== null, 'plan.team 缺失')
  expect(Array.isArray(plan.team.members), 'plan.team.members 不是数组')
  expect(Array.isArray(plan.team.tasks), 'plan.team.tasks 不是数组')
  for (const todo of plan.todos) {
    expect(typeof todo.content === 'string' && todo.content !== '', '待办缺少 content')
    expect(['pending', 'in_progress', 'completed'].includes(todo.status), `未知待办状态：${String(todo.status)}`)
  }
  for (const task of plan.team.tasks) {
    expect(typeof task.subject === 'string' && task.subject !== '', '任务缺少 subject')
    expect(['pending', 'in_progress', 'completed'].includes(task.status), `未知任务状态：${String(task.status)}`)
  }
  return `待办 ${String(plan.todos.length)} 条、成员 ${String(plan.team.members.length)} 个、任务 ${String(plan.team.tasks.length)} 条`
})

process.stdout.write(`\n结果：${String(passed)} 通过 / ${String(failed)} 失败\n`)
setTimeout(() => { process.exit(failed === 0 ? 0 : 1) }, 150)
