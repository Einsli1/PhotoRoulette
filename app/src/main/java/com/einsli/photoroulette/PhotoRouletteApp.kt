package com.einsli.photoroulette

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.memory.MemoryCache

/** 全局图片加载器：内存缓存提到 40% 堆上限（默认 25%），配合缩略图降采样，
 *  让宫格预载窗口内及浏览过的缩略图常驻内存，滑回来直接命中、不再反复解码。 */
class PhotoRouletteApp : Application(), ImageLoaderFactory {
    override fun newImageLoader(): ImageLoader =
        ImageLoader.Builder(this)
            .memoryCache {
                // 用 Runtime.maxMemory 计算（感知 largeHeap），而不是 memoryClass。
                MemoryCache.Builder(this)
                    .maxSizeBytes((Runtime.getRuntime().maxMemory() * 0.4).toInt())
                    .build()
            }
            .build()
}
