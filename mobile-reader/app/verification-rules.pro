# Instrumentation lives in another APK. App shrinking cannot see its calls to
# Kotlin test helpers; retain those helpers only in the independent QA variant.
# The production release keeps its existing R8 rules.
-keep class kotlin.** { *; }
-keep class kotlinx.coroutines.BuildersKt { *; }
-keep class kotlinx.coroutines.BuildersKt__BuildersKt { *; }
-keepclassmembers class com.shengzimao.reader.mobile.data.SonovelEngine {
    private java.lang.Class apiClass;
}

# Page fixtures live in the QA app so their implementation is optimized in the
# same R8 invocation as the pages. Only these three entrypoints cross APKs.
-keep class com.shengzimao.reader.mobile.MobileFeatureSmokeKt { public *; }
-keep class com.shengzimao.reader.mobile.DownloadRegressionSmokeKt { public *; }
-keep class com.shengzimao.reader.mobile.ReaderRegressionSmokeKt { public *; }
-keep class com.shengzimao.reader.mobile.ReadingInteractionSmokeKt { public *; }
-keep class com.shengzimao.reader.mobile.MangaLiveSmokeKt { public *; }
-keep class com.shengzimao.reader.mobile.MangaSourcePickerSmokeKt { public *; }
-keep class com.shengzimao.reader.mobile.DiscoverSearchSmokeKt { public *; }
-keep class com.shengzimao.reader.mobile.NovelIndentSmokeKt { public *; }
-keep class com.shengzimao.reader.mobile.NovelIndentReaderSmokeKt { public *; }
-keep class com.shengzimao.reader.mobile.ReadingEnhancementsSmokeKt { public *; }
-keep class com.shengzimao.reader.mobile.CatalogScrollSmokeKt { public *; }
-keep class com.shengzimao.reader.mobile.TypographySettingsSmokeKt { public *; }
-keep class com.shengzimao.reader.mobile.MangaDirectoryRecoverySmokeKt { public *; }
-keep class com.shengzimao.reader.mobile.BaoziDirectoryRecoveryLiveKt { public *; }
-keep class com.shengzimao.reader.mobile.MangaReaderSessionSmokeKt { public *; }
-keep class com.shengzimao.reader.mobile.MangaImportNetworkSmokeKt { public *; }
-keep class com.shengzimao.reader.mobile.NovelPagingListeningSmokeKt { public *; }
-keep class com.shengzimao.reader.mobile.ReportedMangaLoadingLiveKt { public *; }

-keep class com.shengzimao.reader.mobile.MangaReadingUpgradeSmokeKt { public *; }
