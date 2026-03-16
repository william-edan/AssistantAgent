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
package com.alibaba.assistant.agent.runtime.execution;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 维护按线程隔离的实时执行事件流。
 *
 * <p>聊天 SSE 和其他实时订阅方都会从这里订阅对应线程的运行事件。
 */
@Component
public class ExecutionEventStreamRegistry {

    private final ConcurrentMap<String, Channel> channels = new ConcurrentHashMap<>();

    public ExecutionEventSubscription open(String threadId) {
        String normalizedThreadId = normalize(threadId);
        if (!StringUtils.hasText(normalizedThreadId)) {
            return new ExecutionEventSubscription(null, Flux.empty(), () -> {
            });
        }
        Channel channel = channels.computeIfAbsent(normalizedThreadId, ignored -> new Channel());
        channel.retain();
        AtomicBoolean released = new AtomicBoolean(false);
        Runnable release = () -> release(normalizedThreadId, channel, released);
        Flux<ExecutionEvent> flux = channel.sink.asFlux().doFinally(signalType -> release.run());
        return new ExecutionEventSubscription(normalizedThreadId, flux, release);
    }

    public void publish(String threadId, ExecutionEvent event) {
        String normalizedThreadId = normalize(threadId);
        if (!StringUtils.hasText(normalizedThreadId) || event == null) {
            return;
        }
        Channel channel = channels.get(normalizedThreadId);
        if (channel == null) {
            return;
        }
        Sinks.EmitResult emitResult = channel.sink.tryEmitNext(event);
        if (emitResult == Sinks.EmitResult.FAIL_TERMINATED) {
            channels.remove(normalizedThreadId, channel);
        }
    }

    private void release(String threadId, Channel channel, AtomicBoolean released) {
        if (channel == null || !released.compareAndSet(false, true)) {
            return;
        }
        if (channel.release() <= 0) {
            channels.remove(threadId, channel);
            channel.sink.tryEmitComplete();
        }
    }

    private String normalize(String threadId) {
        return StringUtils.hasText(threadId) ? threadId.trim() : null;
    }

    public static final class ExecutionEventSubscription implements AutoCloseable {

        private final String threadId;

        private final Flux<ExecutionEvent> flux;

        private final Runnable closeAction;

        private ExecutionEventSubscription(String threadId, Flux<ExecutionEvent> flux, Runnable closeAction) {
            this.threadId = threadId;
            this.flux = flux;
            this.closeAction = closeAction;
        }

        public String threadId() {
            return threadId;
        }

        public Flux<ExecutionEvent> flux() {
            return flux;
        }

        @Override
        public void close() {
            closeAction.run();
        }
    }

    private static final class Channel {

        private final Sinks.Many<ExecutionEvent> sink = Sinks.many().replay().limit(256);

        private final AtomicInteger subscribers = new AtomicInteger();

        private void retain() {
            subscribers.incrementAndGet();
        }

        private int release() {
            return subscribers.decrementAndGet();
        }
    }
}
