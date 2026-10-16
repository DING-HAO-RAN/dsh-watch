/**
 * 中继协议自检：对 DSH 逐一实调用到的每个端点，确认参数形态与返回形状。
 *
 * 这些端点的参数名不统一（有的直接传字段，有的要 request / _request 包装），
 * 而且 DSH 只在调用时才校验，所以这里做一次真实往返，避免上线后才发现。
 *
 * 用法：node tests/check-protocol.mjs [--dsh-port 19387]
 */
import { DshLink } from '../relay/src/dsh.mjs'

const args = process.argv.slice(2)
let port = 19387
for (let i = 0; i < args.length; i += 1) {
  if (args[i] === '--dsh-port') port = Number(args[i + 1] ?? '19387')
}

const link = new DshLink({ port, log: () => {} })
let passed = 0
let failed = 0

/** 断言一次调用成功，并可选地校验返回结构的某个字段。 */
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

process.stdout.write('=== DSH 端点参数形态自检 ===\n')

let sampleSessionId = ''
let sampleSessionTitle = ''

await check('session/list（_request 包装）', async () => {
  const value = await link.rpc('session/list', { _request: {} })
  const items = Array.isArray(value?.items) ? value.items : []
  const main = items.find((item) => item.origin !== 'subagent') ?? items[0]
  sampleSessionId = String(main?.sessionId ?? '')
  sampleSessionTitle = String(main?.projections?.values?.title ?? '')
  return `${String(items.length)} 个会话，样本=${sampleSessionTitle}`
})

await check('session/projections（request 包装）', async () => {
  if (sampleSessionId === '') throw new Error('缺少样本会话')
  const value = await link.rpc('session/projections', { request: { sessionId: sampleSessionId } })
  return `asOfSeq=${String(value?.asOfSeq ?? '(null)')}`
})

await check('session/page（request 包装 + address）', async () => {
  const projection = await link.rpc('session/projections', { request: { sessionId: sampleSessionId } })
  const asOfSeq = Number(projection?.asOfSeq ?? 0)
  const value = await link.rpc('session/page', {
    request: { address: { kind: 'session', sessionId: sampleSessionId }, throughSeq: asOfSeq, maxMessages: 3 },
  })
  const records = Array.isArray(value?.records) ? value.records : []
  return `${String(records.length)} 条事件，hasMore=${String(value?.hasMore ?? false)}`
})

// 只读端点：不真的取消会话，只验证参数形态能被接受（用不存在的会话 id 触发业务层错误即说明参数通过）。
await check('session/cancel（request 包装，参数形态）', async () => {
  try {
    await link.rpc('session/cancel', { request: { sessionId: 'session-00000000-0000-4000-8000-000000000000' } })
    return '被接受（会话不存在时应报错，此处不判失败）'
  } catch (error) {
    if (String(error.message).includes('arguments-invalid')) throw error
    return `参数通过（业务层回复：${String(error.message).slice(0, 60)}）`
  }
})

await check('session/create（request 包装，参数形态）', async () => {
  // 只验证参数形态：故意给一个非法 workspaceId，业务层报错即说明 args 形状正确。
  try {
    await link.rpc('session/create', { request: { workspaceId: '__protocol-selftest__' } })
    return '被接受'
  } catch (error) {
    if (String(error.message).includes('arguments-invalid')) throw error
    return `参数通过（业务层回复：${String(error.message).slice(0, 60)}）`
  }
})

await check('userQuestions/answer（agentId 在顶层）', async () => {
  try {
    await link.rpc('userQuestions/answer', {
      agentId: 'session-00000000-0000-4000-8000-000000000000',
      callId: 'call_self_test',
      answer: { answers: [{ id: 'x', selected: [] }] },
    })
    return '被接受'
  } catch (error) {
    if (String(error.message).includes('arguments-invalid')) throw error
    return `参数通过（业务层回复：${String(error.message).slice(0, 60)}）`
  }
})

await check('$events 事件流（WebSocket 握手 + ready 帧）', async () => {
  const clientId = await new Promise((resolve, reject) => {
    // 必须持有 close 引用并在拿到 ready 后立即断开：长连不关会让进程退出时
    // 触碰已关闭的 libuv 句柄，在某些 Node 版本上以断言失败告终。
    let close = () => {}
    const timer = setTimeout(() => {
      close()
      reject(new Error('10 秒内未收到 ready 帧'))
    }, 10_000)
    close = link.openEvents({
      ready: (id) => {
        clearTimeout(timer)
        close()
        resolve(id)
      },
      link: () => {},
      item: () => {},
    })
  })
  return `clientId=${clientId.slice(0, 8)}…`
})

process.stdout.write(`\n结果：${String(passed)} 通过 / ${String(failed)} 失败\n`)
// 稍等一拍让 WebSocket 的关闭过程走完，再退出；直接退出会触发 libuv 的句柄断言。
setTimeout(() => { process.exit(failed === 0 ? 0 : 1) }, 150)
