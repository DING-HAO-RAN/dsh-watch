/**
 * 探测 DSH 会话事件里「思考」块的真实形态。
 *
 * 用法：node tests/probe-reasoning.mjs
 *
 * 直连 DSH（不经过中继），把 assistant/message 的 content 块按类型统计出来，
 * 并打印 reasoning 类块的字段名与前几十个字。
 */
import { readFileSync } from 'node:fs'
import { createHash, createHmac, randomUUID } from 'node:crypto'
import { homedir } from 'node:os'
import { join } from 'node:path'

const DSH_PORT = 19387
const authority = '127.0.0.1:' + String(DSH_PORT)

function b64url(buffer) {
  return Buffer.from(buffer).toString('base64').replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '')
}

const credentials = readFileSync(join(homedir(), '.dsh', '.credentials.yaml'), 'utf8')
const at = credentials.indexOf('client-connection/browser-session:')
const match = /secret:\s*([A-Za-z0-9_-]+)/.exec(credentials.slice(at))
const secret = Buffer.from(match[1].replace(/-/g, '+').replace(/_/g, '/'), 'base64')

const issuedAt = Date.now()
const body = b64url(JSON.stringify({
  version: 1, authority, issuedAt, expiresAt: issuedAt + 29 * 24 * 3600 * 1000,
}))
const signature = b64url(createHmac('sha256', secret).update(body).digest())
const name = 'dsh-auth-' + b64url(createHash('sha256').update(authority).digest())
const cookie = name + '=v1.' + body + '.' + signature

async function rpc(method, args) {
  const response = await fetch('http://' + authority + '/api/' + method, {
    method: 'POST',
    headers: { 'content-type': 'application/json', cookie, host: authority },
    body: JSON.stringify({ type: 'client-request', rpcId: randomUUID(), method, payload: { args } }),
  })
  return (await response.json())?.result
}

const listed = await rpc('session/list', { _request: {} })
const items = listed?.value?.items ?? []
process.stdout.write('会话总数：' + String(items.length) + '\n')

// 挑 running=false 且步骤多的会话
const candidates = items.filter((s) => s.running !== true).slice(0, 40)
let best = null
for (const item of candidates) {
  const page = await rpc('session/page', {
    request: {
      address: { kind: 'session', sessionId: item.sessionId },
      throughSeq: item.projections?.asOfSeq ?? 0,
      maxMessages: 40,
    },
  })
  const records = page?.value?.records ?? []
  if (best === null || records.length > best.records.length) best = { item, records }
}
if (best === null) {
  process.stdout.write('没有可用会话\n')
  process.exit(0)
}
process.stdout.write('样本：' + String(best.item.projections?.values?.title) + '（记录 ' + String(best.records.length) + ' 条）\n\n')

// 统计所有 content 块的 type，并打印非 text 的样本
const types = new Map()
const samples = new Map()
for (const record of best.records) {
  const evt = record.event ?? {}
  if (evt.type !== 'assistant/message') continue
  const content = evt.data?.message?.content ?? evt.data?.content ?? []
  for (const block of content) {
    const type = String(block?.type ?? '(无 type)')
    types.set(type, (types.get(type) ?? 0) + 1)
    if (!samples.has(type)) samples.set(type, block)
  }
}

process.stdout.write('content 块类型统计：\n')
for (const [type, count] of types) {
  process.stdout.write('  ' + type + ' × ' + String(count) + '\n')
}
process.stdout.write('\n各类型首块样例（字段 + 前 200 字）：\n')
for (const [type, block] of samples) {
  process.stdout.write('\n--- ' + type + ' ---\n')
  process.stdout.write('  字段：' + Object.keys(block).join(', ') + '\n')
  const text = String(block.text ?? block.thinking ?? block.content ?? '')
  process.stdout.write('  文本：' + JSON.stringify(text.slice(0, 200)) + '\n')
}
