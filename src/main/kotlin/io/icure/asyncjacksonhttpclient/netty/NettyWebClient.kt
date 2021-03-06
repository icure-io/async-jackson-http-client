/*
 *    Copyright 2020 Taktik SA
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 *
 */

package io.icure.asyncjacksonhttpclient.netty

import io.icure.asyncjacksonhttpclient.net.web.HttpMethod
import io.icure.asyncjacksonhttpclient.net.web.Request
import io.icure.asyncjacksonhttpclient.net.web.Response
import io.icure.asyncjacksonhttpclient.net.web.ResponseStatus
import io.icure.asyncjacksonhttpclient.net.web.WebClient
import io.netty.buffer.Unpooled.EMPTY_BUFFER
import io.netty.buffer.Unpooled.wrappedBuffer
import io.netty.handler.codec.http.DefaultHttpHeaders
import io.netty.handler.codec.http.HttpHeaders
import io.netty.handler.logging.LogLevel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.reactor.asFlux
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.netty.http.client.HttpClient
import reactor.netty.transport.logging.AdvancedByteBufFormat
import java.net.URI
import java.nio.ByteBuffer
import java.time.Duration

@ExperimentalCoroutinesApi
class NettyWebClient : WebClient {
    override fun uri(uri: URI): Request {
        val client = HttpClient.create().wiretap("io.icure.asyncjacksonhttpclient.netty", LogLevel.DEBUG, AdvancedByteBufFormat.HEX_DUMP)
        return NettyRequest(client, uri)
    }
}

@ExperimentalCoroutinesApi
class NettyRequest(
    private val client: HttpClient,
    private val uri: URI,
    private val method: HttpMethod? = null,
    private val headers: HttpHeaders = DefaultHttpHeaders(),
    private val bodyPublisher: Flow<ByteBuffer>? = null
) : Request {
    override fun method(method: HttpMethod, timeoutDuration: Duration?): Request =
        NettyRequest(timeoutDuration?.let { client.responseTimeout(it) } ?: client, uri, method, headers, bodyPublisher)

    override fun header(name: String, value: String): Request =
        NettyRequest(client, uri, method, headers.add(name, value), bodyPublisher)

    override fun body(producer: Flow<ByteBuffer>): Request = NettyRequest(client, uri, method, headers, producer)
    override fun retrieve(): Response = NettyResponse(
        when (method) {
            HttpMethod.GET -> client.headers { it.add(headers) }.get().uri(uri)
            HttpMethod.HEAD -> client.headers { it.add(headers) }.head().uri(uri)
            HttpMethod.DELETE -> client.headers { it.add(headers) }.delete().uri(uri)
            HttpMethod.OPTIONS -> client.headers { it.add(headers) }.options().uri(uri)
            HttpMethod.POST -> client.headers { it.add(headers) }.post().uri(uri)
                .send(bodyPublisher?.map { wrappedBuffer(it) }?.asFlux() ?: Mono.just(EMPTY_BUFFER))
            HttpMethod.PUT -> client.headers { it.add(headers) }.put().uri(uri)
                .send(bodyPublisher?.map { wrappedBuffer(it) }?.asFlux() ?: Mono.just(EMPTY_BUFFER))
            HttpMethod.PATCH -> client.headers { it.add(headers) }.put().uri(uri)
                .send(bodyPublisher?.map { wrappedBuffer(it) }?.asFlux() ?: Mono.just(EMPTY_BUFFER))
            else -> throw IllegalStateException("Invalid HTTP method")
        }
    )
}

@ExperimentalCoroutinesApi
class NettyResponse(
    private val responseReceiver: HttpClient.ResponseReceiver<*>,
    private val statusHandlers: Map<Int, (ResponseStatus) -> Mono<out Throwable>> = mapOf(),
    private val headerHandler: Map<String, (String) -> Mono<Unit>> = mapOf(),
    private val timingHandler: ((Long) -> Mono<Unit>)? = null,
    ) : Response {
    override fun toFlux(): Flux<ByteBuffer> {
        val start = System.currentTimeMillis()
        return Flux.deferContextual { ctx -> responseReceiver.response { clientResponse, flux ->
            val code = clientResponse.status().code()

            val headerHandlers = (if (headerHandler.isNotEmpty()) {
                clientResponse.responseHeaders().fold(Mono.empty<Any>()) { m: Mono<*>, (k, v) -> m.then(headerHandler[k]?.let { it(v) } ?: Mono.empty()) }
            } else Mono.empty())

            headerHandlers.thenMany((statusHandlers[code] ?: statusHandlers[code - (code % 100)])?.let { handler ->
                val agg = flux.aggregate().asByteArray()
                agg.flatMap { bytes ->
                    val res = handler(object : ResponseStatus(code, clientResponse.responseHeaders().entries()) {
                        override fun responseBodyAsString() = bytes.toString(Charsets.UTF_8)
                    })
                    if (res == Mono.empty<Throwable>()) {
                        Mono.just(ByteBuffer.wrap(bytes))
                    } else {
                        res.flatMap { Mono.error(it) }
                    }
                }.switchIfEmpty(handler(object : ResponseStatus(code, clientResponse.responseHeaders().entries()) {
                    override fun responseBodyAsString() = ""
                }).let { res ->
                    if (res == Mono.empty<Throwable>()) {
                        Mono.just(ByteBuffer.wrap(ByteArray(0)))
                    } else {
                        res.flatMap { Mono.error(it) }
                    }
                })
            } ?: flux.map {
                val ba = ByteArray(it.readableBytes())
                it.readBytes(ba) //Bytes need to be read now, before they become unavailable. If we just return the nioBuffer(), we have no guarantee that the bytes will be the same when the ByteBuffer will be processed down the flux
                ByteBuffer.wrap(ba)
            })
        }.doOnTerminate {
            timingHandler?.let { it(System.currentTimeMillis() - start).contextWrite(ctx).subscribe() }
        } }
    }

    override fun onStatus(status: Int, handler: (ResponseStatus) -> Mono<out Throwable>): Response {
         return NettyResponse(responseReceiver, statusHandlers + (status to handler), headerHandler, timingHandler)
    }

    override fun onHeader(header: String, handler: (String) -> Mono<Unit>): Response {
        return NettyResponse(responseReceiver, statusHandlers, headerHandler + (header to handler), timingHandler)
    }

    override fun withTiming(handler: (Long) -> Mono<Unit>): Response {
        return NettyResponse(responseReceiver, statusHandlers, headerHandler, handler)
    }
}
