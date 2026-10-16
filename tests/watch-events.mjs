/**
 * 事件流观察工具：连上 DSH 的 $events 流并打印收到的帧，用于诊断提问帧的投递。
 * 只观察，不应答（避免与桌面端抢占 waterfall 的应答权）。
 *
 * 用法：node tests/watch-events.mjs [--seconds 30] [--dsh-port 19387]
 */
import { DshLink } from '../relay/src/dsh.mjs'

const args = process.argv.slice(2)
let seconds = 30
let port = 19387
for (let i = 0; i < args.length; i += 1) {
  if (args[i] === '--seconds') seconds = Number(args[i + 1] ?? '30')
  if (args[i] === '--dsh-port') port = Number(args[i + 1] ?? '19387')
}

const link = new DshLink({ port, log: (m) => { process.stdout.write(`[link] ${m}\n`) } })
let count = 0

link.openEvents({
  ready: (clientId) => { process.stdout.write(`ready clientId=${clientId}\n`) },
  item: (frame) => {
    count += 1
    const summary = JSON.stringify(frame)
    process.stdout.write(`帧 ${String(count)}: ${summary.slice(0, 400)}\n`)
  },
  link: (up) => { process.stdout.write(up ? '事件流已连接\n' : '事件流已断开\n') },
})

process.stdout.write(`观察 ${String(seconds)} 秒…\n`)
setTimeout(() => {
  process.stdout.write(`共收到 ${String(count)} 帧\n`)
  process.exit(0)
}, seconds * 1000)
