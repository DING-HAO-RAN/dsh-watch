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
import { readFileSync, writeFileSync, existsSync } from 'node:fs'
import { readFile, readdir, stat, writeFile } from 'node:fs/promises'
import { basename, extname, join, resolve, sep } from 'node:path'
import { randomBytes, randomInt, randomUUID, timingSafeEqual } from 'node:crypto'
import { moduleDirectory } from './paths.mjs'
import { TerminalHub } from './terminals.mjs'
import { MAX_MESSAGES, MAX_SESSIONS, groupByProject, normalizeMessages, normalizeModelCatalog, normalizePlan, normalizeQueue, normalizeSessionList, normalizeSteps, normalizeUsage, questionOfSession, subagentsOf } from './state.mjs'

/**
 * 控制面板静态文件所在目录。
 *
 * 开发时是 relay/public/；打包成单文件后，面板被放在可执行文件同级的 public/，
 * 因此两个位置都要探一次。
 */
const PANEL_DIR = (() => {
  const here = moduleDirectory()
  const candidates = [join(here, '..', 'public'), join(here, 'public')]
  for (const candidate of candidates) {
    try {
      if (existsSync(join(candidate, 'index.html'))) return candidate
    } catch { /* 目录不可读时试下一个 */ }
  }
  return candidates[0]
})()

const DISCOVERY_PORT = 19391
const DISCOVERY_INTERVAL_MS = 2_000
const SERVICE_TAG = 'dsh-watch-relay/1'
const MAX_BODY_BYTES = 256 * 1024
/** 令牌有效期：30 天。 */
const TOKEN_TTL_MS = 30 * 24 * 60 * 60 * 1000
/** 一个 waterfall 事件的有效期：超过后只能走 RPC 补答。 */
const EVENT_WINDOW_MS = 10 * 60 * 1000
/**
 * 会话索引的新鲜期。
 *
 * DSH 的 session/list 要 2~5 秒、返回约 1MB，绝不能每次请求都穿透。
 * 取 6 秒：比手机列表页的 10 秒轮询短，比会话页的 4 秒刷新长——
 * 既能保证用户改完会话名几秒内可见，又不会让 4 秒一次的会话刷新反复触发重拉。
 */
const FRESH_MS = 6_000
/** 内容缓存最多保留几个会话（每个约 100KB）。 */
const MAX_SESSION_PAGES = 12
/**
 * 发出消息后的「乐观运行」窗口。
 *
 * 会话列表有 6 秒缓存，刚发完消息时 running 还是 false，界面会显示「空闲」，
 * 用户以为没发出去。这里在发送后的一小段时间内直接把该会话当运行中，
 * 等真实数据接管（6 秒足够列表缓存刷新）。
 */
const OPTIMISTIC_RUNNING_MS = 8_000

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

/** 这一批事件里最早的 seq，手表拿它继续往前翻页。 */
function earliestSeqOf(records) {
  let earliest = 0
  for (const record of Array.isArray(records) ? records : []) {
    const seq = Number(record?.event?.seq ?? 0)
    if (seq > 0 && (earliest === 0 || seq < earliest)) earliest = seq
  }
  return earliest
}

/**
 * 给每个会话标上「派生了几个子任务、其中几个正在跑」。
 *
 * 手表列表页靠它一眼看出哪个会话在后台干活，不必点进去。
 * @param {Array<object>} all 含子代理的全量列表
 * @param {Array<object>} sessions 即将返回给手表的会话（与 all 共享对象引用）
 */
function attachSubagentCounts(all, sessions) {
  const counts = new Map()
  for (const item of all) {
    if (item.subagent !== true || item.parentId === '') continue
    const entry = counts.get(item.parentId) ?? { total: 0, running: 0 }
    entry.total += 1
    if (item.running) entry.running += 1
    counts.set(item.parentId, entry)
  }
  for (const session of sessions) {
    const entry = counts.get(session.id) ?? { total: 0, running: 0 }
    session.subagentCount = entry.total
    session.subagentRunning = entry.running
  }
}

