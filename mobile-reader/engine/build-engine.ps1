param(
    [string]$JavaHome = 'D:/java21',
    [string]$AndroidSdk = "$env:LOCALAPPDATA/Android/Sdk",
    [string]$UpstreamJar = "$PSScriptRoot/../../independent-reader/resources/sonovel/app.jar",
    [string]$AsmJar = "$PSScriptRoot/../.tools/gradle-9.7.1/lib/asm-9.9.jar"
)
$ErrorActionPreference = 'Stop'
$project = Split-Path $PSScriptRoot -Parent
$work = Join-Path $project '.tools/android-engine'
$assets = Join-Path $project 'app/src/main/assets/engine'
foreach ($folder in @($work, "$work/classes", "$work/core-dex", "$work/wrapper-dex")) {
    New-Item -ItemType Directory -Path $folder -Force | Out-Null
}
Add-Type -AssemblyName System.IO.Compression.FileSystem
$source = [System.IO.Compression.ZipFile]::OpenRead((Resolve-Path $UpstreamJar))
try {
    $outputFile = [IO.File]::Create("$work/core.jar")
    $output = [IO.Compression.ZipArchive]::new($outputFile, [IO.Compression.ZipArchiveMode]::Create)
    try {
        foreach ($entry in $source.Entries) {
            if (!$entry.FullName.EndsWith('.class') -or $entry.FullName -match '(^|/)module-info.class$|^META-INF/|^com/caoccao/|^org/jline/nativ/|^com/pcdd/sonovel/utils/(JsCaller|VirtualThreadLimiter|LogUtils|CoverUpdater).class$|^com/pcdd/sonovel/core/ChapterRenderer.class$') { continue }
            $copy = $output.CreateEntry($entry.FullName)
            $input = $entry.Open(); $destination = $copy.Open()
            try { $input.CopyTo($destination) } finally { $input.Dispose(); $destination.Dispose() }
        }
    } finally { $output.Dispose(); $outputFile.Dispose() }
    $resource = $source.GetEntry('application.properties')
    if ($null -eq $resource) { throw 'Missing upstream application.properties' }
    $input = $resource.Open(); $destination = [IO.File]::Create("$assets/application.properties")
    try { $input.CopyTo($destination) } finally { $input.Dispose(); $destination.Dispose() }
} finally { $source.Dispose() }
$sources = Get-ChildItem "$PSScriptRoot/src" -Recurse -Filter '*.java' | Select-Object -ExpandProperty FullName
New-Item -ItemType Directory -Path "$work/build-tools" -Force | Out-Null
if (!(Test-Path $AsmJar)) { throw 'ASM 9 JAR not found; pass -AsmJar pointing to Gradle lib/asm-*.jar' }
& "$JavaHome/bin/javac.exe" -cp $AsmJar -encoding UTF-8 -d "$work/build-tools" "$PSScriptRoot/build-tools/AndroidApiRewriter.java"
if ($LASTEXITCODE -ne 0) { throw 'Android API rewriter compilation failed' }
& "$JavaHome/bin/java.exe" -cp "$work/build-tools;$AsmJar" AndroidApiRewriter "$work/core.jar" "$work/core-android.jar"
if ($LASTEXITCODE -ne 0) { throw 'Android executor API rewrite failed' }
& "$JavaHome/bin/javac.exe" -source 17 -target 17 -encoding UTF-8 -cp $UpstreamJar -d "$work/classes" @sources
if ($LASTEXITCODE -ne 0) { throw 'Wrapper Java compilation failed' }
& "$JavaHome/bin/jar.exe" --create --file "$work/wrapper.jar" -C "$work/classes" .
if ($LASTEXITCODE -ne 0) { throw 'Wrapper JAR creation failed' }
$d8 = "$AndroidSdk/build-tools/36.0.0/d8.bat"
$platform = "$AndroidSdk/platforms/android-37.0/android.jar"
if (!(Test-Path $platform)) { $platform = "$AndroidSdk/platforms/android-36/android.jar" }
if (!(Test-Path $platform)) { throw 'Android SDK platform JAR not found' }
& $d8 --release --min-api 26 --lib $platform --classpath "$work/wrapper.jar" --output "$work/core-dex" "$work/core-android.jar"
if ($LASTEXITCODE -ne 0) { throw 'Core dex conversion failed' }
& $d8 --release --min-api 26 --lib $platform --classpath $UpstreamJar --output "$work/wrapper-dex" "$work/wrapper.jar"
if ($LASTEXITCODE -ne 0) { throw 'Wrapper dex conversion failed' }
if (!(Test-Path "$work/core-dex/classes2.dex") -or (Test-Path "$work/core-dex/classes3.dex")) { throw 'Unexpected core multidex count; update host manifest before packaging' }
Copy-Item -LiteralPath "$work/core-dex/classes.dex" -Destination "$assets/dex/classes.dex" -Force
Copy-Item -LiteralPath "$work/core-dex/classes2.dex" -Destination "$assets/dex/classes2.dex" -Force
Copy-Item -LiteralPath "$work/wrapper-dex/classes.dex" -Destination "$assets/dex/wrapper.dex" -Force
Get-FileHash "$assets/dex/*.dex" -Algorithm SHA256 | Select-Object Hash,Path
