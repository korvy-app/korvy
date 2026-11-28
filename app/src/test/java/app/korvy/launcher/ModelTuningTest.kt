package app.korvy.launcher

import app.korvy.launcher.core.ModelTuning
import org.junit.Assert.assertEquals
import org.junit.Test

class ModelTuningTest {
    @Test fun eachModelKeepsItsOwnSettings() {
        val all = mapOf("gemma4-e4b" to ModelTuning(0.7f, 600, 8192), "custom:x/y/main/a.gguf" to ModelTuning(0.0f, 100, 4096),
            "qwen2.5-1.5b" to ModelTuning())
        val back = ModelTuning.parse(ModelTuning.serialize(all))
        assertEquals(ModelTuning(0.7f, 600, 8192), back["gemma4-e4b"])
        assertEquals(ModelTuning(0.0f, 100, 4096), back["custom:x/y/main/a.gguf"])
        assertEquals(null, back["qwen2.5-1.5b"]) // defaults aren't stored
    }

    @Test fun outOfRangeValuesAreClamped() {
        assertEquals(ModelTuning(1.5f, 1000, 4096), ModelTuning.parse("m|9|99999|1234")["m"])
        assertEquals(emptyMap<String, ModelTuning>(), ModelTuning.parse("garbage\n|0.2|220|4096"))
    }
}
