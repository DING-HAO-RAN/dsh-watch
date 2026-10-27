/**
 * 终端中心：把 DSH 的 terminal/attach 流桥接成手机能轮询的增量缓冲。
 *
 * 为什么不用 WebSocket 直接连手机：中继本身是一个纯 node:http 服务，没有 WS 服务端；
 * 为终端再写一套 RFC6455 握手与分帧既增加出错面，也不比 250ms 轮询快多少。
 * 因此这里把「流」拆成两个 HTTP 动作：POST 输入、GET 增量输出。
 *
 * 三个必须守住的点（都对应真实故障）：
 * - **每条终端一条独立的逻辑流**：与 $events 共用一条 remote.mux 连接，streamId 必须唯一，
 *   否则终端的输出帧会被事件处理器当成提问帧。
 * - **上行 sequence 必须单调递增**：DSH 按 sequence 排序并回 ack，重复或倒退的序号会被丢弃。
 * - **snapshot 帧代表整屏重绘**：收到它必须让手机端清屏，否则历史输出会叠加成乱码。
 */

/** 每个终端保留的最大帧数；超出后丢最旧的，手机端会看到 truncated 标记。 */
const MAX_FRAMES = 1_500
/** 每个终端保留的最大字符数，防止 cat 一个大文件把内存吃满。 */
const MAX_CHARS = 400_000
/** 单次轮询最多返回的帧数。 */
const MAX_BATCH = 240

export class TerminalHub {
  /**
   * @param {object} options
   * @param {import('./dsh.mjs').DshLink} options.link 已连上 DSH 的连接
   * @param {(message: string) => void} [options.log]
   */
  constructor(options) {
    this.link = options.link
    this.log = options.log ?? (() => {})
    /** terminalId → 记录 */
    this.terminals = new Map()
  }

  /** 中继侧的一帧：seq 是本地的单调序号，与 DSH 的 sequence 解耦。 */
  recordOf(id) {
    return this.terminals.get(id)
  }

  /**
   * 列出某个会话可见的终端。会话 id 必填：terminal/list 按会话归属过滤。
   * @param {string} sessionId
   */
  async list(sessionId) {
    const rows = await this.link.rpc('terminal/list', { sessionId })
    const items = Array.isArray(rows) ? rows : []
    return items.map((item) => ({
      id: String(item?.id ?? ''),
      title: String(item?.title ?? ''),
      cwd: String(item?.cwd ?? ''),
      state: String(item?.state ?? ''),
      cols: Number(item?.cols ?? 0),
      rows: Number(item?.rows ?? 0),
      shell: String(item?.shell?.name ?? ''),
      // 中继是否已经 attach 过它（决定手机端能否立刻拿到输出）
      attached: this.terminals.has(String(item?.id ?? '')),
    }))
  }

  /**
   * 创建（或幂等复用）一个终端并立即 attach。
   * @param {object} input
   * @param {string} input.sessionId 归属会话，作为 agentId 传给 DSH
   * @param {string} [input.id] 终端 id；省略则随机生成
   * @param {number} [input.cols] 列数
   * @param {number} [input.rows] 行数
   * @param {string} [input.shellPath] 指定的 shell 路径
   */
  async create(input) {
    const sessionId = String(input.sessionId ?? '')
    if (sessionId === '') throw new Error('缺少 sessionId')
    const id = typeof input.id === 'string' && input.id !== '' ? input.id : `t-${randomId()}`
    const cols = clamp(input.cols ?? 80, 20, 400)
    const rows = clamp(input.rows ?? 24, 6, 200)
    const request = { id, cols, rows }
    if (typeof input.shellPath === 'string' && input.shellPath !== '') request.shellPath = input.shellPath
    const info = await this.link.rpc('terminal/create', { agentId: sessionId, request })
    const record = this.ensure(sessionId, id)
    record.info = info
    record.cols = Number(info?.cols ?? cols)
    record.rows = Number(info?.rows ?? rows)
    this.attach(sessionId, id)
    return this.describe(id)
  }

  /** 已存在的终端：直接 attach 并返回当前缓冲。 */
  async attachExisting(sessionId, id) {
    const record = this.ensure(sessionId, id)
    if (record.streamId === undefined) this.attach(sessionId, id)
    return this.describe(id)
  }

  ensure(sessionId, id) {
    let record = this.terminals.get(id)
    if (record === undefined) {
      record = {
        id,
        sessionId,
        streamId: undefined,
        frames: [],
        nextSeq: 1,
        firstSeq: 1,
        chars: 0,
        truncated: false,
        writable: false,
        info: undefined,
        inputSeq: 0,
        ended: false,
      }
      this.terminals.set(id, record)
    }
    if (record.sessionId !== sessionId && sessionId !== '') record.sessionId = sessionId
    return record
  }

  /**
   * 在 remote.mux 上开一条 attach 流。
   * streamId 用 `term:<id>` 前缀，避免与 $events 的 'ev' 以及别的流重名。
   */
  attach(sessionId, id) {
    const record = this.ensure(sessionId, id)
    if (record.streamId !== undefined) return
    const streamId = `term:${id}`
    record.streamId = streamId
    record.ended = false
    this.link.openStream(streamId, 'terminal/attach', { agentId: sessionId, id }, {
      onItem: (value) => { this.onFrame(record, value) },
      onEnd: (frame) => {
        record.ended = true
        record.streamId = undefined
        this.log(`终端 ${id} 的流已结束${frame?.error === undefined ? '' : `：${String(frame.error.message ?? '')}`}`)
      },
    })
  }

