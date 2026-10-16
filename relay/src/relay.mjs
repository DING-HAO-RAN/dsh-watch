/**
 * 手表端接入层：面向手表的精简 REST API + 局域网自动发现。
 *
 * 设计取舍：
 * - 手表不直接说 DSH 协议（Typert 信封、authority 绑定的 cookie、数百 KB 的原始响应
 *   都不适合手表），中继把协议差异吸收掉，手表只看到「会话列表 / 消息 / 发送 / 回答」。
 * - 配对是必需的：中继监听 0.0.0.0，同一局域网内任何设备都能连上，若无口令就等于把
 *   DSH 的完全控制权开放出去。启动时生成 6 位一次性口令，手表输入一次换取长期令牌。
 * - 发现用 UDP 广播而不是 mDNS：手表侧只需一个 DatagramSocket，不引入任何依赖，
 *   且能同时广播「服务名 + 端口」。
 */
import { createServer } from 'node:http'
import { createSocket } from 'node:dgram'
import { networkInterfaces } from 'node:os'
import { readFileSync, writeFileSync } from 'node:fs'
import { randomBytes, randomInt, randomUUID, timingSafeEqual } from 'node:crypto'
import { MAX_MESSAGES, normalizeMessages, normalizeSessionList, questionOfSession } from './state.mjs'

const DISCOVERY_PORT = 19391
const DISCOVERY_INTERVAL_MS = 2_000
const SERVICE_TAG = 'dsh-watch-relay/1'
const MAX_BODY_BYTES = 256 * 1024
/** 令牌有效期：30 天。 */
const TOKEN_TTL_MS = 30 * 24 * 60 * 60 * 1000
/** 一个 waterfall 事件的有效期：超过后只能走 RPC 补答。 */
const EVENT_WINDOW_MS = 10 * 60 * 1000

function json(res, status, payload) {
  const body = JSON.stringify(payload)
  res.writeHead(status, {
    'content-type': 'application/json; charset=utf-8',
    'content-length': Buffer.byteLength(body),
    'cache-control': 'no-store',
  })
  res.end(body)
}

/** 常量时间比较，避免用响应时间侧信道猜口令。 */
function equalsSecret(a, b) {
  const left = Buffer.from(String(a), 'utf8')
  const right = Buffer.from(String(b), 'utf8')
  return left.byteLength === right.byteLength && timingSafeEqual(left, right)
}

async function readJsonBody(req) {
  const chunks = []
  let size = 0
  for await (const chunk of req) {
    size += chunk.length
    if (size > MAX_BODY_BYTES) throw new Error('请求体过大')
    chunks.push(chunk)
  }
  if (size === 0) return {}
  try {
    return JSON.parse(Buffer.concat(chunks).toString('utf8'))
  } catch {
    throw new Error('请求体不是合法 JSON')
  }
}

/** 生成人类可读的 6 位配对口令（避开容易看错的 0/O/1/I）。 */
export function newPairingCode() {
  const alphabet = '23456789'
  let code = ''
  for (let i = 0; i < 6; i += 1) code += alphabet[randomInt(alphabet.length)]
  return code
}

/** 把提问投影压成手表要的结构（与 state.js 的 questions 形状保持一致）。 */
function compactQuestions(questions) {
  return (Array.isArray(questions) ? questions : []).map((entry) => ({
    id: String(entry?.id ?? ''),
    header: typeof entry?.header === 'string' ? entry.header : '',
    question: typeof entry?.question === 'string' ? entry.question : '',
    multiSelect: entry?.multiSelect === true,
    options: (Array.isArray(entry?.options) ? entry.options : [])
      .map((option) => ({
        label: typeof option?.label === 'string' ? option.label : '',
        description: typeof option?.description === 'string' ? option.description : '',
      }))
      .filter((option) => option.label !== ''),
  }))
}

