'use strict';
// 从 listen/qr.js 拷来的纯本地二维码生成器（listen 已 DEPRECATED，这里独立维护）。

class QrError extends Error {
  constructor(message) {
    super(message);
    this.name = 'QrError';
  }
}

function makeQrDataUri(text) {
  const matrix = makeQrMatrix(String(text || ''));
  const quiet = 4;
  const cell = 6;
  const size = matrix.length + quiet * 2;
  let rects = '';
  for (let y = 0; y < matrix.length; y += 1) {
    for (let x = 0; x < matrix.length; x += 1) {
      if (matrix[y][x]) rects += '<rect x="' + ((x + quiet) * cell) + '" y="' + ((y + quiet) * cell) + '" width="' + cell + '" height="' + cell + '"/>';
    }
  }
  const svg = '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 ' + (size * cell) + ' ' + (size * cell) + '"><rect width="100%" height="100%" fill="#fff"/><g fill="#111">' + rects + '</g></svg>';
  return 'data:image/svg+xml;base64,' + Buffer.from(svg, 'utf8').toString('base64');
}

function makeQrMatrix(text) {
  const bytes = Array.from(Buffer.from(text, 'utf8'));
  if (bytes.length > 106) throw new QrError('二维码内容过长');
  const version = 5;
  const size = 37;
  const dataCodewords = 108;
  const eccCodewords = 26;
  const matrix = Array.from({ length: size }, () => Array(size).fill(null));
  const reserved = Array.from({ length: size }, () => Array(size).fill(false));
  const set = (x, y, value, isReserved = true) => {
    if (x < 0 || y < 0 || x >= size || y >= size) return;
    matrix[y][x] = !!value;
    if (isReserved) reserved[y][x] = true;
  };

  drawFinder(matrix, reserved, 0, 0);
  drawFinder(matrix, reserved, size - 7, 0);
  drawFinder(matrix, reserved, 0, size - 7);
  for (let i = 8; i < size - 8; i += 1) {
    set(i, 6, i % 2 === 0);
    set(6, i, i % 2 === 0);
  }
  drawAlignment(matrix, reserved, 30, 30);
  reserveFormat(reserved, matrix);
  set(8, 4 * version + 9, true);

  const bits = [];
  appendBits(bits, 0x4, 4);
  appendBits(bits, bytes.length, 8);
  bytes.forEach(byte => appendBits(bits, byte, 8));
  const maxBits = dataCodewords * 8;
  appendBits(bits, 0, Math.min(4, maxBits - bits.length));
  while (bits.length % 8) bits.push(0);

  const data = [];
  for (let i = 0; i < bits.length; i += 8) data.push(bitsToByte(bits.slice(i, i + 8)));
  for (let pad = 0; data.length < dataCodewords; pad += 1) data.push(pad % 2 ? 0x11 : 0xec);
  const codewords = data.concat(reedSolomon(data, eccCodewords));
  const allBits = [];
  codewords.forEach(byte => appendBits(allBits, byte, 8));

  let bitIndex = 0;
  let upward = true;
  for (let right = size - 1; right >= 1; right -= 2) {
    if (right === 6) right -= 1;
    for (let vert = 0; vert < size; vert += 1) {
      const y = upward ? size - 1 - vert : vert;
      for (let dx = 0; dx < 2; dx += 1) {
        const x = right - dx;
        if (reserved[y][x]) continue;
        matrix[y][x] = !!allBits[bitIndex];
        bitIndex += 1;
      }
    }
    upward = !upward;
  }

  const mask = 0;
  for (let y = 0; y < size; y += 1) {
    for (let x = 0; x < size; x += 1) {
      if (!reserved[y][x] && ((x + y) % 2 === 0)) matrix[y][x] = !matrix[y][x];
    }
  }
  drawFormat(matrix, reserved, mask);
  return matrix.map(row => row.map(Boolean));
}

