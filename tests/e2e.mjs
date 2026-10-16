/**
 * 端到端验收：以真实 HTTP 往返驱动中继，覆盖手表会用到的主要功能。
 *
 * 这些用例走的是手表实际调用的同一组接口，因此能替代在手表上重复点击验证。
 * 用法：node tests/e2e.mjs [--port 19390] [--code 123456]
 */
const args = process.argv.slice(2)
let port = 19390
let host = '127.0.0.1'
let code = ''
let token = ''
for (let i = 0; i < args.length; i += 1) {
  if (args[i] === '--port') port = Number(args[i + 1] ?? '19390')
  if (args[i] === '--host') host = String(args[i + 1] ?? '127.0.0.1')
  if (args[i] === '--code') code = String(args[i + 1] ?? '')
  // 已配对过的环境可以直接给令牌，跳过配对步骤。
  if (args[i] === '--token') token = String(args[i + 1] ?? '')
}

const base = `http://${host}:${String(port)}`
let passed = 0
let failed = 0

/** 一次调用；返回解析后的 JSON。 */
async function call(method, path, body, useToken = true) {
  const headers = { accept: 'application/json' }
  if (useToken && token !== '') headers.authorization = `Bearer ${token}`
  if (body !== undefined) headers['content-type'] = 'application/json'
  const response = await fetch(`${base}${path}`, {
    method,
    headers,
    ...(body === undefined ? {} : { body: JSON.stringify(body) }),
  })
  const text = await response.text()
  let json
  try { json = JSON.parse(text) } catch { json = { raw: text } }
  return { status: response.status, json }
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

process.stdout.write('=== 手表 ↔ 中继 端到端验收 ===\n')

await check('未配对时被拒绝', async () => {
  const { status } = await call('GET', '/api/sessions', undefined, false)
  expect(status === 401, `期望 401，实际 ${String(status)}`)
  return 'HTTP 401'
})

await check('状态接口无需鉴权', async () => {
  const { status, json } = await call('GET', '/api/status', undefined, false)
  expect(status === 200 && json.service === 'dsh-watch-relay/1', '状态接口返回异常')
  return `service=${String(json.service)}`
})

if (code !== '') {
  await check('用配对码换取令牌', async () => {
    const { status, json } = await call('POST', '/api/pair', { code }, false)
    expect(status === 200 && json.ok === true, `配对失败：${JSON.stringify(json)}`)
    token = String(json.token)
    return `令牌长度 ${String(token.length)}`
  })
  if (token === '') {
    // 配对失败时后续用例全部无意义；直接给出结论，避免刷出一片误导性的失败。
    process.stdout.write('\n配对未成功，后续用例已跳过。请核对电脑上中继显示的配对码。\n')
    process.stdout.write(`\n结果：${String(passed)} 通过 / ${String(failed)} 失败\n`)
    process.exit(1)
  }
}

await check('错误口令被拒绝', async () => {
  const { status } = await call('POST', '/api/pair', { code: '000000' }, false)
  expect(status === 403, `期望 403，实际 ${String(status)}`)
  return 'HTTP 403'
})

if (token === '') {
  process.stdout.write('\n未提供 --code 或 --token，跳过需要令牌的用例。\n')
} else {
  let sessionId = ''
  let sessionTitle = ''

  await check('会话列表', async () => {
    const { status, json } = await call('GET', '/api/sessions')
    expect(status === 200 && json.ok === true, '会话列表失败')
    expect(Array.isArray(json.sessions), 'sessions 不是数组')
    expect(json.sessions.length > 0, '会话列表为空')
    expect(Array.isArray(json.pending), 'pending 不是数组')
    const first = json.sessions[0]
    sessionId = String(first.id)
    sessionTitle = String(first.title)
    const hasSubagent = json.sessions.some((item) => item.subagent === true)
    expect(hasSubagent === false, '默认不应包含子代理会话')
    return `${String(json.sessions.length)} 个会话，首个=${sessionTitle}`
  })

  await check('读取会话消息', async () => {
    const { status, json } = await call('GET', `/api/session?id=${encodeURIComponent(sessionId)}`)
    expect(status === 200 && json.ok === true, `读取失败：${JSON.stringify(json).slice(0, 200)}`)
    expect(Array.isArray(json.messages), 'messages 不是数组')
    // 所有保留下来的消息都必须是真人消息或助手消息，不能混入系统注入。
    for (const message of json.messages) {
      expect(message.role === 'user' || message.role === 'assistant', `意外的角色：${String(message.role)}`)
      expect(typeof message.text === 'string', 'text 不是字符串')
      expect(Array.isArray(message.tools), 'tools 不是数组')
    }
    return `${String(json.messages.length)} 条消息，hasMore=${String(json.hasMore)}`
  })

  await check('会话消息随内容变化（非空正文）', async () => {
    const { json } = await call('GET', `/api/session?id=${encodeURIComponent(sessionId)}`)
    const withText = json.messages.filter((message) => message.text.trim() !== '')
    expect(withText.length > 0, '没有任何带正文的消息')
    return `最早一条：${String(withText[0].text.slice(0, 24))}…`
  })

  await check('创建新会话', async () => {
    const { status, json } = await call('POST', '/api/create', {})
    expect(status === 200 && json.ok === true, `创建失败：${JSON.stringify(json)}`)
    expect(typeof json.sessionId === 'string' && json.sessionId !== '', 'sessionId 为空')
    const { json: list } = await call('GET', '/api/sessions')
    const found = list.sessions.some((item) => item.id === json.sessionId)
    return `已创建 ${String(json.sessionId).slice(0, 20)}…（列表可见=${String(found)}）`
  })

  await check('发送消息并确认进入 DSH', async () => {
    const marker = `手表端验收-${Date.now().toString(36)}`
    const { status, json } = await call('POST', '/api/send', { sessionId, text: marker })
    expect(status === 200 && json.ok === true, `发送失败：${JSON.stringify(json)}`)
    // 稍等片刻让 DSH 完成入档，再从会话历史里找这条消息。
    let found = false
    for (let attempt = 0; attempt < 12 && !found; attempt += 1) {
      await new Promise((resolve) => setTimeout(resolve, 1000))
      const { json: page } = await call('GET', `/api/session?id=${encodeURIComponent(sessionId)}`)
      found = page.messages.some((message) => message.role === 'user' && message.text.includes(marker))
    }
    expect(found, '发送后未在会话历史里找到该消息')
    return `已确认入档：${marker}`
  })

  await check('空消息被拒绝', async () => {
    const { status } = await call('POST', '/api/send', { sessionId, text: '   ' })
    expect(status === 400, `期望 400，实际 ${String(status)}`)
    return 'HTTP 400'
  })

  await check('待答问题结构（当前无提问时应为空）', async () => {
    const { json } = await call('GET', '/api/sessions')
    for (const ask of json.pending) {
      expect(typeof ask.sessionId === 'string' && ask.sessionId !== '', 'pending 缺少 sessionId')
      expect(typeof ask.callId === 'string' && ask.callId !== '', 'pending 缺少 callId')
      expect(Array.isArray(ask.questions), 'questions 不是数组')
    }
    return `${String(json.pending.length)} 个待答问题`
  })
}

process.stdout.write(`\n结果：${String(passed)} 通过 / ${String(failed)} 失败\n`)
// 稍等一拍再退出：HTTP 连接池的收尾需要一点时间，直接退出会触发 libuv 句柄断言。
setTimeout(() => { process.exit(failed === 0 ? 0 : 1) }, 150)
