package com.maxrave.language

/**
 * 歌词脚本的轻量语言检测，只为 AI 翻译的"同语言跳过"门服务（目标语言与歌词同语言时
 * 不发请求，省配额也省等待）。
 *
 * 只输出高置信度结论：假名→ja、谚文→ko、汉字占优→zh、西里尔→ru。拉丁字母等歧义
 * 脚本一律返回 null（不拦截，照常发请求）——这个门的成本模型是"漏放=白烧一次请求，
 * 误拦=功能失效"，所以宁可漏放，不可误拦。
 */
object ScriptLanguageDetector {
    /** 假名（平/片），日语的决定性证据 */
    private const val THRESHOLD = 2

    fun detect(text: String): String? {
        if (text.isBlank()) return null
        var kana = 0
        var hangul = 0
        var han = 0
        var cyrillic = 0
        for (c in text) {
            when (c.code) {
                in 0x3040..0x30FF -> kana++ // Hiragana + Katakana
                in 0xAC00..0xD7AF, in 0x1100..0x11FF -> hangul++ // Hangul syllables + Jamo
                in 0x4E00..0x9FFF, in 0x3400..0x4DBF -> han++ // CJK Unified Ideographs + Ext A
                in 0x0400..0x04FF -> cyrillic++
            }
        }
        // 顺序即优先级：日语歌词几乎必带假名（防纯汉字歌词误判 zh）；韩文歌词的偶见
        // 汉字（Hanja）不许压过谚文。汉字阈值取 4：外文歌词里引用三五汉字不翻车。
        return when {
            kana >= THRESHOLD -> "ja"
            hangul >= THRESHOLD -> "ko"
            han > 4 -> "zh"
            cyrillic >= THRESHOLD -> "ru"
            else -> null
        }
    }
}