function drawFinder(matrix, reserved, x, y) {
  for (let dy = -1; dy <= 7; dy += 1) {
    for (let dx = -1; dx <= 7; dx += 1) {
      const xx = x + dx;
      const yy = y + dy;
      if (xx < 0 || yy < 0 || yy >= matrix.length || xx >= matrix.length) continue;
      const inPattern = dx >= 0 && dx <= 6 && dy >= 0 && dy <= 6;
      const black = inPattern && (dx === 0 || dx === 6 || dy === 0 || dy === 6 || (dx >= 2 && dx <= 4 && dy >= 2 && dy <= 4));
      matrix[yy][xx] = black;
      reserved[yy][xx] = true;
    }
  }
}

function drawAlignment(matrix, reserved, cx, cy) {
  for (let dy = -2; dy <= 2; dy += 1) {
    for (let dx = -2; dx <= 2; dx += 1) {
      const dist = Math.max(Math.abs(dx), Math.abs(dy));
      matrix[cy + dy][cx + dx] = dist !== 1;
      reserved[cy + dy][cx + dx] = true;
    }
  }
}

function reserveFormat(reserved, matrix) {
  const size = matrix.length;
  const mark = (x, y) => {
    if (x < 0 || y < 0 || x >= size || y >= size) return;
    reserved[y][x] = true;
    if (matrix[y][x] === null) matrix[y][x] = false;
  };
  for (let i = 0; i <= 5; i += 1) { mark(8, i); mark(i, 8); }
  mark(8, 7); mark(8, 8); mark(7, 8);
  for (let i = 9; i <= 14; i += 1) mark(14 - i, 8);
  for (let i = 0; i <= 7; i += 1) mark(size - 1 - i, 8);
  for (let i = 8; i <= 14; i += 1) mark(8, size - 15 + i);
}

function drawFormat(matrix, reserved, mask) {
  const size = matrix.length;
  const data = (1 << 3) | mask;
  let rem = data;
  for (let i = 0; i < 10; i += 1) rem = (rem << 1) ^ (((rem >>> 9) & 1) ? 0x537 : 0);
  const bits = ((data << 10) | rem) ^ 0x5412;
  const bit = i => ((bits >>> i) & 1) !== 0;
  const set = (x, y, value) => {
    matrix[y][x] = value;
    reserved[y][x] = true;
  };
  for (let i = 0; i <= 5; i += 1) set(8, i, bit(i));
  set(8, 7, bit(6));
  set(8, 8, bit(7));
  set(7, 8, bit(8));
  for (let i = 9; i <= 14; i += 1) set(14 - i, 8, bit(i));
  for (let i = 0; i <= 7; i += 1) set(size - 1 - i, 8, bit(i));
  for (let i = 8; i <= 14; i += 1) set(8, size - 15 + i, bit(i));
  set(8, size - 8, true);
}

function appendBits(out, value, length) {
  for (let i = length - 1; i >= 0; i -= 1) out.push((value >>> i) & 1);
}

function bitsToByte(bits) {
  return bits.reduce((value, bit) => (value << 1) | bit, 0);
}

function reedSolomon(data, degree) {
  const divisor = rsDivisor(degree);
  const result = Array(degree).fill(0);
  data.forEach(byte => {
    const factor = byte ^ result.shift();
    result.push(0);
    for (let i = 0; i < degree; i += 1) result[i] ^= gfMultiply(divisor[i], factor);
  });
  return result;
}

function rsDivisor(degree) {
  const result = Array(degree).fill(0);
  result[degree - 1] = 1;
  let root = 1;
  for (let i = 0; i < degree; i += 1) {
    for (let j = 0; j < degree; j += 1) {
      result[j] = gfMultiply(result[j], root);
      if (j + 1 < degree) result[j] ^= result[j + 1];
    }
    root = gfMultiply(root, 2);
  }
  return result;
}

function gfMultiply(x, y) {
  let z = 0;
  for (let i = 7; i >= 0; i -= 1) {
    z = (z << 1) ^ (((z >>> 7) & 1) ? 0x11d : 0);
    if (((y >>> i) & 1) !== 0) z ^= x;
  }
  return z & 0xff;
}

module.exports = {
  makeQrDataUri,
  makeQrMatrix,
  QrError
};
