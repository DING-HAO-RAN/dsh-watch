/**
 * DSH 协议层：把桌面版 DSH 的浏览器会话鉴权、Typert RPC 与事件流封装成类。
 *
 * DSH 桌面版的 /api 只认自己签发的 `dsh-auth-<hash(authority)>` 会话 cookie：
 * 它是 HMAC-SHA256 签名的自包含票据，签名密钥持久化在 $DSH_HOME/.credentials.yaml 的
 * `client-connection/browser-session` 记录里。本模块按同一算法为 127.0.0.1:<port>
 * 签一张票，因此中继无需改动 DSH 的绑定或配置即可作为合法客户端调用全部 API。
 *
 * 两个必须遵守的协议约束（均为实测）：
 * - cookie 的有效期不能超过 DSH 的 cookieMaxAgeDays（默认 30 天），否则 isAuthenticated 拒绝；
 * - 一元 RPC 的路径是 /api/<namespace>/<method>（斜杠分隔），信封为
 *   {type:"client-request", rpcId, method, payload:{args}}，且 args 的顶层字段必须与
 *   descriptor 精确匹配。
 */
import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import { createHash, createHmac, randomUUID } from 'node:crypto'

const SECRET_BYTES = 32
const COOKIE_PREFIX = 'dsh-auth-'
// DSH 默认 cookieMaxAgeDays = 30；留一天余量避免边界比较失败。
const COOKIE_TTL_MS = 29 * 24 * 60 * 60 * 1000
const RPC_TIMEOUT_MS = 30_000

const b64url = (value) => Buffer.from(value).toString('base64').replaceAll('+', '-').replaceAll('/', '_').replace(/=+$/u, '')

function b64urlDecode(value) {
  const padding = '='.repeat((4 - (value.length % 4)) % 4)
  return Buffer.from(value.replaceAll('-', '+').replaceAll('_', '/') + padding, 'base64')
}

/** DSH 不上报的运行时错误，携带稳定 code 供上层区分处理。 */
export class DshError extends Error {
  constructor(code, message) {
    super(message)
    this.name = 'DshError'
    this.code = code
  }
}

export class DshLink {
  /**
   * @param {object} options
   * @param {string} [options.dshHome] DSH 主目录（含 .credentials.yaml），默认 ~/.dsh
   * @param {number} [options.port] DSH web 端口，默认 19387
   * @param {(message: string) => void} [options.log] 日志回调
   */
  constructor(options = {}) {
    this.dshHome = options.dshHome ?? path.join(os.homedir(), '.dsh')
    this.port = options.port ?? 19387
    this.log = options.log ?? (() => {})
    this.authority = `127.0.0.1:${String(this.port)}`
    this.base = `http://${this.authority}`
    this.secret = undefined
    this.cookie = undefined
    this.socket = undefined
    this.clientId = undefined
    this.streamId = 'ev'
    this.handlers = {}
    this.reconnectDelayMs = 1_000
    this.closed = false
  }

  /** 读取并缓存 DSH 的 cookie 签名密钥；文件被替换时会重新读取。 */
  loadSecret() {
    const file = path.join(this.dshHome, '.credentials.yaml')
    let text
    try {
      text = fs.readFileSync(file, 'utf8')
    } catch (error) {
      throw new DshError('credentials-missing', `无法读取 ${file}：${String(error.message)}`)
    }
    const marker = 'client-connection/browser-session:'
    const at = text.indexOf(marker)
    if (at === -1) throw new DshError('credentials-invalid', `${file} 里没有 browser-session 记录`)
    const match = /secret:\s*([A-Za-z0-9_-]+)/.exec(text.slice(at))
    if (match === null) throw new DshError('credentials-invalid', `${file} 的 browser-session 记录缺少 secret`)
    const secret = b64urlDecode(match[1])
    if (secret.byteLength !== SECRET_BYTES) {
      throw new DshError('credentials-invalid', `browser-session 密钥长度应为 ${String(SECRET_BYTES)} 字节，实际 ${String(secret.byteLength)}`)
    }
    this.secret = secret
    this.cookie = undefined
    return secret
  }

  /** 组装当前 authority 对应的会话 cookie 头；密钥变化后自动重签。 */
  cookieHeader() {
    if (this.secret === undefined) this.loadSecret()
    if (this.cookie !== undefined) return this.cookie
    const issuedAt = Date.now()
    const payload = { version: 1, authority: this.authority, issuedAt, expiresAt: issuedAt + COOKIE_TTL_MS }
    const body = b64url(Buffer.from(JSON.stringify(payload), 'utf8'))
    const signature = b64url(createHmac('sha256', this.secret).update(body).digest())
    const name = `${COOKIE_PREFIX}${b64url(createHash('sha256').update(this.authority).digest())}`
    this.cookie = `${name}=v1.${body}.${signature}`
    return this.cookie
  }

