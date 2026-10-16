/**
 * 状态聚合层：把 DSH 的原始事件流归一化成手表能直接渲染的紧凑结构。
 *
 * 手表屏幕只有 233dp 见方，且 `session/page` 的原始响应可达数百 KB（含大量
 * 流式分片与工具 diff）。因此归一化必须在中继完成：手表只收到已经压到最小的
 * 消息列表与待答问题。
 *
 * 归一化规则（基于实测的事件形状）：
 * - 只保留 `source.kind === 'user'` 的 user/message —— DSH 会把时间上下文、记忆快照、
 *   系统指令等也写成 user/message，直接渲染会淹没真实对话。
 * - assistant/message 合并为一条：正文文本 + 去重后的工具名列表。
 * - tool/result、step/*、turn/* 不进入消息列表（手表上没有展示价值）。
 */
import { basename } from 'node:path'

/** 单条消息的正文上限：超过则截断，避免一次轮询就把手表内存拉满。 */
const MAX_TEXT_CHARS = 4_000
/** 每个会话缓存的消息条数上限。 */
export const MAX_MESSAGES = 40
/** 会话列表返回的条目上限（DSH 实测可返回数百条）。 */
const MAX_SESSIONS = 60
/** 子代理会话在列表里标记出来，默认不进入手表的会话列表。 */
const SUBAGENT_ORIGIN = 'subagent'

/** 工具名到显示标签的映射：手表上是图标化短标签，不需要原始英文名。 */
const TOOL_LABELS = {
  pwsh: '命令',
  bash: '命令',
  read: '读文件',
  edit: '改文件',
  write: '写文件',
  grep: '搜索',
  glob: '找文件',
  skill: '技能',
  subagent: '子任务',
  subagent_fork: '子任务',
  web_search: '联网',
  web_fetch: '联网',
  generate_image: '画图',
  edit_image: '改图',
  ask_user_question: '提问',
  update_goal: '目标',
  todo_write: '清单',
  computer_use: '操作电脑',
  android_tap: '操作手机',
  android_screenshot: '手机截屏',
  present: '交付',
}

function truncate(text, limit = MAX_TEXT_CHARS) {
  if (typeof text !== 'string') return ''
  if (text.length <= limit) return text
  return `${text.slice(0, limit)}…`
}

/** 从消息 content 数组里抽取纯文本（跳过 reasoning，它通常很长且是内部思考）。 */
function textOfContent(content) {
  if (!Array.isArray(content)) return ''
  const parts = []
  for (const block of content) {
    if (block === null || typeof block !== 'object') continue
    if (block.type === 'text' && typeof block.text === 'string') parts.push(block.text)
  }
  return parts.join('\n').trim()
}

/** 从消息 content 数组里抽取工具调用名（去重、保序）。 */
function toolsOfContent(content) {
  if (!Array.isArray(content)) return []
  const names = []
  for (const block of content) {
    if (block === null || typeof block !== 'object') continue
    if (block.type !== 'tool-call') continue
    const name = typeof block.name === 'string' ? block.name : ''
    const label = name === '' ? '工具' : (TOOL_LABELS[name] ?? name)
    if (!names.includes(label)) names.push(label)
  }
  return names
}

/**
 * 判断一条 user/message 是否来自真人输入。
 *
 * DSH 会用 user/message 承载多种系统注入（time-context 快照、mnemon 记忆、全局指令等）。
 * 只有 source.kind 为 user / user-question-reply 的才是对话内容。
 */
function isHumanMessage(data) {
  const kind = data?.source?.kind
  if (kind === undefined) {
    // 老格式没有 source：退回按是否存在 role 判断，避免漏掉真实消息。
    return data?.message?.role === 'user' || data?.role === 'user'
  }
  return kind === 'user' || kind === 'user-question-reply'
}

/**
 * 把 `session/page` 的 records 归一化为消息列表。
 * @param {Array<object>} records DSH 返回的事件记录
 * @returns {Array<{seq:number, role:string, text:string, tools:string[], time:number}>}
 */
