'use strict';
// Follows SoNovel SearchResultsHandler's relevant-first search: title/author
// similarity, low-quality filtering, then descending relevance. Compound queries
// additionally require every word, so a first-word source retry cannot leak noise.
const normalize = value => String(value || '').normalize('NFKC').toLocaleLowerCase().replace(/[^\p{L}\p{N}]/gu, '');
const titleOf = book => normalize(book.bookName || book.title);
const authorOf = book => normalize(String(book.author || '').replace(/^\s*作者\s*[:：]?\s*/, ''));
function similarity(left, right) {
  const a = Array.from(left), b = Array.from(right);
  if (!a.length || !b.length) return 0;
  let row = Array.from({length:b.length + 1}, (_, i) => i);
  for (let i = 1; i <= a.length; i++) {
    const next = [i];
    for (let j = 1; j <= b.length; j++) next[j] = Math.min(next[j - 1] + 1, row[j] + 1, row[j - 1] + (a[i - 1] === b[j - 1] ? 0 : 1));
    row = next;
  }
  return 1 - row[b.length] / Math.max(a.length, b.length);
}
function filterResults(query, books) {
  query = String(query || '');
  const tokens = query.trim().split(/[\s\u3000]+/).map(normalize).filter(Boolean);
  const list = Array.isArray(books) ? books : [];
  if (!tokens.length) return query.trim() ? [] : list.map(book => ({...book, matchScore:0}));
  const whole = normalize(query);
  const candidates = list.filter(book => book && titleOf(book)).map(book => {
    const title = titleOf(book), author = authorOf(book);
    const values = tokens.map(token => Math.max(...[title, author].map(field => field === token ? 100 : field.includes(token) ? 92 - Math.min(12, field.length - token.length) : 0)));
    const exact = title === whole || author === whole;
    const strict = values.every(Boolean);
    const near = !strict && tokens.length === 1 && whole.length >= 3 ? Math.max(similarity(whole, title.slice(0, 160)), similarity(whole, author.slice(0, 160))) : 0;
    const matchScore = exact ? 100 : strict ? Math.round(values.reduce((sum, value) => sum + value, 0) / values.length) : Math.round(near * 100);
    const matchType = title === whole ? '书名完全匹配' : author === whole ? '作者完全匹配' : strict ? tokens.length > 1 ? '多关键词匹配' : '关键词匹配' : '近似匹配';
    return {book:{...book, matchScore, matchType}, strict, exact, near};
  });
  const hasStrict = candidates.some(item => item.strict);
  return candidates.filter(item => hasStrict ? item.strict : item.near >= 0.7)
    .sort((a, b) => Number(b.exact) - Number(a.exact) || b.book.matchScore - a.book.matchScore || titleOf(a.book).localeCompare(titleOf(b.book), 'zh-CN') || authorOf(a.book).localeCompare(authorOf(b.book), 'zh-CN'))
    .map(item => item.book);
}
module.exports = {filterResults, normalize};
