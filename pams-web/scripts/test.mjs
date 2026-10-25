import { spawnSync } from 'node:child_process'
import { fileURLToPath } from 'node:url'

// Node 26 exposes an unavailable native localStorage before jsdom is installed.
// Keep browser storage owned by the test DOM, while supporting older Node releases.
const flags = process.allowedNodeEnvironmentFlags.has('--no-experimental-webstorage')
  ? ['--no-experimental-webstorage'] : []
const vitest = fileURLToPath(new URL('../node_modules/vitest/vitest.mjs', import.meta.url))
const env = { ...process.env, NODE_OPTIONS: [process.env.NODE_OPTIONS, ...flags].filter(Boolean).join(' ') }
const result = spawnSync(process.execPath, [...flags, vitest, ...process.argv.slice(2)], { stdio: 'inherit', env })
if (result.error) throw result.error
process.exitCode = result.status ?? 1
