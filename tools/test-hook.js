/*
 * Node harness that runs the real capture hook against a mocked MSE + bridge.
 *   node tools/test-hook.js
 */
'use strict';

const assert = require('assert');
const fs = require('fs');
const path = require('path');
const vm = require('vm');

const hookSource = fs.readFileSync(
  path.join(__dirname, '..', 'app', 'src', 'main', 'assets', 'hooks', 'v1.js'),
  'utf8'
);

let checks = 0;
function ok(name, fn) {
  fn();
  checks++;
  console.log('  \u2713 ' + name);
}

function makeEnv(options) {
  options = options || {};
  const posted = [];
  const infos = [];

  class FakeSourceBuffer {
    constructor(mime) { this.mime = mime; this.appended = []; }
    appendBuffer(data) {
      this.appended.push(new Uint8Array(data));
      if (options.appendThrows) throw new Error('QuotaExceededError');
    }
  }
  class FakeMediaSource {
    constructor() { this.buffers = []; }
    addSourceBuffer(mime) {
      if (options.rejectMime && options.rejectMime(mime)) {
        throw new Error('NotSupportedError');
      }
      const buffer = new FakeSourceBuffer(mime);
      this.buffers.push(buffer);
      return buffer;
    }
  }

  const meta = Object.assign({
    'og:title': '',
    'og:image': '',
    'og:description': '',
  }, options.meta || {});

  const dom = options.dom || {};
  const documentMock = {
    title: options.title || 'Some video - YouTube',
    querySelector(selector) {
      const propMatch = /^meta\[property="(.+)"\]$/.exec(selector);
      if (propMatch && meta[propMatch[1]]) return { content: meta[propMatch[1]] };
      const nameMatch = /^meta\[name="(.+)"\]$/.exec(selector);
      if (nameMatch && (options.metaNames || {})[nameMatch[1]]) {
        return { content: options.metaNames[nameMatch[1]] };
      }
      if (Object.prototype.hasOwnProperty.call(dom, selector)) {
        const text = dom[selector];
        return { textContent: text, innerText: text };
      }
      return null;
    },
    addEventListener() {},
  };

  const windowMock = {
    __ytcCaptureEnabled: options.armed !== false,
    ytInitialPlayerResponse: options.playerResponse || null,
    YTCacheAndroid: {
      postChunk(b64) { posted.push(b64); },
      saveVideoInfo(json) { infos.push(JSON.parse(json)); return 'ok'; },
    },
  };

  const sandbox = {
    window: windowMock,
    document: documentMock,
    location: { href: options.href || 'https://m.youtube.com/watch?v=dQw4w9WgXcQ', pathname: '/watch', hostname: 'm.youtube.com', searchParams: new URLSearchParams('v=dQw4w9WgXcQ') },
    MediaSource: FakeMediaSource,
    SourceBuffer: FakeSourceBuffer,
    TextEncoder,
    TextDecoder,
    btoa: (value) => Buffer.from(value, 'binary').toString('base64'),
    console: { log() {} },
    setTimeout: (fn) => { return 0; },
    setInterval: () => 0,
    clearTimeout: () => {},
    URL,
    WeakMap,
    Uint8Array,
    ArrayBuffer,
    Promise,
    JSON,
    Math,
  };
  sandbox.globalThis = sandbox;
  sandbox.window.window = windowMock;

  vm.createContext(sandbox);
  vm.runInContext(hookSource, sandbox);
  return { sandbox, windowMock, posted, infos, FakeMediaSource, FakeSourceBuffer };
}

function decodeFrames(posted) {
  return posted.map((b64) => {
    const bytes = new Uint8Array(Buffer.from(b64, 'base64'));
    const headerLength = bytes[0] | (bytes[1] << 8) | (bytes[2] << 16) | (bytes[3] << 24);
    const header = JSON.parse(Buffer.from(bytes.subarray(4, 4 + headerLength)).toString('utf8'));
    const payload = bytes.subarray(4 + headerLength);
    return { header, payload };
  });
}

console.log('capture hook');

ok('installs onto MediaSource/SourceBuffer', () => {
  const env = makeEnv();
  const mediaSource = new env.FakeMediaSource();
  const buffer = mediaSource.addSourceBuffer('video/mp4; codecs="av01.0.05M.08"');
  assert.strictEqual(typeof buffer.appendBuffer, 'function');
  assert.ok(env.windowMock.__ytcHookInstalled, 'flag set');
});

ok('is idempotent across double injection', () => {
  const env = makeEnv();
  vm.runInContext(hookSource, env.sandbox);
  const mediaSource = new env.FakeMediaSource();
  const buffer = mediaSource.addSourceBuffer('video/mp4; codecs="av01.0.05M.08"');
  buffer.appendBuffer(new Uint8Array([1, 2, 3]));
  const frames = decodeFrames(env.posted);
  assert.strictEqual(frames.length, 1, 'one send per append');
  assert.strictEqual(frames[0].header.seq, 0, 'sequence started once');
});

