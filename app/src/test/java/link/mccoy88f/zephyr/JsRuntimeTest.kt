package link.mccoy88f.zephyr

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class JsRuntimeTest {
    @Test fun aScriptAsksZephyrForAServiceByName() {
        val script = """
            async function go() {
              var a = await __zephyr("twice", { n: 21 });
              var b;
              try { await __zephyr("nothing", {}); } catch (e) { b = e.message; }
              return { a: a, b: b };
            }
        """
        val out = Js.run(script, "go()", timeoutMs = 5_000, services = mapOf<String, suspend (JSONObject) -> Any?>("twice" to { args -> args.getInt("n") * 2 }))!!
        val o = JSONObject(out)
        assertEquals(42, o.getInt("a"))
        assertEquals("no service nothing", o.getString("b"))
    }

    @Test fun localStorageHasALimit() {
        val script = """
            function go() {
              globalThis.__storeLimit = 40;
              localStorage.setItem("a", "0123456789");
              var full = "";
              try { localStorage.setItem("b", "0123456789012345678901234567890123456789"); } catch (e) { full = e.name; }
              return { full: full, keys: Object.keys(localStorage).join(",") };
            }
        """
        val o = JSONObject(Js.run(script, "go()", timeoutMs = 5_000)!!)
        assertEquals("QuotaExceededError", o.getString("full"))
        assertEquals("a", o.getString("keys"))
    }

    @Test fun requireLoadsAnExtraCommonJsLibrary() {
        val script = "function go() { return require(\"toy\").n; }"
        val out = Js.run(script, "go()", timeoutMs = 5_000, extraModules = mapOf("toy" to "exports.n = 7;"))!!
        assertEquals("7", out)
    }
}
