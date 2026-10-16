/**
 * 一次性数据形状侦察：为手表新增「步骤与工具详情」「按项目分组」「模型选择」「用量与耗时」
 * 四项能力摸清 DSH 侧到底给了什么字段。
 *
 * 用法：node tests/probe-shapes.mjs [--dsh-port 19387] [--session <id>]
 */
import { DshLink } from '../relay/src/dsh.mjs'

const args = process.argv.slice(2)
let port = 19387
let sessionId = ''
for (let i = 0; i < args.length; i += 1) {
  if (args[i] === '--dsh-port') port = Number(args[i + 1] ?? '19387')
  if (args[i] === '--session') sessionId = String(args[i + 1] ?? '')
}

const link = new DshLink({ port, log: () => {} })

// 1) 会话列表：看 projections 里有哪些可用字段（模型、用量、耗时、工作目录）
const list = await link.rpc('session/list', { _request: {} })
const items = Array.isArray(list?.items) ? list.items : []
const main = sessionId === ''
  ? items.filter((i) => i.origin !== 'subagent').sort((a, b) => b.updatedAt - a.updatedAt)[1]
  : items.find((i) => i.sessionId === sessionId)
if (main === undefined) { process.stdout.write('找不到样本会话\n'); process.exit(1) }
sessionId = String(main.sessionId)
process.stdout.write(`样本会话：${String(main.projections?.values?.title ?? '(无标题)')}\n`)
process.stdout.write(`sessionId=${sessionId}\n`)
process.stdout.write(`cwd=${String(main.cwd ?? '(无)')}\n`)
process.stdout.write(`顶层字段：${Object.keys(main).join(', ')}\n`)
process.stdout.write(`projections.kind=${String(main.projections?.kind)} asOfSeq=${String(main.projections?.asOfSeq)}\n`)
const values = main.projections?.values ?? {}
process.stdout.write(`projections.values 键：${Object.keys(values).join(', ')}\n\n`)

process.stdout.write('=== 模型相关 ===\n')
process.stdout.write(`modelSelection=${JSON.stringify(values.modelSelection)}\n`)
process.stdout.write(`agentPreset=${JSON.stringify(values.agentPreset)}\n\n`)

process.stdout.write('=== 用量相关 ===\n')
process.stdout.write(`tokenUsage=${JSON.stringify(values.tokenUsage)}\n`)
process.stdout.write(`contextPressure=${JSON.stringify(values.contextPressure)}\n`)
process.stdout.write(`contextBreakdown=${JSON.stringify(values.contextBreakdown)}\n`)
process.stdout.write(`sessionStats=${JSON.stringify(values.sessionStats)}\n\n`)

process.stdout.write('=== 待答问题与计划 ===\n')
process.stdout.write(`userQuestions=${JSON.stringify(values.userQuestions)}\n`)
process.stdout.write(`todos=${JSON.stringify(values.todos)?.slice(0, 300)}\n`)
process.stdout.write(`plan=${JSON.stringify(values.plan)?.slice(0, 200)}\n\n`)

// 2) 会话消息：看工具调用与结果的完整形状（文件名、结果文本、diff）
const projection = await link.rpc('session/projections', { request: { sessionId } })
const asOfSeq = Number(projection?.asOfSeq ?? 0)
const page = await link.rpc('session/page', {
  request: { address: { kind: 'session', sessionId }, throughSeq: asOfSeq, maxMessages: 30 },
})
const records = Array.isArray(page?.records) ? page.records : []
process.stdout.write(`=== 事件类型分布（${String(records.length)} 条）===\n`)
const byType = new Map()
for (const record of records) {
  const type = record?.event?.type ?? '?'
  byType.set(type, (byType.get(type) ?? 0) + 1)
}
for (const [type, count] of byType) process.stdout.write(`  ${type} x${String(count)}\n`)
process.stdout.write('\n')

// 工具调用的参数里有没有文件路径
const toolCall = records.find((r) => r?.event?.type === 'tool/call')
if (toolCall !== undefined) {
  process.stdout.write('=== tool/call 样例 ===\n')
  process.stdout.write(`${JSON.stringify(toolCall.event.data).slice(0, 500)}\n\n`)
}
const toolResult = records.find((r) => r?.event?.type === 'tool/result')
if (toolResult !== undefined) {
  const data = toolResult.event.data ?? {}
  process.stdout.write('=== tool/result 样例 ===\n')
  process.stdout.write(`data 键：${Object.keys(data).join(', ')}\n`)
  process.stdout.write(`message.content=${JSON.stringify(data.message?.content).slice(0, 400)}\n`)
  process.stdout.write(`isError=${String(data.message?.isError)}\n`)
  process.stdout.write(`meta 键：${data.meta === undefined ? '(无)' : Object.keys(data.meta).join(', ')}\n`)
  const diffs = data.meta?.diffs
  if (Array.isArray(diffs) && diffs.length > 0) {
    process.stdout.write(`diffs[0] 键：${Object.keys(diffs[0]).join(', ')}\n`)
    process.stdout.write(`diffs[0].path=${String(diffs[0].path)}\n`)
    process.stdout.write(`oldText 长度=${String(diffs[0].oldText?.length ?? 0)} newText 长度=${String(diffs[0].newText?.length ?? 0)}\n`)
  }
  process.stdout.write('\n')
}

// assistant/message 里的 usage（每步的 token）
const assistant = records.find((r) => r?.event?.type === 'assistant/message' && r.event.data?.usage !== undefined)
if (assistant !== undefined) {
  process.stdout.write('=== assistant/message usage 样例 ===\n')
  process.stdout.write(`${JSON.stringify(assistant.event.data.usage)}\n`)
  process.stdout.write(`step=${String(assistant.event.data.step)} turn=${String(assistant.event.data.turn)}\n\n`)
}

// 3) 模型目录
process.stdout.write('=== session/modelCatalog ===\n')
try {
  const catalog = await link.rpc('session/modelCatalog', {})
  process.stdout.write(`${JSON.stringify(catalog).slice(0, 1200)}\n`)
} catch (error) {
  process.stdout.write(`失败：${String(error.message)}\n`)
}

// 4) 工作区分组：会话列表里能直接按 cwd 分组吗
process.stdout.write('\n=== 按 cwd 分组统计（前 10）===\n')
const byCwd = new Map()
for (const item of items) {
  if (item.origin === 'subagent') continue
  const cwd = String(item.cwd ?? '(无)')
  byCwd.set(cwd, (byCwd.get(cwd) ?? 0) + 1)
}
const top = [...byCwd.entries()].sort((a, b) => b[1] - a[1]).slice(0, 10)
for (const [cwd, count] of top) process.stdout.write(`  ${String(count).padStart(4)}  ${cwd}\n`)
