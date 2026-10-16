/**
 * 公网中转：可选地为本机中继开一条临时公网地址。
 *
 * 实现方式是与 cloudflared（Cloudflare 的隧道客户端）配合：
 * 它把 https://<随机名>.trycloudflare.com 的流量转发到本机端口，
 * 不需要域名、不需要在路由器上开放端口，退出即失效。
 *
 * 这里只做「拉起进程 + 从它输出里解析公网地址 + 退出时收掉」，
 * 不引入任何 npm 依赖；cloudflared 可执行文件由调用方指定（见 bin/ 目录）。
 */
import { spawn } from 'node:child_process'
import { existsSync } from 'node:fs'

/** 公网地址的匹配模式：trycloudflare 固定域名。 */
const PUBLIC_URL_PATTERN = /https:\/\/[a-z0-9-]+\.trycloudflare\.com/

export class PublicTunnel {
  /**
   * @param {object} options
   * @param {string} options.binary cloudflared 可执行文件路径
   * @param {number} options.port 本机中继端口
   * @param {(message: string) => void} [options.log]
   */
  constructor(options) {
    this.binary = options.binary
    this.port = options.port
    this.log = options.log ?? (() => {})
    this.child = undefined
    this.url = undefined
  }

  /** 可执行文件是否就绪。 */
  available() {
    return typeof this.binary === 'string' && this.binary !== '' && existsSync(this.binary)
  }

  /**
   * 启动隧道并等待公网地址出现。
   * @param {number} [timeoutMs] 等待上限
   * @returns {Promise<string>} 公网地址
   */
  async start(timeoutMs = 60_000) {
    if (!this.available()) throw new Error(`找不到 cloudflared：${String(this.binary)}`)
    if (this.child !== undefined) throw new Error('隧道已经在运行')

    this.log('正在开启公网中转…')
    const child = spawn(this.binary, [
      'tunnel',
      '--url', `http://127.0.0.1:${String(this.port)}`,
      '--no-autoupdate',
    ], { stdio: ['ignore', 'pipe', 'pipe'] })
    this.child = child

    const url = await new Promise((resolve, reject) => {
      const timer = setTimeout(() => {
        reject(new Error(`等待公网地址超时（${String(Math.round(timeoutMs / 1000))} 秒）`))
      }, timeoutMs)
      const onData = (chunk) => {
        const text = chunk.toString()
        const found = PUBLIC_URL_PATTERN.exec(text)
        if (found !== null) {
          clearTimeout(timer)
          resolve(found[0])
        }
      }
      child.stdout.on('data', onData)
      // cloudflared 把地址写在 stderr 上，两路都要看。
      child.stderr.on('data', onData)
      child.on('error', (error) => {
        clearTimeout(timer)
        reject(new Error(`cloudflared 启动失败：${String(error.message)}`))
      })
      child.on('exit', (code) => {
        clearTimeout(timer)
        reject(new Error(`cloudflared 提前退出（代码 ${String(code)}）`))
      })
    })

    this.url = url
    this.log(`公网地址已就绪：${url}`)
    // 地址拿到后不再需要持续读输出，避免占内存。
    child.stdout.removeAllListeners('data')
    child.stderr.removeAllListeners('data')
    return url
  }

  /** 收掉隧道进程。 */
  stop() {
    if (this.child === undefined) return
    try { this.child.kill() } catch { /* 进程可能已退出 */ }
    this.child = undefined
    this.url = undefined
  }
}