export class RelayServer {
  /**
   * @param {object} options
   * @param {import('./dsh.mjs').DshLink} options.link 已配置的 DSH 连接
   * @param {number} [options.port] 手表接入端口
   * @param {string} [options.stateFile] 已配对令牌的持久化文件；省略则不落盘
   * @param {(message: string) => void} [options.log] 日志回调
   */
  constructor(options) {
    this.link = options.link
    this.port = options.port ?? 19390
    this.stateFile = options.stateFile
    this.log = options.log ?? (() => {})
    this.pairingCode = newPairingCode()
    this.tokens = new Map()
    /** callId → {eventId, at}：用于优先走 waterfall 前台应答通道。 */
    this.pendingEvents = new Map()
    this.lastReconcileAt = 0
    this.server = undefined
    this.beacon = undefined
    this.beaconSocket = undefined
    this.startedAt = Date.now()
    this.loadTokens()
  }

  /** 载入上次运行留下的令牌，避免中继重启后手表需要重新配对。 */
  loadTokens() {
    if (this.stateFile === undefined) return
    try {
      const raw = readFileSync(this.stateFile, 'utf8')
      const parsed = JSON.parse(raw)
      const now = Date.now()
      for (const [token, record] of Object.entries(parsed?.tokens ?? {})) {
        const createdAt = Number(record?.createdAt ?? 0)
        if (createdAt > 0 && now - createdAt <= TOKEN_TTL_MS) {
          this.tokens.set(token, { createdAt, lastSeenAt: Number(record?.lastSeenAt ?? createdAt) })
        }
      }
      if (this.tokens.size > 0) this.log(`已恢复 ${String(this.tokens.size)} 个配对令牌`)
    } catch (error) {
      if (error.code !== 'ENOENT') this.log(`读取配对状态失败（将重新配对）：${String(error.message)}`)
    }
  }

  /** 把令牌写入磁盘；失败不影响服务运行。 */
  saveTokens() {
    if (this.stateFile === undefined) return
    try {
      const tokens = {}
      for (const [token, record] of this.tokens) tokens[token] = record
      writeFileSync(this.stateFile, JSON.stringify({ version: 1, tokens }, null, 2), { mode: 0o600 })
    } catch (error) {
      this.log(`保存配对状态失败：${String(error.message)}`)
    }
  }

  /** 局域网 IPv4 地址列表，用于打印提示。 */
  addresses() {
    const list = []
    for (const entries of Object.values(networkInterfaces())) {
      for (const entry of entries ?? []) {
        if (entry.family === 'IPv4' && entry.internal !== true) list.push(entry.address)
      }
    }
    return list
  }

  newToken() {
    const token = randomBytes(24).toString('base64url')
    this.tokens.set(token, { createdAt: Date.now(), lastSeenAt: Date.now() })
    return token
  }

  /** 校验请求携带的令牌；未知或过期的令牌返回 false。 */
  authorized(req) {
    const header = req.headers.authorization
    if (typeof header !== 'string' || !header.startsWith('Bearer ')) return false
    const token = header.slice('Bearer '.length).trim()
    const record = this.tokens.get(token)
    if (record === undefined) return false
    if (Date.now() - record.createdAt > TOKEN_TTL_MS) {
      this.tokens.delete(token)
      return false
    }
    record.lastSeenAt = Date.now()
    return true
  }

  /**
   * 记录 waterfall 帧：既保存 eventId 用于走前台应答通道，也保存问题内容，
   * 这样即使投影还没登记（前台 open 状态），手表也能看到并回答。
   *
   * 注意：DSH 的前台提问帧**不带 callId**（只有 eventId + questions），
   * 因此这里以 eventId 作为条目标识；投影登记的超时问题才带 callId。
   */
  onEventFrame(frame) {
    if (frame.type === 'waterfall' && frame.event === 'user-questions/request') {
      const request = frame.request ?? {}
      const eventId = String(frame.eventId ?? '')
      const callId = String(request.callId ?? '') || eventId
      if (callId !== '') {
        this.pendingEvents.set(callId, {
          eventId,
          sessionId: String(frame.agentId ?? ''),
          questions: Array.isArray(request.questions) ? request.questions : [],
          at: Date.now(),
        })
        const fresh = [...this.pendingEvents.entries()].filter(([, record]) => Date.now() - record.at < EVENT_WINDOW_MS)
        this.pendingEvents = new Map(fresh.slice(-20))
      }
    }
    if (frame.type === 'cancel' && typeof frame.eventId === 'string') {
      for (const [key, record] of this.pendingEvents) {
        if (record.eventId === frame.eventId) this.pendingEvents.delete(key)
      }
    }
  }

