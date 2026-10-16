/**
 * 把中继的多个 ES 模块合成一个单文件，供 Node 的 SEA 打包使用。
 *
 * 为什么需要：SEA 只接受单一入口脚本。这些模块之间没有循环依赖，
 * 因此按固定顺序拼接、去掉 import/export 关键字后，顶层声明天然互相可见。
 * 用 Node 做这件事而不是 shell 正则：要正确处理各种 import 写法与字符串内容。
 *
 * 四条必须遵守的规则（都是踩过的坑）：
 * 1. **内置模块的导入必须保留**，否则拼接结果里 join/dirname 全都不存在。
 * 2. **`import path from 'node:path'` 这类默认导入要整体引入**（`const path = require(...)`）。
 *    Node 内置模块的 default 是命名空间对象，拆成 `default: path` 解构会得到 undefined，
 *    随后 `path.join` 就报 "Cannot read properties of undefined"。
 * 3. **不能给模块套块级作用域**，否则跨模块使用的函数（如 moduleDirectory）不可见。
 * 4. **CJS 不允许顶层 await**，入口用了它，因此整体包一层 async main()。
 *
 * 用法：node scripts/bundle-relay.mjs <输出文件>
 */
import { readFileSync, writeFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'

// 顺序即依赖顺序：被依赖的在前。
const MODULES = [
  'paths.mjs',
  'tunnel.mjs',
  'state.mjs',
  'dsh.mjs',
  'relay.mjs',
  'server.mjs',
]

const here = dirname(fileURLToPath(import.meta.url))
const srcDir = join(here, '..', 'relay', 'src')
const output = process.argv[2]
if (output === undefined) {
  process.stderr.write('用法：node scripts/bundle-relay.mjs <输出文件>\n')
  process.exit(1)
}

/** 已收集的内置模块导入：源 → { whole, bindings }。 */
const requires = new Map()

/**
 * 解析 import 语句的子句，拆出「整体引入的名字」与「具名解构」。
 * @param {string} clause from 之前的整段
 * @returns {{whole?: string, bindings: string[]}}
 */
function splitClause(clause) {
  const result = { whole: undefined, bindings: [] }
  let rest = clause.trim()

  // 先摘掉具名部分。
  const named = /\{([^}]*)\}/.exec(rest)
  if (named !== null) {
    for (const piece of named[1].split(',')) {
      const item = piece.trim()
      if (item === '') continue
      const asMatch = /^(\S+)\s+as\s+(\S+)$/.exec(item)
      result.bindings.push(asMatch === null ? item : `${asMatch[1]}: ${asMatch[2]}`)
    }
    rest = rest.replace(/\{[^}]*\}/u, '')
  }
  // 命名空间导入 `* as ns`：等价于整体引入。
  const star = /\*\s+as\s+(\S+)/.exec(rest)
  if (star !== null) {
    result.whole = star[1]
    return result
  }
  // 剩下的裸名字就是默认导入，整体引入。
  const bare = rest.replace(/,/gu, '').trim()
  if (bare !== '') result.whole = bare
  return result
}

/** 记录一次内置模块导入，按名字去重。 */
function recordRequire(specifier, whole, bindings) {
  const entry = requires.get(specifier) ?? { whole: undefined, bindings: [] }
  if (whole !== undefined && entry.whole === undefined) entry.whole = whole
  for (const binding of bindings) {
    if (!entry.bindings.includes(binding)) entry.bindings.push(binding)
  }
  requires.set(specifier, entry)
}

/** 去掉 export 关键字，并把 import.meta 改写成 CJS 可用的形式。 */
function transformLine(line) {
  const trimmed = line.trimStart()
  if (/^export\s*\{/.test(trimmed)) return null
  if (/^export\s+default\s/.test(trimmed)) return line.replace(/^(\s*)export\s+default\s/, '$1')
  if (/^export\s+/.test(trimmed)) return line.replace(/^(\s*)export\s+/, '$1')
  // import.meta 在 CJS 里是语法错误（不是运行时未定义），必须改写；
  // __filename 在打包后的可执行文件里就是本模块的位置。
  if (line.includes('import.meta')) {
    return line.replace(/fileURLToPath\(import\.meta\.url\)/gu, '__filename')
      .replace(/import\.meta\.url/gu, "'file://' + __filename")
  }
  return line
}

const bodies = []
for (const name of MODULES) {
  const text = readFileSync(join(srcDir, name), 'utf8')
  const kept = []
  for (const line of text.split('\n')) {
    const importMatch = /^\s*import\s+(.+?)\s+from\s+['"]([^'"]+)['"]\s*;?\s*$/.exec(line)
    if (importMatch !== null) {
      const [, clause, specifier] = importMatch
      // 本地模块的内容已经在同一文件里，导入语句本身不再需要。
      if (!specifier.startsWith('./') && !specifier.startsWith('../')) {
        const { whole, bindings } = splitClause(clause)
        recordRequire(specifier, whole, bindings)
      }
      continue
    }
    const next = transformLine(line)
    if (next !== null) kept.push(next)
  }
  bodies.push(`// ========== relay/src/${name} ==========`)
  bodies.push(kept.join('\n').trimEnd())
  bodies.push('')
}

const header = []
header.push('// 本文件由 scripts/bundle-relay.mjs 自动生成，请勿手工编辑。')
header.push('// 源码在 relay/src/ 下；改那里，然后重新执行打包。')
header.push('')
// CJS 不允许顶层 await，而入口用了它：整体包一层立即执行的 async 函数。
header.push('async function main() {')
const seen = new Set()
for (const [specifier, entry] of requires) {
  if (entry.whole !== undefined && !seen.has(entry.whole)) {
    seen.add(entry.whole)
    header.push(`const ${entry.whole} = require('${specifier}')`)
  }
  const unique = entry.bindings.filter((binding) => {
    const name = binding.includes(':') ? binding.split(':')[1].trim() : binding
    if (seen.has(name)) return false
    seen.add(name)
    return true
  })
  if (unique.length > 0) {
    header.push(`const { ${unique.join(', ')} } = require('${specifier}')`)
  }
}
header.push('')

const footer = [
  '}',
  '',
  'main().catch((error) => {',
  '  process.stderr.write(`[中继] 启动失败：${error?.message ?? error}\\n`)',
  '  // 分发到别的电脑后没有调试环境，把堆栈一并打出来便于远程排查。',
  '  if (error?.stack) process.stderr.write(`${error.stack}\\n`)',
  '  process.exit(1)',
  '})',
  '',
]

writeFileSync(output, [...header, ...bodies, ...footer].join('\n'), 'utf8')
process.stdout.write(`已生成 ${output}（${String(MODULES.length)} 个模块，${String(requires.size)} 个内置依赖）\n`)
