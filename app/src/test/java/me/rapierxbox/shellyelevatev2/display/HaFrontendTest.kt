package me.rapierxbox.shellyelevatev2.display

import me.rapierxbox.shellyelevatev2.display.webview.HaFrontend
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit

class HaFrontendTest {

    // what the stargate webview reports
    private val stargateUa = "Mozilla/5.0 (Linux; Android 7.0; Stargate Build/NRD90M; wv) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Version/4.0 Chrome/119.0.6045.194 Mobile Safari/537.36"

    // the android chrome part of the modern bundle check in the ha 2026 index page
    private val haModernAndroid = Regex("""Android.+Chrom(ium|e)/(13\d|1[4-9]\d|[2-9]\d{2}|\d{4,})\.\d+(\.\d+|)""")

    @Test
    fun raisesOldChromeToModernMajor() {
        val ua = HaFrontend.modernUserAgent(stargateUa)
        assertEquals(stargateUa.replace("Chrome/119.0.6045.194", "Chrome/${HaFrontend.MODERN_CHROME_MAJOR}.0.0.0"), ua)
        assertTrue(ua.contains("; wv)"))
    }

    @Test
    fun spoofedAgentPassesHaModernCheck() {
        assertFalse(haModernAndroid.containsMatchIn(stargateUa))
        assertTrue(haModernAndroid.containsMatchIn(HaFrontend.modernUserAgent(stargateUa)))
    }

    @Test
    fun newerChromeStaysUntouched() {
        val newer = stargateUa.replace("Chrome/119.0.6045.194", "Chrome/150.0.1.2")
        assertEquals(newer, HaFrontend.modernUserAgent(newer))
    }

    @Test
    fun agentWithoutChromeStaysUntouched() {
        val firefox = "Mozilla/5.0 (Android 7.0; Mobile; rv:143.0) Gecko/143.0 Firefox/143.0"
        assertEquals(firefox, HaFrontend.modernUserAgent(firefox))
    }

    @Test
    fun onlyTheChromeTokenChanges() {
        val ua = "Foo Chrome/51 Bar"
        assertEquals("Foo Chrome/${HaFrontend.MODERN_CHROME_MAJOR}.0.0.0 Bar", HaFrontend.modernUserAgent(ua))
    }

    // the scripts really run in node with a fake page around them. skipped where node is missing
    private fun runScript(script: String, path: String, stripNative: Boolean): String {
        val node = nodeBinary()
        Assume.assumeTrue("node not installed", node != null)
        val dir = Files.createTempDirectory("hafrontend").toFile()
        try {
            val scriptFile = File(dir, "script.js").apply { writeText(script) }
            val harnessFile = File(dir, "harness.js").apply { writeText(HARNESS) }
            val process = ProcessBuilder(node, harnessFile.path, scriptFile.path, path, stripNative.toString())
                .redirectErrorStream(true)
                .start()
            val out = process.inputStream.bufferedReader().readText().trim()
            assertTrue("node timed out", process.waitFor(30, TimeUnit.SECONDS))
            assertEquals(out, 0, process.exitValue())
            return out
        } finally {
            dir.deleteRecursively()
        }
    }

    private fun nodeBinary(): String? = listOf("node", "node.exe").firstOrNull { cmd ->
        try {
            ProcessBuilder(cmd, "--version").redirectErrorStream(true).start().waitFor(10, TimeUnit.SECONDS)
        } catch (_: Exception) {
            false
        }
    }

    @Test
    fun startScriptOnlyFakesReducedMotionOnLoginPages() {
        for (path in listOf("/auth/authorize", "/onboarding.html")) {
            val out = runScript(HaFrontend.DOCUMENT_START_SCRIPT, path, stripNative = false)
            assertTrue(out, out.contains("\"reduce\":true,\"noPreference\":false"))
        }
        for (path in listOf("/lovelace/0", "/", "/config/auth")) {
            val out = runScript(HaFrontend.DOCUMENT_START_SCRIPT, path, stripNative = false)
            assertTrue(out, out.contains("\"reduce\":false,\"noPreference\":true"))
        }
        // other media queries keep their real answer even on the login page
        val login = runScript(HaFrontend.DOCUMENT_START_SCRIPT, "/auth/authorize", stripNative = false)
        assertTrue(login, login.contains("\"width\":false"))
    }

