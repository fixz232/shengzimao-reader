package com.shengzimao.reader.mobile.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.shengzimao.reader.mobile.ReaderApp

/** 单 Activity 场景下共享同一个 ViewModel 实例（书库 / 阅读 / 设置读取同一份状态）。 */
@Composable
fun rememberReaderViewModel(): ReaderViewModel = rememberReaderViewModelImpl()

@Composable
private fun rememberReaderViewModelImpl(): ReaderViewModel =
    remember { ReaderApp.instance.readerViewModel }

private val readerViewModelFactory: ViewModelProvider.Factory = object : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
        ReaderApp.instance.readerViewModel as T
}
