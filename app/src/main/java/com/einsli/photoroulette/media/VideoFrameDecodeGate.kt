package com.einsli.photoroulette.media

import android.os.SystemClock
import android.util.Log
import coil.ImageLoader
import coil.decode.DecodeResult
import coil.decode.Decoder
import coil.decode.VideoFrameDecoder
import coil.fetch.SourceResult
import coil.request.Options
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * 视频抽帧闸门：把「同时抽视频帧」的请求数限死在 [MAX_PARALLELISM] 个，并给抽帧失败兜一次重试。
 *
 * 为什么需要限并发（2026-09-15 真机实测）：coil-video 抽一帧就 new 一个 MediaMetadataRetriever
 * （见 VideoFrameDecoder.decode），而 Coil 默认 decoderDispatcher = Dispatchers.IO（不限并发，
 * 见 DefaultRequestOptions）。回收站一屏 ~28 格几乎全是视频，加上预载窗口，进回收站的一瞬间
 * 会同时发起几十个抽帧请求；HyperOS 的 mediaserver 对并发 MetadataRetriever 客户端有上限，
 * 超出的只能排队，排不到就超时：
 *
 *     D/MediaMetadataRetriever: Metadata retriever server is busy, bp retriever is waiting a signal  ×113
 *     W/MediaMetadataRetriever: Acquire metadata retriever resource timeout!!                         ×53
 *     E/MediaMetadataRetrieverJNI: getFrameAtTime: videoFrame is a NULL pointer                       ×25
 *
 * 抽帧返回 null → VideoFrameDecoder 里 checkNotNull 抛异常 → 这条请求以 ErrorResult 结束。
 * 后果是那一格永远停在 surfaceVariant 占位色（SharedGridImage 里 `if (!thumbReady)` 那一层），
 * 而 Coil 不重试失败的请求、失败结果也不进内存缓存——只有该格被回收再组合（滑走再滑回）
 * 才会重新解码，所以表现为「随机某一格黑着，滑一圈又好了」。限并发后实测超时/空帧/黑格
 * 全部归零（53 → 0、25 → 0、每屏 2~3 格 → 0 格）。
 *
 * 为什么拦在 Decoder 而不是 Interceptor：Decoder 只在**真的需要解码**时被调用（EngineInterceptor
 * 先查内存缓存，命中就直接返回、根本走不到 Decoder）。若用 Interceptor 包住 chain.proceed()，
 * 内存命中的请求也会被卷进信号量队列，排在几十个抽帧后面——预载刚解好的缩略图反而要等几秒
 * 才显示。拦 Decoder 则：缓存命中的不占名额、不排队；只有要碰 MediaMetadataRetriever 的才排队。
 *
 * 为什么重试也放在这里：① 所有视频缩略图路径（宫格 cell、全屏预览、首页封面）一次覆盖；
 * ② 失败点就在这一句 decode 里（系统取帧名额被抢空是瞬时的，隔 400ms 再来基本必成）；
 * ③ 若改在 UI（SharedGridImage 的 onError）重发整条请求，重建 ImageRequest 稍有不慎就会
 * 改到内存缓存 key——而宫格 cell / 预览占位 / 全屏三处的请求参数必须完全一致才能命中同一条
 * 缓存（见 photoThumbRequest 的注释），风险比这里大。只重试一次：文件真损坏/已删除时第二次
 * 同样会失败，不能无限重试。
 */
internal class GatedVideoFrameDecoder private constructor(
    private val delegate: Decoder,
) : Decoder {

    override suspend fun decode(): DecodeResult? {
        return try {
            decodeWithGate()
        } catch (e: CancellationException) {
            // 请求被取消（滑出视口/离开页面）要照常传播，绝不能被当成"失败"吞掉。
            throw e
        } catch (e: Throwable) {
            Log.d(TAG, "video frame decode failed, retrying once: " + e.message)
            // 在闸门外等：重试前先把名额让出去，别让一次等待拖住其余抽帧。
            delay(RETRY_DELAY_MS)
            decodeWithGate()
        }
    }

    private suspend fun decodeWithGate(): DecodeResult? {
        val start = SystemClock.elapsedRealtime()
        return gate.withPermit {
            val waited = SystemClock.elapsedRealtime() - start
            if (waited >= WAIT_LOG_MS) {
                Log.d(TAG, "video frame decode waited " + waited + "ms (concurrency cap " + MAX_PARALLELISM + ")")
            }
            delegate.decode()
        }
    }

    /** 替换 `VideoFrameDecoder.Factory` 注册：非视频（mimeType 不以 video/ 开头）时返回 null，
     *  交给注册表里的下一个 Decoder（图片走 BitmapFactoryDecoder）。
     *  注意 KDoc 里**不能**写 "video/" 加星号的通配写法——`星号+斜杠` 会提前闭合块注释。 */
    class Factory : Decoder.Factory {
        override fun create(result: SourceResult, options: Options, imageLoader: ImageLoader): Decoder? {
            val delegate = VideoFrameDecoder.Factory().create(result, options, imageLoader) ?: return null
            return GatedVideoFrameDecoder(delegate)
        }

        override fun equals(other: Any?) = other is Factory

        override fun hashCode() = javaClass.hashCode()
    }

    private companion object {
        const val TAG = "VideoDecodeGate"

        /** 同时抽帧上限。真机实测：几十个并发时 mediaserver 只肯同时服务个位数，
         *  这里取 3——足够让可见区几秒内铺满，又留足余量给系统里别的取帧者。 */
        const val MAX_PARALLELISM = 3

        /** 排队超过这个时长才打日志（用于诊断系统侧是否又在抢名额；平时不刷屏）。 */
        const val WAIT_LOG_MS = 300L

        /** 抽帧失败后的重试间隔：也让系统侧的取帧名额有机会释放。 */
        const val RETRY_DELAY_MS = 400L

        /** 进程级共享：所有 ImageLoader 实例的视频抽帧都走这一个闸门。 */
        val gate = Semaphore(MAX_PARALLELISM)
    }
}
