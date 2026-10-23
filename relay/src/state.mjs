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
export const MAX_SESSIONS = 60
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
 *
 * 截断由调用方决定（`limit`）：子代理动辄数百条且更新时间很新，若在这里先截断
 * 再过滤，父会话会被子代理挤出列表。传 0 表示不截断。
 * @param {unknown} value `session/list` 的 value
 * @param {boolean} includeSubagents 是否包含子代理会话
 * @param {number} [limit] 返回条数上限，0 表示不截断
 */
export function normalizeSessionList(value, includeSubagents = false, limit = MAX_SESSIONS) {
  const items = Array.isArray(value?.items) ? value.items : []
  const sessions = []
  for (const item of items) {
    const origin = String(item?.origin ?? '')
    const isSubagent = origin === SUBAGENT_ORIGIN
    if (isSubagent && !includeSubagents) continue
    const values = item?.projections?.values ?? {}
    const title = values.title
    const cwd = typeof item?.cwd === 'string' ? item.cwd : ''
    const model = values.modelSelection?.next ?? values.modelSelection?.lastUsed
    const identity = values.subagent
    const timing = values.subagentTiming ?? {}
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
      // 子代理会话靠 parentSessionId 挂回父会话；普通会话为空串。
      parentId: typeof item?.parentSessionId === 'string' ? item.parentSessionId : '',
      // 子代理的显示名来自它自己的 subagent.label（continuable 模式必有）。
      subagentLabel: typeof identity?.label === 'string' ? identity.label : '',
      subagentMode: typeof identity?.mode === 'string' ? identity.mode : '',
      agentAvailable: item?.agentAvailable === true,
      // 子代理耗时与活跃标记：settledMs 是已跑时长，active 存在表示当前仍在跑。
      settledMs: numberOr(timing.settledMs),
      active: timing.active !== undefined && timing.active !== null,
      completed: timing.lastTurnCompleted === true,
      // 轮数与步数：子任务列表里用来显示「干了多少活」。
      turns: numberOr(values?.sessionStats?.turns),
      steps: numberOr(values?.sessionStats?.steps),
      cwd,
      // 项目分组用：取工作目录的最后一段作为组名，根目录会话归到「未分组」。
      project: projectNameOf(cwd),
      projectPath: cwd,
      provider: typeof model?.provider === 'string' ? model.provider : '',
      model: typeof model?.model === 'string' ? model.model : '',
      effort: typeof model?.reasoningEffort === 'string' ? model.reasoningEffort : '',
    })
  }
  sessions.sort((a, b) => b.updatedAt - a.updatedAt)
  return limit > 0 ? sessions.slice(0, limit) : sessions
}

/** 数字兜底：非有限数一律按 0 处理，避免手表端拿到 null 崩掉。 */
function numberOr(value) {
  return typeof value === 'number' && Number.isFinite(value) ? value : 0
}

/**
 * 挑出某个会话派生的子代理，压成手表列表要的结构。
 *
 * 数据全部来自一次 `session/list`（每个条目自带 projections.values），因此这里
 * 不额外发起任何 RPC——498 个子代理逐个拉投影会慢到不可用。
 *
 * @param {Array<object>} sessions `normalizeSessionList(value, true)` 的结果
 * @param {string} parentId 父会话 id
 * @returns {Array<object>} 按「运行中优先、再按创建时间倒序」排好的子代理
 */
export function subagentsOf(sessions, parentId) {
  const list = []
  for (const session of Array.isArray(sessions) ? sessions : []) {
    if (session.parentId !== parentId) continue
    const name = session.subagentLabel !== '' ? session.subagentLabel : session.title
    list.push({
      id: session.id,
      // 子代理不一定有 label（one-shot 模式），退回会话标题。
      label: name === '' ? '子任务' : name,
      title: session.title,
      mode: session.subagentMode,
      state: stateOf(session),
      running: session.running,
      settledMs: session.settledMs,
      active: session.active,
      completed: session.completed,
      updatedAt: session.updatedAt,
      turns: session.turns,
      steps: session.steps,
    })
  }
  list.sort((a, b) => {
    if (a.running !== b.running) return a.running ? -1 : 1
    return b.updatedAt - a.updatedAt
  })
  return list
}

/** 子代理状态：运行中 / 失败 / 已完成 / 空闲。 */
function stateOf(session) {
  if (session.running) return 'running'
  if (session.completed) return 'done'
  if (session.agentAvailable) return 'idle'
  return 'done'
}

