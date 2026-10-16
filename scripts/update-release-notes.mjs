// 更新已发布 Release 的说明文字（用 UTF-8 编码提交，避免中文变问号）。
//
// 用法：node scripts/update-release-notes.mjs <tag> <说明文件>
import { readFileSync } from 'node:fs'
import { execFileSync } from 'node:child_process'

const [tag, notesFile] = process.argv.slice(2)
if (!tag || !notesFile) {
  process.stderr.write('用法：node scripts/update-release-notes.mjs <tag> <说明文件>\n')
  process.exit(1)
}

// 从 git 凭据管理器取令牌（与 release.ps1 同一来源，不落盘）。
const credential = execFileSync('git', ['credential', 'fill'], {
  input: 'protocol=https\nhost=github.com\n\n',
  encoding: 'utf8',
})
const token = /password=(.+)/.exec(credential)?.[1]?.trim()
if (!token) {
  process.stderr.write('取不到 GitHub 凭据\n')
  process.exit(1)
}

const body = readFileSync(notesFile, 'utf8')
const repo = 'DING-HAO-RAN/dsh-watch'
const headers = {
  authorization: 'token ' + token,
  accept: 'application/vnd.github+json',
  'content-type': 'application/json; charset=utf-8',
}

// PATCH 只能按 release id 打，不能按 tag（按 tag 会 404）。
const found = await fetch('https://api.github.com/repos/' + repo + '/releases/tags/' + tag, { headers })
if (!found.ok) {
  process.stderr.write('找不到 ' + tag + '：' + String(found.status) + '\n')
  process.exit(1)
}
const release = await found.json()

const response = await fetch('https://api.github.com/repos/' + repo + '/releases/' + String(release.id), {
  method: 'PATCH',
  headers,
  // fetch 会把字符串按 UTF-8 编码，中文不会丢。
  body: JSON.stringify({ body }),
})
const json = await response.json()
if (!response.ok) {
  process.stderr.write('更新失败：' + JSON.stringify(json) + '\n')
  process.exit(1)
}
const text = json.body ?? ''
process.stdout.write('已更新 ' + tag + '：' + String(text.length) + ' 字符，含中文=' + String(/[\u4e00-\u9fa5]/.test(text)) + '\n')
