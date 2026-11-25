/*
 * YTCache capture hook (v1.1)
 * -------------------------------------------------------------------------
 * Tees bytes at SourceBuffer.appendBuffer so whatever the YouTube player
 * decoded online is exactly what we store offline. Fails open: the page always
 * receives the original call even if capture throws.
 *
 * Rules for staying invisible to YouTube:
 *  - never touch history, DOM or events the page owns;
 *  - never block the renderer longer than a base64 encode;
 *  - metadata is read, never written, and is sent only when it improves.
 */
(function () {
  'use strict';
  if (window.__ytcHookInstalled) return;
  window.__ytcHookInstalled = true;

  var TAG = '[ytc]';
  var ANDROID = 'YTCacheAndroid';
  var streams = new WeakMap();
  var streamCounter = 0;
  var lastHref = '';
  var cachedId = '';
  var lastSent = { id: '', title: '', description: '' };
  var infoTimer = null;
  var infoAttempts = 0;
  var infoSettled = false;

  function log(message) { try { console.log(TAG + ' ' + message); } catch (e) { /* ignore */ } }

  function bridge() {
    try { return window[ANDROID] || null; } catch (e) { return null; }
  }

  function armed() {
    return window.__ytcCaptureEnabled !== false;
  }

  // ------------------------------------------------------------- transport

  /**
   * btoa() needs a binary string. TextDecoder('latin1') must NOT be used here:
   * the encoding spec aliases latin1 to windows-1252, which mangles bytes
   * 0x80-0x9F and silently corrupts the media. A chunked fromCharCode build is
   * the only exact pure-JS mapping.
   */
  function toBase64(bytes) {
    var text = '';
    for (var i = 0; i < bytes.length; i += 0x8000) {
      text += String.fromCharCode.apply(null, bytes.subarray(i, i + 0x8000));
    }
    return btoa(text);
  }

  function utf8(value) { return new TextEncoder().encode(value); }

  function send(header, bytes) {
    var head = utf8(JSON.stringify(header));
    var frame = new Uint8Array(4 + head.length + bytes.byteLength);
    frame[0] = head.length & 0xff;
    frame[1] = (head.length >>> 8) & 0xff;
    frame[2] = (head.length >>> 16) & 0xff;
    frame[3] = (head.length >>> 24) & 0xff;
    frame.set(head, 4);
    frame.set(bytes, 4 + head.length);

    try {
      var api = bridge();
      if (api && typeof api.postChunk === 'function') {
        api.postChunk(toBase64(frame));
        return true;
      }
    } catch (e) {
      log('send failed: ' + e);
    }
    return false;
  }

  // ------------------------------------------------------------ MSE hooks

  function kindOf(mime) {
    if (/^audio\//i.test(mime)) return 'audio';
    if (/^video\//i.test(mime)) return 'video';
    return 'other';
  }

  var originalAddSourceBuffer = MediaSource.prototype.addSourceBuffer;
  MediaSource.prototype.addSourceBuffer = function (mime) {
    var buffer = originalAddSourceBuffer.apply(this, arguments);
    try {
      if (buffer && !streams.has(buffer)) {
        var type = String(mime || '');
        var kind = kindOf(type);
        var key = 's' + (streamCounter++) + '-' + kind;
        streams.set(buffer, { mime: type, streamKey: key, kind: kind, seq: 0 });
        log('stream ' + key + ' ' + type);
        scheduleInfo(true);
      }
    } catch (e) { /* fail open */ }
    return buffer;
  };

  function lookup(buffer) {
    if (streams.has(buffer)) return streams.get(buffer);
    var created = { mime: '', streamKey: 's' + (streamCounter++) + '-other', kind: 'other', seq: 0 };
    try { streams.set(buffer, created); } catch (e) { /* ignore */ }
    return created;
  }

  var originalAppendBuffer = SourceBuffer.prototype.appendBuffer;
  SourceBuffer.prototype.appendBuffer = function (data) {
    try {
      if (armed() && data) {
        var info = lookup(this);
        var view = null;
        if (data instanceof ArrayBuffer) {
          view = new Uint8Array(data);
        } else if (ArrayBuffer.isView(data)) {
          view = new Uint8Array(data.buffer, data.byteOffset, data.byteLength);
        }
        if (view && view.byteLength > 0) {
          var seq = info.seq++;
          send({
            v: 1,
            videoId: videoId(),
            streamKey: info.streamKey,
            kind: info.kind,
            mime: info.mime,
            seq: seq,
            isFirstAppend: seq === 0,
            byteLength: view.byteLength
          }, view);
          if (seq === 0) scheduleInfo(true);
        }
      }
    } catch (e) {
      log('capture error: ' + e);
    }
    return originalAppendBuffer.apply(this, arguments);
  };

  // ------------------------------------------------------------- metadata

  function videoId() {
    try {
      var href = location.href;
      if (href !== lastHref) {
        lastHref = href;
        cachedId = '';
      }
      if (cachedId) return cachedId;
      var url = new URL(href);
      var v = url.searchParams.get('v');
      if (v) { cachedId = v; return v; }
      var shorts = url.pathname.match(/\/shorts\/([A-Za-z0-9_-]{5,})/);
      if (shorts) { cachedId = shorts[1]; return cachedId; }
      if (url.hostname === 'youtu.be') {
        var tail = url.pathname.replace(/^\//, '');
        if (tail) { cachedId = tail.split('/')[0]; return cachedId; }
      }
      var embed = url.pathname.match(/\/embed\/([A-Za-z0-9_-]{5,})/);
      if (embed) { cachedId = embed[1]; return cachedId; }
      var response = window.ytInitialPlayerResponse;
      if (response && response.videoDetails && response.videoDetails.videoId) {
        cachedId = response.videoDetails.videoId;
        return cachedId;
      }
      var meta = document.querySelector('meta[itemprop="videoId"]');
      if (meta && meta.content) { cachedId = meta.content; return cachedId; }
    } catch (e) { /* ignore */ }
    return cachedId || 'unknown';
  }

  function metaProperty(name) {
    try {
      var el = document.querySelector('meta[property="' + name + '"]');
      return el && el.content ? el.content : '';
    } catch (e) { return ''; }
  }

  function metaName(name) {
    try {
      var el = document.querySelector('meta[name="' + name + '"]');
      return el && el.content ? el.content : '';
    } catch (e) { return ''; }
  }

  var TEXT_SELECTORS = [
    // Shorts (2023-2026 layouts), then the watch page
    'ytd-reel-video-renderer[is-active] yt-shorts-video-title-view-model',
    'ytd-reel-video-renderer[is-active] #video-title',
    'ytd-reel-video-renderer[is-active] .reel-player-header-renderer .title',
    'ytd-shorts yt-shorts-video-title-view-model',
    'ytm-slim-video-information-renderer .slim-video-information-title',
    'ytm-slim-video-information-renderer h2',
    'h1.title yt-formatted-string',
    'h1.title',
    '#title h1 yt-formatted-string',
    '#title h1'
  ];

  function firstText(selectors) {
    for (var i = 0; i < selectors.length; i++) {
      try {
        var el = document.querySelector(selectors[i]);
        var text = el && (el.textContent || el.innerText);
        if (text && text.trim()) return text.trim().split('\n')[0].trim();
      } catch (e) { /* ignore */ }
    }
    return '';
  }

  /** Full text of the first matching block (descriptions keep their lines). */
  function firstBlock(selectors) {
    for (var i = 0; i < selectors.length; i++) {
      try {
        var el = document.querySelector(selectors[i]);
        var text = el && (el.innerText || el.textContent);
        if (text && text.trim()) return text.trim().slice(0, 4000);
      } catch (e) { /* ignore */ }
    }
    return '';
  }

  var AUTHOR_SELECTORS = [
    'ytd-reel-video-renderer[is-active] #channel-name a',
    'ytd-reel-video-renderer[is-active] .reel-player-header-renderer a',
    'ytm-slim-owner-renderer a',
    '#owner #channel-name a',
    '#owner yt-formatted-string',
    '.slim-video-information-channel-name',
    'ytd-video-owner-renderer #channel-name a',
    '#channel-name a'
  ];

  var DESCRIPTION_SELECTORS = [
    '#description-inline-expander yt-attributed-string',
    '#description-inline-expander',
    'ytd-text-inline-expander#description-inline-expander',
    'ytm-slim-video-description-renderer',
    '#description yt-formatted-string',
    '#description',
    '.slim-video-description'
  ];

  var ISO_DURATION = /^PT(?:(\d+)H)?(?:(\d+)M)?(?:(\d+(?:\.\d+)?)S)?$/;

  function isoToSeconds(value) {
    var match = ISO_DURATION.exec(value || '');
    if (!match) return 0;
    return ((+match[1] || 0) * 3600) + ((+match[2] || 0) * 60) + Math.round(+match[3] || 0);
  }

  function cleanTitle(value) {
    var text = (value || '').replace(/\s*[-\u2013\u2014|]\s*YouTube\s*$/, '').trim();
    if (/^youtube(\.com)?$/i.test(text) || /^shorts$/i.test(text)) return '';
    return text;
  }

  function playerDetails(id) {
    var response = window.ytInitialPlayerResponse || null;
    if (!response || !response.videoDetails) return null;
    if (response.videoDetails.videoId && response.videoDetails.videoId !== id) return null;
    return response.videoDetails;
  }

  function mediaSessionMeta() {
    try {
      var nav = typeof navigator !== 'undefined' ? navigator : null;
      var meta = nav && nav.mediaSession && nav.mediaSession.metadata;
      if (!meta) return null;
      var artwork = '';
      if (meta.artwork && meta.artwork.length) {
        artwork = meta.artwork[meta.artwork.length - 1].src || '';
      }
      return { title: meta.title || '', artist: meta.artist || '', artwork: artwork };
    } catch (e) { return null; }
  }

  function collectInfo() {
    var id = videoId();
    if (!id || id === 'unknown') return null;
    var details = playerDetails(id);
    var session = mediaSessionMeta();

    var title = cleanTitle(
      (details && details.title) ||
      (session && session.title) ||
      firstText(TEXT_SELECTORS) ||
      metaProperty('og:title') ||
      metaName('title') ||
      document.title
    );
    if (!title) return null;

    var author = (details && details.author) ||
      (session && session.artist) ||
      firstText(AUTHOR_SELECTORS) ||
      metaName('author') || '';

    var length = 0;
    if (details && details.lengthSeconds) {
      length = parseInt(details.lengthSeconds, 10) || 0;
    }
    if (!length) length = isoToSeconds(metaName('duration') || metaProperty('video:duration'));
    if (!length) {
      try {
        var video = document.querySelector('video');
        if (video && isFinite(video.duration) && video.duration > 0) {
          length = Math.round(video.duration);
        }
      } catch (e) { /* ignore */ }
    }

    var thumbUrl = (session && session.artwork) || '';
    if (details && details.thumbnail && details.thumbnail.thumbnails) {
      var list = details.thumbnail.thumbnails;
      for (var i = 0; i < list.length; i++) {
        if (list[i] && list[i].url) thumbUrl = list[i].url;
      }
    }
    if (!thumbUrl) thumbUrl = metaProperty('og:image');
    if (!thumbUrl) thumbUrl = 'https://i.ytimg.com/vi/' + id + '/hqdefault.jpg';

    var description = '';
    try {
      if (details && details.shortDescription) description = details.shortDescription;
      if (!description && window.ytInitialPlayerResponse &&
          window.ytInitialPlayerResponse.microformat &&
          window.ytInitialPlayerResponse.microformat.playerMicroformatRenderer) {
        description = window.ytInitialPlayerResponse.microformat.playerMicroformatRenderer.description || '';
      }
    } catch (e) { /* ignore */ }
    if (!description) description = firstBlock(DESCRIPTION_SELECTORS);
    if (!description) description = metaProperty('og:description') || '';

    return {
      v: 1,
      videoId: id,
      title: title,
      author: author,
      lengthSeconds: length || 0,
      description: description,
      thumbUrl: thumbUrl
    };
  }

  function reportInfo(force) {
    if (!armed()) return;
    var api = bridge();
    if (!api || typeof api.saveVideoInfo !== 'function') return;
    var info = collectInfo();
    if (!info || !info.title) return;
    var unchanged = info.videoId === lastSent.id &&
      info.title === lastSent.title &&
      info.description === lastSent.description;
    if (!force && unchanged) return;
    try {
      api.saveVideoInfo(JSON.stringify(info));
      lastSent = { id: info.videoId, title: info.title, description: info.description };
      log('metadata ' + info.videoId + ' "' + info.title + '"');
    } catch (e) {
      log('metadata failed: ' + e);
    }
  }

  /** Polls briefly after a navigation or a new append, then backs off. */
  function scheduleInfo(restart) {
    if (!armed()) return;
    if (restart) {
      infoAttempts = 0;
      infoSettled = false;
      if (infoTimer) { clearTimeout(infoTimer); infoTimer = null; }
    }
    if (infoTimer || infoSettled) return;
    infoAttempts = 0;
    var tick = function () {
      infoTimer = null;
      reportInfo(false);
      infoAttempts++;
      if (armed() && infoAttempts < 24) {
        infoTimer = setTimeout(tick, infoAttempts < 8 ? 400 : 1200);
      } else {
        // Stop polling until a navigation/append asks again: this hook must
        // stay invisible to the page's own event loop.
        infoSettled = true;
      }
    };
    tick();
  }

  function onNavigate() {
    cachedId = '';
    scheduleInfo(true);
  }

  try { lastHref = location.href; } catch (e) { lastHref = ''; }
  scheduleInfo(true);

  try {
    document.addEventListener('yt-navigate-finish', onNavigate, true);
  } catch (e) { /* ignore */ }

  setInterval(function () {
    try {
      var href = location.href;
      if (href !== lastHref) {
        lastHref = href;
        log('navigation ' + href);
        onNavigate();
      }
      if (armed() && !infoTimer) scheduleInfo(false);
    } catch (e) { /* ignore */ }
  }, 1500);

  // Native side can ask for a refresh after the user taps Cache.
  window.__ytcRefreshInfo = function () { scheduleInfo(true); };

  log('hook v1.1 installed');
})();
