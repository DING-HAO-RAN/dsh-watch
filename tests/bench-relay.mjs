/**
 * 中继性能基线：确认「每个请求都穿透 DSH」这类退化不会被悄悄加回来。
 *
 * 背景：DSH 的 session/list 一次返回约 1MB、耗时 2~5 秒，中继靠共享缓存把它压到
 * 毫秒级。只要有人在新接口里又直接调一次 session/list，这里的数字就会从个位数
 * 涨到几百毫秒甚至秒级——这正是手表上「转圈很久然后超时」的成因。
 *
 * 用法：node tests/bench-relay.mjs --port 19390 --token <令牌>
 *      不传 --token 时读 ~/.dsh-watch-relay.json（命令行版中继的存档）。
 *
 * 判定标准（局域网、DSH 在跑）：
 *   会话列表稳定值 < 150ms、会话详情稳定值 < 400ms、工作区 < 150ms。
 * 首次请求允许更慢（冷缓存要等 DSH 一次）。
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
  try {
    const file = join(homedir(), '.dsh-watch-relay.json')
    const parsed = JSON.parse(readFileSync(file, 'utf8'))
    token = String(Object.keys(parsed?.tokens ?? {})[0] ?? '')
  } catch { /* 没有存档就继续用空令牌，下面会报 401 */ }
}

const base = `http://127.0.0.1:${String(port)}`
const headers = token === '' ? {} : { authorization: `Bearer ${token}` }

async function call(path) {
  const started = process.hrtime.bigint()
  const response = await fetch(base + path, { headers })
  const text = await response.text()
  const elapsed = Number(process.hrtime.bigint() - started) / 1e6
  if (!response.ok) throw new Error(`${path} → HTTP ${String(response.status)}：${text.slice(0, 120)}`)
  return { json: JSON.parse(text), elapsed }
}

const rows = []
let failures = 0

function check(label, condition, detail) {
  if (condition) {
    process.stdout.write(`  OK   ${label} — ${detail}\n`)
  } else {
    failures += 1
    process.stdout.write(`  SLOW ${label} — ${detail}\n`)
  }
}

process.stdout.write('=== 中继性能基线 ===\n')

const listFirst = await call('/api/sessions')
const sessions = listFirst.json.sessions ?? []
const sample = sessions.find((item) => item.subagent !== true && item.projectPath !== '')
if (sample === undefined) {
  process.stdout.write('  跳过：没有可用的样本会话\n')
  process.exit(0)
}

// 稳定值取后续几次的中位数：单次测量受 DSH 当前负载影响太大。
async function stable(path, times = 4) {
  const values = []
  for (let i = 0; i < times; i += 1) {
    const { elapsed } = await call(path)
    values.push(elapsed)
    await new Promise((resolve) => setTimeout(resolve, 200))
  }
  values.sort((a, b) => a - b)
  return values[Math.floor(values.length / 2)]
}

const listStable = await stable('/api/sessions')
check('会话列表', listStable < 150, `首次 ${listFirst.elapsed.toFixed(0)}ms、稳定 ${listStable.toFixed(0)}ms（阈值 150ms）`)

const detailStable = await stable(`/api/session?id=${encodeURIComponent(sample.id)}`)
// 运行中的会话内容一直在变，缓存必然频繁失效，阈值放得比空闲会话宽。
const detailLimit = sample.running ? 800 : 400
check('会话详情', detailStable < detailLimit, `稳定 ${detailStable.toFixed(0)}ms（阈值 ${String(detailLimit)}ms，会话${sample.running ? '运行中' : '空闲'}）`)

const workspaceStable = await stable('/api/workspaces')
check('工作区列表', workspaceStable < 150, `稳定 ${workspaceStable.toFixed(0)}ms（阈值 150ms）`)

process.stdout.write(failures === 0 ? '\n结果：全部在阈值内\n' : `\n结果：${String(failures)} 项超出阈值\n`)
process.exit(failures === 0 ? 0 : 1)
