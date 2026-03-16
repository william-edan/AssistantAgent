/*
 * Copyright 2024-2025 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.alibaba.assistant.agent.api.stream;

import com.alibaba.assistant.agent.api.protocol.FrontendEvent;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 维护线程级前端事件流，供长任务异步更新和会话实时订阅复用。
 */
@Component
@Profile("migration")
public class FrontendEventStreamRegistry {

    private final ConcurrentMap<String, Channel> channels = new ConcurrentHashMap<>();

    /**
     * 打开指定线程的前端事件流订阅。
     */
    public FrontendEventSubscription open(String threadId) {
        String normalizedThreadId = normalize(threadId);
        if (!StringUtils.hasText(normalizedThreadId)) {
            return new FrontendEventSubscription(null, Flux.empty(), () -> {
            });
        }
        Channel channel = channels.computeIfAbsent(normalizedThreadId, ignored -> new Channel());
        channel.retain();
        AtomicBoolean released = new AtomicBoolean(false);
        Runnable release = () -> release(normalizedThreadId, channel, released);
        Flux<FrontendEvent> flux = channel.sink.asFlux().doFinally(signalType -> release.run());
        return new FrontendEventSubscription(normalizedThreadId, flux, release);
    }

    /**
     * 向线程事件流发布一个前端事件。
     */
    public void publish(String threadId, FrontendEvent event) {
        String normalizedThreadId = normalize(threadId);
        if (!StringUtils.hasText(normalizedThreadId) || event == null) {
            return;
        }
        Channel channel = channels.computeIfAbsent(normalizedThreadId, ignored -> new Channel());
        channel.sink.tryEmitNext(event);
    }

    private void release(String threadId, Channel channel, AtomicBoolean released) {
        if (channel == null || !released.compareAndSet(false, true)) {
            return;
        }
        if (channel.release() <= 0 && channel.isIdle()) {
            channels.remove(threadId, channel);
        }
    }

    private String normalize(String threadId) {
        return StringUtils.hasText(threadId) ? threadId.trim() : null;
    }

    /**
     * 线程级前端事件流订阅句柄。
     */
    public static final class FrontendEventSubscription implements AutoCloseable {

        private final String threadId;

        private final Flux<FrontendEvent> flux;

        private final Runnable closeAction;

        private FrontendEventSubscription(String threadId, Flux<FrontendEvent> flux, Runnable closeAction) {
            this.threadId = threadId;
            this.flux = flux;
            this.closeAction = closeAction;
        }

        public String threadId() {
            return threadId;
        }

        public Flux<FrontendEvent> flux() {
            return flux;
        }

        @Override
        public void close() {
            closeAction.run();
        }
    }

    private static final class Channel {

        private final Sinks.Many<FrontendEvent> sink = Sinks.many().replay().limit(256);

        private final AtomicInteger subscribers = new AtomicInteger();

        private void retain() {
            subscribers.incrementAndGet();
        }

        private int release() {
            return subscribers.decrementAndGet();
        }

        private boolean isIdle() {
            return subscribers.get() <= 0;
        }
    }
}
