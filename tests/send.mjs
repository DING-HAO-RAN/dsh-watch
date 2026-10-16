/**
 * 从中继侧发一条消息（避免 shell 处理中文带来的编码问题）。
 * 用法：node tests/send.mjs --session <id> --text "内容" [--port 19390]
 */
import { readFileSync } from 'node:fs'
import { homedir } from 'node:os'
import { join } from 'node:path'

const args = process.argv.slice(2)
let port = 19390
let sessionId = ''
let text = ''
let token = ''
for (let i = 0; i < args.length; i += 1) {
  if (args[i] === '--port') port = Number(args[i + 1] ?? '19390')
  if (args[i] === '--session') sessionId = String(args[i + 1] ?? '')
  if (args[i] === '--text') text = String(args[i + 1] ?? '')
  if (args[i] === '--token') token = String(args[i + 1] ?? '')
}
if (token === '') {
  const state = JSON.parse(readFileSync(join(homedir(), '.dsh-watch-relay.json'), 'utf8'))
  token = Object.keys(state.tokens ?? {})[0] ?? ''
}
if (sessionId === '' || text === '') {
  process.stderr.write('需要 --session 与 --text\n')
  process.exit(1)
}

const response = await fetch(`http://127.0.0.1:${String(port)}/api/send`, {
  method: 'POST',
  headers: { 'content-type': 'application/json', authorization: `Bearer ${token}` },
  body: JSON.stringify({ sessionId, text }),
})
process.stdout.write(`${String(response.status)} ${await response.text()}\n`)
