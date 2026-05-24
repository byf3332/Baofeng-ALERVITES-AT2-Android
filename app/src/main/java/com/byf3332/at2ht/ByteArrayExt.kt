package com.byf3332.at2ht

fun ByteArray.containsSubsequence(needle: ByteArray): Boolean {
    if (needle.isEmpty()) return true
    if (size < needle.size) return false
    for (i in 0..(size - needle.size)) {
        var matched = true
        for (j in needle.indices) {
            if (this[i + j] != needle[j]) {
                matched = false
                break
            }
        }
        if (matched) return true
    }
    return false
}
