package io.github.nisaka520.jevguide

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 判读端点可配置后的纯函数契约：base_url → 实际请求地址的拼接规则，以及明文闸门的接线。
 *
 * 规则与 [ChatHttp] 的 /chat/completions 拼接同一套口径（钉住防止回归）：
 * - 留空 = 官方端点（老用户升级前后一个字节都不变）；
 * - base 填到 /v1 这一级，这里补 /systemone；
 * - 已经填成完整的 …/systemone 就沿用，不再拼一层；
 * - **不**自动补 /v1 —— 自动猜会让「填错地址」表现成「偶发 404」。
 * 全部离线：不联网、不碰设备。
 */
class JevEndpointTest {

    @Test
    fun blankFallsBackToOfficialEndpoint() {
        assertEquals(JevHttp.DEFAULT_ENDPOINT, JevHttp.endpoint(""))
        assertEquals(JevHttp.DEFAULT_ENDPOINT, JevHttp.endpoint("   "))
    }

    @Test
    fun v1BaseGetsSystemoneAppended() {
        assertEquals(
            "https://api.typesafe.ai/v1/systemone",
            JevHttp.endpoint("https://api.typesafe.ai/v1")
        )
        // 末尾带不带斜杠都行
        assertEquals(
            "https://api.typesafe.ai/v1/systemone",
            JevHttp.endpoint("https://api.typesafe.ai/v1/")
        )
    }

    @Test
    fun relayBaseAlsoGetsThePathAppended() {
        assertEquals(
            "https://relay.example.com/v1/systemone",
            JevHttp.endpoint("https://relay.example.com/v1")
        )
    }

    @Test
    fun missingVersionSegmentIsNotGuessed() {
        // 不自动补 /v1：拼出来就是 /systemone，404 的报错会把 /v1 那层点出来
        assertEquals(
            "https://relay.example.com/systemone",
            JevHttp.endpoint("https://relay.example.com")
        )
    }

    @Test
    fun fullUrlIsKeptAsIs() {
        assertEquals(
            "https://api.typesafe.ai/v1/systemone",
            JevHttp.endpoint("https://api.typesafe.ai/v1/systemone")
        )
        // 复制时多带了尾斜杠：trimEnd 吃掉后仍认得是完整端点，不会拼出两层
        assertEquals(
            "https://api.typesafe.ai/v1/systemone",
            JevHttp.endpoint("https://api.typesafe.ai/v1/systemone/")
        )
    }

    @Test
    fun plaintextHttpGateReusesTheChatGate() {
        // 与聊天模型同一道闸：公网 http 拒、本机/内网 http 放
        assertNotNull(JevHttp.guard("http://example.com/v1"))
        assertNull(JevHttp.guard("http://127.0.0.1:8080/v1"))
        assertNull(JevHttp.guard("https://api.typesafe.ai/v1"))
    }
}
