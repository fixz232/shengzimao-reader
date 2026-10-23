package com.shengzimao.reader.mobile

import android.app.Application
import android.content.Context
import com.shengzimao.reader.mobile.data.LibraryStore
import com.shengzimao.reader.mobile.data.SettingsRepository
import com.shengzimao.reader.mobile.data.ReaderSettings
import com.shengzimao.reader.mobile.data.ReaderTheme
import com.shengzimao.reader.mobile.data.PageAnimation
import java.io.File

class ReaderApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        instance = this
    }

    companion object {
        lateinit var instance: ReaderApp
            private set
    }
}

class AppContainer(val context: Context) {
    private val files = File(context.filesDir, "library").apply { mkdirs() }
    val store: LibraryStore = LibraryStore(files)
    val settings: SettingsRepository = SettingsRepository(context)

    /** 当前页在下次启动时的定位依据：书籍 + 页码签名 + 页码。 */
    private val progressFile = File(files, "position.json")

    fun readPositionHint(): PositionHint? = runCatching {
        val bytes = progressFile.readText(Charsets.UTF_8)
        val parts = bytes.split('\u0000')
        if (parts.size < 3) null else PositionHint(parts[0], parts[1], parts[2].toIntOrNull() ?: 0)
    }.getOrNull()

    fun savePositionHint(hint: PositionHint) {
        runCatching { progressFile.writeText("${hint.bookId}\u0000${hint.signature}\u0000${hint.page}", Charsets.UTF_8) }
    }

    data class PositionHint(val bookId: String, val signature: String, val page: Int)
}

/** 首次启动时给一个可读的开场说明，避免空书库无从下手。 */
object WelcomeBook {
    const val ID = "welcome-guide-0001"

    val text: String by lazy {
        """
        第 1 章 欢迎使用圣子猫阅读

        圣子猫阅读是一款专注阅读本身的小说阅读器：导入 TXT 或 EPUB，选个舒服的字体，然后一章接一章读下去。
        这里没有后台推送，也没有花哨的社区功能，所有的书、进度和阅读时长都只保存在这台手机上。

        第 2 章 导入书籍

        在书库右上角点「导入」，可以从手机存储里挑选一个或多个 TXT / EPUB 文件。
        TXT 会自动识别编码：UTF-8、UTF-16 以及常见的 GB18030 都能正确显示，不会出现乱码。
        EPUB 会读取目录顺序与书籍信息，正文按段落重新排版。

        第 3 章 阅读与翻页

        点击屏幕左右两侧翻页，中间区域打开菜单；也可以在设置里改为「上下滑动翻页」。
        「音量键翻页」打开后，按音量下键即下一页、上键即上一页，单手拿手机也能读。
        菜单里的目录可以按章节名或序号搜索，长按目录项还能把书签放在当前页。

        第 4 章 排版与主题

        字号、行距、页边距、段落缩进都可以调整，也能在「纸白 / 护眼 / 豆绿 / 夜间 / 纯黑」五种底色之间切换。
        翻页动画提供平移、覆盖、淡入、仿真和关闭五种选择；不想要动画就选「无」。

        第 5 章 进度与书签

        退出阅读器或切到后台时会自动保存进度，下次打开直接回到上次的位置，即使改了字号也会尽量落回同一段文字。
        书签按书籍单独保存，可以在菜单的「书签」里管理。

        第 6 章 在线搜书与追更

        底部切到「在线搜书」，输入书名或「书名 作者」即可检索第三方书源；同一本书会聚合多个书源，可以对比后选一个下载入库。
        首次使用需要滚动读完使用声明并勾选确认，确认记录只保存在本机。
        入库的在线书籍会自动加入追更：源站更新时只追加新章节，旧正文保持不变。

        第 7 章 数据说明

        所有数据保存在应用私有目录：书库索引、正文文件与阅读设置分开存放。
        卸载应用会一并删除这些数据，重要书籍请保留原始文件。祝阅读愉快。
        """.trimIndent()
    }

    val defaultSettings = ReaderSettings(fontSize = 20, lineSpacingPercent = 170, theme = ReaderTheme.PAPER, pageAnimation = PageAnimation.SLIDE)
}
