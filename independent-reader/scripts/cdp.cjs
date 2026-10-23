'use strict';
const net = require('node:net');
const fs = require('node:fs');
const wait = ms => new Promise(resolve => setTimeout(resolve, ms));
async function until(fn, timeout = 20000) {
  const end = Date.now() + timeout; let last;
  while (Date.now() < end) { try { const value = await fn(); if (value) return value; } catch (error) { last = error; } await wait(120); }
  throw last || new Error('等待超时');
}
async function freePort() {
  return new Promise(resolve => { const server = net.createServer(); server.listen(0, '127.0.0.1', () => { const value = server.address().port; server.close(() => resolve(value)); }); });
}
class CDP {
  constructor(ws) {
    this.ws = ws; this.id = 0; this.pending = new Map(); this.errors = [];
    ws.addEventListener('close', () => { for (const item of this.pending.values()) { clearTimeout(item.timer); item.reject(new Error('页面已关闭')); } this.pending.clear(); });
    ws.addEventListener('message', event => {
      const msg = JSON.parse(event.data);
      if (msg.id) { const item = this.pending.get(msg.id); this.pending.delete(msg.id); if (item) { clearTimeout(item.timer); msg.error ? item.reject(new Error(msg.error.message)) : item.resolve(msg.result); } }
      if (msg.method === 'Runtime.exceptionThrown') this.errors.push(msg.params.exceptionDetails);
    });
  }
  static async connect(url) { const ws = new WebSocket(url); await new Promise((resolve, reject) => { ws.addEventListener('open', resolve, { once: true }); ws.addEventListener('error', reject, { once: true }); }); const client = new CDP(ws); await client.call('Runtime.enable'); return client; }
  call(method, params = {}, timeout = 20000) { const id = ++this.id; return new Promise((resolve, reject) => { const timer = setTimeout(() => { this.pending.delete(id); reject(new Error('CDP 超时：' + method)); }, timeout); this.pending.set(id, { resolve, reject, timer }); this.ws.send(JSON.stringify({ id, method, params })); }); }
  async eval(expression) { const result = await this.call('Runtime.evaluate', { expression, awaitPromise: true, returnByValue: true, userGesture: true }, 30000); if (result.exceptionDetails) throw new Error(result.exceptionDetails.exception?.description || result.exceptionDetails.text); return result.result.value; }
  request(command, payload = {}) { return this.eval(`window.reader.request(${JSON.stringify(command)},${JSON.stringify(payload)})`); }
  click(selector) { return this.eval(`document.querySelector(${JSON.stringify(selector)}).click()`); }
  // 截图对渲染负载敏感：给更长的超时并重试一次，避免偶发合成器繁忙导致误报。
  async screenshot(file) {
    const mode = await this.eval('typeof window.reader?.request==="function"?new URLSearchParams(location.search).get("mode"):null');
    if (mode === 'reader' || mode === 'manager') { await this.request('show-' + mode); await wait(150); }
    await this.call('Page.bringToFront', {}, 30000);
    let result;
    try { result = await this.call('Page.captureScreenshot', { format: 'png', captureBeyondViewport: false }, 30000); }
    catch { await wait(400); result = await this.call('Page.captureScreenshot', { format: 'png', captureBeyondViewport: false }, 45000); }
    fs.writeFileSync(file, Buffer.from(result.data, 'base64'));
  }
  close() { this.ws.close(); }
}
module.exports = { CDP, until, wait, freePort };
