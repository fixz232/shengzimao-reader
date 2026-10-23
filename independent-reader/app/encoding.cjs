'use strict';
// Shared encoding detection: BOM sniffing + sampled UTF-8 validity ratio.
// Used by TXT import, EPUB embedded XHTML and full-text search.
const iconv = require('iconv-lite');

function detectBom(buffer) {
  if (buffer.length >= 4 && buffer[0] === 0xff && buffer[1] === 0xfe && buffer[2] === 0 && buffer[3] === 0) return { encoding: 'utf32le', length: 4 };
  if (buffer.length >= 4 && buffer[0] === 0 && buffer[1] === 0 && buffer[2] === 0xfe && buffer[3] === 0xff) return { encoding: 'utf32be', length: 4 };
  if (buffer.length >= 3 && buffer[0] === 0xef && buffer[1] === 0xbb && buffer[2] === 0xbf) return { encoding: 'utf8', length: 3 };
  if (buffer.length >= 2 && buffer[0] === 0xff && buffer[1] === 0xfe) return { encoding: 'utf16le', length: 2 };
  if (buffer.length >= 2 && buffer[0] === 0xfe && buffer[1] === 0xff) return { encoding: 'utf16be', length: 2 };
  return null;
}

// Count bytes that cannot appear in valid UTF-8 (excludes ASCII).
function invalidUtf8Bytes(bytes) {
  let bad = 0;
  for (let i = 0; i < bytes.length;) {
    const b = bytes[i];
    if (b < 0x80) { i++; continue; }
    let length = 0, low = 0x80;
    if (b >= 0xc2 && b <= 0xdf) length = 1;
    else if (b >= 0xe0 && b <= 0xef) { length = 2; if (b === 0xe0) low = 0xa0; }
    else if (b >= 0xf0 && b <= 0xf4) { length = 3; if (b === 0xf0) low = 0x90; }
    else { bad++; i++; continue; }
    let ok = i + length < bytes.length;
    for (let k = 1; ok && k <= length; k++) { const c = bytes[i + k]; if (c < (k === 1 ? low : 0x80) || c > 0xbf) ok = false; }
    if (b === 0xed && length === 2 && bytes[i + 1] >= 0xa0) ok = false; // surrogates
    if (!ok) { bad++; i++; } else i += length + 1;
  }
  return bad;
}

// Sample up to 4MB (head + tail) so huge files stay cheap to sniff.
function invalidRatio(buffer) {
  const limit = 2 * 1024 * 1024;
  if (buffer.length <= limit * 2) return invalidUtf8Bytes(buffer) / Math.max(1, buffer.length);
  const head = buffer.subarray(0, limit), tail = buffer.subarray(buffer.length - limit);
  return (invalidUtf8Bytes(head) + invalidUtf8Bytes(tail)) / (limit * 2);
}

// BOM first, then explicit hint, then UTF-8 validity ratio (low → UTF-8 with replacement, high → GB18030).
function decode(buffer, encoding) {
  const bom = detectBom(buffer);
  const body = bom ? buffer.subarray(bom.length) : buffer;
  if (encoding && iconv.encodingExists(encoding)) return iconv.decode(body, encoding);
  if (bom) return iconv.decode(body, bom.encoding);
  return invalidRatio(body) > 0.01 ? iconv.decode(body, 'gb18030') : body.toString('utf8');
}

// XML/XHTML: BOM wins, then the XML declaration, then plain sniffing.
function decodeXml(buffer) {
  const bom = detectBom(buffer);
  if (bom && bom.encoding !== 'utf8') return decode(buffer, bom.encoding);
  const head = buffer.subarray(0, 200).toString('latin1');
  const decl = /<\?xml[^>]*?encoding\s*=\s*["']([\w.-]+)["']/i.exec(head);
  if (decl && !/^utf-?8$/i.test(decl[1]) && iconv.encodingExists(decl[1])) return decode(buffer, decl[1]);
  return decode(buffer);
}

module.exports = { detectBom, decode, decodeXml, invalidRatio };
