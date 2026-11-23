#!/usr/bin/env python3
"""Offline sanity check: XML parsing, resource IDs, source cross-references."""
from pathlib import Path
from xml.etree import ElementTree as ET
import re

root = Path(__file__).resolve().parents[1]
src = root / 'app/src/main'
xmls = sorted(src.rglob('*.xml'))
for path in xmls:
    ET.parse(path)
print(f'{len(xmls)} XML files parsed')
ids = set()
for path in (src / 'res/layout').glob('*.xml'):
    text = path.read_text()
    ids.update(re.findall(r'android:id="@\+id/([A-Za-z_][A-Za-z_0-9]*)"',text))
missing = {}
for path in (src / 'java').rglob('*.kt'):
    refs = set(re.findall(r'R\.id\.([A-Za-z_][A-Za-z_0-9]*)',path.read_text()))
    if refs - ids: missing[path.name] = sorted(refs - ids)
assert not missing, f'Missing view IDs: {missing}'
print(f'{len(ids)} view IDs match Kotlin references')
files = (src / 'java/jp/example/floatsaver')
assert 'markInterrupted' in (files / 'HistoryStore.kt').read_text()
assert 'ArrayDeque<Job>()' in (files / 'DownloadService.kt').read_text()
assert 'BrowserScanSchedule.delayMillis(found.size, recentlyPlaying)' in (files / 'BrowserActivity.kt').read_text()
assert 'scanInFlight' in (files / 'BrowserActivity.kt').read_text()
assert (files / 'BrowserScanSchedule.kt').exists()
assert 'pageGeneration.incrementAndGet()' in (files / 'BrowserActivity.kt').read_text()
assert 'destroyProcessById' in (files / 'DownloadService.kt').read_text()
assert 'container == CONTAINER_ORIGINAL || it.extension.equals(container, true)' in (files / 'MediaEngine.kt').read_text()
assert 'choice.estimatedBytes == null' in (files / 'MainActivity.kt').read_text()
assert 'runCatching { wm.addView(scroll, params) }' in (files / 'OverlayBubbleService.kt').read_text()
version = re.search(r'versionCode\s*=\s*(\d+)', (root / 'app/build.gradle.kts').read_text())
assert version and int(version.group(1)) >= 9
browser = (files / 'BrowserActivity.kt').read_text()
assert 'BrowserMediaScanner.script' in browser
assert 'AdClassification.classify' in browser
assert 'setTitle("広告の可能性があります")' in browser
assert '"playing-video" -> 7' in browser
assert 'source == "playing-video"' in browser
assert (files / 'BrowserMediaScanner.kt').exists()
assert (files / 'AdClassification.kt').exists()
assert 'AdClassification.observe(' in browser
assert 's.observedPlayingSamples >= 2' in (files / 'AdClassification.kt').read_text()
assert 'historyId = history?.let' in (files / 'DownloadService.kt').read_text()
assert 'saveDirectVideo' in (files / 'MediaEngine.kt').read_text()
assert (files / 'DirectMediaHints.kt').exists()
assert 'DirectMediaHints.isCompleteRange(range)' in (files / 'MediaEngine.kt').read_text()
assert 'video-network' in (files / 'BrowserMediaScanner.kt').read_text()
assert 'knownVideo: Boolean = false' in (files / 'MediaEngine.kt').read_text()
assert 'selector.startsWith("direct:")' in (files / 'MediaEngine.kt').read_text()
assert '動画の受信が途中で終了しました' in (files / 'MediaEngine.kt').read_text()
assert 'putExtra("sourceIsVideo", knownVideo)' in (files / 'BrowserActivity.kt').read_text()
assert 'source in setOf("video", "playing-video")' in (files / 'BrowserActivity.kt').read_text()
assert 'BravePageUrlService.latestUrl()' in (files / 'OverlayBubbleService.kt').read_text()
assert 'Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP' in (files / 'OverlayBubbleService.kt').read_text()
assert 'FLAG_WATCH_OUTSIDE_TOUCH' in (files / 'OverlayBubbleService.kt').read_text()
assert 'getBrowserUrlWithoutRedirect()' in (files / 'MainActivity.kt').read_text()
assert 'R.id.panelBackdrop' in (files / 'MainActivity.kt').read_text()
assert 'R.id.panelShell' in (files / 'MainActivity.kt').read_text()
assert 'R.id.resizeRightHandle' in (files / 'MainActivity.kt').read_text()
assert 'R.id.resizeBottomHandle' in (files / 'MainActivity.kt').read_text()
assert 'R.id.resizeCornerHandle' in (files / 'MainActivity.kt').read_text()
assert 'R.id.resizeBottomLeftHandle' in (files / 'MainActivity.kt').read_text()
assert 'connectResizeHandle(R.id.resizeBottomLeftHandle, -1, true)' in (files / 'MainActivity.kt').read_text()
assert 'PanelResizeMath.resized(' in (files / 'MainActivity.kt').read_text()
assert 'R.id.resetPanelSizeButton' in (files / 'MainActivity.kt').read_text()
assert 'R.id.panelMoveHandle' in (files / 'MainActivity.kt').read_text()
assert 'FLAG_NOT_TOUCH_MODAL' in (files / 'MainActivity.kt').read_text()
assert 'FLAG_WATCH_OUTSIDE_TOUCH' in (files / 'MainActivity.kt').read_text()
assert 'PanelResizeMath.moved(' in (files / 'MainActivity.kt').read_text()
assert 'window.attributes = window.attributes.apply' in (files / 'MainActivity.kt').read_text()
assert 'offsetXDp' in (files / 'MainActivity.kt').read_text()
assert 'offsetYDp' in (files / 'MainActivity.kt').read_text()
assert (files / 'PanelResizeMath.kt').exists()
assert 'R.id.fallbackBrowserButton' in (files / 'MainActivity.kt').read_text()
assert 'R.id.versionText' in (files / 'MainActivity.kt').read_text()
assert 'R.id.urlCaptureStatus' in (files / 'MainActivity.kt').read_text()
main_source = (files / 'MainActivity.kt').read_text()
auto_handler = main_source.split('private fun getBrowserUrlWithoutRedirect()', 1)[1].split('private fun updateUrlCaptureStatus()', 1)[0]
assert 'CLIPBOARD_SERVICE' not in auto_handler, 'Automatic URL capture must not fall back to stale clipboard'
overlay_source = (files / 'OverlayBubbleService.kt').read_text()
assert 'putExtra("autoPaste", true)' not in overlay_source, 'Bubble must never auto-reuse clipboard'
assert 'SystemClock.elapsedRealtime() - savedAt in 0L..12_000L' in (files / 'BravePageUrlService.kt').read_text()
assert '900L' in (files / 'OverlayBubbleService.kt').read_text()
assert 'com.android.chrome' in (files / 'BravePageUrlService.kt').read_text()
assert 'networkHint' in (files / 'BrowserActivity.kt').read_text()
assert 'R.id.adBlockSwitch' in browser
assert 'R.id.originalPageButton' in browser
assert 'goBackSafely()' in browser
assert 'Intent.FLAG_ACTIVITY_CLEAR_TOP' not in browser, 'The analyzer must keep the browser on the back stack'
assert 'WebAdBlockRules.shouldBlock' in browser
assert 'BrowserNavigationGuard.shouldBlock' in browser
assert 'request.hasGesture()' in browser
assert 'private fun showQuickSave(candidate: Candidate)' in browser
assert 'DownloadService.start(this, videoUrl' in browser
assert (files / 'BrowserNavigationGuard.kt').exists()
assert 'ByteArrayInputStream' in browser
assert (files / 'WebAdBlockRules.kt').exists()
print('Main features and v0.8 detection, ease-of-use and ad guards present')
