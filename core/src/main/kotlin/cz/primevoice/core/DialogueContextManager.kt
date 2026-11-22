package cz.primevoice.core

data class DialoguePair(val english: String, val czech: String)

/** Local bounded context, no claim that ML Kit supports a conversational context parameter. */
class DialogueContextManager {
    private val recent = ArrayDeque<DialoguePair>()
    @Synchronized fun remember(english: String, czech: String) {
        recent.addLast(DialoguePair(english, czech))
        while (recent.size > 8) recent.removeFirst()
    }
    @Synchronized fun snapshot(): List<DialoguePair> = recent.toList()
    @Synchronized fun clear() = recent.clear()
    @Synchronized fun prepare(text: String, names: List<String>): ProtectedText {
        var normalized = TextRules.clean(text)
            .replace(Regex("(?i)\\bgonna\\b"), "going to")
            .replace(Regex("(?i)\\bwanna\\b"), "want to")
            .replace(Regex("(?i)\\bgotta\\b"), "have to")
        // Reuse the user's canonical spelling when OCR differs only in capitalization.
        val usedNames = names.map { it.trim() }.filter { it.length in 2..40 }.distinct().take(30)
        val markers = mutableMapOf<String, String>()
        usedNames.sortedByDescending { it.length }.forEachIndexed { i, name ->
            val pattern = Regex("(?i)(?<![\\p{L}\\p{N}])${Regex.escape(name)}(?![\\p{L}\\p{N}])")
            if (pattern.containsMatchIn(normalized)) {
                val marker = "ZXQ${100 + i}QXZ"
                normalized = pattern.replace(normalized, marker); markers[marker] = name
            }
        }
        return ProtectedText(normalized, markers)
    }
}
data class ProtectedText(val text: String, val names: Map<String, String>) {
    fun restore(translated: String): String {
        var result = translated
        for ((marker, name) in names) {
            val pattern = Regex(marker.toCharArray().joinToString("\\s*") { Regex.escape(it.toString()) }, RegexOption.IGNORE_CASE)
            check(pattern.containsMatchIn(result)) { "Překladač změnil značku jména. Zkuste odstranit jméno ze slovníku." }
            result = pattern.replace(result, name)
        }
        return TextRules.clean(result)
    }
}
