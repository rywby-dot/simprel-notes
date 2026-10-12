package com.simprel.notes

/** A bounded Myers diff maps rendered text back onto the untouched Markdown source. */
data class MarkdownRun(val sourceStart: Int, val sourceEnd: Int, val renderedStart: Int, val renderedEnd: Int, val equal: Boolean)
fun alignMarkdown(source: String, rendered: String): List<MarkdownRun>? {
    val n = source.length; val m = rendered.length
    val limit = minOf(n + m, 1024)
    val offset = limit + 1
    val v = IntArray(2 * limit + 3)
    val trace = mutableListOf<IntArray>()
    var distance = -1
    outer@ for (d in 0..limit) {
        trace.add(v.copyOf())
        for (k in -d..d step 2) {
            var x = if (k == -d || (k != d && v[offset + k - 1] < v[offset + k + 1])) v[offset + k + 1] else v[offset + k - 1] + 1
            var y = x - k
            while (x < n && y < m && source[x] == rendered[y]) { x++; y++ }
            v[offset + k] = x
            if (x >= n && y >= m) { distance = d; break@outer }
        }
    }
    if (distance < 0) return null // Very large edits remain readable as source rather than blocking input.
    val operations = mutableListOf<Char>()
    var x = n; var y = m
    for (d in distance downTo 0) {
        val previous = trace[d]
        val k = x - y
        val previousK = if (k == -d || (k != d && previous[offset + k - 1] < previous[offset + k + 1])) k + 1 else k - 1
        val previousX = previous[offset + previousK]
        val previousY = previousX - previousK
        while (x > previousX && y > previousY) { operations.add('='); x--; y-- }
        if (d > 0) { if (x == previousX) { operations.add('+'); y-- } else { operations.add('-'); x-- } }
    }
    operations.reverse()
    val runs = mutableListOf<MarkdownRun>()
    var a = 0; var b = 0; var i = 0
    while (i < operations.size) {
        val equal = operations[i] == '='
        val startA = a; val startB = b
        while (i < operations.size && (operations[i] == '=') == equal) {
            when (operations[i++]) { '=' -> { a++; b++ }; '-' -> a++; '+' -> b++ }
        }
        runs.add(MarkdownRun(startA, a, startB, b, equal))
    }
    return runs
}

/** Whitespace omitted by Markdown rendering must still occupy space while editing. */
fun editorWhitespace(source: String): BooleanArray {
    val preserved = BooleanArray(source.length)
    var trailing = true
    for (i in source.lastIndex downTo 0) {
        when (source[i]) {
            '\n', '\r' -> { preserved[i] = true; trailing = true }
            ' ', '\t' -> preserved[i] = trailing
            else -> trailing = false
        }
    }
    return preserved
}
