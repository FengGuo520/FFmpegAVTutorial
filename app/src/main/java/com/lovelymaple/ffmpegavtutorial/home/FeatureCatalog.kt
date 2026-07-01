package com.lovelymaple.ffmpegavtutorial.home

import androidx.annotation.StringRes
import com.lovelymaple.ffmpegavtutorial.R

data class FeatureSection(
    @param:StringRes val titleRes: Int,
    val items: List<FeatureItem>
)

data class FeatureItem(
    val id: String,
    @param:StringRes val titleRes: Int,
    @param:StringRes val summaryRes: Int,
    val destination: FeatureDestination
)

sealed interface FeatureDestination {
    data object FFmpegInfo : FeatureDestination
    data object AvRationalGuide : FeatureDestination
    data object BufferGuide : FeatureDestination
    data object AvBufferGuide : FeatureDestination
    data object AvPacketGuide : FeatureDestination
    data object AvFrameGuide : FeatureDestination
    data object MovieProber : FeatureDestination
    data object MovieExtract : FeatureDestination
    data object StreamingTopic : FeatureDestination
    data object FlvMux : FeatureDestination
    data object LiveFlvMux : FeatureDestination
    data object RtmpPush : FeatureDestination
    data class Detail(val featureId: String) : FeatureDestination
}

data class FeatureDetailContent(
    @param:StringRes val titleRes: Int,
    @param:StringRes val summaryRes: Int,
    @param:StringRes val overviewRes: Int,
    @param:StringRes val focusRes: Int,
    @param:StringRes val practiceRes: Int
)

object FeatureCatalog {

    const val EXTRA_FEATURE_ID = "feature_id"

    val sections = listOf(
        FeatureSection(
            titleRes = R.string.section_basic,
            items = listOf(
                FeatureItem(
                    id = "opengl_version",
                    titleRes = R.string.feature_opengl_version_title,
                    summaryRes = R.string.feature_opengl_version_summary,
                    destination = FeatureDestination.FFmpegInfo
                ),
                FeatureItem(
                    id = "custom_thread",
                    titleRes = R.string.feature_custom_thread_title,
                    summaryRes = R.string.feature_custom_thread_summary,
                    destination = FeatureDestination.Detail("custom_thread")
                ),
                FeatureItem(
                    id = "av_rational_guide",
                    titleRes = R.string.feature_av_rational_title,
                    summaryRes = R.string.feature_av_rational_summary,
                    destination = FeatureDestination.AvRationalGuide
                ),
                FeatureItem(
                    id = "buffer_guide",
                    titleRes = R.string.feature_buffer_guide_title,
                    summaryRes = R.string.feature_buffer_guide_summary,
                    destination = FeatureDestination.BufferGuide
                ),
                FeatureItem(
                    id = "movie_prober",
                    titleRes = R.string.feature_movie_prober_title,
                    summaryRes = R.string.feature_movie_prober_summary,
                    destination = FeatureDestination.MovieProber
                ),
                FeatureItem(
                    id = "movie_extract",
                    titleRes = R.string.feature_movie_extract_title,
                    summaryRes = R.string.feature_movie_extract_summary,
                    destination = FeatureDestination.MovieExtract
                ),
                FeatureItem(
                    id = "read_packet",
                    titleRes = R.string.feature_read_packet_title,
                    summaryRes = R.string.feature_read_packet_summary,
                    destination = FeatureDestination.Detail("read_packet")
                ),
                FeatureItem(
                    id = "decode_packet",
                    titleRes = R.string.feature_decode_packet_title,
                    summaryRes = R.string.feature_decode_packet_summary,
                    destination = FeatureDestination.Detail("decode_packet")
                ),
                FeatureItem(
                    id = "custom_decoder",
                    titleRes = R.string.feature_custom_decoder_title,
                    summaryRes = R.string.feature_custom_decoder_summary,
                    destination = FeatureDestination.Detail("custom_decoder")
                )
            )
        ),
        FeatureSection(
            titleRes = R.string.section_video_render,
            items = listOf(
                FeatureItem(
                    id = "core_animation",
                    titleRes = R.string.feature_core_animation_title,
                    summaryRes = R.string.feature_core_animation_summary,
                    destination = FeatureDestination.Detail("core_animation")
                ),
                FeatureItem(
                    id = "legacy_opengl",
                    titleRes = R.string.feature_legacy_opengl_title,
                    summaryRes = R.string.feature_legacy_opengl_summary,
                    destination = FeatureDestination.Detail("legacy_opengl")
                ),
                FeatureItem(
                    id = "modern_opengl",
                    titleRes = R.string.feature_modern_opengl_title,
                    summaryRes = R.string.feature_modern_opengl_summary,
                    destination = FeatureDestination.Detail("modern_opengl")
                ),
                FeatureItem(
                    id = "modern_opengl_rect",
                    titleRes = R.string.feature_modern_opengl_rect_title,
                    summaryRes = R.string.feature_modern_opengl_rect_summary,
                    destination = FeatureDestination.Detail("modern_opengl_rect")
                ),
                FeatureItem(
                    id = "metal",
                    titleRes = R.string.feature_metal_title,
                    summaryRes = R.string.feature_metal_summary,
                    destination = FeatureDestination.Detail("metal")
                )
            )
        ),
        FeatureSection(
            titleRes = R.string.section_audio_render,
            items = listOf(
                FeatureItem(
                    id = "audio_unit",
                    titleRes = R.string.feature_audio_unit_title,
                    summaryRes = R.string.feature_audio_unit_summary,
                    destination = FeatureDestination.Detail("audio_unit")
                ),
                FeatureItem(
                    id = "audio_queue",
                    titleRes = R.string.feature_audio_queue_title,
                    summaryRes = R.string.feature_audio_queue_summary,
                    destination = FeatureDestination.Detail("audio_queue")
                )
            )
        ),
        FeatureSection(
            titleRes = R.string.section_mux_container,
            items = listOf(
                FeatureItem(
                    id = "flv_mux",
                    titleRes = R.string.feature_flv_mux_title,
                    summaryRes = R.string.feature_flv_mux_summary,
                    destination = FeatureDestination.FlvMux
                ),
                FeatureItem(
                    id = "live_flv_mux",
                    titleRes = R.string.feature_live_flv_mux_title,
                    summaryRes = R.string.feature_live_flv_mux_summary,
                    destination = FeatureDestination.LiveFlvMux
                ),
                FeatureItem(
                    id = "rtmp_push",
                    titleRes = R.string.feature_rtmp_push_title,
                    summaryRes = R.string.feature_rtmp_push_summary,
                    destination = FeatureDestination.RtmpPush
                )
            )
        ),
        FeatureSection(
            titleRes = R.string.section_streaming_media,
            items = listOf(
                FeatureItem(
                    id = "streaming_topic",
                    titleRes = R.string.feature_streaming_topic_title,
                    summaryRes = R.string.feature_streaming_topic_summary,
                    destination = FeatureDestination.StreamingTopic
                )
            )
        )
    )