ok('captures bytes byte-exactly', () => {
  const env = makeEnv();
  const mediaSource = new env.FakeMediaSource();
  const buffer = mediaSource.addSourceBuffer('video/mp4; codecs="av01.0.05M.08"');
  const init = new Uint8Array([0, 0, 0, 24, 0x66, 0x74, 0x79, 0x70, 1, 2, 3, 4]);
  buffer.appendBuffer(init);
  const frames = decodeFrames(env.posted);
  assert.deepStrictEqual(Array.from(frames[0].payload), Array.from(init));
});

ok('captures a subarray view without over-reading', () => {
  const env = makeEnv();
  const mediaSource = new env.FakeMediaSource();
  const buffer = mediaSource.addSourceBuffer('video/mp4');
  const backing = new Uint8Array([9, 9, 7, 8, 9, 9]);
  buffer.appendBuffer(backing.subarray(2, 5));
  const frames = decodeFrames(env.posted);
  assert.deepStrictEqual(Array.from(frames[0].payload), [7, 8, 9]);
});

ok('numbers fragments per stream and flags the init segment', () => {
  const env = makeEnv();
  const mediaSource = new env.FakeMediaSource();
  const video = mediaSource.addSourceBuffer('video/mp4');
  const audio = mediaSource.addSourceBuffer('audio/mp4');
  video.appendBuffer(new Uint8Array([1]));
  video.appendBuffer(new Uint8Array([2]));
  audio.appendBuffer(new Uint8Array([3]));
  const frames = decodeFrames(env.posted);
  assert.deepStrictEqual(frames.map((f) => f.header.seq), [0, 1, 0]);
  assert.deepStrictEqual(frames.map((f) => f.header.isFirstAppend), [true, false, true]);
  assert.deepStrictEqual(frames.map((f) => f.header.kind), ['video', 'video', 'audio']);
  assert.strictEqual(frames[0].header.videoId, 'dQw4w9WgXcQ');
  assert.ok(/^s\d+-video$/.test(frames[0].header.streamKey), 'stream key format');
});

ok('records byteLength in the header', () => {
  const env = makeEnv();
  const mediaSource = new env.FakeMediaSource();
  const buffer = mediaSource.addSourceBuffer('video/mp4');
  buffer.appendBuffer(new Uint8Array(37));
  const frames = decodeFrames(env.posted);
  assert.strictEqual(frames[0].header.byteLength, 37);
  assert.strictEqual(frames[0].payload.length, 37);
});

ok('does nothing while capture is disarmed', () => {
  const env = makeEnv({ armed: false });
  const mediaSource = new env.FakeMediaSource();
  const buffer = mediaSource.addSourceBuffer('video/mp4');
  buffer.appendBuffer(new Uint8Array([1, 2, 3]));
  assert.strictEqual(env.posted.length, 0);
});

ok('still forwards the append when armed', () => {
  const env = makeEnv();
  const mediaSource = new env.FakeMediaSource();
  const buffer = mediaSource.addSourceBuffer('video/mp4');
  const payload = new Uint8Array([4, 5, 6]);
  buffer.appendBuffer(payload);
  assert.deepStrictEqual(Array.from(buffer.appended[0]), [4, 5, 6]);
});

ok('propagates a player exception but keeps the capture', () => {
  const env = makeEnv({ appendThrows: true });
  const mediaSource = new env.FakeMediaSource();
  const buffer = mediaSource.addSourceBuffer('video/mp4');
  assert.throws(() => buffer.appendBuffer(new Uint8Array([1])), /QuotaExceededError/);
  const frames = decodeFrames(env.posted);
  assert.strictEqual(frames.length, 1, 'capture happened before the player threw');
  assert.deepStrictEqual(Array.from(frames[0].payload), [1]);
});

ok('wraps an unknown SourceBuffer without a mime', () => {
  const env = makeEnv();
  const buffer = new env.FakeSourceBuffer('unregistered');
  buffer.appendBuffer(new Uint8Array([5]));
  const frames = decodeFrames(env.posted);
  assert.strictEqual(frames.length, 1);
  assert.strictEqual(frames[0].header.kind, 'other');
  assert.ok(/^s\d+-other$/.test(frames[0].header.streamKey));
});

