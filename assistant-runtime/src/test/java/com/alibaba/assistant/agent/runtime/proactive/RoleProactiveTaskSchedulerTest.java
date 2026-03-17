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
package com.alibaba.assistant.agent.runtime.proactive;

import com.alibaba.assistant.agent.controlplane.rolepackage.RoleProactiveTaskQueryService;
import com.alibaba.assistant.agent.execution.persistence.ProactiveRunLease;
import com.alibaba.assistant.agent.execution.persistence.ProactiveRunLeaseService;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RoleProactiveTaskSchedulerTest {

    @Test
    void shouldAcquireLeaseBeforeDispatchingPublishedArtifact() {
        RoleProactiveTaskQueryService queryService = mock(RoleProactiveTaskQueryService.class);
        ProactiveRunLeaseService leaseService = mock(ProactiveRunLeaseService.class);
        RoleProactiveTaskDispatcher dispatcher = mock(RoleProactiveTaskDispatcher.class);
        Clock clock = Clock.fixed(Instant.parse("2026-03-16T02:05:00Z"), ZoneOffset.UTC);
        RoleProactiveTaskScheduler scheduler = new RoleProactiveTaskScheduler(
                queryService,
                leaseService,
                dispatcher,
                clock,
                Duration.ofMinutes(15),
                Duration.ofMinutes(2),
                4,
                "node-a");

        RoleProactiveTaskQueryService.PublishedRoleProactiveTask task = new RoleProactiveTaskQueryService.PublishedRoleProactiveTask(
                10L,
                "prod",
                "finance-agent",
                "digital-admin",
                "v1",
                "approval_cleanup",
                "0 */5 * * * *",
                "office1.approval_cleanup",
                "approval-cleanup",
                Map.of("channel", "email"));
        ProactiveRunLease lease = new ProactiveRunLease();
        lease.setId(7L);
        when(queryService.listPublishedTasks()).thenReturn(List.of(task));
        when(leaseService.findLatestScheduledAt(task.taskKey())).thenReturn(Optional.of(LocalDateTime.of(2026, 3, 16, 10, 0)));
        when(leaseService.acquire(eq(task.taskKey()), eq(LocalDateTime.of(2026, 3, 16, 10, 5)), eq("node-a"), any(Duration.class)))
                .thenReturn(Optional.of(lease));

        scheduler.scanDueTasks();

        InOrder order = inOrder(leaseService, dispatcher);
        order.verify(leaseService).acquire(eq(task.taskKey()), eq(LocalDateTime.of(2026, 3, 16, 10, 5)), eq("node-a"), any(Duration.class));
        order.verify(dispatcher).dispatch(task, lease);
    }

    @Test
    void shouldResolvePublishedTasksByAgentAppScope() {
        RoleProactiveTaskQueryService queryService = mock(RoleProactiveTaskQueryService.class);
        ProactiveRunLeaseService leaseService = mock(ProactiveRunLeaseService.class);
        RoleProactiveTaskDispatcher dispatcher = mock(RoleProactiveTaskDispatcher.class);
        Clock clock = Clock.fixed(Instant.parse("2026-03-16T02:05:00Z"), ZoneOffset.UTC);
        RoleProactiveTaskScheduler scheduler = new RoleProactiveTaskScheduler(
                queryService,
                leaseService,
                dispatcher,
                clock,
                Duration.ofMinutes(15),
                Duration.ofMinutes(2),
                4,
                "node-a");

        RoleProactiveTaskQueryService.PublishedRoleProactiveTask financeTask = new RoleProactiveTaskQueryService.PublishedRoleProactiveTask(
                10L, "prod", "finance-agent", "digital-admin", "v1", "approval_cleanup",
                "0 */5 * * * *", "office1.approval_cleanup", "approval-cleanup", Map.of());
        RoleProactiveTaskQueryService.PublishedRoleProactiveTask opsTask = new RoleProactiveTaskQueryService.PublishedRoleProactiveTask(
                10L, "prod", "ops-agent", "digital-admin", "v1", "approval_cleanup",
                "0 */5 * * * *", "office1.approval_cleanup", "approval-cleanup", Map.of());
        when(queryService.listPublishedTasks()).thenReturn(List.of(financeTask, opsTask));
        when(leaseService.findLatestScheduledAt(any())).thenReturn(Optional.of(LocalDateTime.of(2026, 3, 16, 10, 0)));
        when(leaseService.acquire(eq(financeTask.taskKey()), eq(LocalDateTime.of(2026, 3, 16, 10, 5)), eq("node-a"), any(Duration.class)))
                .thenReturn(Optional.of(new ProactiveRunLease()));
        when(leaseService.acquire(eq(opsTask.taskKey()), eq(LocalDateTime.of(2026, 3, 16, 10, 5)), eq("node-a"), any(Duration.class)))
                .thenReturn(Optional.of(new ProactiveRunLease()));

        scheduler.scanDueTasks();

        verify(dispatcher).dispatch(eq(financeTask), any(ProactiveRunLease.class));
        verify(dispatcher).dispatch(eq(opsTask), any(ProactiveRunLease.class));
    }
}