    val details = mapOf(
        "custom_thread" to FeatureDetailContent(
            titleRes = R.string.feature_custom_thread_title,
            summaryRes = R.string.feature_custom_thread_summary,
            overviewRes = R.string.detail_custom_thread_overview,
            focusRes = R.string.detail_custom_thread_focus,
            practiceRes = R.string.detail_custom_thread_practice
        ),
        "movie_prober" to FeatureDetailContent(
            titleRes = R.string.feature_movie_prober_title,
            summaryRes = R.string.feature_movie_prober_summary,
            overviewRes = R.string.detail_movie_prober_overview,
            focusRes = R.string.detail_movie_prober_focus,
            practiceRes = R.string.detail_movie_prober_practice
        ),
        "read_packet" to FeatureDetailContent(
            titleRes = R.string.feature_read_packet_title,
            summaryRes = R.string.feature_read_packet_summary,
            overviewRes = R.string.detail_read_packet_overview,
            focusRes = R.string.detail_read_packet_focus,
            practiceRes = R.string.detail_read_packet_practice
        ),
        "decode_packet" to FeatureDetailContent(
            titleRes = R.string.feature_decode_packet_title,
            summaryRes = R.string.feature_decode_packet_summary,
            overviewRes = R.string.detail_decode_packet_overview,
            focusRes = R.string.detail_decode_packet_focus,
            practiceRes = R.string.detail_decode_packet_practice
        ),
        "custom_decoder" to FeatureDetailContent(
            titleRes = R.string.feature_custom_decoder_title,
            summaryRes = R.string.feature_custom_decoder_summary,
            overviewRes = R.string.detail_custom_decoder_overview,
            focusRes = R.string.detail_custom_decoder_focus,
            practiceRes = R.string.detail_custom_decoder_practice
        ),
        "core_animation" to FeatureDetailContent(
            titleRes = R.string.feature_core_animation_title,
            summaryRes = R.string.feature_core_animation_summary,
            overviewRes = R.string.detail_core_animation_overview,
            focusRes = R.string.detail_core_animation_focus,
            practiceRes = R.string.detail_core_animation_practice
        ),
        "legacy_opengl" to FeatureDetailContent(
            titleRes = R.string.feature_legacy_opengl_title,
            summaryRes = R.string.feature_legacy_opengl_summary,
            overviewRes = R.string.detail_legacy_opengl_overview,
            focusRes = R.string.detail_legacy_opengl_focus,
            practiceRes = R.string.detail_legacy_opengl_practice
        ),
        "modern_opengl" to FeatureDetailContent(
            titleRes = R.string.feature_modern_opengl_title,
            summaryRes = R.string.feature_modern_opengl_summary,
            overviewRes = R.string.detail_modern_opengl_overview,
            focusRes = R.string.detail_modern_opengl_focus,
            practiceRes = R.string.detail_modern_opengl_practice
        ),
        "modern_opengl_rect" to FeatureDetailContent(
            titleRes = R.string.feature_modern_opengl_rect_title,
            summaryRes = R.string.feature_modern_opengl_rect_summary,
            overviewRes = R.string.detail_modern_opengl_rect_overview,
            focusRes = R.string.detail_modern_opengl_rect_focus,
            practiceRes = R.string.detail_modern_opengl_rect_practice
        ),
        "metal" to FeatureDetailContent(
            titleRes = R.string.feature_metal_title,
            summaryRes = R.string.feature_metal_summary,
            overviewRes = R.string.detail_metal_overview,
            focusRes = R.string.detail_metal_focus,
            practiceRes = R.string.detail_metal_practice
        ),
        "audio_unit" to FeatureDetailContent(
            titleRes = R.string.feature_audio_unit_title,
            summaryRes = R.string.feature_audio_unit_summary,
            overviewRes = R.string.detail_audio_unit_overview,
            focusRes = R.string.detail_audio_unit_focus,
            practiceRes = R.string.detail_audio_unit_practice
        ),
        "audio_queue" to FeatureDetailContent(
            titleRes = R.string.feature_audio_queue_title,
            summaryRes = R.string.feature_audio_queue_summary,
            overviewRes = R.string.detail_audio_queue_overview,
            focusRes = R.string.detail_audio_queue_focus,
            practiceRes = R.string.detail_audio_queue_practice
        )
    )
}