  /**
   * 调用一个一元 RPC 端点。
   * @param {string} method 形如 `session/list`（namespace/method，斜杠分隔）
   * @param {object} args 顶层参数对象，字段须与 descriptor 精确匹配
   * @returns {Promise<unknown>} 成功的业务返回值
   * @throws {DshError} 传输失败或 DSH 返回错误信封时
   */
  async rpc(method, args) {
    const rpcId = randomUUID()
    const envelope = { type: 'client-request', rpcId, method, payload: { args } }
    const controller = new AbortController()
    const timer = setTimeout(() => { controller.abort() }, RPC_TIMEOUT_MS)
    let response
    try {
      response = await fetch(`${this.base}/api/${method}`, {
        method: 'POST',
        headers: { 'content-type': 'application/json', cookie: this.cookieHeader() },
        body: JSON.stringify(envelope),
        signal: controller.signal,
      })
    } catch (error) {
      throw new DshError('transport', `${method} 请求失败：${String(error.message)}`)
    } finally {
      clearTimeout(timer)
    }
    if (response.status === 401) {
      // 密钥可能在 DSH 重启后被替换：重读一次并让调用方重试。
      this.loadSecret()
      throw new DshError('unauthorized', `${method} 被拒绝（401），已重新载入签名密钥，请重试`)
    }
    if (response.status !== 200) {
      throw new DshError('transport', `${method} 返回 HTTP ${String(response.status)}`)
    }
    let envelopeOut
    try {
      envelopeOut = await response.json()
    } catch {
      throw new DshError('protocol', `${method} 的响应不是 JSON`)
    }
    const result = envelopeOut?.result
    if (result === undefined || result === null) throw new DshError('protocol', `${method} 的响应缺少 result`)
    if (result.ok !== true) {
      const error = result.error ?? {}
      throw new DshError(String(error.code ?? 'remote-error'), `${method}: ${String(error.message ?? '未知错误')}`)
    }
    return result.value
  }

  /** 与 rpc 相同，但失败时返回 undefined 而不抛出，用于非关键路径。 */
  async rpcSafe(method, args) {
    try {
      return await this.rpc(method, args)
    } catch (error) {
      this.log(`${method} 失败（已忽略）：${String(error.message)}`)
      return undefined
    }
  }

  /**
   * 打开到 DSH 的 $events 事件流并保持长连，断线自动重连。
   *
   * $events 是回答问题（waterfall 帧）、观察会话状态的总入口。应答 waterfall 必须
   * 用同一条流的 clientId，因此这里把 clientId 与帧一起交给上层。
   *
   * 关键机制（DSH 的 openRemoteEvents 实现）：新客户端连接时，DSH 会把**所有仍待处理
   * 的 waterfall 事件重放给它**。因此重连本身就是一次「补齐遗漏提问」的机会——这正是
   * 中继在启动后才出现的提问也能拿到的原因。
   *
   * @param {object} handlers
   * @param {(clientId: string) => void} [handlers.ready] 收到 ready 帧
   * @param {(frame: object) => void} [handlers.item] 收到任意业务帧（ready 除外）
   * @param {(up: boolean) => void} [handlers.link] 连接状态变化
   */
  openEvents(handlers = {}) {
    this.handlers = handlers
    this.closed = false
    this.connectEvents()
    return () => {
      this.closed = true
      this.socket?.close()
      this.socket = undefined
    }
  }

  /**
   * 主动重连一次事件流，用于补齐连接期间可能错过的提问。
   *
   * 典型场景：中继先启动，之后用户在电脑上发起提问；此时中继那条长连是「旧客户端」，
   * 若它已断开或错过推送，重连即可让 DSH 重放待处理事件。
   */
  refreshEvents() {
    const socket = this.socket
    if (socket === undefined) {
      this.connectEvents()
      return
    }
    // 关闭会触发 close 处理器里的自动重连，不必在这里重复建立。
    try { socket.close() } catch { this.connectEvents() }
  }

  connectEvents() {
    if (this.closed) return
    let socket
    try {
      socket = new WebSocket(`ws://${this.authority}/api/remote.mux`, { headers: { cookie: this.cookieHeader() } })
    } catch (error) {
      this.log(`事件流建立失败：${String(error.message)}`)
      this.scheduleReconnect()
      return
    }
    this.socket = socket
    socket.addEventListener('open', () => {
      this.reconnectDelayMs = 1_000
      this.handlers.link?.(true)
      socket.send(JSON.stringify({ type: 'open', streamId: this.streamId, endpoint: '$events', payload: { args: {} } }))
    })
    socket.addEventListener('message', (event) => { this.onEventsFrame(String(event.data)) })
    socket.addEventListener('error', () => { /* close 事件会跟随，这里不重复处理 */ })
    socket.addEventListener('close', () => {
      this.clientId = undefined
      this.handlers.link?.(false)
      this.scheduleReconnect()
    })
  }

  scheduleReconnect() {
    if (this.closed) return
    const delay = this.reconnectDelayMs
    this.reconnectDelayMs = Math.min(this.reconnectDelayMs * 2, 15_000)
    setTimeout(() => { this.connectEvents() }, delay)
  }

  onEventsFrame(text) {
    let frame
    try {
      frame = JSON.parse(text)
    } catch {
      return
    }
    if (frame?.type !== 'item') return
    const value = frame.value
    if (value === undefined || value === null) return
    if (value.type === 'ready') {
      this.clientId = String(value.clientId ?? '')
      this.log(`事件流就绪，clientId=${this.clientId}`)
      this.handlers.ready?.(this.clientId)
      return
    }
    this.handlers.item?.(value)
  }

  /**
   * 回填一个 waterfall 事件的结果（用于应答仍在等待中的提问）。
   * @param {string} eventId waterfall 帧的 eventId
   * @param {object} value 业务返回值（提问场景为 {answers:[...]}）
   * @returns {Promise<boolean>} 是否被接受
   */
  async resolveEvent(eventId, value) {
    if (this.clientId === undefined) throw new DshError('events-not-ready', '事件流尚未就绪，无法回填结果')
    await this.rpc('$events/result', {
      clientId: this.clientId,
      eventId,
      outcome: { kind: 'result', value },
    })
    return true
  }
}
