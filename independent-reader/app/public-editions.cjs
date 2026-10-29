'use strict';

const { htmlText } = require('./importer.cjs');
// Pin the catalogue and chapters to the same revision so a mid-download edit
// cannot change chapter order or mix editions. Book text is fetched on demand.
const BASE = 'https://raw.githubusercontent.com/weiyinfu/MaoZeDongAnthology/cf86018096980815a5b885dd9d7211da29fbf287/';
const CDN = 'https://cdn.jsdelivr.net/gh/weiyinfu/MaoZeDongAnthology@cf86018096980815a5b885dd9d7211da29fbf287/';
const SOURCE_NAME = '公开文集 · MaoZeDongAnthology';
const SOURCE_URL = 'https://github.com/weiyinfu/MaoZeDongAnthology';
const NOTICE = '番茄网页正文不可用时，将下载同名公开文集版本。公开版不含番茄版权页、分卷扉页，目录与排版有所不同。';

function publicEditionFor(book) {
  const name = String(book.bookName || '').replace(/[\s《》()（）]/gu, '');
  const author = String(book.author || '').replace(/[\s著]/gu, '');
  const volume = name.match(/^毛泽东选集第([一二三四五])卷$/u)?.[1];
  return volume && author === '毛泽东' ? { volume, sourceName: SOURCE_NAME, sourceUrl: SOURCE_URL, notice: NOTICE } : null;
}

async function fetchEditionText(url, signal, providers) {
  if (!url.startsWith(BASE)) throw new Error('公开文集地址不在允许的来源目录内。');
  for (const provider of [...providers]) {
    try {
      const timer = AbortSignal.timeout(15000);
      const response = await fetch(provider + url.slice(BASE.length), { redirect: 'error', signal: signal ? AbortSignal.any([signal, timer]) : timer });
      if (!response.ok) throw Object.assign(new Error(`公开文集请求失败（HTTP ${response.status}）。`), { status: response.status });
      const body = await response.text();
      if (!body.trim()) throw new Error('公开文集未返回正文，请稍后重试。');
      providers.splice(0, providers.length, provider, ...providers.filter(base => base !== provider));
      return body;
    } catch (error) {
      signal?.throwIfAborted();
      if (provider === providers.at(-1) || error.status && error.status < 500) throw error;
    }
  }
}

function parseEditionDirectory(markdown, volume) {
  const section = String(markdown).split(/^# /mu).find(part => part.startsWith(`第${volume}卷 `));
  if (!section) throw new Error('公开文集没有匹配的分卷，已停止下载。');
  const toc = section.split('\n').filter(line => /^\*\s+\[/u.test(line)).map(line => {
    const match = line.match(/^\* \[(\d{3})-([^\]\r\n]+)\]\(\.\/src\/(\d{3}-[^\r\n]+\.md)\)\s*$/u);
    if (!match || !match[3].startsWith(match[1] + '-') || /[\\/?#]/u.test(match[3])) throw new Error('公开文集目录地址无效。');
    return { id: `public-mao-${match[1]}`, title: match[2].trim(), url: BASE + 'src/' + encodeURIComponent(match[3]) };
  });
  if (!toc.length || new Set(toc.map(chapter => chapter.id)).size !== toc.length) throw new Error('公开文集目录为空或重复。');
  return toc;
}

function parseEditionChapter(markdown, title) {
  const source = String(markdown).replace(/^\uFEFF/u, '');
  const heading = source.match(/^#\s+([^\r\n]+)/u)?.[1]?.trim();
  const titleKey = value => String(value || '').replace(/[\u2460-\u2487\s]/gu, '');
  if (titleKey(heading) !== titleKey(title)) throw new Error(`公开文集篇目「${title}」标题不匹配，已停止下载。`);
  const body = htmlText(source.slice(source.indexOf('\n') + 1)
    .replace(/^#{1,6}\s+/gmu, '')
    .replace(/^>\s?/gmu, '')
    .replace(/\[([^\]]+)\]\((?:https?:\/\/|\.\.?\/)[^)]+\)/gu, '$1'));
  if (body.length < 80 || /[\uE000-\uF8FF]/u.test(body)) throw new Error(`公开文集篇目「${title}」正文无效，已停止下载。`);
  return body;
}

async function downloadPublicEdition(edition, { signal, onProgress = () => {} } = {}) {
  signal?.throwIfAborted();
  const providers = [BASE, CDN];
  const toc = parseEditionDirectory(await fetchEditionText(BASE + 'README.md', signal, providers), edition.volume);
  const parts = [];
  for (const chapter of toc) {
    signal?.throwIfAborted();
    const body = parseEditionChapter(await fetchEditionText(chapter.url, signal, providers), chapter.title);
    signal?.throwIfAborted();
    parts.push(`${chapter.title}\n\n${body}`);
    onProgress(parts.length, toc.length, chapter.title);
  }
  return { parts, toc, sourceName: SOURCE_NAME, onlineUrl: SOURCE_URL };
}

module.exports = { publicEditionFor, parseEditionDirectory, parseEditionChapter, downloadPublicEdition };
