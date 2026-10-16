/**
 * 中继服务入口：在 PC 上常驻运行，把本机 DSH 桌面版桥接给局域网内的手表。
 *
 * 用法：
 *   node relay/src/server.mjs [--port 19390] [--dsh-port 19387] [--dsh-home <目录>]
 *
 * 之所以需要中继而不是让手表直连 DSH：DSH 的 /api 只接受绑定到自身 authority 的
 * 签名 cookie，且响应里包含大量手表用不到的流式分片；中继在保持 DSH 配置不变的前提
 * 下完成鉴权、裁剪与配对保护。
 */
import { DshLink } from './dsh.mjs'
import { RelayServer } from './relay.mjs'
import { homedir } from 'node:os'
import { join } from 'node:path'

function parseArgs(argv) {
  const options = {}
  for (let i = 0; i < argv.length; i += 1) {
    const token = argv[i]
    if (!token.startsWith('--')) continue
    const key = token.slice(2)
    const value = argv[i + 1]
    if (value === undefined || value.startsWith('--')) {
      options[key] = 'true'
      continue
    }
    options[key] = value
    i += 1
  }
  return options
}

const args = parseArgs(process.argv.slice(2))
const port = Number(args.port ?? '19390')
const dshPort = Number(args['dsh-port'] ?? '19387')
const log = (message) => { process.stdout.write(`[中继] ${message}\n`) }

const link = new DshLink({ port: dshPort, log })

// 启动即验证一次：读不到密钥或连不上 DSH 时立刻报错，而不是等手表来连才发现。
try {
  link.cookieHeader()
  const sessions = await link.rpc('session/list', { _request: {} })
  const count = Array.isArray(sessions?.items) ? sessions.items.length : 0
  log(`已连接 DSH（127.0.0.1:${String(dshPort)}），可见会话 ${String(count)} 个`)
} catch (error) {
  log(`无法连接 DSH：${String(error.message)}`)
  log('请确认 DSH 桌面版正在运行，且 --dsh-port 与它的端口一致。')
  process.exit(1)
}

const relay = new RelayServer({
  link,
  port,
  // 令牌落盘：中继重启后手表不必重新配对。
  stateFile: args['state-file'] ?? join(homedir(), '.dsh-watch-relay.json'),
  log,
})

// 保持一条 $events 长连：既用于回答前台等待中的提问，也让中继掌握实时状态。
link.openEvents({
  ready: () => {},
  item: (frame) => { relay.onEventFrame(frame) },
  link: (up) => { log(up ? '事件流已连接' : '事件流断开，正在重连…') },
})

const addresses = await relay.start()
log('中继已启动')
log(`  监听：0.0.0.0:${String(port)}`)
for (const address of addresses) log(`  局域网地址：http://${address}:${String(port)}`)
log(`  配对码：${relay.pairingCode}`)
log('  在手表上打开应用，选择「连接电脑」，输入上面的配对码。')

// 首次配对码变化时提示一次，避免用户拿着旧码反复失败。
let lastCode = relay.pairingCode
setInterval(() => {
  if (relay.pairingCode !== lastCode) {
    lastCode = relay.pairingCode
    log(`配对码已更新：${lastCode}`)
  }
}, 2_000).unref?.()

const shutdown = () => {
  log('正在退出…')
  void relay.stop().then(() => { process.exit(0) })
}
process.on('SIGINT', shutdown)
process.on('SIGTERM', shutdown)
