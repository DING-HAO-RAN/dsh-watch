/**
 * 本模块所在目录的解析。
 *
 * 为什么要单独一个模块：中继既可能以 ES 模块运行（node relay/src/server.mjs），
 * 也可能被 scripts/bundle-relay.mjs 打成单文件后以 CJS 形式运行（SEA 可执行文件）。
 * 两种情况下取目录的方式不同，而 `import.meta` 在 CJS 里是**语法错误**（不是运行时
 * 未定义），所以不能用 try/catch 兜住，必须在打包时改写成 `__filename`。
 *
 * 打包脚本会把下面的 `fileURLToPath(import.meta.url)` 整段替换成 `__filename`，
 * 因此这里不要依赖文件顶部的 import —— 替换后那些导入会被删掉，导致 dirname
 * 之类的名字不存在。逻辑全部写在函数体内并用 require 取依赖。
 */
export function moduleDirectory() {
  // 打包后（CJS）：__filename 直接可用。
  if (typeof __filename === 'string') {
    // eslint-disable-next-line no-undef
    return require('node:path').dirname(__filename)
  }
  // 开发时（ESM）：从 import.meta.url 反推；这行会被打包脚本替换掉。
  return fileURLToPath(import.meta.url).replace(/[\\/][^\\/]*$/u, '')
}

// 开发时的 ESM 依赖；打包脚本会把这一行删掉（整行 import 都被移除）。
import { fileURLToPath } from 'node:url'
