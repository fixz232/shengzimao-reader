package com.shengzimao.reader.mobile.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * 统一书架：小说与漫画共用一套页头与切换条。
 *
 * 原先小说书库和漫库是两套页面，各自带 TopAppBar、统计头、搜索框、一排 chip，
 * 叠起来首屏几乎没有内容。这里把它们收在一个壳里，只保留
 * 「标题 + 分区切换 + 轻量筛选」，其余操作进 ⋮ 菜单。
 */
@Composable
fun BookshelfScreen(
    viewModel: ReaderViewModel,
    navigator: Navigator,
    section: LibrarySection,
    onSectionChange: (LibrarySection) -> Unit,
    onOpenSettings: () -> Unit,
) {
    val state by viewModel.ui.collectAsStateWithLifecycle()
    val mangaState by viewModel.manga.state.collectAsStateWithLifecycle()
    val savedSections = rememberSaveableStateHolder()

    val sections = LibrarySection.entries.map { it.label }
    val booksCount = state.books.size
    val mangaCount = mangaState.books.size
    val subtitle = when (section) {
        LibrarySection.NOVEL -> "$booksCount 本小说"
        LibrarySection.COMIC -> "$mangaCount 部漫画"
    }

    Column(Modifier.fillMaxSize()) {
        AppPageHeader(
            title = "书架",
            subtitle = subtitle,
            actions = {
                IconButton(onClick = onOpenSettings) {
                    Icon(Icons.Filled.Settings, contentDescription = "设置")
                }
            },
        )
        AppSectionTabs(sections, section.ordinal) { onSectionChange(LibrarySection.entries[it]) }
        Box(Modifier.fillMaxSize()) {
          savedSections.SaveableStateProvider(section.name) {
            when (section) {
                LibrarySection.COMIC -> MangaLibraryScreen(viewModel, navigator, embedded = true)
                // 「全部」与「小说」都渲染小说书库：漫画分区用紧凑筛选行，
                // 若要在「全部」里混排两类内容，需要统一卡片模型，后续版本再做。
                LibrarySection.NOVEL -> LibraryScreen(
                    viewModel = viewModel,
                    navigator = navigator,
                    embedded = true,
                    onOpenSettings = onOpenSettings,
                    showSectionChrome = false,
                )
            }
          }
        }
    }
}
