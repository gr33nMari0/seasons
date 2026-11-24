// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
// What stands in front of Seasons Web in production, for browser scenarios (docs/adr/0007): an
// HTTPS reverse proxy on localhost with the suite's self-signed certificate, adding the visitor's
// address in CF-Connecting-IP as Cloudflare's tunnel does. It streams, so the event stream works.
// Also small HTTPS stand-ins for a BlueMap and a static site, on ports of the shard's own.
import { readFileSync } from 'node:fs'
import http from 'node:http'
import https from 'node:https'
import { extname, join, normalize } from 'node:path'

/** The shard's ports beyond Seasons Web's own: the tunnel, up to five maps and a site. */
export const tunnelPort = (ctx) => ctx.webPort + 1
export const mapPort = (ctx, n = 0) => ctx.webPort + 2 + n
export const sitePort = (ctx) => ctx.webPort + 7

let visitors = 0

function tls (ctx) {
  if (!ctx.tls) throw new Error('no --tls certificate for the tunnel')
  return { pfx: readFileSync(ctx.tls), passphrase: 'changeit' }
}

async function listen (ctx, server, port) {
  await new Promise((resolve, reject) => {
    server.once('error', reject)
    server.listen(port, '127.0.0.1', resolve)
  })
  ctx.servers.push(server)
  return server
}

/**
 * Starts the tunnel in front of the shard's Seasons Web and resolves with { origin, ip }. Every
 * request it passes on says it came from `ip`, a documentation address of its own unless given, so
 * one scenario's browsers don't share another's rate limits. A scenario has one tunnel: asked again,
 * this resolves with the one already running.
 */
export async function startTunnel (ctx, { ip = `198.51.100.${(++visitors % 250) + 1}` } = {}) {
  if (ctx.tunnel && ctx.servers.includes(ctx.tunnel.server)) return ctx.tunnel
  const port = tunnelPort(ctx)
  const server = https.createServer(tls(ctx), (req, res) => {
    const headers = { ...req.headers, 'cf-connecting-ip': ip }
    const upstream = http.request(
      { host: '127.0.0.1', port: ctx.webPort, method: req.method, path: req.url, headers },
      (answer) => {
        res.writeHead(answer.statusCode, answer.headers)
        answer.pipe(res)
        // A stream cut off upstream, as when Seasons Web restarts, is cut off for the browser too.
        answer.on('close', () => { if (!res.writableEnded) res.destroy() })
      })
    upstream.on('error', () => {
      if (!res.headersSent) res.writeHead(502)
      res.end()
    })
    res.on('close', () => upstream.destroy())
    req.pipe(upstream)
  })
  await listen(ctx, server, port)
  ctx.tunnel = { origin: `https://localhost:${port}`, ip, server }
  return ctx.tunnel
}

/**
 * A stand-in for a BlueMap webserver on https://localhost:<port>: its page answers with `headers`
 * (such as a CSP naming who may frame it), or never answers with { hang: true }. Resolves with its
 * origin.
 */
export async function startMap (ctx, { n = 0, headers = {}, hang = false } = {}) {
  const port = mapPort(ctx, n)
  const server = https.createServer(tls(ctx), (req, res) => {
    ctx.log(`[map ${port}] ${req.method} ${req.url}`)
    if (hang) return // never answers
    res.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8', ...headers })
    res.end('<!doctype html><title>BlueMap</title><p id="bluemap">The map</p>')
  })
  await listen(ctx, server, port)
  return `https://localhost:${port}`
}

const TYPES = {
  '.html': 'text/html; charset=utf-8',
  '.mjs': 'text/javascript; charset=utf-8',
  '.js': 'text/javascript; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.svg': 'image/svg+xml',
  '.png': 'image/png',
  '.woff2': 'font/woff2',
  '.json': 'application/json',
  '.wasm': 'application/wasm'
}

/**
 * Serves a folder over plain http on 127.0.0.1, as GitHub Pages serves the public site (a folder's
 * index.html for its address). Resolves with its origin.
 */
export async function startSite (ctx, root) {
  const port = sitePort(ctx)
  const server = http.createServer((req, res) => {
    const path = new URL(req.url, 'http://127.0.0.1').pathname
    let file = normalize(join(root, decodeURIComponent(path)))
    if (!file.startsWith(root)) {
      res.writeHead(404)
      res.end()
      return
    }
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
  await listen(ctx, server, port)
  return `http://127.0.0.1:${port}`
}
