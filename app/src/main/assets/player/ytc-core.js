/*
 * Pure helpers shared by the offline player and the node test harness.
 * No DOM, no MSE: only stream picking, container sniffing and time formatting.
 */
(function (global) {
  'use strict';

  var FOURCC = [
    // ISO-BMFF sample entry fourccs
    ['av01', 'av01.0.05M.08'],
    ['vp09', 'vp09.00.10.08'],
    ['vp08', 'vp08.00.10.08'],
    ['avc1', 'avc1.42E01E'],
    ['hvc1', 'hvc1.1.6.L93.B0'],
    ['hev1', 'hev1.1.6.L93.B0'],
    ['mp4a', 'mp4a.40.2'],
    ['Opus', 'opus'],
    ['opus', 'opus'],
    ['vorbis', 'vorbis'],
    // Matroska/WebM CodecIDs
    ['V_AV1', 'av01.0.05M.08'],
    ['V_VP9', 'vp09.00.10.08'],
    ['V_VP8', 'vp08.00.10.08'],
    ['A_OPUS', 'opus'],
    ['A_VORBIS', 'vorbis']
  ];

  function toBytes(input) {
    if (!input) return new Uint8Array(0);
    if (input instanceof Uint8Array) return input;
    if (input instanceof ArrayBuffer) return new Uint8Array(input);
    if (ArrayBuffer.isView(input)) {
      return new Uint8Array(input.buffer, input.byteOffset, input.byteLength);
    }
    return new Uint8Array(0);
  }

  function sniff(input) {
    var bytes = toBytes(input);
    var container = 'unknown';
    if (bytes.length >= 4 && bytes[0] === 0x1a && bytes[1] === 0x45 &&
        bytes[2] === 0xdf && bytes[3] === 0xa3) {
      container = 'webm';
    } else if (bytes.length >= 8 && bytes[4] === 0x66 && bytes[5] === 0x74 &&
        bytes[6] === 0x79 && bytes[7] === 0x70) {
      container = 'mp4';
    }

    var limit = Math.min(bytes.length, 8192);
    var text = '';
    for (var i = 0; i < limit; i++) text += String.fromCharCode(bytes[i]);

    var codecs = [];
    for (var k = 0; k < FOURCC.length; k++) {
      if (text.indexOf(FOURCC[k][0]) >= 0 && codecs.indexOf(FOURCC[k][1]) < 0) {
        codecs.push(FOURCC[k][1]);
      }
    }
    return { container: container, codecs: codecs };
  }

  function mimeCandidates(initBytes, kind, recordedMime) {
    var type = kind === 'audio' ? 'audio' : 'video';
    var info = sniff(initBytes);
    var base = type + '/' + (info.container === 'webm' ? 'webm' : 'mp4');
    var out = [];
    function add(value) { if (value && out.indexOf(value) < 0) out.push(value); }

    var recorded = (recordedMime || '').trim();
    var recordedMatchesType = recorded.toLowerCase().indexOf(type + '/') === 0;
    if (recordedMatchesType) {
      var recordedIsWebm = /webm/i.test(recorded);
      var agrees = info.container === 'unknown' || recordedIsWebm === (info.container === 'webm');
      if (agrees) add(recorded);
    }

    var codecs = info.codecs.filter(function (codec) {
      if (type === 'audio') return codec === 'opus' || codec === 'vorbis' || codec.indexOf('mp4a') === 0;
      return codec.indexOf('av01') === 0 || codec.indexOf('vp0') === 0 ||
        codec.indexOf('avc1') === 0 || codec.indexOf('hvc1') === 0 || codec.indexOf('hev1') === 0;
    });
    var usable = codecs.length ? codecs : info.codecs;
    for (var i = 0; i < usable.length; i++) add(base + '; codecs="' + usable[i] + '"');

    add(base);
    add(type + '/mp4');
    add(type + '/webm');
    if (recordedMatchesType) add(recorded);
    return out;
  }

  function streamIndex(streamKey) {
    var match = /^s(\d+)-/.exec(streamKey || '');
    return match ? parseInt(match[1], 10) : -1;
  }

  /** Usable streams (init segment present) beat larger mid-stream captures. */
  function rank(list) {
    return list.slice().sort(function (a, b) {
      var pa = a.playable === false ? 0 : 1;
      var pb = b.playable === false ? 0 : 1;
      if (pa !== pb) return pb - pa;
      return b.bytes - a.bytes;
    });
  }

  /**
   * A video is split across many SourceBuffers. The largest video stream is the
   * dominant one; prefer the audio stream that shares its index, else the largest.
   */
  function pickStreams(streams) {
    var list = (streams || []).filter(function (s) {
      return s && s.fragmentCount > 0;
    });
    var videos = rank(list.filter(function (s) { return s.kind === 'video'; }));
    var audios = rank(list.filter(function (s) { return s.kind === 'audio'; }));

    var video = videos[0] || null;
    var audio = null;
    if (video) {
      var index = streamIndex(video.streamKey);
      if (index >= 0) {
        for (var i = 0; i < audios.length; i++) {
          if (streamIndex(audios[i].streamKey) === index) { audio = audios[i]; break; }
        }
      }
      if (!audio) audio = audios[0] || null;
    } else {
      audio = audios[0] || null;
    }
    return { video: video, audio: audio };
  }

  function pad(value) { return value < 10 ? '0' + value : '' + value; }

  function fmtTime(seconds) {
    if (!isFinite(seconds) || seconds < 0) seconds = 0;
    var total = Math.floor(seconds);
    var s = total % 60;
    var m = Math.floor(total / 60) % 60;
    var h = Math.floor(total / 3600);
    return h > 0 ? h + ':' + pad(m) + ':' + pad(s) : m + ':' + pad(s);
  }

  var api = {
    sniff: sniff,
    rank: rank,
    mimeCandidates: mimeCandidates,
    pickStreams: pickStreams,
    streamIndex: streamIndex,
    fmtTime: fmtTime
  };

  global.YTC = api;
  if (typeof module !== 'undefined' && module.exports) module.exports = api;
})(typeof globalThis !== 'undefined' ? globalThis : this);
