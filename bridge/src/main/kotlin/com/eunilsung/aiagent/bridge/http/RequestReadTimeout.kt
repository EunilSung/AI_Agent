package com.eunilsung.aiagent.bridge.http

import io.netty.channel.ChannelDuplexHandler
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelPipeline
import io.netty.channel.ChannelPromise
import io.netty.handler.codec.http.HttpRequest
import io.netty.handler.codec.http.HttpResponse
import io.netty.handler.codec.http.HttpStatusClass
import io.netty.handler.codec.http.LastHttpContent
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * 요청 하나를 머리부터 본문 끝까지 [timeoutMillis] 안에 다 받지 못하면 연결을 닫는다. 아주 느리게 보내며 연결을 붙잡아
 * 두는 공격(slowloris)을 막는다.
 *
 * Netty 엔진의 `requestReadTimeoutSeconds` 를 쓰지 않는 이유: 그 설정은 연결에서 마지막으로 읽은 뒤의 시간을 잰다. 앱은
 * SSE 답변을 받는 동안 아무것도 보내지 않으므로, 몇 분 걸리는 요약·긴 답변이 그 시간에 닿으면 Ktor 가 진행 중인 호출을
 * 취소하고 408 로 끊는다(Ktor 3.4 `NettyApplicationCallHandler`).
 *
 * 이 처리기는 요청을 받는 동안만 잰다. 연결이 열리거나 응답을 다 보낸 때부터 재기 시작해, 요청의 끝(`LastHttpContent`)이
 * 오면 멈춘다. 응답보다 요청이 늦게 끝나면(본문을 다 받기 전에 413 으로 답한 경우) 그때부터 다음 요청을 기다리며 다시 잰다.
 * keep-alive 로 놀고 있는 연결도 같은 시간이 지나면 닫힌다. 100 Continue 같은 중간 응답은 응답의 끝으로 보지 않는다.
 *
 * 모든 콜백은 그 연결의 이벤트 루프 한 스레드에서 불리므로 따로 잠그지 않는다.
 */
class RequestReadTimeoutHandler(private val timeoutMillis: Long) : ChannelDuplexHandler() {
    private var deadline: ScheduledFuture<*>? = null
    private var responded = false

    override fun handlerAdded(ctx: ChannelHandlerContext) {
        if (ctx.channel().isActive) arm(ctx)
    }

    override fun channelActive(ctx: ChannelHandlerContext) {
        arm(ctx)
        ctx.fireChannelActive()
    }

    override fun channelRead(ctx: ChannelHandlerContext, msg: Any) {
        if (msg is HttpRequest) responded = false
        if (msg is LastHttpContent) {
            if (responded) arm(ctx) else disarm()
        }
        ctx.fireChannelRead(msg)
    }

    override fun write(ctx: ChannelHandlerContext, msg: Any, promise: ChannelPromise) {
        val interim = msg is HttpResponse && msg.status().codeClass() == HttpStatusClass.INFORMATIONAL
        if (!interim && msg is LastHttpContent) {
            responded = true
            arm(ctx)
        }
        ctx.write(msg, promise)
    }

    override fun channelInactive(ctx: ChannelHandlerContext) {
        disarm()
        ctx.fireChannelInactive()
    }

    override fun handlerRemoved(ctx: ChannelHandlerContext) {
        disarm()
    }

    private fun arm(ctx: ChannelHandlerContext) {
        disarm()
        deadline = ctx.executor().schedule({ ctx.close() }, timeoutMillis, TimeUnit.MILLISECONDS)
    }

    private fun disarm() {
        deadline?.cancel(false)
        deadline = null
    }

    companion object {
        const val NAME = "requestReadTimeout"
        /** Ktor 가 HTTP/1.1 연결에 붙이는 HTTP 해석기의 이름. 이 처리기는 그 바로 뒤에서 해석된 요청·응답을 본다. */
        private const val HTTP_CODEC = "codec"

        /**
         * Ktor Netty 의 `channelPipelineConfig` 에서 부른다. HTTP/1.1 연결(이 브릿지는 TLS·h2c 를 쓰지 않는다)에만 붙인다 —
         * 해석기가 없는 연결에 붙이면 요청의 끝을 알아보지 못해 모든 연결을 닫게 된다.
         */
        fun install(pipeline: ChannelPipeline, timeoutMillis: Long) {
            if (timeoutMillis <= 0 || pipeline.get(HTTP_CODEC) == null) return
            pipeline.addAfter(HTTP_CODEC, NAME, RequestReadTimeoutHandler(timeoutMillis))
        }
    }
}
