package io.github.nisaka520.jevguide

import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

/** 调 Jev 的结果：要么拿到响应体，要么带一句人话的错误 */
sealed class JevResult {
    data class Ok(val body: String) : JevResult()
    data class Err(val message: String) : JevResult()
}

/**
 * Jev 判读客户端。走的是 typesafe.ai 的 **systemone 协议**（state + questions 的问卷式判读），
 * 不是 OpenAI 的 /chat/completions —— 所以能换的端点只有「兼容这套协议」的那类
 * （官方端点、兼容中转、上架了 Jev 的聚合站），普通 OpenAI 兼容中转对判读没用。
 *
 * 端点**不再写死**：base_url 由设置页下发（与聊天模型的 base_url 同一套玩法），留空＝官方。
 * 与 ChatHttp 同源：只用 HttpURLConnection，整包运行期零第三方依赖，无障碍服务这种
 * 常驻进程里少带一个库就少一份自己控制不了的东西。这里用 HttpURLConnection 的**裸类**
 * 而不是 HttpsURLConnection —— base_url 允许填 `http://127.0.0.1:…` 这类本地中转，
 * 写死 https 会把本地端点挡在门外（公网明文另有一道闸，见 [guard]）。
 */
object JevHttp {

    /** 官方完整端点：base_url 留空/未配置时用它 —— 老行为一个字节都不变 */
    const val DEFAULT_ENDPOINT = "https://api.typesafe.ai/v1/systemone"

    /** 官方基地址（设置页默认值与占位提示共用这一份；要填到 /v1 这一级） */
    const val DEFAULT_BASE = "https://api.typesafe.ai/v1"

    /**
     * 端点拼接：base 填到 `/v1` 这一级，这里补 `/systemone`（末尾带不带 `/` 都行）。
     * 用户可能直接复制了完整地址（以 /systemone 结尾），那就别再拼 —— 拼出
     * `…/systemone/systemone` 的 404 最容易被误判成「服务商挂了」。
     * **不会**自动补 `/v1`：自动猜会让「填错地址」表现成「偶发 404」，排查成本远高于
     * 让用户照抄一次文档（与 ChatHttp 的 /chat/completions 拼接同一口径）。
     */
    fun endpoint(baseUrl: String): String {
        val base = baseUrl.trim().trimEnd('/')
        if (base.isEmpty()) return DEFAULT_ENDPOINT
        if (base.endsWith("/systemone")) return base
        return "$base/systemone"
    }

    /**
     * 明文端点闸门：返回 null＝放行；返回一段人话＝拒绝原因。
     * 直接复用 [ChatHttp.httpGuard]：判读内容（对方原话 + 关系档案）与聊天内容一样敏感，
     * 两道闸必须是同一个口径 —— 不能一边拦明文一边放明文。
     */
    fun guard(baseUrl: String): String? = ChatHttp.httpGuard(baseUrl)

    /**
     * 发一次判读请求，成功时返回**原始响应体**（解析交给 Verdicts）。
     *
     * @param baseUrl 判读端点基地址，**要填到 /v1 这一级**（例：https://api.typesafe.ai/v1）；
     *                留空＝官方端点；已填成完整的 `…/systemone` 时直接沿用。
     */
    fun analyze(
        apiKey: String,
        requestBody: String,
        timeoutMs: Int = 25000,
        baseUrl: String = ""
    ): JevResult {
        // 明文 http 只放行本机/内网（自建网关请上证书）：判读请求里是对话原文，
        // 裸奔给链路中间人比一次请求失败严重得多。
        guard(baseUrl)?.let { return JevResult.Err(it) }
        return post(endpoint(baseUrl), apiKey, requestBody, timeoutMs)
    }

    /** 设置页「测试端点」用：一条极小的请求，验证 base_url / 密钥 / 模型名三者可用 */
    fun test(apiKey: String, baseUrl: String = "", model: String = ""): JevResult {
        val state = Json.write(
            mapOf(
                "关系" to "普通朋友",
                "对方性别" to "未知",
                "会话类型" to "单聊",
                "最近对话" to emptyList<String>(),
                "待分析消息" to "在吗？"
            )
        )
        val body = Prompt.requestJson(state, model.ifBlank { Prompt.MODELS[0] }, "zh")
        guard(baseUrl)?.let { return JevResult.Err(it) }
        return post(endpoint(baseUrl), apiKey, body, 20000)
    }

    private fun post(url: String, apiKey: String, body: String, timeoutMs: Int): JevResult {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = timeoutMs
                readTimeout = timeoutMs
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("Authorization", "Bearer $apiKey")
                setRequestProperty("Accept", "application/json")
            }
            OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { it.write(body) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            // 带上限地读：响应体是外部输入，无上限的 readText() 等于把 OOM 的机会交给对方
            val text = stream?.let { HttpRead.text(it) }.orEmpty()
            if (code in 200..299) {
                JevResult.Ok(text)
            } else {
                JevResult.Err(explain(code, text, url))
            }
        } catch (e: java.net.SocketTimeoutException) {
            JevResult.Err("请求超时（网络慢或接口忙），过一会儿再试")
        } catch (e: javax.net.ssl.SSLException) {
            JevResult.Err("TLS 握手失败：${e.message ?: ""}")
        } catch (e: Exception) {
            JevResult.Err("网络失败：${e.javaClass.simpleName} ${e.message ?: ""}".trim())
        } finally {
            try {
                conn?.disconnect()
            } catch (_: Exception) {
            }
        }
    }

    /**
     * 错误翻译成人话。提示要认得出「官方端点」：官方密钥去 console.typesafe.ai 查，
     * 自定义中转的密钥得去那一家的控制台查 —— 答非所问的指引比没有指引更烦。
     */
    private fun explain(code: Int, body: String, url: String): String {
        val official = url.contains("typesafe.ai")
        val brief = body.replace(Regex("""\s+"""), " ").take(160)
        return when (code) {
            401, 403 ->
                if (official) "密钥无效或被撤销（HTTP $code）—— 去 console.typesafe.ai/api-keys 确认"
                else "密钥无效或被撤销（HTTP $code）—— 确认这把密钥属于你填的端点"
            404 -> "接口地址 404，检查 base_url 是否少/多了 /v1" +
                if (official) "（官方填到 https://api.typesafe.ai/v1 这一级）" else ""
            429 -> "被限流了（HTTP 429），等一会儿再试"
            in 500..599 -> "接口服务端错误 HTTP $code"
            else -> "HTTP $code" + if (brief.isEmpty()) "" else "：$brief"
        }
    }
}