/**
 * 把一个会话的「计划与任务」压成手表要的结构。
 *
 * 四块内容来源不同、含义也不同，不要混为一谈：
 * - todos：todo_write 产生的待办清单，是真正会逐项勾选的东西；
 * - plan：只是「计划模式」的开关状态（active/pending 两个布尔），没有正文；
 * - team：Agent Teams 的成员与任务板（谁负责、什么状态、被谁阻塞）；
 * - goal：长目标（objective + phase），只在有目标时存在。
 * @param {object} values `session/projections` 的 values
 */
export function normalizePlan(values) {
  const todos = (Array.isArray(values?.todos) ? values.todos : [])
    .map((item) => ({
      content: typeof item?.content === 'string' ? item.content : '',
      status: typeof item?.status === 'string' ? item.status : 'pending',
    }))
    .filter((item) => item.content !== '')

  const members = (Array.isArray(values?.agentTeam?.members) ? values.agentTeam.members : [])
    .map((member) => ({
      name: typeof member?.name === 'string' ? member.name : '',
      role: typeof member?.role === 'string' ? member.role : 'teammate',
      phase: typeof member?.phase === 'string' ? member.phase : 'active',
      error: typeof member?.error === 'string' ? member.error : '',
    }))
    .filter((member) => member.name !== '')

  const tasks = (Array.isArray(values?.agentTeam?.tasks) ? values.agentTeam.tasks : [])
    .filter((task) => String(task?.status ?? '') !== 'deleted')
    .map((task) => ({
      id: String(task?.id ?? ''),
      subject: typeof task?.subject === 'string' ? task.subject : '',
      status: typeof task?.status === 'string' ? task.status : 'pending',
      owner: typeof task?.ownerName === 'string' ? task.ownerName : '',
      ready: task?.ready === true,
      // 被谁阻塞只给名字，手表上没有空间展开 id。
      blockedBy: (Array.isArray(task?.blockedBy) ? task.blockedBy : []).map((value) => String(value)),
    }))
    .filter((task) => task.subject !== '')

  const goal = values?.goal?.goal
  const identity = values?.subagent
  return {
    todos,
    mode: {
      active: values?.plan?.active === true,
      pending: values?.plan?.pending === true,
    },
    team: { members, tasks },
    goal: goal === undefined || goal === null ? undefined : {
      objective: typeof goal.objective === 'string' ? goal.objective : '',
      phase: typeof goal.phase === 'string' ? goal.phase : 'active',
      rounds: numberOr(values?.goal?.roundsStarted),
      maxRounds: numberOr(goal.maxGoalRounds),
      blockedReason: typeof goal.blockedReason === 'string' ? goal.blockedReason : '',
    },
    subagent: identity === null || identity === undefined ? undefined : {
      label: typeof identity.label === 'string' ? identity.label : '',
      mode: typeof identity.mode === 'string' ? identity.mode : '',
    },
  }
}

/**
 * 项目的显示名。
 *
 * 手表屏幕上放不下完整路径，用最后一段目录名即可区分；但会话直接开在盘符或
 * 用户主目录时最后一段没有信息量（如 `D:\`），这时退回完整路径。
 */
export function projectNameOf(cwd) {
  if (typeof cwd !== 'string' || cwd.trim() === '') return '未分组'
  const parts = cwd.split(/[\\/]+/).filter((part) => part !== '')
  const last = parts[parts.length - 1] ?? ''
  // 「D:」「C:」这类盘符根目录没有可读名称。
  if (last === '' || last.endsWith(':')) return cwd
  return last
}

/** 会话内每一步的展示条目：用户消息、助手回复、工具调用。 */
const STEP_KINDS = {
  message: 'message',
  tool: 'tool',
}

/**
 * 把会话事件压成「步骤」序列，尽量还原电脑上的观感。
 *
 * 与 normalizeMessages 的区别：这里保留工具调用与它的结果，并尽量抽出
 * 可读的参数摘要（命令、文件路径）和结果开头，让手表能按步展示。
 * @param {Array<object>} records `session/page` 的 records
 * @param {number} [maxText] 单条文本上限
 */
