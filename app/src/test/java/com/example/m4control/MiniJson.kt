package com.example.m4control

/** Minimal JSON reader for test fixtures (org.json isn't available in local unit tests). */
object MiniJson {
    fun parse(s: String): Any? = Reader(s).run { val v = value(); ws(); v }

    private class Reader(val s: String) {
        var i = 0
        fun ws() { while (i < s.length && s[i].isWhitespace()) i++ }
        fun value(): Any? {
            ws()
            return when (s[i]) {
                '{' -> obj()
                '[' -> arr()
                '"' -> str()
                't' -> { i += 4; true }
                'f' -> { i += 5; false }
                'n' -> { i += 4; null }
                else -> num()
            }
        }
        fun obj(): Map<String, Any?> {
            val m = LinkedHashMap<String, Any?>(); i++; ws()
            if (s[i] == '}') { i++; return m }
            while (true) {
                ws(); val k = str(); ws(); i++ // ':'
                m[k] = value(); ws()
                if (s[i++] == '}') return m
            }
        }
        fun arr(): List<Any?> {
            val l = ArrayList<Any?>(); i++; ws()
            if (s[i] == ']') { i++; return l }
            while (true) {
                l += value(); ws()
                if (s[i++] == ']') return l
            }
        }
        fun str(): String {
            val sb = StringBuilder(); i++
            while (s[i] != '"') {
                if (s[i] == '\\') { i++; sb.append(if (s[i] == 'n') '\n' else s[i]) } else sb.append(s[i])
                i++
            }
            i++
            return sb.toString()
        }
        fun num(): Number {
            val start = i
            while (i < s.length && (s[i].isDigit() || s[i] in "-+.eE")) i++
            val t = s.substring(start, i)
            return if (t.contains('.') || t.contains('e')) t.toDouble() else t.toLong()
        }
    }
}
