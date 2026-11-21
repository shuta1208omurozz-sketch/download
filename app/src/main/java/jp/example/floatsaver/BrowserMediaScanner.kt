package jp.example.floatsaver

/** Read-only DOM inspection, no script bridge or request modification. */
object BrowserMediaScanner {
    // Always returns an array to evaluateJavascript's JSON callback.
    val script = """
        (function(){
          const items = [], seen = new Set();
          const add = (v, kind, playing, duration, elapsed, adMarked) => {
            if (!v || typeof v !== 'string') return;
            try {
              const u = new URL(v, document.baseURI).href;
              if (!/^https?:\/\//i.test(u)) return;
              const key = u + '|' + kind;
              if (seen.has(key) || items.length >= 220) return;
              seen.add(key);
              items.push({url:u, kind:kind, playing:!!playing,
                duration:Number.isFinite(duration) && duration > 0 ? duration : null,
                elapsed:Number.isFinite(elapsed) && elapsed >= 0 ? elapsed : null,
                adMarked:!!adMarked});
            } catch (e) {}
          };
          const looksLikeAdContainer = (el) => {
            let node = el;
            for (let i=0; node && i<4; i++, node=node.parentElement) {
              const info = [node.id, typeof node.className === 'string' ? node.className : '',
                node.getAttribute('aria-label') || '', node.getAttribute('data-ad-state') || ''].join(' ');
              if (/(^|[\s_-])(ads?|advert|advertisement|preroll|midroll|postroll|adplayer|adplaying|sponsor)(?=$|[\s_-])/i.test(info)) return true;
            }
            return false;
          };
          document.querySelectorAll('iframe[src]').forEach((el, i) => {
            if (i < 12 && (el.clientWidth >= 150 || el.clientHeight >= 100))
              add(el.src, 'iframe', false, null, null, false);
          });
          document.querySelectorAll('video').forEach(el => {
            const playing = !el.paused && !el.ended && el.readyState >= 2;
            const kind = playing ? 'playing-video' : 'video';
            const marked = looksLikeAdContainer(el);
            add(el.currentSrc, kind, playing, el.duration, el.currentTime, marked);
            add(el.src, kind, playing, el.duration, el.currentTime, marked);
            el.querySelectorAll('source').forEach(source =>
              add(source.src, kind, playing, el.duration, el.currentTime, marked));
          });
          document.querySelectorAll('meta[property="og:video"], meta[property="og:video:url"], meta[property="og:video:secure_url"], meta[name="twitter:player:stream"]').forEach(el => add(el.content, 'meta'));
          document.querySelectorAll('img').forEach((el, i) => { if (i < 180) add(el.currentSrc || el.src, 'image'); });
          document.querySelectorAll('a').forEach((el, i) => { if (i < 180) add(el.href, 'link'); });
          if (window.performance && performance.getEntriesByType) {
            performance.getEntriesByType('resource').slice(-100).forEach(el => add(el.name, 'resource'));
          }
          return items;
        })()
    """.trimIndent()
}