  /** 处理 DSH 下发的一帧：snapshot 重置缓冲，output 追加，state 更新信息。 */
  onFrame(record, value) {
    if (value === undefined || value === null) return
    const type = String(value.type ?? '')
    if (type === 'snapshot') {
      record.frames = []
      record.chars = 0
      record.truncated = false
      record.writable = value.writable === true
      record.info = value.info ?? record.info
      record.cols = Number(value.info?.cols ?? record.cols ?? 0)
      record.rows = Number(value.info?.rows ?? record.rows ?? 0)
      const screen = String(value.screen ?? '')
      if (screen !== '') this.push(record, 'reset', screen)
      return
    }
    if (type === 'output') {
      this.push(record, 'output', String(value.data ?? ''))
      return
    }
    if (type === 'state') {
      record.writable = value.writable === true
      record.info = value.info ?? record.info
      record.cols = Number(value.info?.cols ?? record.cols ?? 0)
      record.rows = Number(value.info?.rows ?? record.rows ?? 0)
      this.push(record, 'state', '')
      return
    }
    // ack 只表示 DSH 收到了第 sequence 条输入，手机端不需要它。
  }

  push(record, kind, text) {
    const seq = record.nextSeq
    record.nextSeq += 1
    record.frames.push({ seq, kind, text })
    record.chars += text.length
    while (record.frames.length > MAX_FRAMES || record.chars > MAX_CHARS) {
      const dropped = record.frames.shift()
      if (dropped === undefined) break
      record.chars -= dropped.text.length
      record.firstSeq = dropped.seq + 1
      record.truncated = true
    }
  }

  /** 手机的增量读取：返回 since 之后的帧。 */
  output(id, since, waitMs = 0) {
    const record = this.terminals.get(id)
    if (record === undefined) return { ok: false, error: '终端不存在或尚未附加' }
    const from = Number.isFinite(since) ? since : 0
    const frames = []
    for (const frame of record.frames) {
      if (frame.seq <= from) continue
      frames.push(frame)
      if (frames.length >= MAX_BATCH) break
    }
    return {
      ok: true,
      id,
      frames,
      nextSeq: frames.length > 0 ? frames[frames.length - 1].seq : from,
      // 手机端据此知道要不要先清屏再追加
      truncated: record.truncated && from < record.firstSeq,
      writable: record.writable,
      ended: record.ended,
      cols: record.cols ?? 0,
      rows: record.rows ?? 0,
      state: String(record.info?.state ?? ''),
      title: String(record.info?.title ?? ''),
      cwd: String(record.info?.cwd ?? ''),
    }
  }

  /** 写入一段键盘输入。返回是否被 DSH 接受。 */
  input(id, data) {
    const record = this.terminals.get(id)
    if (record === undefined) return { ok: false, error: '终端不存在或尚未附加' }
    if (record.streamId === undefined) return { ok: false, error: '终端未连接' }
    record.inputSeq += 1
    const accepted = this.link.sendStream(record.streamId, {
      type: 'write',
      sequence: record.inputSeq,
      data: String(data ?? ''),
    })
    return accepted ? { ok: true } : { ok: false, error: '连接未就绪' }
  }

  /** 改尺寸。手机旋转或键盘弹出时用。 */
  resize(id, cols, rows) {
    const record = this.terminals.get(id)
    if (record === undefined) return { ok: false, error: '终端不存在或尚未附加' }
    if (record.streamId === undefined) return { ok: false, error: '终端未连接' }
    record.inputSeq += 1
    const accepted = this.link.sendStream(record.streamId, {
      type: 'resize',
      sequence: record.inputSeq,
      cols: clamp(cols, 20, 400),
      rows: clamp(rows, 6, 200),
    })
    return accepted ? { ok: true } : { ok: false, error: '连接未就绪' }
  }

  /** 关闭终端：先断流，再让 DSH 结束进程。 */
  async close(id) {
    const record = this.terminals.get(id)
    if (record === undefined) return { ok: true }
    if (record.streamId !== undefined) this.link.closeStream(record.streamId)
    this.terminals.delete(id)
    try {
      await this.link.rpc('terminal/close', { agentId: record.sessionId, id })
    } catch (error) {
      this.log(`关闭终端 ${id} 失败（已忽略）：${String(error.message)}`)
    }
    return { ok: true }
  }

  /** 中继退出或 DSH 重连后，把所有终端流标记为待重开。 */
  detachAll() {
    for (const record of this.terminals.values()) record.streamId = undefined
  }

  describe(id) {
    const record = this.terminals.get(id)
    if (record === undefined) return { ok: false, error: '终端不存在' }
    return {
      ok: true,
      id,
      title: String(record.info?.title ?? ''),
      cwd: String(record.info?.cwd ?? ''),
      state: String(record.info?.state ?? ''),
      cols: record.cols ?? 0,
      rows: record.rows ?? 0,
      writable: record.writable,
      nextSeq: record.nextSeq - 1,
    }
  }
}

function clamp(value, min, max) {
  const number = Number(value)
  if (!Number.isFinite(number)) return min
  return Math.max(min, Math.min(max, Math.round(number)))
}

function randomId() {
  return Math.random().toString(36).slice(2, 10)
}