export function normalizeSteps(records, maxText = 1_200) {
  const steps = []
  /** callId → 已生成的工具条目，便于把结果挂回去。 */
  const pendingTools = new Map()
  let pendingAssistant = undefined

  const flushAssistant = () => {
    if (pendingAssistant === undefined) return
    if (pendingAssistant.text !== '' || pendingAssistant.tools.length > 0) steps.push(pendingAssistant)
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
      steps.push({ kind: STEP_KINDS.message, seq, time, role: 'user', text: clip(text, maxText), tools: [] })
      continue
    }

    if (event.type === 'assistant/message') {
      const message = data.message ?? {}
      const text = textOfContent(message?.content)
      const calls = Array.isArray(message?.content) ? message.content.filter((b) => b?.type === 'tool-call') : []
      if (pendingAssistant === undefined) {
        pendingAssistant = { kind: STEP_KINDS.message, seq, time, role: 'assistant', text: '', tools: [] }
      }
      if (text !== '') {
        pendingAssistant.text = pendingAssistant.text === '' ? text : `${pendingAssistant.text}\n\n${text}`
      }
      pendingAssistant.seq = seq
      pendingAssistant.time = time
      // 每次工具调用单独成条，排在助手正文之后，顺序与电脑上一致。
      for (const call of calls) {
        flushAssistant()
        const name = typeof call.name === 'string' ? call.name : '工具'
        const label = TOOL_LABELS[name] ?? name
        if (pendingAssistant === undefined) {
          pendingAssistant = { kind: STEP_KINDS.message, seq, time, role: 'assistant', text: '', tools: [] }
        }
        pendingAssistant.tools.push(label)
        const tool = {
          kind: STEP_KINDS.tool,
          seq,
          time,
          name,
          label,
          detail: describeToolCall(name, call.arguments),
          status: 'running',
          result: '',
          files: [],
          isError: false,
        }
        steps.push(tool)
        if (typeof call.id === 'string') pendingTools.set(call.id, tool)
      }
      continue
    }

    if (event.type === 'tool/result') {
      const message = data.message ?? {}
      const callId = typeof message.toolCallId === 'string' ? message.toolCallId : ''
      flushAssistant()
      const tool = callId === '' ? undefined : pendingTools.get(callId)
      const text = textOfContent(message?.content)
      const diffs = Array.isArray(data.meta?.diffs) ? data.meta.diffs : []
      if (tool === undefined) {
        // 没有对应调用（例如从中间翻页）：单独成一条，信息不丢。
        steps.push({
          kind: STEP_KINDS.tool,
          seq,
          time,
          name: '',
          label: '工具结果',
          detail: '',
          status: message.isError === true ? 'error' : 'done',
          result: clip(text, maxText),
          files: diffs.map((d) => String(d?.path ?? '')).filter((p) => p !== ''),
          isError: message.isError === true,
        })
        continue
      }
      tool.status = message.isError === true ? 'error' : 'done'
      tool.result = clip(text, maxText)
      tool.isError = message.isError === true
      tool.files = diffs.map((d) => String(d?.path ?? '')).filter((p) => p !== '')
      tool.resultSeq = seq
      continue
    }

    if (event.type === 'turn/end') flushAssistant()
  }
  flushAssistant()
  return steps
}

/** 截断长文本并标注省略。 */
function clip(text, limit) {
  if (typeof text !== 'string') return ''
  const value = text.trim()
  if (value.length <= limit) return value
  return `${value.slice(0, limit)}…（已省略）`
}

/**
 * 从工具参数里抽出最有信息量的一句说明。
 *
 * 手表上不可能展示完整 JSON，因此按工具类型挑关键字段：命令类给命令，
 * 文件类给路径，搜索类给关键词或模式。
 */
export function describeToolCall(name, rawArguments) {
  let args
  if (typeof rawArguments === 'string') {
    try { args = JSON.parse(rawArguments) } catch { return clipOneLine(rawArguments, 120) }
  } else {
    args = rawArguments
  }
  if (args === null || typeof args !== 'object') return ''

  const order = [
    'description', 'file_path', 'path', 'notebook_path',
    'command', 'pattern', 'query', 'url', 'prompt', 'name', 'subagent_type', 'skill',
  ]
  for (const key of order) {
    const value = args[key]
    if (typeof value === 'string' && value.trim() !== '') return clipOneLine(value, key === 'command' ? 160 : 120)
  }
  // 没有已知字段时退回首行，至少让用户知道调了什么。
  const text = typeof rawArguments === 'string' ? rawArguments : JSON.stringify(args)
  return clipOneLine(text, 100)
}

/** 压成单行短文本。 */
function clipOneLine(text, limit) {
  const value = String(text).replace(/\s+/gu, ' ').trim()
  if (value.length <= limit) return value
  return `${value.slice(0, limit)}…`
}

/**
 * 把会话的用量与运行统计压成手表能直接显示的结构。
 *
 * 数值都保持原始含义，格式化交给手表端（不同语言的单位写法不同）。
 * @param {object} values `session/projections` 的 values
 */
