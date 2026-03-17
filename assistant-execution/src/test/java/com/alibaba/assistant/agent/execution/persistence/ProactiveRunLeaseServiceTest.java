/*
 * Copyright 2024-2025 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 */
package com.alibaba.assistant.agent.execution.persistence;

import com.alibaba.assistant.agent.execution.persistence.mapper.ProactiveRunLeaseMapper;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ProactiveRunLeaseServiceTest {

    @Test
    void shouldAcquireAndHeartbeatLeaseWithoutDuplicateRuns() {
        ProactiveRunLeaseMapper mapper = mock(ProactiveRunLeaseMapper.class);
        Clock clock = Clock.fixed(Instant.parse("2026-03-16T02:05:00Z"), ZoneId.of("Asia/Shanghai"));
        ProactiveRunLeaseService service = new ProactiveRunLeaseService(mapper, clock);
        LocalDateTime scheduledAt = LocalDateTime.of(2026, 3, 16, 10, 5);

        ProactiveRunLease persisted = new ProactiveRunLease();
        persisted.setId(7L);
        persisted.setTaskKey("finance|cleanup");
        persisted.setScheduledAt(scheduledAt);

        when(mapper.findByTaskKeyAndScheduledAt("finance|cleanup", scheduledAt))
                .thenReturn(null, persisted, persisted);
        when(mapper.acquireLease(eq(7L), eq("node-a"), eq(LocalDateTime.of(2026, 3, 16, 10, 7)), eq(LocalDateTime.of(2026, 3, 16, 10, 5))))
                .thenReturn(1, 0);
        when(mapper.heartbeat(7L, "node-a", LocalDateTime.of(2026, 3, 16, 10, 7), LocalDateTime.of(2026, 3, 16, 10, 5)))
                .thenReturn(1);

        Optional<ProactiveRunLease> acquired = service.acquire("finance|cleanup", scheduledAt, "node-a", Duration.ofMinutes(2));
        boolean renewed = service.heartbeat(7L, "node-a", Duration.ofMinutes(2));
        Optional<ProactiveRunLease> duplicate = service.acquire("finance|cleanup", scheduledAt, "node-a", Duration.ofMinutes(2));

        assertTrue(acquired.isPresent());
        assertEquals("node-a", acquired.get().getLeaseOwner());
        assertEquals("LEASED", acquired.get().getRunStatus());
        assertTrue(renewed);
        assertTrue(duplicate.isEmpty());
        verify(mapper).insert(any(ProactiveRunLease.class));
        verify(mapper, times(3)).findByTaskKeyAndScheduledAt("finance|cleanup", scheduledAt);
    }
}
