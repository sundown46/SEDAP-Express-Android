package de.visentra.sedapexpress.wire

import org.json.JSONObject
import java.nio.charset.StandardCharsets

/** vectors/golden-vectors.json, read in place from the test classpath. */
internal object Vectors {
    val root: JSONObject by lazy {
        val stream = checkNotNull(Vectors::class.java.classLoader?.getResourceAsStream("golden-vectors.json")) {
            "golden-vectors.json missing on the test classpath"
        }
        JSONObject(stream.use { String(it.readBytes(), StandardCharsets.UTF_8) })
    }
}
