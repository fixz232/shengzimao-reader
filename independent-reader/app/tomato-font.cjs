'use strict';

// Translation data and its AGPL attribution are kept alongside this module.
const font = require('./tomato-font.json');
function decodeChapterFont(html, body) {
  if (!/[\uE000-\uF8FF]/u.test(body)) return body;
  // Only decode the known public web font. A changed font must not silently
  // turn into plausible but incorrect book text.
  if (!String(html).includes(font.family) || !String(html).includes(font.asset)) return body;
  return body.replace(/[\uE000-\uF8FF]/gu, character => {
    const mapped = font.characters[character.codePointAt(0) - font.start];
    return mapped && mapped !== '?' ? mapped : character;
  });
}

module.exports = { decodeChapterFont };
