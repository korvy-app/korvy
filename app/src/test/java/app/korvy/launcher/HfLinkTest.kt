package app.korvy.launcher

import app.korvy.launcher.core.HfFile
import app.korvy.launcher.core.HfLink
import org.junit.Assert.assertEquals
import org.junit.Test

class HfLinkTest {
    private fun ok(link: String) = (HfLink.parse(link) as HfLink.Ok).file

    @Test fun readsTheUsualLinkShapes() {
        assertEquals(HfFile("bartowski/Qwen_Qwen3-1.7B-GGUF", "main", "Qwen_Qwen3-1.7B-Q4_K_M.gguf"),
            ok("https://huggingface.co/bartowski/Qwen_Qwen3-1.7B-GGUF/blob/main/Qwen_Qwen3-1.7B-Q4_K_M.gguf"))
        assertEquals(HfFile("ggml-org/gemma-3-1b-it-GGUF", "main", "gemma-3-1b-it-Q4_K_M.gguf"),
            ok("https://huggingface.co/ggml-org/gemma-3-1b-it-GGUF/resolve/main/gemma-3-1b-it-Q4_K_M.gguf?download=true"))
        val sub = ok("https://hf.co/someone/model-GGUF/resolve/v1.0/Q4_K_M/model-Q4_K_M.gguf")
        assertEquals("Q4_K_M/model-Q4_K_M.gguf", sub.path); assertEquals("v1.0", sub.rev); assertEquals("model-Q4_K_M.gguf", sub.fileName)
        assertEquals("a b.gguf", ok("https://huggingface.co/x/y/blob/main/a%20b.gguf").path)
    }

    @Test fun explainsWhatIsWrong() {
        assertEquals(HfLink.Error.NOT_HUGGING_FACE, HfLink.parse("https://example.com/x/y/resolve/main/m.gguf"))
        assertEquals(HfLink.Error.NOT_HUGGING_FACE, HfLink.parse("https://huggingface.co.evil.com/x/y/resolve/main/m.gguf"))
        assertEquals(HfLink.Error.NOT_A_FILE, HfLink.parse("https://huggingface.co/bartowski/Qwen_Qwen3-1.7B-GGUF"))
        assertEquals(HfLink.Error.NOT_GGUF, HfLink.parse("https://huggingface.co/x/y/blob/main/model.safetensors"))
        assertEquals(HfLink.Error.NOT_A_FILE, HfLink.parse("https://huggingface.co/x/y/resolve/main/../secret.gguf"))
    }
}
