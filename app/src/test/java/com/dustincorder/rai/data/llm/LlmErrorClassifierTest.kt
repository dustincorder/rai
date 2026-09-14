package com.dustincorder.rai.data.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException

class LlmErrorClassifierTest {
    @Test fun `401 maps to invalid api key message`() {
        assertEquals("Неверный API key или провайдер отклонил авторизацию.", LlmErrorClassifier.userMessage(LlmHttpException(401)))
    }

    @Test fun `403 maps to access denied message`() {
        assertEquals("Доступ запрещён. Проверьте API key и права модели.", LlmErrorClassifier.userMessage(LlmHttpException(403)))
    }

    @Test fun `404 maps to endpoint or model not found`() {
        assertEquals("Endpoint или модель не найдены.", LlmErrorClassifier.userMessage(LlmHttpException(404)))
    }

    @Test fun `429 maps to rate limit message`() {
        assertEquals("Превышен лимит запросов провайдера.", LlmErrorClassifier.userMessage(LlmHttpException(429)))
    }

    @Test fun `500 maps to server error message`() {
        assertEquals("Провайдер вернул ошибку сервера (HTTP 500).", LlmErrorClassifier.userMessage(LlmHttpException(500)))
    }

    @Test fun `dns failure maps to server not found`() {
        assertEquals("Не удалось найти сервер провайдера.", LlmErrorClassifier.userMessage(UnknownHostException("host")))
    }

    @Test fun `connection refused maps to connection failure`() {
        assertEquals("Не удалось подключиться к провайдеру.", LlmErrorClassifier.userMessage(ConnectException("refused")))
    }

    @Test fun `timeout maps to no timely response`() {
        assertEquals("Провайдер не ответил вовремя.", LlmErrorClassifier.userMessage(SocketTimeoutException()))
    }

    @Test fun `tls failure maps to secure connection message`() {
        assertEquals("Не удалось установить защищённое соединение с провайдером.", LlmErrorClassifier.userMessage(SSLHandshakeException("tls")))
    }

    @Test fun `generic io exception maps to connection check message`() {
        assertEquals("Не удалось обратиться к провайдеру. Проверьте соединение.", LlmErrorClassifier.userMessage(IOException("boom")))
    }

    @Test fun `unescaped http message does not leak provider body`() {
        assertEquals(
            "Некорректный запрос к провайдеру: Модель openai/gpt-oss-20b недоступна для этого проекта.",
            LlmErrorClassifier.userMessage(LlmHttpException(400, "Модель openai/gpt-oss-20b недоступна для этого проекта.")),
        )
    }

    @Test fun `provider message is sanitized to plain short text`() {
        assertEquals(
            "upstream failed try later",
            LlmErrorClassifier.sanitizeProviderMessage("<html><body>upstream failed   try later</body></html>"),
        )
        assertNull(LlmErrorClassifier.sanitizeProviderMessage(""))
    }
}