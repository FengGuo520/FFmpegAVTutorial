package io.ffmpegtutotial.player.internal;


public class NativeInstance {

    static {
        System.loadLibrary("ffmpegavtutorial");
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

    public native String probeMediaFile(long nativePtr, String mediaPath);

    public native void runAvRationalDemo(long nativePtr);

    public native void runAvBufferDemo(long nativePtr);

    public native void runAvPacketDemo(long nativePtr);

    public native void runAvFrameDemo(long nativePtr);

    public native void runFrameQueueDemo(long nativePtr);

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

    public long getNativePtr() {
        return nativePtr;
    }

    public String getInfo() {
        return getInfo(nativePtr);
    }

    public String probeMediaFile(String mediaPath) {
        return probeMediaFile(nativePtr, mediaPath);
    }

    public void runAvRationalDemo() {
        runAvRationalDemo(nativePtr);
    }

    public void runAvBufferDemo() {
        runAvBufferDemo(nativePtr);
    }

    public void runAvPacketDemo() {
        runAvPacketDemo(nativePtr);
    }

    public void runAvFrameDemo() {
        runAvFrameDemo(nativePtr);
    }

    public void runFrameQueueDemo() {
        runFrameQueueDemo(nativePtr);
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

    public String openRtmpPush(
        String outputUrl,
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
            outputUrl,
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

    public int writeRtmpVideoPacket(byte[] data, long ptsUs, int flags) {
        return writeLiveVideoPacket(data, ptsUs, flags);
    }

    public int writeRtmpAudioPacket(byte[] data, long ptsUs, int flags) {
        return writeLiveAudioPacket(data, ptsUs, flags);
    }

    public String closeLiveFlvMuxer() {
        return closeLiveFlvMuxer(nativePtr);
    }

    public String closeRtmpPush() {
        return closeLiveFlvMuxer();
    }

//
//    protected native int nativeSetAppInBackground(boolean isBackground);
//
//    protected native void nativeRelease();
//
//
//    //player
//    protected native long nativeCreatePlayKit();
//
//    protected native void nativeSetPlayerobserver(long nativePtr, NativePlayObserver observer);
//
//    protected native int nativeSetPlayerView(long nativeId, VideoSink playSink);
//
//    protected native int nativeStartPlay(long nativePtr, String url);
//
//    protected native int nativeStopPlay(long nativePtr);
//
//    protected native int nativeIsPlaying(long nativePtr);
//
//    protected native int nativeSetRenderRotation(long nativePtr,int rotation);
//
//    protected native int nativePauseAudio(long nativePtr);
//
//    protected native int nativeResumeAudio(long nativePtr);
//
//    protected native int nativePauseVideo(long nativePtr);
//
//    protected native int nativeResumeVideo(long nativePtr);
//
//    protected native int nativeSetPlayoutVolume(long nativePtr, int volume);
//
//    protected native int nativeSetCacheParams(long nativePtr, float minTime, float maxTime);
//
//    protected native int nativeEnableVolumeEvaluation(long nativePtr, int intervalMs);
//
//    protected native int nativeEnableCustomRendering(long nativePtr, boolean enable, int format, int type);
//
//    protected native int nativeEnableReceiveSeiMessage(long nativePtr, boolean enable, int payloadType);
//
//    protected native void nativeShowDebugView(long nativePtr, boolean isShow);
//
//    protected native int nativeSetPlayMode(long nativePtr,int mode);
//
//    protected native void nativePlayKitRelease(long nativePtr);
//
//    //pusher
//    protected native long nativeCreatePushKit();
//
//    protected native void nativeSetPushObserver(NativePushObserver observer, long nativePtr);
//
//    protected native int nativeSetRenderView(long nativePtr, VideoSink localSink);
//
//    protected native int nativeSetPushRenderRotation(long nativePtr,int rotation);
//
//    protected native int nativeStartCamera(long nativePtr, boolean isFront);
//
//    protected native int nativeStopCamera(long nativePtr);
//
//    protected native int nativeSetEncoderMirror(long nativePtr, boolean mirror);
//
//    protected native int nativeStartMicrophone(long nativePtr);
//
//    protected native int nativeStopMicrophone(long nativePtr);
//
//    protected native int nativeSetBeautyEffect(boolean enable);
//
//    protected native int nativeSetWhitenessLevel(float level);
//
//    protected native int nativeSetBeautyLevel(float level);
//
//    protected native int nativeSetToneLevel(float level);
//
//    protected native int nativeStartVirtualCamera(long nativePtr,int type,byte[] bitmap,int width ,int height);
//
//    protected native int nativeStopVirtualCamera(long nativePtr);
//
//    protected native int nativePausePusherAudio(long nativePtr);
//
//    protected native int nativeResumePusherAudio(long nativePtr);
//
//    protected native int nativePausePusherVideo(long nativePtr);
//
//    protected native int nativeResumePusherVideo(long nativePtr);
//
//    protected native int nativeStartPush(long nativePtr, String url);
//
//    protected native int nativeStopPush(long nativePtr);
//
//    protected native void nativeReleasePusher(long nativePtr);
//
//
//    protected native int nativeIsPushing(long nativePtr);
//
//    protected native int nativeSetVideoQuality(long nativePtr, int videoResolution, int videoResolutionMode,int fps,int bitrate,int minBitrate,int scaleMode);
//
//    protected native int nativeSetAudioQuality(long nativePtr,int mode);
//
//
//    protected native int nativePusherEnableVolumeEvaluation(long nativePtr,int intervalMs);
//
//    //protected native int nativeEnableCustomVideoProcess(boolean var1, ArLiveDef.ArLivePixelFormat var2, ArLiveDef.ArLiveBufferType var3);
//
//    protected native int nativeEnableCustomVideoCapture(long nativePtr,boolean enable);
//
//    protected native int nativeSendCustomVideoFrame(long nativePtr, int pixelFormat, int bufferType, byte[] data, ByteBuffer buffer,int width,int height,int rotation,int stride);
//
//    protected native int nativeEnableCustomAudioCapture(long nativePtr,boolean enable);
//
//    protected native int nativeSendCustomAudioFrame(long nativePtr,int channel,int sampleRate,byte[] data);
//
//    protected native int nativeSendSeiMessage(long nativePtr,int var1, byte[] var2);
//
//
//
//    protected native void switchCamera(boolean front);
//    protected native float getCameraZoomMaxRatio();
//    protected native int setCameraZoomRatio(float var1);
//    protected native boolean isAutoFocusEnabled();
//    protected native int enableCameraAutoFocus(boolean var1);
//    protected native int setCameraFocusPosition(float var1, float var2);
//    protected native boolean enableCameraTorch(boolean enable);
//    protected native void setCameraCapturerParam(int mode,int width,int height);
//    public native void startScreenCapture(long nativePtr);
//    protected native void stopScreenCapture(long nativePtr);

}
