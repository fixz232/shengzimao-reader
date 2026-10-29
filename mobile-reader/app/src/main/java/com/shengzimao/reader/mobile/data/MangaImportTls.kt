package com.shengzimao.reader.mobile.data

import java.io.IOException
import java.io.EOFException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.URI
import java.security.cert.CertPathValidatorException
import java.security.cert.CertificateException
import javax.net.ssl.SSLException
import javax.net.ssl.SSLPeerUnverifiedException

/** 只处理导入链接的安全连接兼容性；证书失败不能当成协议兼容问题重试。 */
internal object MangaImportTls {
    private fun causes(failure: Throwable): List<Throwable> {
        val result = mutableListOf<Throwable>()
        var next: Throwable? = failure
        while (result.size < 16) {
            val current = next ?: break
            if (result.any { it === current }) break
            result += current
            next = current.cause
        }
        return result
    }

    fun certificateFailure(failure: Throwable) = causes(failure).any {
        it is CertificateException || it is CertPathValidatorException || it is SSLPeerUnverifiedException
    }

    fun canRetry(failure: Throwable): Boolean = !certificateFailure(failure) && causes(failure).any {
        it is SSLException || it is EOFException || it is SocketTimeoutException ||
            (it is SocketException && Regex("connection reset|connection aborted|broken pipe", RegexOption.IGNORE_CASE).containsMatchIn(it.message.orEmpty())) ||
            it.message.orEmpty().contains("Unable to parse TLS packet header", ignoreCase = true) ||
            it.message.orEmpty().contains("unexpected end of stream", ignoreCase = true)
    }

    fun diagnose(url: String, failure: IOException, compatibilityTried: Boolean): IOException {
        val host = runCatching { URI(url).host }.getOrNull().orEmpty()
        val message = when {
            certificateFailure(failure) -> "漫画源站点 $host 的证书校验失败。请检查手机日期和网络，或使用可信的 JSON 文件 / 粘贴导入。"
            compatibilityTried -> "漫画源站点 $host 的连接被中断，TLS 1.2 / HTTP/1.1 兼容重试也未成功。请切换 Wi-Fi / 移动数据后重试，或使用文件 / 粘贴导入 JSON。"
            else -> return failure
        }
        return IOException(message, failure)
    }
}