    @Test
    fun polyfillsFillMissingApis() {
        val out = runScript(HaFrontend.DOCUMENT_START_SCRIPT, "/lovelace/0", stripNative = true)
        assertTrue(out, out.contains("\"canParse\":[true,false,true]"))
        assertTrue(out, out.contains("\"parse\":[null,\"http://h/x\"]"))
        assertTrue(out, out.contains("\"sets\":[[1,2,3],[2,3],[1,3],[1,3],true,false,true,false,true,false]"))
        assertTrue(out, out.contains("\"fromAsync\":[[1,2],[10,21],[\"a\",\"b\"]]"))
        // built ins are not enumerable so for in loops over them stay unchanged
        assertTrue(out, out.contains("\"enumerable\":[]"))
        assertTrue(out, out.contains("\"native\":false"))
    }

    @Test
    fun polyfillsLeaveNativeApisAlone() {
        val out = runScript(HaFrontend.DOCUMENT_START_SCRIPT, "/lovelace/0", stripNative = false)
        assertTrue(out, out.contains("\"native\":true"))
        assertTrue(out, out.contains("\"sets\":[[1,2,3],[2,3],[1,3],[1,3],true,false,true,false,true,false]"))
    }

    @Test
    fun reducedMotionScriptAppliesEverywhere() {
        val out = runScript(HaFrontend.REDUCED_MOTION_SCRIPT, "/lovelace/0", stripNative = false)
        assertTrue(out, out.contains("\"reduce\":true,\"noPreference\":false"))
        assertTrue(out, out.contains("--ha-animation-duration-normal:1ms!important"))
    }

    @Test
    fun reducedMotionScriptCoversEveryHaDuration() {
        val js = HaFrontend.REDUCED_MOTION_SCRIPT
        for (name in listOf("none", "instant", "fast", "normal", "slow")) {
            assertTrue(name, js.contains("--ha-animation-duration-$name:1ms!important"))
        }
    }

    companion object {
        // a page with a native matchMedia that prefers motion and a document that records styles
        // strip removes the apis node has natively so the polyfills run
        private val HARNESS = """
const fs = require('fs');
const [script, path, strip] = process.argv.slice(2);
const setMethods = ['union', 'intersection', 'difference', 'symmetricDifference', 'isSubsetOf', 'isSupersetOf', 'isDisjointFrom'];
const originals = [URL.canParse, URL.parse, Array.fromAsync].concat(setMethods.map(k => Set.prototype[k]));
if (strip === 'true') {
  delete URL.canParse; delete URL.parse; delete Array.fromAsync;
  setMethods.forEach(k => delete Set.prototype[k]);
}
const styles = [];
globalThis.window = globalThis;
globalThis.location = { pathname: path };
globalThis.matchMedia = q => ({ media: String(q), matches: String(q).indexOf('no-preference') >= 0 });
globalThis.document = {
  head: { appendChild: el => styles.push(el.textContent) },
  createElement: () => ({}),
  addEventListener: () => {}
};
(0, eval)(fs.readFileSync(script, 'utf8'));
(async () => {
  const now = [URL.canParse, URL.parse, Array.fromAsync].concat(setMethods.map(k => Set.prototype[k]));
  const out = {
    reduce: matchMedia('(prefers-reduced-motion: reduce)').matches,
    noPreference: matchMedia('(prefers-reduced-motion: no-preference)').matches,
    width: matchMedia('(min-width: 1px)').matches,
    canParse: [URL.canParse('http://a/b'), URL.canParse('nope'), URL.canParse('/x', 'http://h/')],
    parse: [URL.parse('bad'), String(URL.parse('/x', 'http://h/'))],
    sets: [
      [...new Set([1, 2]).union(new Set([2, 3]))],
      [...new Set([1, 2, 3]).intersection(new Set([2, 3, 4]))],
      [...new Set([1, 2, 3]).difference(new Set([2]))],
      [...new Set([1, 2]).symmetricDifference(new Set([2, 3]))],
      new Set([1]).isSubsetOf(new Set([1, 2])), new Set([1, 3]).isSubsetOf(new Set([1, 2])),
      new Set([1, 2]).isSupersetOf(new Set([1])), new Set([1]).isSupersetOf(new Set([1, 2])),
      new Set([1]).isDisjointFrom(new Set([2])), new Set([1]).isDisjointFrom(new Set([1]))
    ],
    fromAsync: [
      await Array.fromAsync([1, Promise.resolve(2)]),
      await Array.fromAsync((async function* () { yield 1; yield 2; })(), (v, i) => v * 10 + i),
      await Array.fromAsync({ length: 2, 0: 'a', 1: 'b' })
    ],
    enumerable: Object.keys(Set.prototype).concat(Object.keys(URL), Object.keys(Array))
      .filter(k => setMethods.concat(['canParse', 'parse', 'fromAsync']).includes(k)),
    native: now.every((f, i) => f === originals[i]),
    styles: styles
  };
  console.log(JSON.stringify(out));
})();
"""
    }
}
