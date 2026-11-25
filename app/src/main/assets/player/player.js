/*
 * YTCache offline player.
 *
 * Fragments are fetched from the app's local origin (no bridge, no base64) and
 * fed to MSE in the same WebView that produced them, so codec support always
 * matches what the device could already play.
 */
(function () {
  'use strict';

  var bridge = window.YTCacheAndroid || null;
  var feed = document.getElementById('feed');
  var boot = document.getElementById('boot');

  var ICONS = {
    back: 'M20 11H7.83l5.59-5.59L12 4l-8 8 8 8 1.41-1.41L7.83 13H20z',
    play: 'M8 5v14l11-7z',
    pause: 'M6 19h4V5H6v14zm8-14v14h4V5h-4z',
    soundOn: 'M3 9v6h4l5 5V4L7 9H3zm13.5 3a4.5 4.5 0 0 0-2.5-4v8a4.5 4.5 0 0 0 2.5-4zM14 3.2v2.1a7 7 0 0 1 0 13.4v2.1a9 9 0 0 0 0-17.6z',
    soundOff: 'M4.3 3L3 4.3 7.7 9H3v6h4l5 5v-6.7l4.3 4.3 1.3-1.3L4.3 3zM13 4L9.9 7.1 13 10.2V4z',
    link: 'M3.9 12c0-1.71 1.39-3.1 3.1-3.1h4V7H7a5 5 0 0 0 0 10h4v-1.9H7c-1.71 0-3.1-1.39-3.1-3.1zM8 13h8v-2H8v2zm9-6h-4v1.9h4c1.71 0 3.1 1.39 3.1 3.1S18.71 15.1 17 15.1h-4V17h4a5 5 0 0 0 0-10z',
    audio: 'M12 3a9 9 0 1 0 0 18 9 9 0 0 0 0-18zm0 14.5a5.5 5.5 0 1 1 0-11 5.5 5.5 0 0 1 0 11zm0-8.5a3 3 0 1 0 0 6 3 3 0 0 0 0-6z',
    video: 'M17 10.5V7a1 1 0 0 0-1-1H4a1 1 0 0 0-1 1v10a1 1 0 0 0 1 1h12a1 1 0 0 0 1-1v-3.5l4 4v-11l-4 4z',
    trash: 'M6 19a2 2 0 0 0 2 2h8a2 2 0 0 0 2-2V7H6v12zM19 4h-3.5l-1-1h-5l-1 1H5v2h14V4z',
    back10: 'M12.5 8c-2.65 0-5.05.99-6.9 2.6L2 7v9h9l-3.62-3.62c1.39-1.16 3.16-1.88 5.12-1.88 3.54 0 6.55 2.31 7.6 5.5l2.37-.78C21.08 11.03 17.15 8 12.5 8z',
    fwd10: 'M11.5 8c2.65 0 5.05.99 6.9 2.6L22 7v9h-9l3.62-3.62c-1.39-1.16-3.16-1.88-5.12-1.88-3.54 0-6.55 2.31-7.6 5.5l-2.37-.78C4.92 11.03 8.85 8 11.5 8z'
  };

  function icon(key) {
    return '<svg viewBox="0 0 24 24"><path d="' + ICONS[key] + '"/></svg>';
  }

  function fmtSize(bytes) {
    if (!bytes) return '';
    var mb = bytes / 1048576;
    if (mb >= 1024) return (mb / 1024).toFixed(2) + ' GB';
    if (mb < 1) return Math.max(1, Math.round(bytes / 1024)) + ' KB';
    return mb.toFixed(1) + ' MB';
  }

  function readList() {
    try {
      var raw = bridge && bridge.listVideos ? bridge.listVideos() : '[]';
      var parsed = JSON.parse(raw);
      return Array.isArray(parsed) ? parsed : [];
    } catch (e) {
      return [];
    }
  }

  /** Sound is on by default: the user asked for no "tap for sound" gate. */
  function readSoundPref() {
    var stored = null;
    try { stored = localStorage.getItem('ytc.muted'); } catch (e) { stored = null; }
    if (stored !== null) return stored === '0';
    try {
      if (bridge && bridge.pref) return bridge.pref('sound') !== '0';
    } catch (e) { /* ignore */ }
    return true;
  }

  var entries = readList();

  if (!entries.length) {
    var empty = document.createElement('div');
    empty.className = 'empty';
    empty.innerHTML =
      '<div><h1>No captures yet</h1>' +
      '<p>Browse YouTube and tap \u201cCache\u201d while a video plays. Your offline library shows up here.</p>' +
      '<button type="button">Browse YouTube</button></div>';
    empty.querySelector('button').addEventListener('click', function () {
      if (bridge && bridge.close) bridge.close();
    });
    document.body.appendChild(empty);
    if (boot && boot.parentNode) boot.parentNode.removeChild(boot);
    return;
  }

  var globalMuted = !readSoundPref();

  var pages = [];
  var byElement = new Map();
  var active = null;

  // ------------------------------------------------------------------- helpers

  /**
   * The seek bar always shows the *real* video length, never just the captured
   * slice: a 10-minute capture of a 40-minute video still looks like a
   * 40-minute video, with the cached window drawn inside it.
   */
  function durationOf(page) {
    var meta = (page.meta && page.meta.lengthSeconds) || (page.entry && page.entry.lengthSeconds) || 0;
    var mse = page.video.duration;
    var captured = page.capturedEnd || 0;
    var best = Math.max(meta, isFinite(mse) ? mse : 0, captured);
    return best > 0 ? best : 0;
  }

  /**
   * The cached window on the real timeline. A capture that started mid-video
   * keeps the stream's own timestamps, so playback begins at ranges.start(0)
   * instead of 0:00 — that is the part this player has to jump to.
   */
  function bufferedRange(page) {
    var start = 0;
    var end = 0;
    try {
      var ranges = page.video.buffered;
      if (ranges && ranges.length) {
        start = ranges.start(0);
        end = ranges.end(ranges.length - 1);
      }
    } catch (e) { /* ignore */ }
    return { start: start, end: end };
  }

  function bufferedEnd(page) {
    return bufferedRange(page).end;
  }

  function localUrl(id, rest) {
    return '/local/' + encodeURIComponent(id) + '/' + rest;
  }

  function fetchJson(id, name) {
    return fetch(localUrl(id, name), { cache: 'no-store' }).then(function (response) {
      if (!response.ok) throw new Error(name + ' unavailable (' + response.status + ')');
      return response.json();
    });
  }

  function fetchFragment(id, streamKey, seq) {
    return fetch(localUrl(id, streamKey + '/' + seq + '.bin'), { cache: 'no-store' })
      .then(function (response) {
        if (!response.ok) throw new Error('Fragment ' + seq + ' is missing.');
        return response.arrayBuffer();
      });
  }

  function once(target, event, timeout) {
    return new Promise(function (resolve, reject) {
      var timer = timeout ? setTimeout(function () {
        cleanup();
        reject(new Error('Timed out waiting for ' + event));
      }, timeout) : null;

      function cleanup() {
        target.removeEventListener(event, onEvent);
        if (timer) clearTimeout(timer);
      }
      function onEvent() {
        cleanup();
        resolve();
      }
      target.addEventListener(event, onEvent);
    });
  }

  function toast(page, message) {
    page.toastEl.textContent = message;
    page.toastEl.classList.add('on');
    clearTimeout(page.toastTimer);
    page.toastTimer = setTimeout(function () {
      page.toastEl.classList.remove('on');
    }, 2200);
  }

  function updateSoundUi(page) {
    var bubble = page.railSound.querySelector('.bubble');
    bubble.innerHTML = icon(globalMuted ? 'soundOff' : 'soundOn');
    page.soundHint.hidden = true;
  }

  // ---------------------------------------------------------------------- MSE

  function createBuffer(ms, track, initBytes) {
    if (!track.playable && track.hasInit === false) {
      throw new Error('no init segment');
    }
    var candidates = YTC.mimeCandidates(initBytes, track.kind, track.mime);
    var lastError = null;
    for (var i = 0; i < candidates.length; i++) {
      try {
        var buffer = ms.addSourceBuffer(candidates[i]);
        return {
          sb: buffer,
          track: track,
          mime: candidates[i],
          count: track.fragmentCount,
          next: 1,
          done: false,
          short: false
        };
      } catch (e) {
        lastError = e;
      }
    }
    throw new Error('This device cannot decode the ' + track.kind +
      ' track (' + ((lastError && lastError.name) || 'unsupported codec') + ').');
  }

  function appendBuffer(buffer, bytes) {
    return new Promise(function (resolve, reject) {
      function onEnd() {
        buffer.sb.removeEventListener('updateend', onEnd);
        buffer.sb.removeEventListener('error', onError);
        resolve();
      }
      function onError() {
        buffer.sb.removeEventListener('updateend', onEnd);
        buffer.sb.removeEventListener('error', onError);
        reject(new Error('The decoder rejected a fragment.'));
      }
      buffer.sb.addEventListener('updateend', onEnd);
      buffer.sb.addEventListener('error', onError);
      try {
        buffer.sb.appendBuffer(bytes);
      } catch (e) {
        buffer.sb.removeEventListener('updateend', onEnd);
        buffer.sb.removeEventListener('error', onError);
        reject(e);
      }
    });
  }

  async function appendWithRecovery(page, buffer, bytes) {
    if (page.destroyed) return;
    try {
      await appendBuffer(buffer, bytes);
    } catch (e) {
      if (e && e.name === 'QuotaExceededError') {
        var current = page.video.currentTime || 0;
        var removed = false;
        try {
          buffer.sb.remove(0, Math.max(0, current - 15));
          removed = true;
        } catch (inner) {
          removed = false;
        }
        if (removed) {
          try { await once(buffer.sb, 'updateend', 4000); } catch (inner) { /* continue */ }
        }
        if (!page.destroyed) await appendBuffer(buffer, bytes);
        return;
      }
      throw e;
    }
  }

  /**
   * Streams fragments into MSE. A missing/truncated fragment ends *this* track
   * politely instead of killing playback: the part that was captured still
   * plays, which is what a stopped capture looks like on disk.
   */
  async function pump(page, buffer) {
    var id = page.entry.videoId;
    var key = buffer.track.streamKey;

    try {
      await appendWithRecovery(page, buffer, page.init[key]);
    } catch (e) {
      throw new Error(
        buffer.track.kind === 'video'
          ? 'This capture has no usable video keyframe segment.'
          : 'This capture has no usable audio segment.'
      );
    }

    var pending = null;
    var seq = 1;
    var tailChecks = 0;
    while (!page.destroyed && !buffer.short) {
      if (seq >= buffer.count) {
        // The capture may still be recording on the other screen: follow the
        // growing tail a few times so a fresh capture plays all the way.
        if (tailChecks >= 5) break;
        tailChecks++;
        await new Promise(function (resolve) { setTimeout(resolve, 800); });
        if (page.destroyed) return;
        var grown = 0;
        try {
          var fresh = await fetchJson(id, 'streams.json');
          for (var i = 0; i < fresh.length; i++) {
            if (fresh[i].streamKey === key) { grown = fresh[i].fragmentCount || 0; break; }
          }
        } catch (e) {
          grown = 0;
        }
        if (grown > buffer.count) {
          buffer.count = grown;
          page.partial = false;
          continue;
        }
        break;
      }

      var bytes = null;
      try {
        bytes = pending ? await pending : await fetchFragment(id, key, seq);
      } catch (e) {
        bytes = null;
      }
      pending = null;
      if (seq + 1 < buffer.count) {
        pending = fetchFragment(id, key, seq + 1).catch(function () { return null; });
      }
      if (!bytes) {
        buffer.short = true;
        page.partial = true;
        break;
      }
      try {
        await appendWithRecovery(page, buffer, bytes);
      } catch (e) {
        buffer.short = true;
        page.partial = true;
        break;
      }
      buffer.next = seq + 1;
      seq++;
    }
    buffer.done = true;
  }

  function finalize(page) {
    if (!page.ms || page.ms.readyState !== 'open') return;
    var end = bufferedEnd(page);
    if (end > 0.2) {
      page.capturedEnd = end;
      try {
        if (!isFinite(page.ms.duration) || Math.abs(page.ms.duration - end) > 0.5) {
          page.ms.duration = end;
        }
      } catch (e) { /* duration is informational */ }
    }
    try { page.ms.endOfStream(); } catch (e) { /* already ended */ }
    updatePartialChip(page);
    alignPosition(page);
    updateTimes(page);
  }

  function updatePartialChip(page) {
    var total = durationOf(page);
    var window = bufferedRange(page);
    var label = '';
    if (window.end > 0.5) {
      var offset = window.start > 1.5;
      var short = offset || page.partial || (total > 0 && window.end + 2 < total);
      if (short) {
        label = 'Cached ' + YTC.fmtTime(window.start) + '\u2013' + YTC.fmtTime(window.end) +
          (total > 0 ? ' / ' + YTC.fmtTime(total) : '');
      }
    }
    if (label) {
      if (page.partialChip.textContent !== label) page.partialChip.textContent = label;
      page.partialChip.hidden = false;
    } else if (!page.partialChip.hidden) {
      page.partialChip.hidden = true;
    }
  }

  function load(page) {
    if (page.ready) return Promise.resolve();
    if (page.loading) return page.loading;

    page.destroyed = false;
    page.spinner.classList.add('on');
    page.errbox.hidden = true;

    page.loading = (async function () {
      var id = page.entry.videoId;
      var meta = page.entry;
      try { meta = await fetchJson(id, 'video.json'); } catch (e) { /* list entry is enough */ }
      page.meta = meta || page.entry;

      var streams = await fetchJson(id, 'streams.json');
      var picked = YTC.pickStreams(streams);
      // A stream recorded mid-playback has no init segment: MSE cannot start
      // from it, so prefer whatever usable track exists instead of failing.
      if (picked.video && !picked.video.playable) picked.video = null;
      if (picked.audio && !picked.audio.playable) picked.audio = null;
      if (!picked.video && !picked.audio) {
        throw new Error('This capture started mid-stream, so it has no init segment. ' +
          'Cache it again from the beginning: arm Cache, then open the video.');
      }

      var tracks = [];
      if (picked.video) tracks.push(picked.video);
      if (picked.audio) tracks.push(picked.audio);
      page.picked = picked;

      page.init = {};
      for (var i = 0; i < tracks.length; i++) {
        page.init[tracks[i].streamKey] = await fetchFragment(id, tracks[i].streamKey, 0);
      }

      var ms = new MediaSource();
      page.ms = ms;
      page.url = URL.createObjectURL(ms);
      page.video.src = page.url;
      await once(ms, 'sourceopen', 8000);

      page.buffers = tracks.map(function (track) {
        return createBuffer(ms, track, page.init[track.streamKey]);
      });
      page.ready = true;

      try { page.resumeMs = Number(bridge.getResume(id)) || 0; } catch (e) { page.resumeMs = 0; }

      page.pumping = Promise.all(page.buffers.map(function (buffer) {
        return pump(page, buffer);
      })).then(function () {
        finalize(page);
      }).catch(function (error) {
        page.showError(error && error.message ? error.message : String(error));
      });
    })();

    page.loading.then(function () {
      page.spinner.classList.remove('on');
    }).catch(function (error) {
      page.spinner.classList.remove('on');
      page.showError(error && error.message ? error.message : String(error));
    });

    return page.loading;
  }

  function unload(page) {
    page.destroyed = true;
    try { page.video.pause(); } catch (e) { /* ignore */ }
    try {
      page.video.removeAttribute('src');
      page.video.load();
    } catch (e) { /* ignore */ }
    if (page.url) {
      try { URL.revokeObjectURL(page.url); } catch (e) { /* ignore */ }
      page.url = null;
    }
    page.ms = null;
    page.buffers = [];
    page.ready = false;
    page.loading = null;
    page.init = {};
    page.capturedEnd = 0;
    page.partial = false;
    page.playbackAligned = false;
    clearTimeout(page.chromeTimer);
    page.chromeTimer = null;
    page.spinner.classList.remove('on');
  }

  // ---------------------------------------------------------------- playback

  function play(page) {
    if (!page.ready) return;
    var video = page.video;
    video.muted = globalMuted;
    if (page.rate !== 1) video.playbackRate = page.rate;
    var attempt = video.play();
    if (attempt && attempt.catch) {
      attempt.catch(function (error) {
        // Only a browser autoplay block needs the user; anything else is a
        // codec/decoder problem that retrying will not fix.
        if (error && error.name === 'NotAllowedError') {
          page.needsTap = true;
          page.bigPlay.hidden = false;
          toast(page, 'Tap play to start');
        }
      });
    }
    updateSoundUi(page);
    if (!page.video.paused) showChrome(page, true);
  }

  function pause(page) {
    try { page.video.pause(); } catch (e) { /* ignore */ }
    showChrome(page, false);
    saveProgress(page);
  }

  function saveProgress(page) {
    if (!bridge || !page.entry || !bridge.saveResume) return;
    page.lastSave = Date.now();
    try {
      bridge.saveResume(page.entry.videoId, Math.round((page.video.currentTime || 0) * 1000));
    } catch (e) { /* ignore */ }
  }

  /**
   * Puts the playhead where there is actually data: the saved position when it
   * falls inside the cached window, otherwise the first cached frame. Without
   * this a partial capture of a long video just sat at 0:00 forever.
   */
  function alignPosition(page) {
    if (page.playbackAligned) return;
    var window = bufferedRange(page);
    if (window.end <= 0) return; // nothing appended yet; progress/canplay retries

    var target = 0;
    var resumed = false;
    var resume = page.resumeMs > 0 ? page.resumeMs / 1000 : 0;
    if (resume >= window.start + 1 && resume < window.end - 0.5) {
      target = resume;
      resumed = true;
    } else if (window.start > 1.5) {
      target = window.start + 0.05;
    }

    if (target <= 0) {
      page.playbackAligned = true;
      return;
    }
    if (Math.abs((page.video.currentTime || 0) - target) < 0.6) {
      page.playbackAligned = true;
      return;
    }
    try {
      page.video.currentTime = target;
      page.playbackAligned = true;
      if (resumed) {
        toast(page, 'Resumed at ' + YTC.fmtTime(target));
      } else {
        toast(page, 'Cached part starts at ' + YTC.fmtTime(target));
      }
    } catch (e) { /* the next progress event retries */ }
  }

  // -------------------------------------------------------------- chrome fade

  /**
   * Real YouTube fades its title, author and action rail away while the video
   * plays. Keeping that overlay on screen forever covered the bottom of every
   * video and made the player feel cluttered.
   */
  function showChrome(page, autoHide) {
    page.el.classList.remove('ui-hidden');
    clearTimeout(page.chromeTimer);
    page.chromeTimer = null;
    if (autoHide && page.ready && !page.video.paused) {
      page.chromeTimer = setTimeout(function () {
        page.chromeTimer = null;
        if (!page.destroyed && !page.video.paused) page.el.classList.add('ui-hidden');
      }, 2600);
    }
  }


  function toggleSound(page) {
    globalMuted = !globalMuted;
    try { localStorage.setItem('ytc.muted', globalMuted ? '1' : '0'); } catch (e) { /* ignore */ }
    pages.forEach(function (other) {
      try { other.video.muted = globalMuted; } catch (e) { /* ignore */ }
      updateSoundUi(other);
    });
    toast(page, globalMuted ? 'Sound off' : 'Sound on');
  }

  function togglePlayback(page) {
    if (page.video.paused) {
      page.needsTap = false;
      play(page);
    } else {
      pause(page);
    }
  }

  /**
   * Seeking over the real duration lands inside the cached window instead of
   * on a gap MSE cannot fill: the bar shows the whole video, playback stays in
   * the part that exists on disk.
   */
  function seekTo(page, target) {
    var window = bufferedRange(page);
    var total = durationOf(page);
    var requested = Math.max(0, target);
    if (window.end > 0) {
      var low = window.start > 0.5 ? window.start : 0;
      var high = Math.max(low, window.end - 0.25);
      if (requested < low || requested > high) {
        requested = Math.min(high, Math.max(low, requested));
        toast(page, 'Cached ' + YTC.fmtTime(low) + '\u2013' + YTC.fmtTime(window.end) +
          (total > 0 ? ' of ' + YTC.fmtTime(total) : ''));
      }
    }
    try { page.video.currentTime = requested; } catch (e) { /* ignore */ }
    showChrome(page, true);
  }

  function updateTimes(page) {
    var video = page.video;
    var duration = durationOf(page);
    var current = video.currentTime || 0;
    var ratio = duration > 0 ? Math.max(0, Math.min(1, current / duration)) : 0;
    if (!page.dragging) page.seek.value = String(Math.round(ratio * 1000));
    page.t1.textContent = YTC.fmtTime(current);
    page.t2.textContent = duration > 0 ? '-' + YTC.fmtTime(Math.max(0, duration - current)) : '--:--';
    page.fill.style.width = (ratio * 100) + '%';

    // The grey band is the cached window itself, so a mid-video capture shows
    // as a segment part-way along the real timeline rather than "everything so
    // far" starting at 0:00.
    var window = bufferedRange(page);
    if (duration > 0 && window.end > 0) {
      var left = Math.max(0, Math.min(1, window.start / duration));
      var right = Math.max(left, Math.min(1, window.end / duration));
      page.buf.style.left = (left * 100) + '%';
      page.buf.style.width = ((right - left) * 100) + '%';
    } else {
      page.buf.style.left = '0%';
      page.buf.style.width = '0%';
    }
    if (window.end > 0 && (!page.capturedEnd || window.end > page.capturedEnd + 0.1)) {
      page.capturedEnd = window.end;
      updatePartialChip(page);
    }
    page.controls.classList.toggle('paused', video.paused);
  }

  // -------------------------------------------------------------------- pages

  function createPage(entry, index) {
    var section = document.createElement('section');
    section.className = 'page';
    section.dataset.id = entry.videoId;
    section.innerHTML =
      '<div class="stage">' +
        '<video class="vid" playsinline webkit-playsinline preload="auto"></video>' +
        '<div class="shade"></div>' +
        '<div class="spinner"><i></i></div>' +
        '<div class="topbar">' +
          '<button class="iconbtn back" type="button" aria-label="Back">' + icon('back') + '</button>' +
          '<div class="chips"><span class="chip offline">Offline</span>' +
            '<span class="chip partial" hidden></span>' +
            '<span class="chip index"></span></div>' +
        '</div>' +
        '<div class="ratebadge" hidden>2x</div>' +
        '<div class="tapzones"><div class="zone left"><span class="tapflash">' + icon('back10') +
          '</span></div><div class="zone right"><span class="tapflash">' + icon('fwd10') +
          '</span></div></div>' +
        '<button class="bigplay" type="button" aria-label="Play" hidden>' + icon('play') + '</button>' +
        '<div class="errbox" hidden><h3>Cannot play this capture</h3><p></p>' +
          '<button class="retry" type="button">Retry</button></div>' +
        '<div class="controls">' +
          '<div class="bottom">' +
            '<h1 class="title"></h1>' +
            '<div class="author"></div>' +
            '<p class="desc"></p>' +
            '<div class="seekrow">' +
              '<span class="t1">0:00</span>' +
              '<div class="seekwrap"><div class="bar"><div class="buf"></div><div class="fill"></div></div>' +
                '<input class="seek" type="range" min="0" max="1000" value="0" step="1" aria-label="Seek"></div>' +
              '<span class="t2">--:--</span></div>' +
          '</div>' +
          '<div class="rail">' +
            '<button class="railbtn sound" type="button"><span class="bubble">' + icon('soundOff') +
              '</span><span class="label">Sound</span></button>' +
            '<button class="railbtn copy" type="button"><span class="bubble">' + icon('link') +
              '</span><span class="label">Link</span></button>' +
            '<button class="railbtn audio" type="button"><span class="bubble">' + icon('audio') +
              '</span><span class="label">Audio</span></button>' +
            '<button class="railbtn video" type="button"><span class="bubble">' + icon('video') +
              '</span><span class="label">Export</span></button>' +
            '<button class="railbtn del" type="button"><span class="bubble">' + icon('trash') +
              '</span><span class="label">Delete</span></button>' +
          '</div>' +
        '</div>' +
        '<button class="soundhint" type="button" hidden>Sound on</button>' +
        '<div class="toast"></div>' +
      '</div>';

    var page = {
      entry: entry,
      index: index,
      el: section,
      video: section.querySelector('.vid'),
      spinner: section.querySelector('.spinner'),
      bigPlay: section.querySelector('.bigplay'),
      errbox: section.querySelector('.errbox'),
      errText: section.querySelector('.errbox p'),
      seek: section.querySelector('.seek'),
      fill: section.querySelector('.bar .fill'),
      buf: section.querySelector('.bar .buf'),
      t1: section.querySelector('.t1'),
      t2: section.querySelector('.t2'),
      soundHint: section.querySelector('.soundhint'),
      railSound: section.querySelector('.railbtn.sound'),
      toastEl: section.querySelector('.toast'),
      descEl: section.querySelector('.desc'),
      indexChip: section.querySelector('.chip.index'),
      partialChip: section.querySelector('.chip.partial'),
      controls: section.querySelector('.controls'),
      rateBadge: section.querySelector('.ratebadge'),
      zones: section.querySelectorAll('.zone'),
      destroyed: false,
      ready: false,
      loading: null,
      active: false,
      ms: null,
      url: null,
      buffers: [],
      init: {},
      picked: null,
      meta: entry,
      resumeMs: 0,
      playbackAligned: false,
      capturedEnd: 0,
      partial: false,
      needsTap: false,
      rate: 1,
      lastSave: 0,
      dragging: false,
      toastTimer: null,
      hintTimer: null,
      tapTimer: null,
      pressTimer: null,
      chromeTimer: null,
      flashTimer: null
    };

    page.showError = function (message) {
      page.spinner.classList.remove('on');
      page.errText.textContent = message;
      page.errbox.hidden = false;
      page.bigPlay.hidden = true;
    };

    page.titleEl = section.querySelector('.title');
    page.authorEl = section.querySelector('.author');

    page.titleEl.textContent = entry.title || 'Untitled video';
    var meta = [
      entry.author || 'YouTube',
      entry.lengthSeconds ? YTC.fmtTime(entry.lengthSeconds) : '',
      entry.sizeBytes ? fmtSize(entry.sizeBytes) : ''
    ].filter(Boolean);
    page.authorEl.textContent = meta.join('  \u00b7  ');
    page.indexChip.textContent = (index + 1) + ' / ' + entries.length;

    if (entry.description) {
      page.descEl.textContent = entry.description;
      page.descEl.addEventListener('click', function (event) {
        event.stopPropagation();
        page.descEl.classList.toggle('open');
      });
    } else {
      page.descEl.hidden = true;
    }

    section.querySelector('.back').addEventListener('click', function (event) {
      event.stopPropagation();
      if (bridge && bridge.close) bridge.close();
    });
    section.querySelector('.copy').addEventListener('click', function () {
      if (!bridge) return;
      if (bridge.copyLink) bridge.copyLink(entry.sourceUrl || entry.videoId);
      toast(page, 'Link copied');
    });
    section.querySelector('.audio').addEventListener('click', function () {
      if (!bridge || !bridge.export) return;
      bridge.export(entry.videoId, 'audio');
      toast(page, 'Exporting audio\u2026');
    });
    section.querySelector('.video').addEventListener('click', function () {
      if (!bridge || !bridge.export) return;
      bridge.export(entry.videoId, 'video');
      toast(page, 'Exporting with sound\u2026');
    });
    section.querySelector('.del').addEventListener('click', function () {
      if (bridge && bridge.deleteVideo) bridge.deleteVideo(entry.videoId);
    });
    section.querySelector('.retry').addEventListener('click', function () {
      unload(page);
      activate(page);
    });
    page.railSound.addEventListener('click', function () { toggleSound(page); });
    page.soundHint.addEventListener('click', function () { toggleSound(page); });
    page.bigPlay.addEventListener('click', function () {
      page.needsTap = false;
      load(page).then(function () { play(page); });
    });

    page.video.addEventListener('loadedmetadata', function () {
      updateTimes(page);
      alignPosition(page);
    });
    page.video.addEventListener('durationchange', function () { updateTimes(page); });
    page.video.addEventListener('timeupdate', function () {
      updateTimes(page);
      if (Date.now() - page.lastSave > 5000) saveProgress(page);
    });
    page.video.addEventListener('progress', function () {
      updateTimes(page);
      alignPosition(page);
    });
    page.video.addEventListener('playing', function () {
      page.spinner.classList.remove('on');
      page.bigPlay.hidden = true;
      page.needsTap = false;
      showChrome(page, true);
    });
    page.video.addEventListener('waiting', function () {
      if (page.buffers.some(function (b) { return !b.done; })) page.spinner.classList.add('on');
    });
    page.video.addEventListener('pause', function () {
      if (page.active && page.ready) page.bigPlay.hidden = false;
      page.controls.classList.add('paused');
      showChrome(page, false);
      saveProgress(page);
    });
    page.video.addEventListener('ended', function () {
      page.bigPlay.hidden = false;
      showChrome(page, false);
      if (page.partial) toast(page, 'End of the cached part');
    });
    page.video.addEventListener('canplay', function () { alignPosition(page); });
    page.video.addEventListener('error', function () {
      if (page.destroyed) return;
      page.showError('Playback failed on this device (code ' +
        (page.video.error && page.video.error.code ? page.video.error.code : '?') + ').');
    });

    page.seek.addEventListener('input', function () {
      page.dragging = true;
      showChrome(page, false);
      var duration = durationOf(page);
      if (duration > 0) {
        var ratio = Number(page.seek.value) / 1000;
        page.t1.textContent = YTC.fmtTime(ratio * duration);
        page.t2.textContent = '-' + YTC.fmtTime(duration - ratio * duration);
        page.fill.style.width = (ratio * 100) + '%';
      }
    });
    page.seek.addEventListener('change', function () {
      page.dragging = false;
      var duration = durationOf(page);
      if (duration <= 0) return;
      seekTo(page, (Number(page.seek.value) / 1000) * duration);
    });

    // Tap to pause, double tap to seek, long press for 2x — all on the stage
    // so the rail buttons stay independent.
    page.zones.forEach(function (zone, zoneIndex) {
      var downAt = 0;
      var downX = 0;
      var downY = 0;

      function cancelPress() {
        clearTimeout(page.pressTimer);
        page.pressTimer = null;
        if (page.rate !== 1) {
          page.rate = 1;
          page.video.playbackRate = 1;
          page.rateBadge.hidden = true;
        }
      }

      // Same circular flash the real app gives on a double tap.
      function flash() {
        var el = zone.querySelector('.tapflash');
        if (!el) return;
        el.classList.add('on');
        clearTimeout(page.flashTimer);
        page.flashTimer = setTimeout(function () { el.classList.remove('on'); }, 420);
      }

      zone.addEventListener('pointerdown', function (event) {
        if (!page.ready) return;
        downAt = Date.now();
        downX = event.clientX;
        downY = event.clientY;
        clearTimeout(page.pressTimer);
        page.pressTimer = setTimeout(function () {
          page.rate = 2;
          page.video.playbackRate = 2;
          page.rateBadge.hidden = false;
          play(page);
        }, 420);
      });
      zone.addEventListener('pointermove', function (event) {
        if (!page.pressTimer) return;
        if (Math.abs(event.clientX - downX) > 12 || Math.abs(event.clientY - downY) > 12) {
          cancelPress();
        }
      });
      zone.addEventListener('pointerup', cancelPress);
      zone.addEventListener('pointercancel', cancelPress);
      zone.addEventListener('pointerleave', cancelPress);

      zone.addEventListener('click', function () {
        if (!page.ready) return;
        if (Date.now() - downAt > 380) return;
        if (page.needsTap) {
          page.needsTap = false;
          play(page);
          return;
        }
        if (page.tapTimer) {
          clearTimeout(page.tapTimer);
          page.tapTimer = null;
          flash();
          seekTo(page, (page.video.currentTime || 0) + (zoneIndex === 0 ? -10 : 10));
          toast(page, zoneIndex === 0 ? '\u00ab 10s' : '10s \u00bb');
          return;
        }
        page.tapTimer = setTimeout(function () {
          page.tapTimer = null;
          togglePlayback(page);
        }, 240);
      });
    });

    byElement.set(section, page);
    return page;
  }

  function activate(page) {
    active = page;
    pages.forEach(function (other) {
      if (other === page) return;
      other.active = false;
      try { other.video.pause(); } catch (e) { /* ignore */ }
      if (other.ready) other.bigPlay.hidden = false;
      if (Math.abs(other.index - page.index) > 1) unload(other);
    });

    page.active = true;
    try { localStorage.setItem('ytc.last', page.entry.videoId); } catch (e) { /* ignore */ }
    showChrome(page, false);
    page.spinner.classList.add('on');
    load(page).then(function () {
      if (active !== page) return;
      page.spinner.classList.remove('on');
      play(page);
    }).catch(function () { /* the error panel is already shown */ });
    updateSoundUi(page);

    [page.index - 1, page.index + 1].forEach(function (index) {
      var neighbour = pages[index];
      if (neighbour && !neighbour.ready) {
        fetchJson(neighbour.entry.videoId, 'streams.json').catch(function () { /* ignore */ });
      }
    });
  }

  var startId = '';
  try {
    startId = new URLSearchParams(location.search).get('start') || '';
  } catch (e) { startId = ''; }

  // Reopening the player (with no explicit video) returns to where the user
  // stopped instead of dumping them back at the top of the feed.
  var startIndex = 0;
  if (!startId) {
    try {
      var lastId = localStorage.getItem('ytc.last') || '';
      for (var l = 0; l < entries.length; l++) {
        if (lastId && entries[l].videoId === lastId) { startIndex = l; break; }
      }
    } catch (e) { /* ignore */ }
  }
  for (var i = 0; i < entries.length; i++) {
    if (entries[i].videoId === startId) { startIndex = i; break; }
  }

  pages = entries.map(function (entry, index) {
    var page = createPage(entry, index);
    feed.appendChild(page.el);
    return page;
  });

  var observer = new IntersectionObserver(function (records) {
    records.forEach(function (record) {
      if (!record.isIntersecting || record.intersectionRatio < 0.55) return;
      var page = byElement.get(record.target);
      if (page && page !== active) activate(page);
    });
  }, { root: feed, threshold: [0.55] });

  pages.forEach(function (page) { observer.observe(page.el); });

  requestAnimationFrame(function () {
    if (startIndex > 0 && pages[startIndex]) {
      pages[startIndex].el.scrollIntoView({ block: 'start' });
    }
    if (boot) boot.classList.add('off');
    setTimeout(function () {
      if (boot && boot.parentNode) boot.parentNode.removeChild(boot);
    }, 320);
    // Do not wait for a scroll event: the first page must start immediately.
    if (pages[startIndex]) activate(pages[startIndex]);
  });

  document.addEventListener('visibilitychange', function () {
    if (document.hidden && active) {
      pause(active);
    } else if (!document.hidden && active) {
      play(active);
    }
  });
})();
