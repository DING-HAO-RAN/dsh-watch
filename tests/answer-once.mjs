/**
 * 回答一个当前待答问题，并确认它从待答列表消失。
 *
 * 用法：node tests/answer-once.mjs [--token <令牌>] [--port 19390] [--pick 米饭]
 *
 * 走的是手表实际调用的同一接口，因此这是「回答问题」能力的真实验收。
 */
import { readFileSync } from 'node:fs'
import { homedir } from 'node:os'
import { join } from 'node:path'

const args = process.argv.slice(2)
let port = 19390
let host = '127.0.0.1'
let token = ''
let pick = ''
for (let i = 0; i < args.length; i += 1) {
  if (args[i] === '--port') port = Number(args[i + 1] ?? '19390')
  if (args[i] === '--host') host = String(args[i + 1] ?? '127.0.0.1')
  if (args[i] === '--token') token = String(args[i + 1] ?? '')
  if (args[i] === '--pick') pick = String(args[i + 1] ?? '')
}
if (token === '') {
  try {
    const state = JSON.parse(readFileSync(join(homedir(), '.dsh-watch-relay.json'), 'utf8'))
    token = Object.keys(state.tokens ?? {})[0] ?? ''
  } catch { /* 下面会提示 */ }
}
if (token === '') {
  process.stderr.write('缺少令牌\n')
  process.exit(1)
}

const base = `http://${host}:${String(port)}`
async function call(method, path, body) {
  const headers = { accept: 'application/json', authorization: `Bearer ${token}` }
  if (body !== undefined) headers['content-type'] = 'application/json'
  const response = await fetch(`${base}${path}`, {
    method,
    headers,
    ...(body === undefined ? {} : { body: JSON.stringify(body) }),
  })
  return { status: response.status, json: await response.json() }
}

const listed = await call('GET', '/api/sessions')
const pending = listed.json.pending ?? []
if (pending.length === 0) {
  process.stdout.write('当前没有待答问题，无需回答。\n')
  process.exit(0)
}

const ask = pending[0]
const question = ask.questions[0]
const option = pick === ''
  ? question.options[0]?.label ?? ''
  : pick
process.stdout.write(`问题：${question.question}\n`)
process.stdout.write(`选项：${question.options.map((o) => o.label).join(' / ')}\n`)
process.stdout.write(`选择：${option}\n`)

const answered = await call('POST', '/api/answer', {
  sessionId: ask.sessionId,
  callId: ask.callId,
  eventId: ask.eventId,
  answers: [{ id: question.id, selected: option === '' ? [] : [option], ...(option === '' ? { custom: '米饭' } : {}) }],
})
process.stdout.write(`应答：HTTP ${String(answered.status)} ${JSON.stringify(answered.json)}\n`)
if (answered.json.ok !== true) process.exit(1)

let cleared = false
for (let attempt = 0; attempt < 15 && !cleared; attempt += 1) {
  await new Promise((resolve) => setTimeout(resolve, 1000))
  const after = await call('GET', '/api/sessions')
  cleared = !(after.json.pending ?? []).some((item) => item.callId === ask.callId)
}
process.stdout.write(cleared ? 'PASS：问题已从待答列表消失\n' : '注意：问题仍在待答列表中\n')
process.exit(cleared ? 0 : 3)
