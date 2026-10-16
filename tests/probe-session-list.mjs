/**
 * 直接调 DSH 的 session/list，打印原始响应的结构（排查中继取不到会话时用）。
 *
 * 用法：node tests/probe-session-list.mjs
 */
import { readFileSync } from 'node:fs'
import { createHash, createHmac } from 'node:crypto'
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

const response = await fetch('http://' + authority + '/api/session/list', {
  method: 'POST',
  headers: { 'content-type': 'application/json', cookie, host: authority },
  body: JSON.stringify({
    type: 'client-request',
    rpcId: crypto.randomUUID(),
    method: 'session/list',
    payload: { args: { _request: {} } },
  }),
})
const text = await response.text()
const json = JSON.parse(text)
const value = json?.result?.value

console.log('HTTP', response.status, ' ok=', json?.result?.ok)
console.log('value 的顶层字段：', value === null || value === undefined ? '(空)' : Object.keys(value))
if (value && typeof value === 'object') {
  for (const [key, item] of Object.entries(value)) {
    if (Array.isArray(item)) {
      console.log(`  ${key}: 数组，${String(item.length)} 项`)
      if (item.length > 0) {
        console.log(`    首项字段：${Object.keys(item[0]).join(', ')}`)
        console.log(`    首项示例：${JSON.stringify(item[0]).slice(0, 400)}`)
      }
    } else {
      console.log(`  ${key}: ${JSON.stringify(item)}`)
    }
  }
}
