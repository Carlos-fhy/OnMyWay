package com.onmyway.app.core.state

import com.onmyway.app.data.remote.ApiHttpException
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import org.junit.Assert.assertEquals
import org.junit.Test

/** 服务端拒绝与真正的断网必须区分：否则请求被 4xx 拒绝时会显示成"无法连接服务"。 */
class NetworkMessageTest {
    @Test
    fun httpRejectionKeepsTheServerReason() {
        val message = networkMessage(ApiHttpException(422, "输入未通过校验（HTTP 422），请检查时间和任务信息。"))
        assertEquals("输入未通过校验（HTTP 422），请检查时间和任务信息。", message)
    }

    @Test
    fun connectionFailuresStillReportTheNetwork() {
        assertEquals("无法连接服务，请检查电脑服务、地址和 USB 转发或局域网连接。", networkMessage(ConnectException("refused")))
        assertEquals("无法连接服务，请检查电脑服务、地址和 USB 转发或局域网连接。", networkMessage(IOException("unexpected end of stream")))
    }

    @Test
    fun timeoutsKeepTheirOwnHint() {
        assertEquals("请求超时，首次规划需要查询多段路线，请稍后重试。", networkMessage(SocketTimeoutException("timeout")))
    }
}