  /** 汇总当前所有待答问题（跨会话），供手表列表页提示。 */
  async collectPending(sessions) {
    const pending = []
    for (const session of sessions) {
      if (!session.waiting) continue
      const projection = await this.link.rpcSafe('session/projections', { request: { sessionId: session.id } })
      const active = projection?.values?.userQuestions?.active
      if (!Array.isArray(active)) continue
      for (const item of active) {
        const callId = String(item?.callId ?? '')
        pending.push({
          sessionId: session.id,
          sessionTitle: session.title,
          callId,
          eventId: this.pendingEvents.get(callId)?.eventId ?? '',
          state: String(item?.state ?? 'open'),
          questions: compactQuestions(item?.questions),
        })
      }
    }
    return pending
  }

  /**
   * 补齐已经在等待、但可能被错过的提问。
   *
   * 为什么需要：提问以 waterfall 帧经 $events 长连推送。DSH 会在新客户端接入时重放
   * 所有待处理事件，所以正常情况下中继不会漏；但若长连在提问期间断开过（网络抖动、
   * DSH 重启），这一条就可能没收到。此时投影又只登记已超时挂起（continued）的问题，
   * 前台阻塞（open）的提问就会看不见。
   *
   * 做法：确实一处待答记录都没有、但存在运行中的会话时，重连一次事件流以触发重放。
   * 加频率限制，避免反复打断长连。
   */
  async reconcilePending(sessions, projectionPending) {
    if (projectionPending.length > 0) return
    // 事件流里已经记着提问时不重连：重连会换掉 clientId，反而让在途应答失效。
    if (this.pendingEvents.size > 0) return
    if (!sessions.some((session) => session.running)) return
    if (this.link.clientId === undefined) return
    const now = Date.now()
    if (now - this.lastReconcileAt < 30_000) return
    this.lastReconcileAt = now
    this.log('未发现待答问题但有会话在运行，重连事件流以触发 DSH 重放待处理事件…')
    this.link.refreshEvents()
  }

  /**
   * 汇总待答问题：先读投影，再在必要时通过事件流重放补齐。
   * 合并两类来源，避免同一 callId 重复出现。
   */
  async pendingQuestions(sessions) {
    const fromProjection = await this.collectPending(sessions)
    await this.reconcilePending(sessions, fromProjection)
    const merged = new Map()
    for (const ask of fromProjection) merged.set(ask.callId, ask)
    // 事件流里已收到、但投影还没登记的提问（前台等待中）：用会话信息补全后加入。
    for (const [eventKey, record] of this.pendingEvents) {
      if (merged.has(eventKey)) continue
      const sessionId = record.sessionId ?? ''
      const session = sessions.find((item) => item.id === sessionId)
      merged.set(eventKey, {
        sessionId,
        sessionTitle: session?.title ?? '',
        // 前台等待中的提问在投影里没有 callId；用 eventId 兼作标识，
        // 应答时 DSH 只按 clientId+eventId 关联，不需要真正的 callId。
        callId: eventKey,
        eventId: record.eventId,
        state: 'open',
        questions: compactQuestions(record.questions),
      })
    }
    return [...merged.values()].filter((ask) => ask.questions.length > 0)
  }

