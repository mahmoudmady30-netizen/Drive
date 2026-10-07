package com.drivevoice.mvp

/** Text normalisation shared by every NLU component. Pure Kotlin, no Android deps. */
object ArabicText {
    private val diacritics = Regex("[\\u064B-\\u0652\\u0640]")
    private val punct = Regex("[؟?!.,،؛;:\"“”()]")
    private val spaces = Regex("\\s+")

    fun digits(s: String): String = buildString {
        for (c in s) append(
            when (c) {
                in '\u0660'..'\u0669' -> '0' + (c - '\u0660')
                in '\u06F0'..'\u06F9' -> '0' + (c - '\u06F0')
                else -> c
            }
        )
    }

    /** Digits->ascii, lowercase, strip diacritics, punctuation -> space. Keeps spelling (ة, أ) for geocoding. */
    fun light(raw: String): String = digits(raw).lowercase()
        .replace(diacritics, "")
        .replace(punct, " ")
        .replace(spaces, " ")
        .trim()

    /** Spelling-insensitive fold used for keyword matching (per character, so tokens stay aligned with [light]). */
    fun fold(s: String): String = s
        .replace('أ', 'ا').replace('إ', 'ا').replace('آ', 'ا')
        .replace('ى', 'ي').replace('ة', 'ه').replace('ؤ', 'و').replace('ئ', 'ي')

    fun normalize(raw: String): String = fold(light(raw))

    /** Remove the wake word only when it is a whole word, never inside another word. */
    fun stripWake(norm: String, wake: String = "فارس"): String {
        val w = normalize(wake)
        val words = norm.split(' ').filter { it.isNotEmpty() }.toMutableList()
        words.removeAll { it == w || it == "يا$w" }
        if (words.size >= 2 && words[0] == "يا" && words.getOrNull(1) == w) { words.removeAt(0); words.removeAt(0) }
        return words.joinToString(" ")
    }

    fun hasWord(norm: String, w: String): Boolean = " $norm ".contains(" ${normalize(w)} ")
    fun hasAny(norm: String, vararg ws: String): Boolean = ws.any { " $norm ".contains(" ${normalize(it)} ") }

    private val numberWords = mapOf(
        "واحد" to 1, "واحده" to 1, "اتنين" to 2, "اثنين" to 2, "تلاته" to 3, "ثلاثه" to 3, "اربعه" to 4, "اربع" to 4,
        "خمسه" to 5, "خمس" to 5, "سته" to 6, "ست" to 6, "سبعه" to 7, "سبع" to 7, "تمانيه" to 8, "ثمانيه" to 8,
        "تسعه" to 9, "تسع" to 9, "عشره" to 10, "عشر" to 10, "حداشر" to 11, "اتناشر" to 12, "تلاتاشر" to 13,
        "خمستاشر" to 15, "خمسطاشر" to 15, "عشرين" to 20, "تلاتين" to 30, "اربعين" to 40, "خمسين" to 50, "ستين" to 60
    )

    fun number(token: String): Int? = token.toIntOrNull() ?: numberWords[normalize(token)]
    fun numberAlternation(): String = (numberWords.keys.sortedByDescending { it.length } + "\\d{1,3}").joinToString("|")
}
