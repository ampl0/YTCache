/*
 * YTCache back-navigation bridge
 * -------------------------------------------------------------------------
 * The Android back button used to close the app while watching YouTube,
 * because m.youtube.com is a single-page app: it navigates with pushState, so
 * WebView.canGoBack() often stays false even though the user is three screens
 * deep. This script runs at document start, counts the app's own pushState
 * entries and exposes window.__ytcGoBack(), which the Activity calls first.
 *
 * It never renders, never blocks and never changes the page's own history
 * handling: it only wraps pushState and listens for popstate in the capture
 * phase.
 */
(function () {
  'use strict';
  if (window.__ytcBackInstalled) return;
  window.__ytcBackInstalled = true;

  var depth = 0;
  var startedAt = location.href;
  var MAX_DEPTH = 64;

  try {
    var push = history.pushState;
    history.pushState = function () {
      var result = push.apply(this, arguments);
      if (depth < MAX_DEPTH) depth++;
      return result;
    };
  } catch (e) { /* history is still usable read-only */ }

  window.addEventListener('popstate', function () {
    if (depth > 0) depth--;
  }, true);

  /**
   * true  -> the page consumed the press (it has somewhere to go back to)
   * false -> the Activity decides: native WebView history, then exit prompt.
   */
  window.__ytcGoBack = function () {
    try {
      if (depth > 0) {
        history.back();
        return true;
      }
      if (history.length > 1 && location.href !== startedAt) {
        history.back();
        return true;
      }
    } catch (e) { /* ignore */ }
    return false;
  };
})();
