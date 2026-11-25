/*
 * Node harness for the pure player core.
 *   node tools/test-core.js
 */
'use strict';

const assert = require('assert');
const path = require('path');
const YTC = require(path.join(__dirname, '..', 'app', 'src', 'main', 'assets', 'player', 'ytc-core.js'));

let checks = 0;
function ok(name, fn) {
  fn();
  checks++;
  console.log('  \u2713 ' + name);
}

// --- helpers to build init segments ---------------------------------------
function ftypInit(fourcc) {
  const head = [0x00, 0x00, 0x00, 0x18, 0x66, 0x74, 0x79, 0x70, 0x69, 0x73, 0x6f, 0x6d];
  const bytes = new Uint8Array(head.length + fourcc.length + 16);
  bytes.set(head, 0);
  for (let i = 0; i < fourcc.length; i++) bytes[head.length + i] = fourcc.charCodeAt(i);
  // moov/hdlr style filler that mentions the track handler
  for (let i = 0; i < 8; i++) bytes[head.length + fourcc.length + i] = 0x00;
  return bytes;
}

function webmInit(codecTag) {
  const bytes = new Uint8Array(48);
  bytes.set([0x1a, 0x45, 0xdf, 0xa3], 0);
  for (let i = 0; i < codecTag.length; i++) bytes[12 + i] = codecTag.charCodeAt(i);
  return bytes;
}

console.log('ytc-core');

ok('detects fragmented MP4 from ftyp', () => {
  assert.strictEqual(YTC.sniff(ftypInit('av01')).container, 'mp4');
});

ok('detects WebM from the EBML magic', () => {
  assert.strictEqual(YTC.sniff(webmInit('V_VP9')).container, 'webm');
});

ok('unknown container for junk bytes', () => {
  assert.strictEqual(YTC.sniff(new Uint8Array([1, 2, 3, 4, 5])).container, 'unknown');
});

ok('sniffs AV1 codec from init bytes', () => {
  assert.deepStrictEqual(YTC.sniff(ftypInit('av01')).codecs, ['av01.0.05M.08']);
});

ok('sniffs VP9 + Opus from the same WebM header', () => {
  const bytes = new Uint8Array(64);
  bytes.set([0x1a, 0x45, 0xdf, 0xa3], 0);
  bytes.set([0x56, 0x5f, 0x56, 0x50, 0x39], 12); // V_VP9
  bytes.set([0x4f, 0x70, 0x75, 0x73], 30); // Opus
  const info = YTC.sniff(bytes);
  assert.ok(info.codecs.includes('vp09.00.10.08'), 'vp9 codec');
  assert.ok(info.codecs.includes('opus'), 'opus codec');
});

ok('accepts the recorded mime when container agrees', () => {
  const candidates = YTC.mimeCandidates(ftypInit('av01'), 'video', 'video/mp4; codecs="av01.0.05M.08"');
  assert.strictEqual(candidates[0], 'video/mp4; codecs="av01.0.05M.08"');
});

ok('rejects a recorded webm mime when the bytes are MP4', () => {
  const candidates = YTC.mimeCandidates(ftypInit('av01'), 'video', 'video/webm; codecs="vp9"');
  assert.ok(!candidates[0].includes('webm'), 'first candidate should be mp4');
  assert.ok(candidates[0].startsWith('video/mp4'), 'first candidate is mp4');
  assert.ok(candidates.includes('video/mp4'), 'plain mp4 fallback present');
  assert.ok(candidates.includes('video/webm'), 'webm fallback present');
});

ok('audio candidates stay in the audio namespace', () => {
  const candidates = YTC.mimeCandidates(webmInit('Opus'), 'audio', 'audio/webm; codecs="opus"');
  assert.ok(candidates.every((c) => c.startsWith('audio/')), 'all audio');
  assert.strictEqual(candidates[0], 'audio/webm; codecs="opus"');
});

ok('candidates are de-duplicated', () => {
  const candidates = YTC.mimeCandidates(ftypInit('avc1'), 'video', 'video/mp4');
  assert.strictEqual(new Set(candidates).size, candidates.length);
});

