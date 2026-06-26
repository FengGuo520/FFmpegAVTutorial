$ErrorActionPreference = "Stop"

$projectRoot = "D:\study\GitProject\AndroidCodec"
$javaRoot = Join-Path $projectRoot "app\src\main\java\com\lovelymaple\codec"
$basicDir = Join-Path $javaRoot "basic"
$videoDir = Join-Path $javaRoot "video"
$manifestPath = Join-Path $projectRoot "app\src\main\AndroidManifest.xml"
$mainActivityPath = Join-Path $javaRoot "MainActivity.kt"

$utf8NoBom = New-Object System.Text.UTF8Encoding($false)

if (-not (Test-Path $basicDir)) {
    throw "basic package not found: $basicDir"
}

New-Item -ItemType Directory -Force -Path $videoDir | Out-Null

$files = Get-ChildItem -Path $basicDir -File
foreach ($file in $files) {
    $sourcePath = $file.FullName
    $targetPath = Join-Path $videoDir $file.Name
    $content = [System.IO.File]::ReadAllText($sourcePath)
    $content = $content.Replace("package com.lovelymaple.codec.basic", "package com.lovelymaple.codec.video")
    $content = $content.Replace("com.lovelymaple.codec.basic.", "com.lovelymaple.codec.video.")
    [System.IO.File]::WriteAllText($targetPath, $content, $utf8NoBom)
}

Remove-Item -LiteralPath $basicDir -Recurse -Force

$manifestContent = [System.IO.File]::ReadAllText($manifestPath)
$manifestContent = $manifestContent.Replace(".basic.", ".video.")
[System.IO.File]::WriteAllText($manifestPath, $manifestContent, $utf8NoBom)

$mainActivityContent = [System.IO.File]::ReadAllText($mainActivityPath)
$mainActivityContent = $mainActivityContent.Replace("com.lovelymaple.codec.basic.", "com.lovelymaple.codec.video.")
[System.IO.File]::WriteAllText($mainActivityPath, $mainActivityContent, $utf8NoBom)