export function normalizeMessages(records) {
  const messages = []
  let pendingAssistant = undefined

  const flushAssistant = () => {
    if (pendingAssistant === undefined) return
    if (pendingAssistant.text !== '' || pendingAssistant.tools.length > 0) messages.push(pendingAssistant)
    pendingAssistant = undefined
  }

  for (const record of Array.isArray(records) ? records : []) {
    const event = record?.event
    if (event === undefined || event === null) continue
    const seq = Number(event.seq ?? 0)
    const time = Number(event.time ?? 0)
    const data = event.data ?? {}

    if (event.type === 'user/message') {
      if (!isHumanMessage(data)) continue
      flushAssistant()
      const message = data.message ?? data
      const text = textOfContent(message?.content)
      if (text === '') continue
      messages.push({ seq, role: 'user', text: truncate(text), tools: [], time })
      continue
    }

    if (event.type === 'assistant/message') {
      const message = data.message ?? {}
      const text = textOfContent(message?.content)
      const tools = toolsOfContent(message?.content)
      if (pendingAssistant === undefined) pendingAssistant = { seq, role: 'assistant', text: '', tools: [], time }
      if (text !== '') {
        pendingAssistant.text = pendingAssistant.text === '' ? text : `${pendingAssistant.text}\n\n${text}`
      }
      for (const tool of tools) {
        if (!pendingAssistant.tools.includes(tool)) pendingAssistant.tools.push(tool)
      }
      pendingAssistant.seq = seq
      pendingAssistant.time = time
      continue
    }

    if (event.type === 'turn/end') flushAssistant()
  }
  flushAssistant()
  return messages
}

/**
 * 从会话摘要里取出待答问题。
 *
 * `session/list` 的 projections 里就带有 userQuestions，这让手表不必单独拉取即可
 * 显示「哪个会话在等我回答」。state 为 open（前台等待）或 continued（已超时挂起）
 * 都要保留——两者的应答通道不同。
 * @returns {object|undefined} 形如 {callId, state, questions:[...]}
 */
export function questionOfSession(item) {
  const active = item?.projections?.values?.userQuestions?.active
  if (!Array.isArray(active) || active.length === 0) return undefined
  const question = active[0]
  const questions = Array.isArray(question?.questions) ? question.questions : []
  if (questions.length === 0) return undefined
  return {
    callId: String(question.callId ?? ''),
    state: String(question.state ?? 'open'),
    questions: questions.map((entry) => ({
      id: String(entry?.id ?? ''),
      header: typeof entry?.header === 'string' ? entry.header : '',
      question: typeof entry?.question === 'string' ? entry.question : '',
      multiSelect: entry?.multiSelect === true,
      options: (Array.isArray(entry?.options) ? entry.options : []).map((option) => ({
        label: typeof option?.label === 'string' ? option.label : '',
        description: typeof option?.description === 'string' ? option.description : '',
      })).filter((option) => option.label !== ''),
    })),
  }
}

/**
 * 把 `session/list` 的返回压成手表用的会话列表。
 * @param {unknown} value `session/list` 的 value
 * @param {boolean} includeSubagents 是否包含子代理会话
 */
export function normalizeSessionList(value, includeSubagents = false) {
  const items = Array.isArray(value?.items) ? value.items : []
  const sessions = []
  for (const item of items) {
    const origin = String(item?.origin ?? '')
    const isSubagent = origin === SUBAGENT_ORIGIN
    if (isSubagent && !includeSubagents) continue
    const title = item?.projections?.values?.title
    const cwd = typeof item?.cwd === 'string' ? item.cwd : ''
    sessions.push({
      id: String(item?.sessionId ?? ''),
      // 空标题的会话用工作目录名兜底，手表上至少能区分。
      title: typeof title === 'string' && title.trim() !== '' ? title.trim() : (cwd === '' ? '未命名会话' : basename(cwd)),
      running: item?.running === true,
      blank: item?.blank === true,
      waiting: questionOfSession(item) !== undefined,
      updatedAt: Number(item?.updatedAt ?? 0),
      seq: Number(item?.projections?.asOfSeq ?? 0),
      subagent: isSubagent,
      cwd,
    })
  }
  sessions.sort((a, b) => b.updatedAt - a.updatedAt)
  return sessions.slice(0, MAX_SESSIONS)
}
