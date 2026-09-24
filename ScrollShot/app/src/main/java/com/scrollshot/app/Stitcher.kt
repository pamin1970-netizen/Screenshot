package com.scrollshot.app

import android.graphics.Bitmap

object Stitcher {
    private fun rowHashes(b: Bitmap): LongArray {
        val w = b.width
        val px = IntArray(w)
        val out = LongArray(b.height)
        for (y in 0 until b.height) {
            b.getPixels(px, 0, w, 0, y, w, 1)
            var h = 1125899906842597L
            var x = 0
            while (x < w) { h = 31 * h + px[x]; x += 3 }
            out[y] = h
        }
        return out
    }

    /**
     * Number of rows at the top of [next] that duplicate the bottom of [prev].
     * Returns >= height-2 when nothing moved (end of content), 0 when no match found.
     */
    fun overlap(prev: Bitmap, next: Bitmap): Int {
        val a = rowHashes(prev)
        val b = rowHashes(next)
        val h = minOf(a.size, b.size)
        val minOverlap = 16
        var o = h
        while (o >= minOverlap) {
            val tolerance = o / 50
            var miss = 0
            var i = 0
            while (i < o) {
                if (a[a.size - o + i] != b[i]) { miss++; if (miss > tolerance) break }
                i++
            }
            if (miss <= tolerance) {
                // ignore matches on near-blank regions
                val distinct = HashSet<Long>()
                for (k in 0 until o) { distinct.add(b[k]); if (distinct.size >= 4) break }
                if (distinct.size >= 4) return o
            }
            o--
        }
        return 0
    }
}