ok('picks the dominant video stream, not the first', () => {
  const streams = [
    { streamKey: 's3-video', kind: 'video', bytes: 900, fragmentCount: 4 },
    { streamKey: 's5-video', kind: 'video', bytes: 91000, fragmentCount: 210 },
    { streamKey: 's7-video', kind: 'video', bytes: 1200, fragmentCount: 6 },
  ];
  const picked = YTC.pickStreams(streams);
  assert.strictEqual(picked.video.streamKey, 's5-video');
});

ok('matches audio to the dominant video index', () => {
  const streams = [
    { streamKey: 's1-audio', kind: 'audio', bytes: 400, fragmentCount: 9 },
    { streamKey: 's5-video', kind: 'video', bytes: 91000, fragmentCount: 210 },
    { streamKey: 's5-audio', kind: 'audio', bytes: 9000, fragmentCount: 220 },
  ];
  const picked = YTC.pickStreams(streams);
  assert.strictEqual(picked.audio.streamKey, 's5-audio');
});

ok('falls back to the largest audio when no index matches', () => {
  const streams = [
    { streamKey: 's1-audio', kind: 'audio', bytes: 400, fragmentCount: 9 },
    { streamKey: 's2-audio', kind: 'audio', bytes: 8000, fragmentCount: 20 },
    { streamKey: 's6-video', kind: 'video', bytes: 91000, fragmentCount: 210 },
  ];
  assert.strictEqual(YTC.pickStreams(streams).audio.streamKey, 's2-audio');
});

ok('ignores streams without fragments', () => {
  const streams = [
    { streamKey: 's0-video', kind: 'video', bytes: 0, fragmentCount: 0 },
    { streamKey: 's1-audio', kind: 'audio', bytes: 100, fragmentCount: 2 },
  ];
  const picked = YTC.pickStreams(streams);
  assert.strictEqual(picked.video, null);
  assert.strictEqual(picked.audio.streamKey, 's1-audio');
});

ok('handles an empty stream list', () => {
  assert.deepStrictEqual(YTC.pickStreams([]), { video: null, audio: null });
  assert.deepStrictEqual(YTC.pickStreams(null), { video: null, audio: null });
});

ok('formats time like a video player', () => {
  assert.strictEqual(YTC.fmtTime(0), '0:00');
  assert.strictEqual(YTC.fmtTime(65), '1:05');
  assert.strictEqual(YTC.fmtTime(3661), '1:01:01');
  assert.strictEqual(YTC.fmtTime(-4), '0:00');
  assert.strictEqual(YTC.fmtTime(NaN), '0:00');
});

ok('parses stream indexes', () => {
  assert.strictEqual(YTC.streamIndex('s12-video'), 12);
  assert.strictEqual(YTC.streamIndex('audio'), -1);
});

ok('prefers a playable stream over a larger mid-stream capture', () => {
  const streams = [
    { streamKey: 's2-video', kind: 'video', bytes: 500000, fragmentCount: 300, playable: false },
    { streamKey: 's7-video', kind: 'video', bytes: 120000, fragmentCount: 80, playable: true },
  ];
  assert.strictEqual(YTC.pickStreams(streams).video.streamKey, 's7-video');
});

ok('keeps the largest stream when both are playable', () => {
  const streams = [
    { streamKey: 's2-video', kind: 'video', bytes: 500000, fragmentCount: 300, playable: true },
    { streamKey: 's7-video', kind: 'video', bytes: 120000, fragmentCount: 80, playable: true },
  ];
  assert.strictEqual(YTC.pickStreams(streams).video.streamKey, 's2-video');
});

ok('does not pair audio from an unusable stream when a playable one exists', () => {
  const streams = [
    { streamKey: 's1-audio', kind: 'audio', bytes: 9000, fragmentCount: 40, playable: false },
    { streamKey: 's3-audio', kind: 'audio', bytes: 3000, fragmentCount: 12, playable: true },
    { streamKey: 's3-video', kind: 'video', bytes: 20000, fragmentCount: 20, playable: true },
  ];
  assert.strictEqual(YTC.pickStreams(streams).audio.streamKey, 's3-audio');
});

console.log('\n' + checks + ' core checks passed');
