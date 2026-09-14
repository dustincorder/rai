package com.dustincorder.rai.data.llm

import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.cert.CertificateException
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException

object LlmErrorClassifier {
    fun userMessage(error: Throwable): String = when (error) {
        is LlmConfigurationException -> error.message ?: "Проверьте настройки LLM-провайдера."
        is LlmHttpException -> httpMessage(error)
        is UnknownHostException -> "Не удалось найти сервер провайдера."
        is ConnectException -> "Не удалось подключиться к провайдеру."
        is SocketTimeoutException -> "Провайдер не ответил вовремя."
        is SSLHandshakeException -> "Не удалось установить защищённое соединение с провайдером."
        is SSLPeerUnverifiedException -> "Не удалось установить защищённое соединение с провайдером."
        is CertificateException -> "Не удалось установить защищённое соединение с провайдером."
        is IllegalArgumentException -> "Проверьте настройки провайдера."
        is IOException -> "Не удалось обратиться к провайдеру. Проверьте соединение."
        else -> "Не удалось проверить подключение."
    }

    fun sanitizeProviderMessage(message: String): String? {
        val cleaned = message
            .replace(Regex("<[^>]*>"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        return cleaned.take(160).takeIf { it.isNotBlank() }
    }

    private fun httpMessage(error: LlmHttpException): String {
        val base = when (error.statusCode) {
            400 -> "Некорректный запрос к провайдеру."
            401 -> "Неверный API key или провайдер отклонил авторизацию."
            403 -> "Доступ запрещён. Проверьте API key и права модели."
            404 -> "Endpoint или модель не найдены."
            429 -> "Превышен лимит запросов провайдера."
            in 500..599 -> "Провайдер вернул ошибку сервера (HTTP ${error.statusCode})."
            else -> "Провайдер вернул ошибку HTTP ${error.statusCode}."
        }
        val detail = error.providerMessage?.takeIf { it.isNotBlank() } ?: return base
        return "${base.trimEnd(' ', '.')}: $detail"
    }
}