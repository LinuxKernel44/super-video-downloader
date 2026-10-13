package com.myAllVideoBrowser.data.remote.service

import com.myAllVideoBrowser.data.local.model.Proxy
import com.myAllVideoBrowser.data.local.model.VideoInfoWrapper
import com.myAllVideoBrowser.data.local.room.entity.VideFormatEntityList
import com.myAllVideoBrowser.data.local.room.entity.VideoFormatEntity
import com.myAllVideoBrowser.data.local.room.entity.VideoInfo
import com.myAllVideoBrowser.util.CookieUtils
import com.myAllVideoBrowser.util.proxy_utils.CustomProxyController
import com.myAllVideoBrowser.util.AppLogger
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import com.yausername.youtubedl_android.mapper.VideoFormat
import okhttp3.Request
import org.json.JSONObject
import java.util.*
import kotlin.text.lowercase

interface VideoService {
    fun getVideoInfo(
        url: Request,
        isM3u8: Boolean = false,
        isMpd: Boolean = false,
        isAudioCheck: Boolean
    ): VideoInfoWrapper?
}

open class VideoServiceLocal(
    private val proxyController: CustomProxyController
) : VideoService {
    companion object {
        const val MP4_EXT = "mp4"
        private const val FACEBOOK_HOST = ".facebook."
        private const val COOKIE_HEADER = "Cookie"
        private const val REFERER_HEADER = "Referer"
    }

    override fun getVideoInfo(
        url: Request,
        isM3u8: Boolean,
        isMpd: Boolean,
        isAudioCheck: Boolean
    ): VideoInfoWrapper? {
        AppLogger.d("Getting info url...:  $url  ${url.headers["Cookie"]}")

        var result: VideoInfoWrapper? = null

        try {
            result = handleYoutubeDlUrl(url, isM3u8 || isMpd, isAudioCheck)
        } catch (e: Throwable) {
            AppLogger.d("YoutubeDL Error: $e")
        }

        return result
    }

    private fun handleYoutubeDlUrl(
        url: Request,
        isM3u8OrMpd: Boolean = false,
        isAudioCheck: Boolean
    ): VideoInfoWrapper {
        val originalUrl =
            url.url.toString().replace(Regex("&feature=youtu.be|#bottom-sheet"), "").trim()
        try {
            val json = try {
                runYoutubeDl(url, originalUrl, forceGeneric = false)
            } catch (e: Throwable) {
                AppLogger.d("YoutubeDL default extractors failed, retrying with generic: $e")
                runYoutubeDl(url, originalUrl, forceGeneric = true)
            }

            val videoTitle = json.optString("title", "no title")
            val videoDuration = json.optLong("duration", 0L)
            val videoThumbnail = json.optString("thumbnail", "")
            val videoExt = json.optString("ext", MP4_EXT)
            val isLiveStream = json.optBoolean("is_live")

            val formats = mutableListOf<VideoFormatEntity>()
            val formatsArray = json.optJSONArray("formats")

            if (formatsArray != null) {
                for (i in 0 until formatsArray.length()) {
                    val f = formatsArray.getJSONObject(i)

                    // Extract localization (language)
                    val lang = f.optString("language", "").replace("null", "")
                    val formatNote = f.optString("format_note", "")

                    // Create localization label: "original [en]" or just "[en]"
                    val localizedNote = when {
                        lang.isNotBlank() && !formatNote.isNullOrBlank() -> "$formatNote [$lang]"
                        lang.isNotBlank() -> "[$lang]"
                        else -> formatNote
                    }

                    val vcodec = f.optString("vcodec", "none")
                    val acodec = f.optString("acodec", "none")

                    val entity = VideoFormatEntity(
                        formatId = f.optString("format_id"),
                        format = f.optString("format"),
                        formatNote = localizedNote,
                        url = f.optString("url"),
                        ext = f.optString("ext"),
                        vcodec = vcodec,
                        acodec = acodec,
                        width = f.optInt("width", 0),
                        height = f.optInt("height", 0),
                        fps = f.optInt("fps", 0),
                        asr = f.optDouble("asr", 0.0).toInt(),
                        tbr = f.optDouble("tbr", 0.0).toInt(),
                        abr = f.optDouble("abr", 0.0).toInt(),
                        fileSize = f.optLong("filesize", 0L),
                        duration = videoDuration * 1000,
                        manifestUrl = f.optString("manifest_url"),
                        httpHeaders = url.headers.toMap()
                    )
                    formats.add(entity)
                }
            }

            if (formats.isEmpty() && json.optString("url").isNotBlank()) {
                // direct media link resolved by the generic extractor
                formats.add(
                    VideoFormatEntity(
                        formatId = json.optString("format_id", "0"),
                        format = json.optString("format", ""),
                        formatNote = json.optString("format_note", ""),
                        url = json.optString("url"),
                        ext = videoExt,
                        vcodec = json.optString("vcodec", "unknown"),
                        acodec = json.optString("acodec", "unknown"),
                        width = json.optInt("width", 0),
                        height = json.optInt("height", 0),
                        fps = json.optInt("fps", 0),
                        asr = json.optDouble("asr", 0.0).toInt(),
                        tbr = json.optDouble("tbr", 0.0).toInt(),
                        abr = json.optDouble("abr", 0.0).toInt(),
                        fileSize = json.optLong("filesize", 0L),
                        duration = videoDuration * 1000,
                        manifestUrl = json.optString("manifest_url"),
                        httpHeaders = url.headers.toMap()
                    )
                )
            }

            val filtered = if (originalUrl.contains(FACEBOOK_HOST)) {
                formats.filter {
                    it.formatId?.lowercase(Locale.ROOT)?.contains(Regex("hd|sd")) == true
                }
            } else {
                emptyList()
            }

            val listFormats = VideFormatEntityList(filtered.ifEmpty {
                if (isAudioCheck) {
                    formats
                } else {
                    formats.filter { it.vcodec != "none" || it.acodec == "none" }
                }
            })

            return VideoInfoWrapper(
                VideoInfo(
                    title = videoTitle,
                    formats = listFormats
                ).apply {
                    ext = videoExt
                    thumbnail = videoThumbnail
                    duration = videoDuration * 1000 // Convert to millis
                    this.originalUrl = originalUrl
                    downloadUrls = if (isM3u8OrMpd) emptyList() else listOf(url)
                    isRegularDownload = false
                    isLive = isLiveStream
                }
            )
        } catch (e: Throwable) {
            throw e
        }
    }

    /**
     * Runs yt-dlp for [originalUrl] and returns the first extracted entry that has formats.
     * With [forceGeneric] only the generic extractor is used, which is what lets yt-dlp
     * work on sites it has no dedicated extractor for (embedded players, <video> tags,
     * direct/HLS/DASH links found in the page).
     */
    private fun runYoutubeDl(url: Request, originalUrl: String, forceGeneric: Boolean): JSONObject {
        val request = YoutubeDLRequest(originalUrl)

        request.addOption("--dump-json")
        // pages with several embeds would otherwise yield many JSON lines / playlists
        request.addOption("--no-playlist")
        request.addOption("--no-warnings")
        if (forceGeneric) {
            request.addOption("--force-generic-extractor")
        }

        url.headers.forEach { (name, value) ->
            if (name != COOKIE_HEADER) {
                request.addOption("--add-header", "$name:${value}")
            }
        }
        // sites hosting players on other domains usually require the page as referer
        if (url.header(REFERER_HEADER) == null) {
            request.addOption("--add-header", "$REFERER_HEADER:$originalUrl")
        }

        val currentProxy = proxyController.getCurrentRunningProxy()
        if (currentProxy != Proxy.noProxy()) {
            attachProxyToRequest(request, currentProxy)
        }

        val tmpCookieFile = CookieUtils.addCookiesToRequest(originalUrl, request)
        try {
            val out = YoutubeDL.getInstance().execute(request).out
            return parseFirstEntryWithFormats(out)
                ?: throw IllegalStateException("yt-dlp returned no downloadable formats")
        } finally {
            tmpCookieFile.delete()
        }
    }

    private fun parseFirstEntryWithFormats(out: String): JSONObject? {
        var first: JSONObject? = null
        for (line in out.lineSequence()) {
            val trimmed = line.trim()
            if (!trimmed.startsWith("{")) continue
            val obj = try {
                JSONObject(trimmed)
            } catch (_: Throwable) {
                continue
            }
            val entry = obj.optJSONArray("entries")?.let { entries ->
                (0 until entries.length()).mapNotNull { entries.optJSONObject(it) }
                    .firstOrNull { (it.optJSONArray("formats")?.length() ?: 0) > 0 }
            } ?: obj
            if ((entry.optJSONArray("formats")?.length() ?: 0) > 0) return entry
            if (first == null) first = entry
        }
        // single direct-URL results may carry only a top-level "url"
        return first?.takeIf { it.has("url") }
    }

    private fun attachProxyToRequest(request: YoutubeDLRequest, currentProxy: Proxy) {
        val user = proxyController.getProxyCredentials().first
        val password = proxyController.getProxyCredentials().second
        if (user.isNotEmpty() && password.isNotEmpty()) {
            request.addOption(
                "--proxy", "http://${user}:${password}@${currentProxy.host}:${currentProxy.port}"
            )
        } else {
            request.addOption(
                "--proxy", "${currentProxy.host}:${currentProxy.port}"
            )
        }
    }
}
