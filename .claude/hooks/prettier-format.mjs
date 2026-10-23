// PostToolUse hook for Write|Edit: formats the file Claude just wrote with
// Prettier, if it is in frontend/. Node rather than jq + shell, because jq is
// not installed everywhere and Windows paths arrive with backslashes.
import { execFileSync } from 'node:child_process'
import { existsSync, readFileSync } from 'node:fs'
import path from 'node:path'

const input = JSON.parse(readFileSync(0, 'utf8'))
const file = input.tool_response?.filePath ?? input.tool_input?.file_path
if (!file) process.exit(0)

const frontend = path.resolve(process.env.CLAUDE_PROJECT_DIR ?? '.', 'frontend')
const relative = path.relative(frontend, path.resolve(file))
if (relative.startsWith('..') || path.isAbsolute(relative)) process.exit(0)

// Not installed yet (no npm install): nothing to format with.
const prettier = path.join(frontend, 'node_modules', 'prettier', 'bin', 'prettier.cjs')
if (!existsSync(prettier)) process.exit(0)

// Run from frontend/ so .prettierrc.json and .prettierignore apply. A file
// Prettier can't parse makes this throw, and the hook reports it.
execFileSync(process.execPath, [prettier, '--write', '--ignore-unknown', '--log-level', 'warn', relative], {
  cwd: frontend,
  stdio: ['ignore', 'ignore', 'inherit'],
})
