package com.maxrave.language

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * AI 翻译同语言跳过门的检测器回归。误拦（把外文判成目标语言）会让翻译静默失效，
 * 漏放只是白烧一次请求——所以每个判定的期望值都以"高置信度"为准绳。
 */
class ScriptLanguageDetectorTest {
    @Test
    fun `chinese lyrics detect zh`() {
        val text =
            "伤心太平洋\n离开真的残酷吗 或者温柔才是可耻的\n往前一步是黄昏 退后一步是人生\n深深太平洋底深深伤心"
        assertEquals("zh", ScriptLanguageDetector.detect(text))
    }

    @Test
    fun `japanese lyrics with kana detect ja not zh`() {
        // 大量汉字+假名混排（日语歌词常态）——假名优先级必须压过汉字
        val text = "夜に駆ける\n沈むように溶けてゆくように\n二人だけの空が広がる夜に"
        assertEquals("ja", ScriptLanguageDetector.detect(text))
    }

    @Test
    fun `korean lyrics detect ko even with hanja`() {
        val text = "너무 아픈 사랑은 사랑이 아니었음을\n그때는 알았지"
        assertEquals("ko", ScriptLanguageDetector.detect(text))
    }

    @Test
    fun `russian lyrics detect ru`() {
        val text = "Подмосковные вечера\nНе слышны в саду даже шорохи"
        assertEquals("ru", ScriptLanguageDetector.detect(text))
    }

    @Test
    fun `latin lyrics return null`() {
        val text = "Yesterday all my troubles seemed so far away\nNow it looks as though they're here to stay"
        assertNull(ScriptLanguageDetector.detect(text))
    }

    @Test
    fun `latin lyrics quoting a few chinese words stay null`() {
        // 外文歌词里引用三两个汉字不许翻转整个判定（误拦=翻译静默失效）
        val text = "We walked the streets of Shanghai 上海 at midnight\nSomething about the neon lights"
        assertNull(ScriptLanguageDetector.detect(text))
    }

    @Test
    fun `empty and blank return null`() {
        assertNull(ScriptLanguageDetector.detect(""))
        assertNull(ScriptLanguageDetector.detect("   \n  ♫  \n"))
    }
}