export function normalizeUsage(values) {
  const tokens = values?.tokenUsage ?? {}
  const pressure = values?.contextPressure ?? {}
  const breakdown = values?.contextBreakdown ?? {}
  const stats = values?.sessionStats ?? {}
  const numberOr = (value) => (typeof value === 'number' && Number.isFinite(value) ? value : 0)
  const contextWindow = numberOr(pressure.contextWindow)
  const pressureTokens = numberOr(pressure.pressureTokens)
  return {
    // token 用量
    inputTokens: numberOr(tokens.uncachedInputTokens),
    outputTokens: numberOr(tokens.outputTokens),
    cacheReadTokens: numberOr(tokens.cacheReadTokens),
    cacheWriteTokens: numberOr(tokens.cacheWriteTokens),
    // 上下文占用
    pressureTokens,
    projectedTokens: numberOr(pressure.projectedTokens),
    contextWindow,
    percent: contextWindow > 0 ? Math.round((pressureTokens / contextWindow) * 1000) / 10 : 0,
    systemTokens: numberOr(breakdown.systemTokens),
    toolsTokens: numberOr(breakdown.toolsTokens),
    messageTokens: numberOr(breakdown.messageTokens),
    // 运行统计
    turns: numberOr(stats.turns),
    steps: numberOr(stats.steps),
    llmMs: numberOr(stats.llmMs),
    toolMs: numberOr(stats.toolMs),
    ttftMs: numberOr(stats.ttftMs),
    // 模型
    provider: typeof values?.modelSelection?.next?.provider === 'string' ? values.modelSelection.next.provider : '',
    model: typeof values?.modelSelection?.next?.model === 'string' ? values.modelSelection.next.model : '',
    effort: typeof values?.modelSelection?.next?.reasoningEffort === 'string' ? values.modelSelection.next.reasoningEffort : '',
    agentPreset: typeof values?.agentPreset === 'string' ? values.agentPreset : '',
  }
}

/**
 * 把 `session/modelCatalog` 压成手表用的分组列表。
 *
 * DSH 返回的 groups 里每个 provider 带一串模型与各自的 reasoning 档位；
 * 手表只保留可路由的 provider，并把档位压成 id 数组。
 */
export function normalizeModelCatalog(catalog) {
  const groups = Array.isArray(catalog?.groups) ? catalog.groups : []
  const routable = new Set(Array.isArray(catalog?.routableProviders) ? catalog.routableProviders : [])
  const out = []
  for (const group of groups) {
    const provider = String(group?.id ?? '')
    if (provider === '') continue
    if (routable.size > 0 && !routable.has(provider)) continue
    const models = []
    for (const model of Array.isArray(group?.models) ? group.models : []) {
      const id = String(model?.id ?? '')
      if (id === '') continue
      models.push({
        id,
        name: typeof model?.name === 'string' && model.name !== '' ? model.name : id,
        efforts: (Array.isArray(model?.reasoning?.efforts) ? model.reasoning.efforts : [])
          .map((effort) => String(effort?.id ?? ''))
          .filter((value) => value !== ''),
      })
    }
    if (models.length === 0) continue
    out.push({
      provider,
      name: typeof group?.name === 'string' && group.name !== '' ? group.name : provider,
      models,
    })
  }
  return {
    groups: out,
    default: {
      provider: typeof catalog?.default?.provider === 'string' ? catalog.default.provider : '',
      model: typeof catalog?.default?.model === 'string' ? catalog.default.model : '',
      effort: typeof catalog?.default?.reasoningEffort === 'string' ? catalog.default.reasoningEffort : '',
    },
  }
}

/**
 * 把会话按项目（工作目录）分组。
 *
 * 手表列表首屏放不下几十个会话，按项目折叠后一眼能看到「有哪些项目」。
 * 组内保持按更新时间倒序，组之间按「最近活动」排序。
 */
export function groupByProject(sessions) {
  const groups = new Map()
  for (const session of sessions) {
    const key = session.project === '' ? '未分组' : session.project
    let group = groups.get(key)
    if (group === undefined) {
      group = {
        project: key,
        path: session.projectPath ?? '',
        sessions: [],
        running: 0,
        waiting: 0,
        updatedAt: 0,
      }
      groups.set(key, group)
    }
    group.sessions.push(session)
    if (session.running) group.running += 1
    if (session.waiting) group.waiting += 1
    if (session.updatedAt > group.updatedAt) group.updatedAt = session.updatedAt
  }
  const out = [...groups.values()]
  for (const group of out) group.sessions.sort((a, b) => b.updatedAt - a.updatedAt)
  out.sort((a, b) => b.updatedAt - a.updatedAt)
  return out
}
