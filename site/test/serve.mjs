// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
// Serves the built site (dist/, from npm run build) over http on a free local port, as GitHub Pages
// serves it, for the browser tests.
import { readFileSync } from 'node:fs'
import http from 'node:http'
import { extname, join, normalize } from 'node:path'

const ROOT = join(import.meta.dirname, '../dist')
const TYPES = { '.html': 'text/html; charset=utf-8', '.mjs': 'text/javascript; charset=utf-8', '.js': 'text/javascript; charset=utf-8', '.css': 'text/css; charset=utf-8', '.svg': 'image/svg+xml', '.png': 'image/png', '.woff2': 'font/woff2', '.json': 'application/json', '.wasm': 'application/wasm', '.xml': 'application/xml', '.txt': 'text/plain' }

export async function serve () {
  const server = http.createServer((req, res) => {
    const path = new URL(req.url, 'http://127.0.0.1').pathname
    let file = normalize(join(ROOT, decodeURIComponent(path)))
    if (!file.startsWith(ROOT)) { res.writeHead(404); res.end(); return }
    if (path.endsWith('/')) file = join(file, 'index.html')
    try {
      const body = readFileSync(file)
      res.writeHead(200, { 'Content-Type': TYPES[extname(file)] ?? 'application/octet-stream' })
      res.end(body)
    } catch {
      res.writeHead(404)
      res.end()
    }
  })
  await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve))
  return { origin: `http://127.0.0.1:${server.address().port}`, close: () => new Promise((resolve) => server.close(resolve)) }
}