ok('captures metadata from ytInitialPlayerResponse', () => {
  const env = makeEnv({
    playerResponse: {
      videoDetails: {
        videoId: 'dQw4w9WgXcQ',
        title: 'Never Gonna Give You Up',
        author: 'Rick Astley',
        lengthSeconds: '213',
        thumbnail: { thumbnails: [{ url: 'https://i.ytimg.com/vi/dQw4w9WgXcQ/default.jpg' }, { url: 'https://i.ytimg.com/vi/dQw4w9WgXcQ/hqdefault.jpg' }] },
      },
      microformat: { playerMicroformatRenderer: { description: 'The official video' } },
    },
  });
  assert.strictEqual(env.infos.length, 1, 'metadata saved once');
  const info = env.infos[0];
  assert.strictEqual(info.title, 'Never Gonna Give You Up');
  assert.strictEqual(info.author, 'Rick Astley');
  assert.strictEqual(info.lengthSeconds, 213);
  assert.strictEqual(info.thumbUrl, 'https://i.ytimg.com/vi/dQw4w9WgXcQ/hqdefault.jpg');
  assert.strictEqual(info.description, 'The official video');
});

ok('falls back to document title and ISO duration', () => {
  const env = makeEnv({
    title: 'Fallback Title - YouTube',
    metaNames: { duration: 'PT1M23S' },
  });
  assert.strictEqual(env.infos.length, 1);
  assert.strictEqual(env.infos[0].title, 'Fallback Title');
  assert.strictEqual(env.infos[0].lengthSeconds, 83);
  assert.strictEqual(env.infos[0].thumbUrl, 'https://i.ytimg.com/vi/dQw4w9WgXcQ/hqdefault.jpg');
});

ok('ignores a stale playerResponse for another video', () => {
  const env = makeEnv({
    title: 'The New Video - YouTube',
    playerResponse: { videoDetails: { videoId: 'OTHERID1234', title: 'The Old Video', author: 'Old' } },
  });
  assert.strictEqual(env.infos[0].title, 'The New Video');
});

ok('parses shorts URLs for the video id', () => {
  const env = makeEnv({ href: 'https://m.youtube.com/shorts/abcDEF12345' });
  const mediaSource = new env.FakeMediaSource();
  const buffer = mediaSource.addSourceBuffer('video/mp4');
  buffer.appendBuffer(new Uint8Array([1]));
  const frames = decodeFrames(env.posted);
  assert.strictEqual(frames[0].header.videoId, 'abcDEF12345');
});

ok('does not send metadata while disarmed', () => {
  const env = makeEnv({ armed: false });
  assert.strictEqual(env.infos.length, 0);
});

ok('reads a Shorts title, channel and description from the active reel', () => {
  const env = makeEnv({
    href: 'https://m.youtube.com/shorts/abcDEF12345',
    dom: {
      'ytd-reel-video-renderer[is-active] yt-shorts-video-title-view-model': 'Cats being cats',
      'ytd-reel-video-renderer[is-active] #channel-name a': 'Cat Channel',
      '#description-inline-expander': 'Line one\nLine two',
    },
  });
  assert.strictEqual(env.infos.length, 1);
  const info = env.infos[0];
  assert.strictEqual(info.videoId, 'abcDEF12345');
  assert.strictEqual(info.title, 'Cats being cats');
  assert.strictEqual(info.author, 'Cat Channel');
  assert.strictEqual(info.description, 'Line one\nLine two');
});

ok('uses shortDescription from the player response', () => {
  const env = makeEnv({
    playerResponse: {
      videoDetails: { videoId: 'dQw4w9WgXcQ', title: 'T', author: 'A', lengthSeconds: '12' },
      microformat: { playerMicroformatRenderer: { description: 'From microformat' } },
    },
  });
  assert.strictEqual(env.infos[0].description, 'From microformat');
});

ok('sends metadata again when a better description arrives', () => {
  const env = makeEnv({ title: 'First title - YouTube' });
  assert.strictEqual(env.infos.length, 1);
  assert.strictEqual(env.infos[0].description, '');

  env.windowMock.ytInitialPlayerResponse = {
    videoDetails: {
      videoId: 'dQw4w9WgXcQ',
      title: 'First title',
      author: 'Chan',
      lengthSeconds: '100',
    },
    microformat: { playerMicroformatRenderer: { description: 'Now described' } },
  };
  env.windowMock.__ytcRefreshInfo();

  assert.strictEqual(env.infos.length, 2);
  assert.strictEqual(env.infos[1].description, 'Now described');
  assert.strictEqual(env.infos[1].lengthSeconds, 100);
});

ok('encodes every byte value exactly', () => {
  const env = makeEnv();
  const mediaSource = new env.FakeMediaSource();
  const buffer = mediaSource.addSourceBuffer('video/mp4');
  const payload = new Uint8Array([0, 255, 128, 7, 200]);
  buffer.appendBuffer(payload);
  const frames = decodeFrames(env.posted);
  assert.deepStrictEqual(Array.from(frames[0].payload), [0, 255, 128, 7, 200]);
});

console.log('\n' + checks + ' hook checks passed');
