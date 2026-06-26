$ErrorActionPreference = "Stop"

$sourceRoot = "D:\study\GitProject\FFmpegAVTutorial"
$targetRoot = "D:\study\GitProject\AndroidCodec"
$utf8NoBom = New-Object System.Text.UTF8Encoding($false)

$sourceActivity = Join-Path $sourceRoot "app\src\main\java\com\lovelymaple\ffmpegavtutorial\basic\H264ProfileLevelGuideActivity.kt"
$targetActivityDir = Join-Path $targetRoot "app\src\main\java\com\lovelymaple\codec\video"
$targetActivity = Join-Path $targetActivityDir "H264ProfileLevelGuideActivity.kt"
$sourceLayout = Join-Path $sourceRoot "app\src\main\res\layout\activity_h264_profile_level_guide.xml"
$targetLayoutDir = Join-Path $targetRoot "app\src\main\res\layout"
$targetLayout = Join-Path $targetLayoutDir "activity_h264_profile_level_guide.xml"
$manifestPath = Join-Path $targetRoot "app\src\main\AndroidManifest.xml"
$mainActivityPath = Join-Path $targetRoot "app\src\main\java\com\lovelymaple\codec\MainActivity.kt"

New-Item -ItemType Directory -Force -Path $targetActivityDir | Out-Null
New-Item -ItemType Directory -Force -Path $targetLayoutDir | Out-Null

$activityContent = [System.IO.File]::ReadAllText($sourceActivity)
$activityContent = $activityContent.Replace("package com.lovelymaple.ffmpegavtutorial.basic", "package com.lovelymaple.codec.video")
$activityContent = $activityContent.Replace("com.lovelymaple.ffmpegavtutorial", "com.lovelymaple.codec")
[System.IO.File]::WriteAllText($targetActivity, $activityContent, $utf8NoBom)

$layoutContent = [System.IO.File]::ReadAllText($sourceLayout)
$layoutContent = $layoutContent.Replace('tools:context=".basic.H264ProfileLevelGuideActivity"', 'tools:context=".video.H264ProfileLevelGuideActivity"')
[System.IO.File]::WriteAllText($targetLayout, $layoutContent, $utf8NoBom)

$manifestContent = [System.IO.File]::ReadAllText($manifestPath)
if ($manifestContent -notmatch '\.video\.H264ProfileLevelGuideActivity') {
    $manifestContent = $manifestContent.Replace(
        '        <activity
            android:name=".video.H265StreamAnalyzerActivity"
            android:exported="false" />',
@"
        <activity
            android:name=".video.H264ProfileLevelGuideActivity"
            android:exported="false" />
        <activity
            android:name=".video.H265StreamAnalyzerActivity"
            android:exported="false" />
"@
    )
    [System.IO.File]::WriteAllText($manifestPath, $manifestContent, $utf8NoBom)
}

$mainActivityContent = [System.IO.File]::ReadAllText($mainActivityPath)
if ($mainActivityContent -notmatch 'H264ProfileLevelGuideActivity') {
    $mainActivityContent = $mainActivityContent.Replace(
        'import com.lovelymaple.codec.video.H264StreamAnalyzerActivity',
@"
import com.lovelymaple.codec.video.H264ProfileLevelGuideActivity
import com.lovelymaple.codec.video.H264StreamAnalyzerActivity
"@
    )
    $mainActivityContent = $mainActivityContent.Replace(
        '                        HomeDestination.VIDEO_ENCODE ->
                            startActivity(Intent(this, VideoEncodeActivity::class.java))',
@"
                        HomeDestination.VIDEO_ENCODE ->
                            startActivity(Intent(this, VideoEncodeActivity::class.java))

                        HomeDestination.H264_PROFILE_LEVEL_GUIDE ->
                            startActivity(Intent(this, H264ProfileLevelGuideActivity::class.java))
"@
    )
    $mainActivityContent = $mainActivityContent.Replace(
        '    VIDEO_ENCODE,
    H264_ANALYZER,',
@"
    VIDEO_ENCODE,
    H264_PROFILE_LEVEL_GUIDE,
    H264_ANALYZER,
"@
    )
    $mainActivityContent = $mainActivityContent.Replace(
        '                FeatureItem(
                    R.string.feature_h264_encode_title,
                    R.string.feature_h264_encode_summary,
                    HomeDestination.VIDEO_ENCODE
                ),
                FeatureItem(
                    R.string.feature_h264_analyzer_title,',
@"
                FeatureItem(
                    R.string.feature_h264_encode_title,
                    R.string.feature_h264_encode_summary,
                    HomeDestination.VIDEO_ENCODE
                ),
                FeatureItem(
                    R.string.feature_h264_profile_level_title,
                    R.string.feature_h264_profile_level_summary,
                    HomeDestination.H264_PROFILE_LEVEL_GUIDE
                ),
                FeatureItem(
                    R.string.feature_h264_analyzer_title,
"@
    )
    [System.IO.File]::WriteAllText($mainActivityPath, $mainActivityContent, $utf8NoBom)
}
