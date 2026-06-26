package io.ffmpegtutotial.player.internal;

public class NativeInstance {

    static {
        System.loadLibrary("codec");
    }

    private long nativePtr;

    private static NativeInstance nativeInstance;

    public NativeInstance() {
        nativeInstance = this;
        nativePtr = makeNativeInstance(this);
    }

    public static NativeInstance getSharedInstance() {
        return nativeInstance;
    }

    protected native long makeNativeInstance(NativeInstance instance);

    public native String getInfo(long nativePtr);

    public native String analyzeH264Stream(long nativePtr, String filePath);

    public native String muxToFlv(long nativePtr, String videoPath, String audioPath, String outputPath);

    public native String openLiveFlvMuxer(
        long nativePtr,
        String outputPath,
        int width,
        int height,
        int frameRate,
        int videoBitrate,
        byte[] videoCsd0,
        byte[] videoCsd1,
        int sampleRate,
        int channelCount,
        int audioBitrate,
        byte[] audioSpecificConfig
    );

    public native int writeLiveVideoPacket(long nativePtr, byte[] data, long ptsUs, int flags);

    public native int writeLiveAudioPacket(long nativePtr, byte[] data, long ptsUs, int flags);

    public native String closeLiveFlvMuxer(long nativePtr);

    public native String openSoftAacEncoder(
        long nativePtr,
        String outputPath,
        int sampleRate,
        int channelCount,
        int bitrate,
        int profile
    );

    public native int writeSoftAacPcm(long nativePtr, byte[] pcmData, int size);

    public native String closeSoftAacEncoder(long nativePtr);

    public native String openSoftVideoEncoder(
        long nativePtr,
        String outputPath,
        int width,
        int height,
        int frameRate,
        int bitrate,
        String profile,
        int iFrameInterval
    );

    public native int writeSoftVideoFrame(
        long nativePtr,
        byte[] i420Data,
        int width,
        int height,
        long ptsUs
    );

    public native String closeSoftVideoEncoder(long nativePtr);

    public long getNativePtr() {
        return nativePtr;
    }

    public String getInfo() {
        return getInfo(nativePtr);
    }

    public String analyzeH264Stream(String filePath) {
        return analyzeH264Stream(nativePtr, filePath);
    }

    public String muxToFlv(String videoPath, String audioPath, String outputPath) {
        return muxToFlv(nativePtr, videoPath, audioPath, outputPath);
    }

    public String openLiveFlvMuxer(
        String outputPath,
        int width,
        int height,
        int frameRate,
        int videoBitrate,
        byte[] videoCsd0,
        byte[] videoCsd1,
        int sampleRate,
        int channelCount,
        int audioBitrate,
        byte[] audioSpecificConfig
    ) {
        return openLiveFlvMuxer(
            nativePtr,
            outputPath,
            width,
            height,
            frameRate,
            videoBitrate,
            videoCsd0,
            videoCsd1,
            sampleRate,
            channelCount,
            audioBitrate,
            audioSpecificConfig
        );
    }

    public int writeLiveVideoPacket(byte[] data, long ptsUs, int flags) {
        return writeLiveVideoPacket(nativePtr, data, ptsUs, flags);
    }

    public int writeLiveAudioPacket(byte[] data, long ptsUs, int flags) {
        return writeLiveAudioPacket(nativePtr, data, ptsUs, flags);
    }

    public String closeLiveFlvMuxer() {
        return closeLiveFlvMuxer(nativePtr);
    }

    public String openSoftAacEncoder(
        String outputPath,
        int sampleRate,
        int channelCount,
        int bitrate,
        int profile
    ) {
        return openSoftAacEncoder(nativePtr, outputPath, sampleRate, channelCount, bitrate, profile);
    }

    public int writeSoftAacPcm(byte[] pcmData, int size) {
        return writeSoftAacPcm(nativePtr, pcmData, size);
    }

    public String closeSoftAacEncoder() {
        return closeSoftAacEncoder(nativePtr);
    }

    public String openSoftVideoEncoder(
        String outputPath,
        int width,
        int height,
        int frameRate,
        int bitrate,
        String profile,
        int iFrameInterval
    ) {
        return openSoftVideoEncoder(
            nativePtr,
            outputPath,
            width,
            height,
            frameRate,
            bitrate,
            profile,
            iFrameInterval
        );
    }

    public int writeSoftVideoFrame(
        byte[] i420Data,
        int width,
        int height,
        long ptsUs
    ) {
        return writeSoftVideoFrame(nativePtr, i420Data, width, height, ptsUs);
    }

    public String closeSoftVideoEncoder() {
        return closeSoftVideoEncoder(nativePtr);
    }
}
