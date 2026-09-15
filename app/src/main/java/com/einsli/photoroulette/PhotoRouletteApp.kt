package com.einsli.photoroulette

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.memory.MemoryCache
import com.einsli.photoroulette.media.GatedVideoFrameDecoder

/** 全局图片加载器（进程唯一；Coil 在首次请求图片时才懒构建本单例）：内存缓存提到
 *  40% 堆上限（默认 25%），配合缩略图降采样，让宫格预载窗口内及浏览过的缩略图常驻
 *  内存，滑回来直接命中、不再反复解码；并注册视频帧解码器——coil-video 2.7.0 不经
 *  ServiceLoader 自注册，缺它视频 content:// URI 解不出来（宫格里视频卡片全空白）。
 *
 *  这里是加载器的唯一配置点：不要再调 Coil.setImageLoader——它会整体覆盖本配置
 *  （两者不合并，后设置者生效），曾在 MainActivity 里覆盖注册视频解码器，导致这里的
 *  40% 内存缓存整体失效。 */
class PhotoRouletteApp : Application(), ImageLoaderFactory {
    /** 全局 DI 容器:数据库/设置/扫描器/仓库的进程唯一持有点,UI 与 worker 层统一从这里取。 */
    val container by lazy { AppContainer(this) }

    override fun newImageLoader(): ImageLoader =
        ImageLoader.Builder(this)
            // GatedVideoFrameDecoder.Factory 内部就是 VideoFrameDecoder.Factory + 并发闸门
            // （见该类注释：几十个视频抽帧撞 mediaserver 名额会导致宫格随机黑格）。别再额外
            // add(VideoFrameDecoder.Factory())——两个都注册时前者先命中，后者成了死代码。
            .components { add(GatedVideoFrameDecoder.Factory()) }
            .memoryCache {
                // 用 Runtime.maxMemory 计算（感知 largeHeap），而不是 memoryClass。
                MemoryCache.Builder(this)
                    .maxSizeBytes((Runtime.getRuntime().maxMemory() * 0.4).toInt())
                    .build()
            }
            .build()
}
