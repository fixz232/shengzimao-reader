package com.shengzimao.reader.mobile.data

import java.io.File

/** Keeps platform decoding explicit. A desktop audit must never claim Android rendering passed. */
internal interface MangaImageRuntime {
    fun checkCached(file: File)
    fun verify(file: File)
    fun decode(file: File, reference: String, spec: MangaDecoder.Spec, cover: Boolean)
    fun elapsedMillis(): Long = System.nanoTime() / 1_000_000
}

internal object AndroidMangaImageRuntime : MangaImageRuntime {
    override fun checkCached(file: File) = MangaStore.checkImage(file)
    override fun verify(file: File) = MangaStore.checkReadableImage(file)
    override fun decode(file: File, reference: String, spec: MangaDecoder.Spec, cover: Boolean) = MangaImageDecoder.decode(file, reference, spec, cover)
}