  /** 读取会话的消息与元信息（列表页与会话页共用）。 */
  async readSession(sessionId, running) {
    const projection = await this.link.rpc('session/projections', { request: { sessionId } })
    const asOfSeq = Number(projection?.asOfSeq ?? 0)
    const page = await this.link.rpc('session/page', {
      request: { address: { kind: 'session', sessionId }, throughSeq: asOfSeq, maxMessages: MAX_MESSAGES },
    })
    const active = projection?.values?.userQuestions?.active
    const first = Array.isArray(active) && active.length > 0 ? active[0] : undefined
    let question
    if (first !== undefined) {
      const callId = String(first.callId ?? '')
      question = {
        callId,
        eventId: this.pendingEvents.get(callId)?.eventId ?? '',
        state: String(first.state ?? 'open'),
        questions: compactQuestions(first.questions),
      }
    } else {
      // 投影里没有，但事件流已收到该会话的前台提问：用事件流的内容顶上。
      const entry = [...this.pendingEvents.entries()].find(([, record]) => record.sessionId === sessionId)
      if (entry !== undefined) {
        question = {
          callId: entry[0],
          eventId: entry[1].eventId,
          state: 'open',
          questions: compactQuestions(entry[1].questions),
        }
      }
    }
    return {
      ok: true,
      sessionId,
      title: typeof projection?.values?.title === 'string' ? projection.values.title : '',
      running,
      hasMore: page?.hasMore === true,
      asOfSeq,
      messages: normalizeMessages(page?.records),
      ...(question === undefined ? {} : { question }),
    }
  }

  async route(req, res, url) {
    const path = url.pathname

    // 无需鉴权：手表用它确认「这是不是 DSH 中继」。
    if (path === '/api/status' && req.method === 'GET') {
      json(res, 200, {
        service: SERVICE_TAG,
        name: 'DSH 手表中继',
        version: 1,
        needsPairing: true,
        uptimeMs: Date.now() - this.startedAt,
      })
      return
    }

    if (path === '/api/pair' && req.method === 'POST') {
      const body = await readJsonBody(req)
      const code = String(body.code ?? '').trim()
      if (code === '' || !equalsSecret(code, this.pairingCode)) {
        json(res, 403, { ok: false, error: '配对码不正确' })
        return
      }
      // 配对成功后换一个新口令，防止同一口令被重复使用。
      this.pairingCode = newPairingCode()
      const token = this.newToken()
      this.saveTokens()
      this.log(`手表已配对，新的配对码：${this.pairingCode}`)
      json(res, 200, { ok: true, token, expiresInMs: TOKEN_TTL_MS })
      return
    }

    if (!this.authorized(req)) {
      json(res, 401, { ok: false, error: '未配对或令牌已失效' })
      return
    }

    if (path === '/api/sessions' && req.method === 'GET') {
      const includeSubagents = url.searchParams.get('subagents') === '1'
      const value = await this.link.rpc('session/list', { _request: {} })
      const sessions = normalizeSessionList(value, includeSubagents)
      json(res, 200, { ok: true, sessions, pending: await this.pendingQuestions(sessions) })
      return
    }

    if (path === '/api/session' && req.method === 'GET') {
      const sessionId = url.searchParams.get('id') ?? ''
      if (sessionId === '') {
        json(res, 400, { ok: false, error: '缺少 id 参数' })
        return
      }
      // running 从列表摘要读：projections 里没有这个字段。
      const listed = normalizeSessionList(await this.link.rpcSafe('session/list', { _request: {} }), true)
      const summary = listed.find((item) => item.id === sessionId)
      json(res, 200, await this.readSession(sessionId, summary?.running === true))
      return
    }

    if (path === '/api/send' && req.method === 'POST') {
      const body = await readJsonBody(req)
      const sessionId = String(body.sessionId ?? '')
      const text = String(body.text ?? '')
      if (sessionId === '' || text.trim() === '') {
        json(res, 400, { ok: false, error: '缺少 sessionId 或 text' })
        return
      }
      await this.link.rpc('session/prompt', {
        request: {
          requestId: randomUUID(),
          sessionId,
          mode: body.mode === 'steer' ? 'steer' : 'queue',
          content: [{ type: 'text', text }],
          clientTimeZone: 'Asia/Shanghai',
        },
      })
      json(res, 200, { ok: true })
      return
    }

    if (path === '/api/cancel' && req.method === 'POST') {
      const body = await readJsonBody(req)
      // session/cancel 同样以 request 为参数名。
      await this.link.rpc('session/cancel', { request: { sessionId: String(body.sessionId ?? '') } })
      json(res, 200, { ok: true })
      return
    }

    if (path === '/api/create' && req.method === 'POST') {
      const body = await readJsonBody(req)
      // session/create 的 descriptor 要求参数名为 request，且必填。
      const args = { request: {} }
      if (typeof body.cwd === 'string' && body.cwd !== '') args.request.cwd = body.cwd
      if (typeof body.workspaceId === 'string' && body.workspaceId !== '') args.request.workspaceId = body.workspaceId
      const value = await this.link.rpc('session/create', args)
      json(res, 200, { ok: true, sessionId: String(value?.sessionId ?? '') })
      return
    }

    if (path === '/api/answer' && req.method === 'POST') {
      const body = await readJsonBody(req)
      const sessionId = String(body.sessionId ?? '')
      const answers = Array.isArray(body.answers) ? body.answers : []
      const callId = String(body.callId ?? '')
      // eventId 可以由手表回传（它从待答问题里拿到了），也可以在服务端按 callId 反查。
      const eventId = String(body.eventId ?? this.pendingEvents.get(callId)?.eventId ?? '')
      if (sessionId === '' || answers.length === 0) {
        json(res, 400, { ok: false, error: '缺少 sessionId 或 answers' })
        return
      }
      const normalized = answers.map((item) => ({
        id: String(item?.id ?? ''),
        selected: (Array.isArray(item?.selected) ? item.selected : []).map((value) => String(value)),
        ...(typeof item?.custom === 'string' && item.custom !== '' ? { custom: item.custom } : {}),
      }))

      // 通道一：问题仍在前台等待时，DSH 只认 $events/result 回填——它按 clientId+eventId
      // 关联，不需要 callId（见 dsh-api-gateway 的 remote-events.answer）。
      if (eventId !== '') {
        try {
          await this.link.resolveEvent(eventId, { answers: normalized })
          this.pendingEvents.delete(callId)
          json(res, 200, { ok: true, via: 'events' })
          return
        } catch (error) {
          this.log(`事件流应答失败，改用 RPC 补答：${String(error.message)}`)
        }
      }

      // 通道二：问题已超时挂起（continued）时用 RPC 补答。
      if (callId === '') {
        json(res, 400, { ok: false, error: '这个提问已失效，请让电脑端重新提问' })
        return
      }
      const accepted = await this.link.rpc('userQuestions/answer', { agentId: sessionId, callId, answer: { answers: normalized } })
      json(res, 200, { ok: true, via: 'rpc', accepted: accepted === true })
      return
    }

    json(res, 404, { ok: false, error: '未知接口' })
  }

