'use strict';
// Bound renderer work for unusually long chapters while preserving source offsets.
const LIMIT = 24000;
function readingSection(book, text, position = book.position) {
  const anchor = Math.max(0, Math.min(text.length - 1, Math.floor(Number(position) || 0)));
  const chapters = book.chapters || [];
  const index = chapters.findLastIndex(chapter => chapter.position <= anchor);
  const chapterStart = index < 0 ? 0 : chapters[index].position;
  const chapterEnd = chapters[index + 1]?.position ?? text.length;
  const part = Math.floor((anchor - chapterStart) / LIMIT);
  let start = chapterStart + part * LIMIT;
  let end = Math.min(start + LIMIT, chapterEnd);
  if (start > 0 && /[\uDC00-\uDFFF]/.test(text[start])) start++;
  if (end < text.length && /[\uDC00-\uDFFF]/.test(text[end])) end++;
  let previousPart = chapterStart + Math.max(0, part - 1) * LIMIT;
  if (previousPart > 0 && /[\uDC00-\uDFFF]/.test(text[previousPart])) previousPart++;
  return { id: book.id, title: book.title, chapterTitle: index < 0 ? '正文' : chapters[index].title,
    chapterIndex: index, start, end, position: anchor, length: text.length, text: text.slice(start, end),
    previous: part > 0 ? previousPart : index > 0 ? chapters[index - 1].position : start > 0 ? 0 : null,
    next: end < text.length ? end : null };
}
module.exports = { readingSection };
