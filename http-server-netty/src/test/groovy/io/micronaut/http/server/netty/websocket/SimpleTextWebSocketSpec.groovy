/*
 * Copyright 2017-2019 original authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.micronaut.http.server.netty.websocket

import io.micronaut.context.ApplicationContext
import io.micronaut.core.util.StreamUtils
import io.micronaut.http.client.annotation.Client
import io.micronaut.runtime.server.EmbeddedServer
import io.micronaut.websocket.WebSocketClient
import io.micronaut.websocket.exceptions.WebSocketClientException
import jakarta.inject.Inject
import jakarta.inject.Singleton
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import spock.lang.Retry
import spock.lang.Specification
import spock.util.concurrent.PollingConditions

class SimpleTextWebSocketSpec extends Specification {

    @Retry
    void "test simple text websocket exchange"() {
        given:
        EmbeddedServer embeddedServer = ApplicationContext.builder('micronaut.server.netty.log-level':'TRACE').run(EmbeddedServer)
        PollingConditions conditions = new PollingConditions(timeout: 15    , delay: 0.5)

        when: "a websocket connection is established"
        WebSocketClient wsClient = embeddedServer.applicationContext.createBean(WebSocketClient, embeddedServer.getURI())
        ChatClientWebSocket fred = Flux.from(wsClient.connect(ChatClientWebSocket, "/chat/stuff/fred")).blockFirst()
        ChatClientWebSocket bob = Flux.from(wsClient.connect(ChatClientWebSocket, [topic:"stuff",username:"bob"])).blockFirst()

        then:"The connection is valid"
        fred.session != null
        fred.session.id != null
        fred.request != null

        then:"A session is established"
        fred.session != null
        fred.session.id != null
        fred.session.id != bob.session.id
        fred.request != null
        fred.topic == 'stuff'
        fred.username == 'fred'
        bob.username == 'bob'
        conditions.eventually {
            fred.replies.contains("[bob] Joined!")
            fred.replies.size() == 1
        }

        when:"A message is sent"
        fred.send("Hello bob!")

        then:
        conditions.eventually {
            bob.replies.contains("[fred] Hello bob!")
            bob.replies.size() == 1
        }

        when:
        bob.send("Hi fred. How are things?")

        then:
        conditions.eventually {

            fred.replies.contains("[bob] Hi fred. How are things?")
            fred.replies.size() == 2
            bob.replies.contains("[fred] Hello bob!")
            bob.replies.size() == 1
        }
        fred.sendAsync("foo").get() == 'foo'
        Mono.from(fred.sendRx("bar")).block() == 'bar'

        when:
        bob.close()
        fred.close()

        then:
        conditions.eventually {
            !bob.session.isOpen()
            !fred.session.isOpen()
        }

        when:"A bean is retrieved that injects a websocket client"
        MyBean myBean = embeddedServer.applicationContext.getBean(MyBean)

        then:
        myBean.myClient != null

        cleanup:
        wsClient.close()
        embeddedServer.close()
    }

    @Retry
    void "test simple text websocket connection over SSL"() {
        given:
        EmbeddedServer embeddedServer = ApplicationContext.builder([
                'micronaut.server.netty.log-level':'TRACE',
                'micronaut.ssl.enabled':true,
                'micronaut.ssl.build-self-signed':true
                ]).run(EmbeddedServer)
        PollingConditions conditions = new PollingConditions(timeout: 15    , delay: 0.5)

        when: "a websocket connection is established"
        WebSocketClient wsClient = embeddedServer.applicationContext.createBean(WebSocketClient, embeddedServer.getURI())
        ChatClientWebSocket fred = Flux.from(wsClient.connect(ChatClientWebSocket, "/chat/stuff/fred")).blockFirst()
        ChatClientWebSocket bob = Flux.from(wsClient.connect(ChatClientWebSocket, [topic:"stuff",username:"bob"])).blockFirst()
        ChatServerWebSocket server = embeddedServer.applicationContext.getBean(ChatServerWebSocket)

        then:"The connection is valid"
        fred.session != null
        fred.session.id != null
        fred.request != null
        fred.subProtocol == null
        server.subProtocol == null

        then:"A session is established"
        fred.session != null
        fred.session.id != null
        fred.session.id != bob.session.id
        fred.request != null
        fred.topic == 'stuff'
        fred.username == 'fred'
        bob.username == 'bob'
        conditions.eventually {
            fred.replies.contains("[bob] Joined!")
            fred.replies.size() == 1
        }

        when:"A message is sent"
        fred.send("Hello bob!")

        then:
        conditions.eventually {
            bob.replies.contains("[fred] Hello bob!")
            bob.replies.size() == 1
        }

        when:
        bob.send("Hi fred. How are things?")

        then:
        conditions.eventually {

            fred.replies.contains("[bob] Hi fred. How are things?")
            fred.replies.size() == 2
            bob.replies.contains("[fred] Hello bob!")
            bob.replies.size() == 1
        }
        fred.sendAsync("foo").get() == 'foo'
        Mono.from(fred.sendRx("bar")).block() == 'bar'

        when:
        bob.close()
        fred.close()

        then:
        conditions.eventually {
            !bob.session.isOpen()
            !fred.session.isOpen()
        }

        when:"A bean is retrieved that injects a websocket client"
        MyBean myBean = embeddedServer.applicationContext.getBean(MyBean)

        then:
        myBean.myClient != null

        cleanup:
        wsClient.close()
        embeddedServer.close()
    }

    void "test simple text websocket connection with query"() {
        given:
        EmbeddedServer embeddedServer = ApplicationContext.builder('micronaut.server.netty.log-level': 'TRACE').run(EmbeddedServer)
        PollingConditions conditions = new PollingConditions(timeout: 2, delay: 0.5)

        when: "a websocket connection is established"
        WebSocketClient wsClient = embeddedServer.applicationContext.createBean(WebSocketClient, embeddedServer.getURI())
        QueryParamClientWebSocket client = wsClient.connect(QueryParamClientWebSocket, "/charity?dinner=chicken%20dumplings").blockFirst()

        then: "The connection is valid"
        client.session.id != null

        when:
        QueryParamServerWebSocket server = embeddedServer.applicationContext.getBean(QueryParamServerWebSocket)

        then:
        conditions.eventually {
            server.dinner == "chicken dumplings"
        }

        cleanup:
        wsClient.close()
        embeddedServer.close()
    }

    void "test a filter responding to a websocket upgrade request"() {
        given:
        EmbeddedServer embeddedServer = ApplicationContext.builder(
                'websocket-filter-respond': true
        ).run(EmbeddedServer)

        when:
        WebSocketClient wsClient = embeddedServer.applicationContext.createBean(WebSocketClient, embeddedServer.getURI())
        Flux.from(wsClient.connect(ChatClientWebSocket, "/chat/stuff/fred")).blockFirst()

        then:
        def ex = thrown(WebSocketClientException)
        ex.message.contains("Invalid handshake response getStatus: 200 OK")
    }

    void "test filters are invoked for web socket requests that don't match any routes"() {
        given:
        EmbeddedServer embeddedServer = ApplicationContext.builder().run(EmbeddedServer)

        when:
        WebSocketClient wsClient = embeddedServer.applicationContext.createBean(WebSocketClient, embeddedServer.getURI())
        Flux.from(wsClient.connect(ChatClientWebSocket, "/abc/def/ghi")).blockFirst()

        then:
        def ex = thrown(WebSocketClientException)
        ex.message.contains("Invalid handshake response getStatus: 404 Not Found")
        embeddedServer.applicationContext.getBean(WebSocketContextValidationFilter).executeCount.get() == 1
    }

    @Singleton
    static class MyBean {
        @Inject
        @Client("http://localhost:8080")
        WebSocketClient myClient
    }
}
