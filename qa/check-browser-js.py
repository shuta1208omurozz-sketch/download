#!/usr/bin/env python3
"""Offline JS syntax and synthetic player/ad signals test, if Node.js is installed."""
from pathlib import Path
import subprocess
root = Path(__file__).resolve().parents[1]
src = (root / 'app/src/main/java/jp/example/floatsaver/BrowserMediaScanner.kt').read_text()
script = src.split('val script = """', 1)[1].split('""".trimIndent()', 1)[0]
# Kotlin triple-quoted string leaves backslashes unchanged.
js = r'''
const vm = require('node:vm');
const assert = require('node:assert/strict');
const scanner = __SCANNER__;
const parent = { id: 'player-preroll', className: 'ad-player', getAttribute: () => '', parentElement: null };
function video(src, isAd) {
 return {currentSrc:src, src, paused:false, ended:false, readyState:3, duration:240, currentTime:25,
  id:'', className:'', getAttribute:()=>'', parentElement:isAd?parent:null,
  querySelectorAll:()=>[]};
}
function test(videos) {
 const doc = {baseURI:'https://test.example/watch', querySelectorAll:sel => sel==='video' ? videos : []};
 const results = vm.runInNewContext(scanner, {document:doc, window:{}, URL, Number, performance:{getEntriesByType:()=>[]}});
 return Array.from(results);
}
new vm.Script(scanner);
const ad = test([video('https://cdn.example/ads/preroll.mp4',true)]);
assert.equal(ad.length,1);
assert.equal(ad[0].kind,'playing-video');
assert.equal(ad[0].adMarked,true);
assert.equal(ad[0].duration,240);
const regular = test([video('https://cdn.example/movie.mp4',false)]);
assert.equal(regular.length,1);
assert.equal(regular[0].adMarked,false);
assert.equal(regular[0].elapsed,25);
console.log('browser DOM scanner: syntax and 7 synthetic assertions passed');
'''
script_to_run = js.replace('__SCANNER__', repr(script))
# Python repr can include escaped newlines, but JS string literal with single quotes is legal.
node = root / 'qa/_temp_scanner_test.js'
node.write_text(script_to_run)
try:
    subprocess.run(['node', str(node)], check=True)
finally:
    node.unlink(missing_ok=True)