  /** 启动 HTTP 服务与 UDP 广播，返回局域网地址列表。 */
  async start() {
    this.server = createServer((req, res) => {
      const url = new URL(req.url ?? '/', `http://${req.headers.host ?? 'relay'}`)
      this.route(req, res, url).catch((error) => {
        this.log(`请求 ${url.pathname} 失败：${String(error.message)}`)
        if (!res.headersSent) json(res, 500, { ok: false, error: String(error.message) })
      })
    })
    await new Promise((resolve, reject) => {
      this.server.once('error', reject)
      this.server.listen(this.port, '0.0.0.0', resolve)
    })
    this.startBeacon()
    return this.addresses()
  }

  /**
   * 周期性 UDP 广播，让手表无需手输 IP 即可发现中继。
   * 报文是单行 JSON，手表只按 `service` 字段识别。
   */
  startBeacon() {
    const socket = createSocket({ type: 'udp4', reuseAddr: true })
    socket.on('error', (error) => { this.log(`广播失败：${String(error.message)}`) })
    socket.bind(() => {
      socket.setBroadcast(true)
      const send = () => {
        const payload = Buffer.from(JSON.stringify({ service: SERVICE_TAG, port: this.port, name: 'DSH 中继' }))
        try {
          socket.send(payload, DISCOVERY_PORT, '255.255.255.255')
        } catch (error) {
          this.log(`广播发送失败：${String(error.message)}`)
        }
      }
      send()
      this.beacon = setInterval(send, DISCOVERY_INTERVAL_MS)
      this.beacon.unref?.()
    })
    socket.unref?.()
    this.beaconSocket = socket
  }

  async stop() {
    if (this.beacon !== undefined) clearInterval(this.beacon)
    this.beacon = undefined
    this.beaconSocket?.close()
    await new Promise((resolve) => { this.server?.close(resolve) })
  }
}