/** 把提问投影压成手表要的结构（与 state.js 的 questions 形状保持一致）。 */
function compactQuestions(questions) {  return (Array.isArray(questions) ? questions : []).map((entry) => ({
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

/**
 * 有并发上限的 map。
 *
 * 用于把「对每个会话各发一次 RPC」这类串行循环改成并行：串行时每次往返都要
 * 几十毫秒到几秒，会话一多就把一个接口拖成十几秒。上限存在的意义是不把
 * DSH 一次性打爆（它自己也是单进程）。
 */
async function mapLimit(items, limit, worker) {
  const results = new Array(items.length)
  let cursor = 0
  const runners = new Array(Math.min(limit, items.length)).fill(0).map(async () => {
    while (true) {
      const index = cursor
      cursor += 1
      if (index >= items.length) return
      results[index] = await worker(items[index], index)
    }
  })
  await Promise.all(runners)
  return results
}


const MAX_FILE_BYTES = 512 * 1024
/** 一个目录最多返回的条目数。 */
const MAX_DIR_ENTRIES = 600
/** 单次上传上限：手机端把文件整段 base64 传过来，太大会把内存撑爆。 */
const MAX_UPLOAD_BYTES = 32 * 1024 * 1024

/** 只接受单个文件名，不允许任何路径成分。 */
function isSafeFileName(name) {
  if (typeof name !== 'string' || name.length === 0 || name.length > 220) return false
  if (name === '.' || name === '..') return false
  if (/[/\\]/.test(name)) return false
  // ASCII 控制字符与 Windows 保留字符。
  if (/[\u0000-\u001f\u007f<>:"|?*]/.test(name)) return false
  if (/[. ]$/.test(name)) return false
  return true
}

/** 同名文件不覆盖，依次尝试 `name (1).ext` 等候选。 */
async function pickAvailableName(root, name) {
  const dot = name.lastIndexOf('.')
  const stem = dot > 0 ? name.slice(0, dot) : name
  const ext = dot > 0 ? name.slice(dot) : ''
  for (let index = 0; index < 100; index += 1) {
    const candidate = index === 0 ? name : `${stem} (${index})${ext}`
    const fullPath = resolve(root, candidate)
    try {
      await stat(fullPath)
    } catch {
      return { name: candidate, fullPath, relative: candidate }
    }
  }
  throw new Error('同名文件过多，无法生成可用文件名')
}

/**
 * 目标路径必须落在工作区根目录内。
 *
 * 手机端的「查看文件」是浏览会话的工作目录，不是浏览整台电脑：没有这道检查，
 * 任何拿到配对令牌的设备都能用 `../../` 读到密钥文件。中继在本机有完整读权限，
 * 因此这里必须自己把关（DSH 的白名单机制在手机端用不上）。
 */
function withinRoot(root, target) {
  const base = resolve(root)
  const full = resolve(base, target)
  return full === base || full.startsWith(base + sep)
}

/** 扩展名 → 语言标识。手机端据此选高亮规则，未命中按纯文本显示。 */
function languageOf(name) {
  const ext = extname(name).toLowerCase()
  const table = {
    '.java': 'java', '.js': 'javascript', '.mjs': 'javascript', '.cjs': 'javascript',
    '.ts': 'typescript', '.tsx': 'typescript', '.jsx': 'javascript', '.json': 'json',
    '.py': 'python', '.md': 'markdown', '.css': 'css', '.html': 'html', '.htm': 'html',
    '.xml': 'xml', '.yml': 'yaml', '.yaml': 'yaml', '.sh': 'shell', '.ps1': 'powershell',
    '.bat': 'batch', '.cmd': 'batch', '.sql': 'sql', '.c': 'c', '.h': 'c', '.cpp': 'cpp',
    '.cs': 'csharp', '.go': 'go', '.rs': 'rust', '.kt': 'kotlin', '.gradle': 'groovy',
    '.toml': 'toml', '.ini': 'ini', '.txt': 'text', '.log': 'text', '.gitignore': 'text',
  }
  return table[ext] ?? 'text'
}

/** 图片不按文本读，直接给 base64，手机端用 BitmapFactory 解码。 */
function isImageName(name) {
  return ['.png', '.jpg', '.jpeg', '.webp', '.gif', '.bmp'].includes(extname(name).toLowerCase())
}

/**
 * 每个网段的定向广播地址（例如 192.168.1.255）。
 *
 * 只发 255.255.255.255 时由系统挑网卡，装了 VPN 的机器会把包送到虚拟网卡，
 * 手机所在局域网一个包都收不到。逐网段发才能保证对端一定收到。
 */
function directedBroadcasts() {
  const targets = []
  for (const entries of Object.values(networkInterfaces())) {
    for (const entry of entries ?? []) {
      if (entry.family !== 'IPv4' || entry.internal === true) continue
      const address = ipv4ToInt(entry.address)
      const mask = ipv4ToInt(entry.netmask)
      if (address === undefined || mask === undefined) continue
      const broadcast = (address & mask) | (~mask >>> 0)
      targets.push(intToIpv4(broadcast))
    }
  }
  return targets
}

function ipv4ToInt(value) {
  const parts = String(value).split('.')
  if (parts.length !== 4) return undefined
  let result = 0
  for (const part of parts) {
    const octet = Number(part)
    if (!Number.isInteger(octet) || octet < 0 || octet > 255) return undefined
    result = (result << 8) | octet
  }
  return result >>> 0
}

function intToIpv4(value) {
  const number = value >>> 0
  return [(number >>> 24) & 255, (number >>> 16) & 255, (number >>> 8) & 255, number & 255].join('.')
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
    /** eventId → 待审批请求。approval/request 也是 waterfall，回填必须带 eventId。 */
    this.pendingApprovals = new Map()
    /** 终端中心：把 attach 流桥接成手机可轮询的增量缓冲。 */
    this.terminals = new TerminalHub({ link: this.link, log: this.log })
    /** 会话索引短缓存，见 sessionIndex()。 */
    this.sessionCache = undefined
    this.sessionCacheAt = 0
    this.sessionPending = undefined
    this.sessionRefreshing = false
    /** 会话内容缓存：key 见 readSession，值是裁剪后的完整响应。 */
    this.sessionPages = new Map()
    /** sessionId → 到期时间：发出消息后的一小段时间内当作运行中，见 OPTIMISTIC_RUNNING_MS。 */
    this.optimisticRunning = new Map()
    this.lastReconcileAt = 0
    /** 公网地址（由入口在隧道就绪后注入），用于状态接口与打印。 */
    this.publicBaseUrl = undefined
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

  /** 设置公网地址（隧道就绪后由入口调用）。 */
  setPublicBaseUrl(url) {
    this.publicBaseUrl = typeof url === 'string' && url !== '' ? url : undefined
  }

  /** 请求是否来自本机（控制面板只对本机开放）。 */
  isLoopback(req) {
    const address = req.socket?.remoteAddress ?? ''
    return address === '127.0.0.1' || address === '::1' || address === '::ffff:127.0.0.1'
  }

  /** 把控制面板页面发给浏览器。 */
  servePanel(res) {
    const file = join(PANEL_DIR, 'index.html')
    readFile(file, 'utf8').then((html) => {
      const body = Buffer.from(html, 'utf8')
      res.writeHead(200, {
        'content-type': 'text/html; charset=utf-8',
        'content-length': body.length,
        'cache-control': 'no-store',
      })
      res.end(body)
    }).catch(() => {
      json(res, 500, { ok: false, error: '控制面板文件缺失，请确认 relay/public/index.html 存在' })
    })
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
   * 会话索引（stale-while-revalidate）。
   *
   * 为什么必须这样：DSH 的 `session/list` 一次返回约 1MB（748 个会话里 534 个是
   * 子代理），实测单次 2.4~4.8 秒，且参数（limit / includeSubagents）几乎压不下来——
   * 开销在 DSH 侧，不在返回体。中继有五个接口要用这份数据（会话列表、会话详情、
   * 文件页、终端页、工作区），若每次请求都穿透到 DSH，手表的 4 秒/10 秒轮询根本
   * 跑不动，表现就是「加载很久然后超时」。
   *
   * 因此：**手上有旧数据就立刻返回旧数据**，同时后台刷新；只有完全没数据时才等待。
   * 代价是新建的会话最多晚一个刷新周期（FRESH_MS）出现在列表里，换来的是任何一次
   * 请求都不再被 2~5 秒的列表拉取卡住。
   */
  async sessionIndex() {
    const now = Date.now()
    if (this.sessionCache !== undefined) {
      if (now - this.sessionCacheAt >= FRESH_MS) this.refreshSessionIndex()
      return this.sessionCache
    }
    // 冷启动：必须等一次。并发请求共用同一个 promise，避免同时打多份 1MB 的列表。
    if (this.sessionPending === undefined) {
      this.sessionPending = this.link.rpc('session/list', { _request: {} })
        .then((value) => {
          this.sessionCache = normalizeSessionList(value, true, 0)
          this.sessionCacheAt = Date.now()
          return this.sessionCache
        })
        .finally(() => { this.sessionPending = undefined })
    }
    return this.sessionPending
  }

  /**
   * 后台刷新会话索引，不阻塞调用方。
   *
   * 失败时保留旧数据并推迟下一次重试：DSH 重启的瞬间列表会报错，
   * 此时把手上的旧数据继续发出去，比让手表看到一片空白好得多。
   */
  refreshSessionIndex() {
    if (this.sessionRefreshing) return
    this.sessionRefreshing = true
    this.link.rpc('session/list', { _request: {} })
      .then((value) => {
        this.sessionCache = normalizeSessionList(value, true, 0)
        this.sessionCacheAt = Date.now()
      })
      .catch((error) => {
        this.log(`后台刷新会话列表失败（继续用旧数据）：${String(error.message)}`)
        // 有旧数据就标记为新鲜，避免 DSH 抖动时反复重试；
        // 一条数据都没有时保持「已过期」，让下一次请求立刻再试。
        if (this.sessionCache !== undefined) this.sessionCacheAt = Date.now()
      })
      .finally(() => { this.sessionRefreshing = false })
  }

  /** 会话数据变了（发送、重命名等）时让缓存立刻过期，下一次读取会重新拉。 */
  invalidateSessionIndex() {
    this.sessionCacheAt = 0
  }

  /**
   * 预热会话索引。
   *
   * DSH 的 session/list 一次 2~5 秒（返回约 1MB）。中继启动时先拉一次，
   * 等设备连上来时缓存已经就绪，第一次打开列表就是毫秒级——否则用户会看到
   * 一个 3 秒以上的白屏，网络差时还会撞上超时。
   */
  async warmup() {
    try {
      await this.sessionIndex()
      this.log(`会话索引已预热（${String(this.sessionCache?.length ?? 0)} 条）`)
    } catch (error) {
      this.log(`预热会话索引失败（首次请求会重试）：${String(error.message)}`)
    }
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
    if (frame.type === 'waterfall' && frame.event === 'approval/request') {
      // 审批与提问同构：都是一次性 waterfall，回填时 DSH 只认 clientId+eventId。
      const request = frame.request ?? {}
      const eventId = String(frame.eventId ?? '')
      if (eventId !== '') {
        this.pendingApprovals.set(eventId, {
          eventId,
          sessionId: String(frame.agentId ?? ''),
          toolName: String(request.toolName ?? ''),
          callId: String(request.callId ?? ''),
          reason: String(request.reason ?? ''),
          displayReason: typeof request.displayReason === 'string' ? request.displayReason : '',
          at: Date.now(),
        })
        const fresh = [...this.pendingApprovals.entries()].filter(([, record]) => Date.now() - record.at < EVENT_WINDOW_MS)
        this.pendingApprovals = new Map(fresh.slice(-20))
      }
    }
    if (frame.type === 'cancel' && typeof frame.eventId === 'string') {
      this.pendingApprovals.delete(frame.eventId)
      for (const [key, record] of this.pendingEvents) {
        if (record.eventId === frame.eventId) this.pendingEvents.delete(key)
      }
    }
  }

  /** 汇总当前所有待答问题（跨会话），供手表列表页提示。 */
  async collectPending(sessions) {
    // 只查标了 waiting 的会话；并发拉取（上限 4）而不是逐个 await——
    // 串行时每个会话一次往返，等待中的会话一多，列表接口就会被拖到几秒。
    const waiting = sessions.filter((session) => session.waiting)
    if (waiting.length === 0) return []
    const results = await mapLimit(waiting, 4, (session) =>
      this.link.rpcSafe('session/projections', { request: { sessionId: session.id } }))
    const pending = []
    for (let index = 0; index < waiting.length; index += 1) {
      const session = waiting[index]
      const active = results[index]?.values?.userQuestions?.active
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
   * DSH 重启），这一条就可能没收到。
   *
   * 只在「确实一处待答记录都没有」且「存在运行中的会话」时才考虑重连，并加两道闸：
   * - 每次重连都会换掉 clientId，正在等待回答的提问会随之失效，因此绝不能频繁触发；
   * - 把「会话列表里有人标了 waiting」也算作已知有待答问题，避免与它打架。
   *
   * 早期版本在这里每 30 秒无脑重连一次，导致日志刷屏、事件流反复断开——不要退回那种写法。
   */
  async reconcilePending(sessions, projectionPending) {
    if (projectionPending.length > 0) return
    if (this.pendingEvents.size > 0) return
    if (sessions.some((session) => session.waiting)) return
    if (!sessions.some((session) => session.running)) return
    if (this.link.clientId === undefined) return
    const now = Date.now()
    if (now - this.lastReconcileAt < 120_000) return
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

  /**
   * 读取会话的消息与元信息（列表页与会话页共用）。
   * @param {string} sessionId 会话 id
   * @param {boolean} running 是否正在运行（来自会话列表，投影里没有这个字段）
   * @param {number} [throughSeq] 从哪个 seq 往前读；省略则读最新
   * @param {string} [parentId] 子代理的父会话 id；普通会话传空串
   * @param {string} [mode] 子代理模式（one-shot / continuable / unknown）
   */
  async readSession(sessionId, running, throughSeq, parentId, mode) {
    // 内容缓存的正确做法：**先用便宜的 projections 拿 asOfSeq，再拿它当缓存键**。
    //
    // 为什么不能按「running 与否」猜 TTL：会话刚发完消息、DSH 还没把 running 翻过来时，
    // 会命中「空闲」的长缓存，用户就看不到自己刚发的那句话（实测踩过）。
    // projections 只要 5ms 且永远反映最新状态；真正昂贵的是 session/page
    // （一次拉回 300KB~1.5MB 原始事件，实测最重 1.5MB / 148ms）。
    // 所以：便宜的每次都问，昂贵的按 asOfSeq 命中。
    const projection = await this.link.rpc('session/projections', { request: { sessionId } })
    const latest = Number(projection?.asOfSeq ?? 0)
    const cacheKey = `${sessionId}|${throughSeq ?? 0}|${parentId ?? ''}|${mode ?? ''}|${latest}`
    const cached = this.sessionPages.get(cacheKey)
    if (cached !== undefined) return cached.value

    const from = typeof throughSeq === 'number' && Number.isSafeInteger(throughSeq) && throughSeq > 0
      ? Math.min(throughSeq, latest)
      : latest
    // 子代理的历史必须用「父地址 + 子地址」读：DSH 对 origin=subagent 的会话
    // 直接给普通 session 地址会拒绝（subagent Sessions require their durable parent address）。
    const address = parentId === undefined || parentId === ''
      ? { kind: 'session', sessionId }
      : { kind: 'subagent', parentSessionId: parentId, childSessionId: sessionId, mode: mode === '' || mode === undefined ? 'continuable' : mode }
    const page = await this.link.rpc('session/page', {
      request: { address, throughSeq: from, maxMessages: MAX_MESSAGES },
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
    // 乐观运行：发出消息后的窗口内直接当作运行中，避免界面显示「空闲」让人以为没发出去。
    const optimisticUntil = this.optimisticRunning.get(sessionId) ?? 0
    if (optimisticUntil !== 0 && Date.now() > optimisticUntil) this.optimisticRunning.delete(sessionId)
    const effectiveRunning = running || Date.now() < optimisticUntil

    const result = {
      ok: true,
      sessionId,
      title: typeof projection?.values?.title === 'string' ? projection.values.title : '',
      running: effectiveRunning,
      hasMore: page?.hasMore === true,
      asOfSeq: latest,
      fromSeq: from,
      // beforeSeq 是这一批最早的 seq，手表用它继续往前翻。
      beforeSeq: earliestSeqOf(page?.records),
      messages: normalizeMessages(page?.records),
      steps: normalizeSteps(page?.records),
      usage: normalizeUsage(projection?.values),
      // 计划与任务：待办清单、计划模式开关、团队任务板、长目标。
      plan: normalizePlan(projection?.values),
      // 排队中的消息：运行中又发的那几条，手机/手表用 session/updateQueue 操作。
      queue: normalizeQueue(projection?.values),
      ...(question === undefined ? {} : { question }),
    }
    this.rememberSessionPage(cacheKey, result)
    return result
  }

  /**
   * 写入会话内容缓存，并按总量淘汰。
   *
   * 单个会话裁剪后约 100KB，缓存太多会把中继的内存吃满（跑几天、看几十个会话
   * 是常态），因此保留最近 12 个会话的结果，超出的按时间淘汰最旧的。
   */
  rememberSessionPage(key, value) {
    const now = Date.now()
    this.sessionPages.set(key, { at: now, value })
    if (this.sessionPages.size <= MAX_SESSION_PAGES) return
    const entries = [...this.sessionPages.entries()].sort((a, b) => a[1].at - b[1].at)
    for (let index = 0; index < entries.length - MAX_SESSION_PAGES; index += 1) {
      this.sessionPages.delete(entries[index][0])
    }
  }

  /** 丢掉某个会话的所有缓存分页（内容已变时调用）。 */
  forgetSessionPage(sessionId) {
    for (const key of [...this.sessionPages.keys()]) {
      if (key.startsWith(`${sessionId}|`)) this.sessionPages.delete(key)
    }
  }

  async route(req, res, url) {
    const path = url.pathname

    // 控制面板：只在 loopback 上提供，方便用户在本机浏览器里看配对码与地址。
    if (path === '/' || path === '/index.html') {
      if (!this.isLoopback(req)) {
        json(res, 403, { ok: false, error: '控制面板只在本机可访问' })
        return
      }
      this.servePanel(res)
      return
    }

    if (path === '/local/state' && req.method === 'GET') {
      if (!this.isLoopback(req)) {
        json(res, 403, { ok: false, error: '控制面板只在本机可访问' })
        return
      }
      json(res, 200, {
        ok: true,
        pairingCode: this.pairingCode,
        lanAddresses: this.addresses().map((address) => `http://${address}:${String(this.port)}`),
        publicUrl: this.publicBaseUrl,
        pairedDevices: this.tokens.size,
      })
      return
    }

    // 无需鉴权：手表用它确认「这是不是 DSH 中继」。
    if (path === '/api/status' && req.method === 'GET') {
      json(res, 200, {
        service: SERVICE_TAG,
        name: 'DSH 手表中继',
        version: 2,
        needsPairing: true,
        uptimeMs: Date.now() - this.startedAt,
        ...(this.publicBaseUrl === undefined ? {} : { publicUrl: this.publicBaseUrl }),
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
      // 一次拉全量（含子代理、不截断）再按需要过滤：截断必须发生在过滤之后，
      // 否则更新时间很新的几百条子代理会把父会话挤出列表。
      const all = await this.sessionIndex()
      const visible = includeSubagents ? all : all.filter((item) => item.subagent !== true)
      const sessions = visible.slice(0, MAX_SESSIONS)
      attachSubagentCounts(all, sessions)
      json(res, 200, {
        ok: true,
        sessions,
        // 按项目折叠后的视图：手表列表首屏显示项目，点进去看会话。
        projects: groupByProject(sessions),
        pending: await this.pendingQuestions(sessions),
      })
      return
    }

    if (path === '/api/subagents' && req.method === 'GET') {
      const parentId = url.searchParams.get('id') ?? ''
      if (parentId === '') {
        json(res, 400, { ok: false, error: '缺少 id 参数' })
        return
      }
      // 子代理的信息全在 session/list 里，不必为每个子代理再拉一次投影。
      const all = await this.sessionIndex()
      json(res, 200, { ok: true, parentId, subagents: subagentsOf(all, parentId) })
      return
    }

    if (path === '/api/session' && req.method === 'GET') {
      const sessionId = url.searchParams.get('id') ?? ''
      if (sessionId === '') {
        json(res, 400, { ok: false, error: '缺少 id 参数' })
        return
      }
      // through 用于往前翻历史；省略即读最新一批。
      const throughRaw = url.searchParams.get('through') ?? ''
      const through = throughRaw === '' ? undefined : Number(throughRaw)
      // running 与父地址都从列表摘要读：projections 里没有 running，子代理的历史
      // 又必须靠父地址才能读到。走 sessionIndex 的短缓存——几百个会话时
      // 每次都拉全量列表会让打开会话明显卡住（实测 700+ 会话要十几秒）。
      const listed = await this.sessionIndex()
      const summary = listed.find((item) => item.id === sessionId)
      json(res, 200, await this.readSession(
        sessionId,
        summary?.running === true,
        through,
        summary?.parentId ?? '',
        summary?.subagentMode ?? '',
      ))
      return
    }

    if (path === '/api/models' && req.method === 'GET') {
      const catalog = normalizeModelCatalog(await this.link.rpc('session/modelCatalog', {}))
      json(res, 200, { ok: true, ...catalog })
      return
    }

    if (path === '/api/model' && req.method === 'POST') {
      const body = await readJsonBody(req)
      const sessionId = String(body.sessionId ?? '')
      const provider = String(body.provider ?? '')
      const model = String(body.model ?? '')
      if (sessionId === '' || provider === '' || model === '') {
        json(res, 400, { ok: false, error: '缺少 sessionId、provider 或 model' })
        return
      }
      // reasoningEffort 可选：不传时由 DSH 用该模型的默认档位。
      const request = { sessionId, provider, model }
      if (typeof body.effort === 'string' && body.effort !== '') request.reasoningEffort = body.effort
      const value = await this.link.rpc('session/selectModel', { request })
      const selected = value?.selected ?? {}
      json(res, 200, {
        ok: true,
        provider: String(selected.provider ?? provider),
        model: String(selected.model ?? model),
        effort: String(selected.reasoningEffort ?? ''),
      })
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
      // 刚发出的消息会改变 running 与 updatedAt，让列表下一次读取重新拉。
      this.invalidateSessionIndex()
      // 内容缓存也要清：否则用户发完消息，4 秒刷新拿到的还是旧内容（看不到自己那句话）。
      this.forgetSessionPage(sessionId)
      // 列表缓存要 6 秒才反映 running，这段时间内先按运行中处理。
      this.optimisticRunning.set(sessionId, Date.now() + OPTIMISTIC_RUNNING_MS)
      json(res, 200, { ok: true })
      return
    }

    if (path === '/api/cancel' && req.method === 'POST') {
      const body = await readJsonBody(req)
      // session/cancel 同样以 request 为参数名。
      const cancelId = String(body.sessionId ?? '')
      await this.link.rpc('session/cancel', { request: { sessionId: cancelId } })
      this.invalidateSessionIndex()
      this.forgetSessionPage(cancelId)
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
      this.invalidateSessionIndex()
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
          // 答完之后 question 字段要从会话内容里消失，缓存必须失效。
          this.forgetSessionPage(sessionId)
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
      this.forgetSessionPage(sessionId)
      json(res, 200, { ok: true, via: 'rpc', accepted: accepted === true })
      return
    }

    // ===== 以下是手机端用的接口 =====

    if (path === '/api/approvals' && req.method === 'GET') {
      // 审批是一次性 waterfall：DSH 只认 clientId+eventId，因此必须原样带出 eventId。
      const now = Date.now()
      const items = [...this.pendingApprovals.values()]
        .filter((record) => now - record.at < EVENT_WINDOW_MS)
        .map((record) => ({
          eventId: record.eventId,
          sessionId: record.sessionId,
          toolName: record.toolName,
          callId: record.callId,
          reason: record.reason,
          displayReason: record.displayReason,
          ageMs: now - record.at,
        }))
      json(res, 200, { ok: true, approvals: items })
      return
    }

    if (path === '/api/approval' && req.method === 'POST') {
      const body = await readJsonBody(req)
      const eventId = String(body.eventId ?? '')
      // DSH 的审批结果只有两个取值：allowed-once（允许这一次）与 rejected（拒绝）。
      const decision = body.decision === 'allowed-once' ? 'allowed-once' : 'rejected'
      const record = this.pendingApprovals.get(eventId)
      if (eventId === '' || record === undefined) {
        json(res, 409, { ok: false, error: '这个审批已失效，请在电脑端处理' })
        return
      }
      try {
        await this.link.resolveEvent(eventId, decision)
        this.pendingApprovals.delete(eventId)
        json(res, 200, { ok: true, decision })
      } catch (error) {
        // 回填失败通常是事件流断开重连换了 clientId：把记录清掉，避免手机端反复点一个死请求。
        this.pendingApprovals.delete(eventId)
        json(res, 409, { ok: false, error: `审批未能送达 DSH：${String(error.message)}` })
      }
      return
    }

    if (path === '/api/workspaces' && req.method === 'GET') {
      const listed = await this.sessionIndex()
      const sessions = listed.filter((item) => item.subagent !== true)
      const groups = new Map()
      for (const session of sessions) {
        const key = session.projectPath === '' ? '' : session.projectPath
        let group = groups.get(key)
        if (group === undefined) {
          group = {
            path: key,
            name: session.project === '' ? '未分组' : session.project,
            sessions: 0,
            running: 0,
            waiting: 0,
            updatedAt: 0,
          }
          groups.set(key, group)
        }
        group.sessions += 1
        if (session.running) group.running += 1
        if (session.waiting) group.waiting += 1
        if (session.updatedAt > group.updatedAt) group.updatedAt = session.updatedAt
      }
      const workspaces = [...groups.values()].sort((a, b) => b.updatedAt - a.updatedAt)
      json(res, 200, { ok: true, workspaces })
      return
    }

    if (path === '/api/files' && req.method === 'GET') {
      const sessionId = url.searchParams.get('id') ?? ''
      const relative = url.searchParams.get('path') ?? ''
      if (sessionId === '') {
        json(res, 400, { ok: false, error: '缺少 id 参数' })
        return
      }
      const listed = await this.sessionIndex()
      const session = listed.find((item) => item.id === sessionId)
      const root = session?.projectPath ?? ''
      if (root === '') {
        json(res, 409, { ok: false, error: '这个会话没有工作目录，无法浏览文件' })
        return
      }
      if (!withinRoot(root, relative)) {
        json(res, 403, { ok: false, error: '只能浏览当前会话的工作目录' })
        return
      }
      const target = resolve(root, relative)
      let entries
      try {
        entries = await readdir(target, { withFileTypes: true })
      } catch (error) {
        json(res, 404, { ok: false, error: `目录不可读：${String(error.message)}` })
        return
      }
      const rows = []
      for (const entry of entries) {
        if (rows.length >= MAX_DIR_ENTRIES) break
        // .git 内部结构对手机没有意义，且条目极多。
        if (entry.name === '.git') continue
        let size = 0
        let mtime = 0
        try {
          const info = await stat(join(target, entry.name))
          size = info.size
          mtime = info.mtimeMs
        } catch { /* 符号链接或权限问题：保留条目，只是没有大小 */ }
        rows.push({
          name: entry.name,
          type: entry.isDirectory() ? 'directory' : (entry.isFile() ? 'file' : 'other'),
          size,
          mtime,
          language: entry.isFile() ? languageOf(entry.name) : '',
        })
      }
      // 目录在前、同类按名字排，和电脑上的文件管理器一致。
      rows.sort((a, b) => {
        if (a.type !== b.type) return a.type === 'directory' ? -1 : 1
        return a.name.localeCompare(b.name, 'zh-Hans-CN')
      })
      json(res, 200, {
        ok: true,
        root,
        path: relative,
        parent: relative === '' ? '' : (relative.includes('/') ? relative.slice(0, relative.lastIndexOf('/')) : ''),
        entries: rows,
        truncated: entries.length > rows.length,
      })
      return
    }

    if (path === '/api/file' && req.method === 'GET') {
      const sessionId = url.searchParams.get('id') ?? ''
      const relative = url.searchParams.get('path') ?? ''
      if (sessionId === '' || relative === '') {
        json(res, 400, { ok: false, error: '缺少 id 或 path 参数' })
        return
      }
      const listed = await this.sessionIndex()
      const session = listed.find((item) => item.id === sessionId)
      const root = session?.projectPath ?? ''
      if (root === '' || !withinRoot(root, relative)) {
        json(res, 403, { ok: false, error: '只能读取当前会话工作目录里的文件' })
        return
      }
      const target = resolve(root, relative)
      let info
      try {
        info = await stat(target)
      } catch (error) {
        json(res, 404, { ok: false, error: `文件不存在：${String(error.message)}` })
        return
      }
      if (!info.isFile()) {
        json(res, 400, { ok: false, error: '这不是文件' })
        return
      }
      if (isImageName(relative)) {
        if (info.size > 4 * 1024 * 1024) {
          json(res, 413, { ok: false, error: '图片超过 4MB，手机端不加载' })
          return
        }
        const bytes = await readFile(target)
        json(res, 200, {
          ok: true,
          name: basename(relative),
          path: relative,
          kind: 'image',
          size: info.size,
          mtime: info.mtimeMs,
          base64: bytes.toString('base64'),
        })
        return
      }
      const bytes = await readFile(target)
      const slice = bytes.subarray(0, MAX_FILE_BYTES)
      // 含 NUL 字节的按二进制处理，避免把 exe 当文本塞给手机端。
      const binary = slice.includes(0)
      json(res, 200, {
        ok: true,
        name: basename(relative),
        path: relative,
        kind: binary ? 'binary' : 'text',
        language: languageOf(relative),
        size: info.size,
        mtime: info.mtimeMs,
        truncated: bytes.byteLength > MAX_FILE_BYTES,
        text: binary ? '' : slice.toString('utf8'),
      })
      return
    }

    if (path === '/api/upload' && req.method === 'POST') {
      // 往当前会话工作目录里写一个文件。
      // 安全边界与只读浏览一致：路径必须落在该会话的 cwd 之内，且只接受单个文件名，
      // 不允许 ../、绝对路径或子目录——否则等于把整台电脑的写入权限交出去。
      const body = await readJsonBody(req)
      const sessionId = String(body.id ?? '')
      const name = String(body.name ?? '').trim()
      const base64 = String(body.base64 ?? '')
      if (sessionId === '' || name === '' || base64 === '') {
        json(res, 400, { ok: false, error: '缺少 id、name 或 base64' })
        return
      }
      if (!isSafeFileName(name)) {
        json(res, 400, { ok: false, error: '文件名不合法：只能是单个文件名，不能含路径分隔符' })
        return
      }
      const listed = await this.sessionIndex()
      const session = listed.find((item) => item.id === sessionId)
      const root = session?.projectPath ?? ''
      if (root === '') {
        json(res, 409, { ok: false, error: '这个会话没有工作目录，无法上传' })
        return
      }
      let bytes
      try {
        bytes = Buffer.from(base64, 'base64')
      } catch {
        json(res, 400, { ok: false, error: 'base64 内容无法解码' })
        return
      }
      if (bytes.byteLength > MAX_UPLOAD_BYTES) {
        json(res, 413, { ok: false, error: `文件超过 ${Math.round(MAX_UPLOAD_BYTES / 1024 / 1024)}MB 上限` })
        return
      }
      try {
        // 不覆盖已有文件：同名时依次试 name (1).ext 等，最多 100 个候选。
        const target = await pickAvailableName(root, name)
        await writeFile(target.fullPath, bytes)
        json(res, 200, {
          ok: true,
          name: target.name,
          path: target.relative,
          bytes: bytes.byteLength,
        })
      } catch (error) {
        json(res, 500, { ok: false, error: `写入失败：${String(error.message)}` })
      }
      return
    }

    if (path === '/api/terminals' && req.method === 'GET') {
      const sessionId = url.searchParams.get('id') ?? ''
      if (sessionId === '') {
        json(res, 400, { ok: false, error: '缺少 id 参数' })
        return
      }
      const items = await this.terminals.list(sessionId)
      json(res, 200, { ok: true, terminals: items })
      return
    }

    if (path === '/api/terminal' && req.method === 'POST') {
      const body = await readJsonBody(req)
      const action = String(body.action ?? '')
      if (action === 'create') {
        const sessionId = String(body.id ?? '')
        if (sessionId === '') {
          json(res, 400, { ok: false, error: '缺少 id（会话）参数' })
          return
        }
        try {
          const terminal = await this.terminals.create({
            sessionId,
            cols: body.cols,
            rows: body.rows,
            shellPath: body.shellPath,
          })
          json(res, 200, { ok: true, terminal })
        } catch (error) {
          json(res, 409, { ok: false, error: `创建终端失败：${String(error.message)}` })
        }
        return
      }
      if (action === 'attach') {
        const sessionId = String(body.id ?? '')
        const terminalId = String(body.terminalId ?? '')
        if (sessionId === '' || terminalId === '') {
          json(res, 400, { ok: false, error: '缺少 id 或 terminalId' })
          return
        }
        json(res, 200, await this.terminals.attachExisting(sessionId, terminalId))
        return
      }
      if (action === 'input') {
        json(res, 200, this.terminals.input(String(body.terminalId ?? ''), body.data ?? ''))
        return
      }
      if (action === 'resize') {
        json(res, 200, this.terminals.resize(String(body.terminalId ?? ''), body.cols, body.rows))
        return
      }
      if (action === 'close') {
        json(res, 200, await this.terminals.close(String(body.terminalId ?? '')))
        return
      }
      json(res, 400, { ok: false, error: '未知的 action' })
      return
    }

    if (path === '/api/terminal/output' && req.method === 'GET') {
      const terminalId = url.searchParams.get('id') ?? ''
      const since = Number(url.searchParams.get('since') ?? '0')
      if (terminalId === '') {
        json(res, 400, { ok: false, error: '缺少 id 参数' })
        return
      }
      const result = this.terminals.output(terminalId, Number.isFinite(since) ? since : 0)
      json(res, result.ok === false ? 404 : 200, result)
      return
    }

    if (path === '/api/session/rename' && req.method === 'POST') {
      const body = await readJsonBody(req)
      const sessionId = String(body.id ?? '')
      const title = String(body.title ?? '').trim()
      if (sessionId === '' || title === '') {
        json(res, 400, { ok: false, error: '缺少 id 或 title' })
        return
      }
      try {
        const value = await this.link.rpc('session/rename', { request: { sessionId, title } })
        this.invalidateSessionIndex()
        json(res, 200, { ok: true, title: String(value?.title ?? title) })
      } catch (error) {
        json(res, 409, { ok: false, error: `重命名失败：${String(error.message)}` })
      }
      return
    }

    if (path === '/api/session/queue' && req.method === 'POST') {
      // 运行中会话的排队消息：对某一条做编辑 / 删除 / 立即发送。
      // DSH 的 session/updateQueue 是「按 itemId 操作」，不是整体替换队列。
      const body = await readJsonBody(req)
      const sessionId = String(body.id ?? '')
      const itemId = String(body.itemId ?? '')
      if (sessionId === '') {
        json(res, 400, { ok: false, error: '缺少 id' })
        return
      }
      if (itemId === '') {
        json(res, 400, { ok: false, error: '缺少 itemId' })
        return
      }
      // action 只允许协议定义的三种；其他值一律按编辑处理会悄悄改坏队列。
      const kind = String(body.kind ?? 'remove')
      let action
      if (kind === 'remove') action = { kind: 'remove' }
      else if (kind === 'steer') action = { kind: 'steer' }
      else if (kind === 'edit') {
        action = { kind: 'edit', content: [{ type: 'text', text: String(body.text ?? '') }] }
      } else {
        json(res, 400, { ok: false, error: `未知的队列操作：${kind}` })
        return
      }
      try {
        const value = await this.link.rpc('session/updateQueue', {
          request: { sessionId, itemId, action },
        })
        this.forgetSessionPage(sessionId)
        json(res, 200, { ok: true, value })
      } catch (error) {
        json(res, 409, { ok: false, error: `更新队列失败：${String(error.message)}` })
      }
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
   * 周期性 UDP 广播，让手机/手表无需手输 IP 即可发现中继。
   * 报文是单行 JSON，设备只按 `service` 字段识别。
   *
   * **必须按每个网段发定向广播**，不能只发 255.255.255.255：
   * 只发全局广播时由系统选路由，装了 VPN 或虚拟网卡的机器会把包从那张网卡送出去，
   * 手机所在的局域网一个包都收不到（实测这台机器上默认走了 198.18.0.1 的虚拟网卡）。
   * 因此这里对每个非回环 IPv4 网卡各算一次定向广播地址。
   */
  startBeacon() {
    const socket = createSocket({ type: 'udp4', reuseAddr: true })
    socket.on('error', (error) => { this.log(`广播失败：${String(error.message)}`) })
    socket.bind(() => {
      socket.setBroadcast(true)
      const payload = Buffer.from(JSON.stringify({ service: SERVICE_TAG, port: this.port, name: 'DSH 中继' }))
      const send = () => {
        const targets = new Set(['255.255.255.255'])
        for (const target of directedBroadcasts()) targets.add(target)
        for (const target of targets) {
          try {
            socket.send(payload, DISCOVERY_PORT, target)
          } catch { /* 单个网卡发不出去不影响其它网卡 */ }
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
