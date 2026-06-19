# Live FLV Mux Debug Report

## Background

This project added a real-time FLV generation demo:

- camera preview and capture
- microphone capture
- H.264 video encode
- AAC audio encode
- live mux to `.flv`

Core files involved:

- `app/src/main/java/com/lovelymaple/ffmpegavtutorial/LiveFlvMuxActivity.kt`
- `app/src/main/cpp/AVEngine.cpp`

This document records two consecutive problems found during live FLV generation, along with the root causes and fixes.

## Problem 1

### Symptoms

- `ffplay` could open the generated FLV, but the video frame was frozen and only audio seemed to continue.
- `VLC` could not play the file normally.
- `ffmpeg -i` showed an absurdly large duration.

Typical symptom:

```text
Duration: 243088:51:52.06
```

### Root Cause

In native mux code, the app passed packet timestamps in microseconds, but the FLV output stream finally used a millisecond-style time base such as `1/1000`.

Before the fix, `WriteLivePacket(...)` in `AVEngine.cpp` wrote:

- `packet.pts = ptsUs`
- `packet.dts = ptsUs`
- `packet.duration = durationUs`

This was incorrect because `ptsUs` and `durationUs` were still in microseconds, not in `stream->time_base`.

As a result:

- video and audio timestamps became much larger than expected
- packet order and duration metadata became abnormal
- some players tolerated it poorly, especially `VLC`

### Fix

Rescale input timestamps from microseconds into the actual stream time base before writing the packet.

Key fix point in `AVEngine.cpp`:

```cpp
AVRational inputTimeBase{1, AV_TIME_BASE};
packet.pts = av_rescale_q(ptsUs, inputTimeBase, stream->time_base);
packet.dts = packet.pts;
packet.duration = av_rescale_q(durationUs, inputTimeBase, stream->time_base);
```

### Result After Fix

- `ffplay` playback became basically correct
- but `VLC` still identified the total duration incorrectly

This means the first issue was fixed, but there was still another timestamp-origin problem.

## Problem 2

### Symptoms

After the first fix:

- `ffplay` could play audio and video normally
- `VLC` still showed an abnormal total duration

Typical diagnostics from `ffprobe`:

```text
video start_time = 875723.562000
audio start_time = 0.000000
format duration = 875739.173000
```

### Root Cause

The video encoder used Surface input, and `MediaCodec` output video PTS based on a large absolute clock value.

At the same time:

- audio PTS was generated locally from `0`
- video PTS started from a large absolute timestamp

So even though native code now rescaled timestamps correctly, the muxed FLV still contained:

- audio stream starting near `0`
- video stream starting at a huge timestamp

Some players such as `ffplay` can still play this, but `VLC` is very sensitive to stream start time mismatch and therefore misjudged total duration.

### Fix

Normalize both audio and video packet PTS before passing them into native mux:

- record the first audio PTS
- record the first video PTS
- subtract the first PTS from every later packet in the same stream

This makes both streams start from `0` or near `0`.

Key fix points in `LiveFlvMuxActivity.kt`:

```kotlin
private var firstVideoPtsUs: Long? = null
private var firstAudioPtsUs: Long? = null
```

```kotlin
private fun normalizePtsUs(isVideo: Boolean, ptsUs: Long): Long {
    return if (isVideo) {
        val firstPts = firstVideoPtsUs ?: ptsUs.also { firstVideoPtsUs = it }
        (ptsUs - firstPts).coerceAtLeast(0L)
    } else {
        val firstPts = firstAudioPtsUs ?: ptsUs.also { firstAudioPtsUs = it }
        (ptsUs - firstPts).coerceAtLeast(0L)
    }
}
```

And use normalized PTS before packet enqueue/write:

```kotlin
val normalizedPtsUs = normalizePtsUs(isVideo, ptsUs)
```

Also reset the baseline when a new recording session starts:

```kotlin
firstVideoPtsUs = null
firstAudioPtsUs = null
```

### Result After Fix

- `ffplay` plays normally
- `VLC` also plays normally
- total duration becomes reasonable

## Final Conclusion

These two problems were not the same issue. They happened in sequence:

1. `time_base` conversion was wrong in native mux layer.
2. video PTS origin was inconsistent with audio PTS origin.

Only after fixing both did the generated FLV become broadly compatible across players.

## Practical Lessons

When doing real-time mux with `MediaCodec` + `FFmpeg`, always check these points first:

1. Are input timestamps and stream `time_base` in the same unit before writing packets?
2. Do audio and video start from comparable timeline origins?
3. Is packet duration also rescaled, not just `pts`/`dts`?
4. Are different players reporting very different durations? If yes, inspect `start_time`, `start_pts`, and `duration` with `ffprobe`.

## Recommended Debug Commands

### Basic media info

```bash
ffmpeg -i live_xxx.flv -hide_banner
```

### Full stream and container metadata

```bash
ffprobe -show_format -show_streams live_xxx.flv
```

### First few video packets

```bash
ffprobe -select_streams v:0 -show_packets -read_intervals %+5 live_xxx.flv
```

### First few audio packets

```bash
ffprobe -select_streams a:0 -show_packets -read_intervals %+3 live_xxx.flv
```

## Code References

- `LiveFlvMuxActivity.kt`
  - PTS normalization
  - session baseline reset
  - live packet enqueue/write path
- `AVEngine.cpp`
  - `OpenLiveFlvMuxer(...)`
  - `WriteLivePacket(...)`
  - packet timestamp rescale before `av_interleaved_write_frame(...)`

## Current Stable Strategy

The current stable live FLV path is:

1. Android camera and microphone produce raw data
2. `MediaCodec` encodes H.264 and AAC
3. Java/Kotlin layer normalizes stream PTS to a local timeline
4. native FFmpeg mux layer rescales normalized microsecond timestamps into output stream time base
5. FLV file is written with consistent audio/video timeline

This is the baseline strategy recommended for later expansion to RTMP live push as well.
