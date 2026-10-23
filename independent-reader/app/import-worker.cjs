'use strict';
const { parentPort, workerData } = require('node:worker_threads');
const fs = require('node:fs/promises');
const crypto = require('node:crypto');
const { parseBook } = require('./importer.cjs');
(async () => {
  const { text, ...metadata } = await parseBook(workerData.filename, workerData.encoding, workerData.options);
  if (!text.trim()) throw new Error('文本没有可读取内容。');
  await fs.writeFile(workerData.output, text, { encoding: 'utf8', flag: 'wx' });
  const { findChapters } = require('./library.cjs');
  const chapters=workerData.onlineChapters?require('./updates.cjs').mapOnlineChapters(text,workerData.onlineChapters):findChapters(text);
  parentPort.postMessage({ ...metadata, chapters, length: text.length, digest: crypto.createHash('sha256').update(text).digest('hex') });
})().catch(error => parentPort.postMessage({ error: error.message }));
