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

import com.alibaba.assistant.agent.execution.persistence.ProactiveRunLease;
import com.alibaba.assistant.agent.execution.persistence.ProactiveRunLeaseService;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConnectorHealthSchedulerTest {

    @Test
    void shouldNotRegisterSchedulerWithoutConnectorHealthProbeBean() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.registerBean(ProactiveRunLeaseService.class, () -> mock(ProactiveRunLeaseService.class));
            context.register(ConnectorHealthScheduler.class);
            context.refresh();

            assertFalse(context.containsBeanDefinition("connectorHealthScheduler"));
            assertTrue(context.getBeansOfType(ConnectorHealthScheduler.class).isEmpty());
        }
    }

    @Test
    void shouldScheduleProbeUsingSharedLeaseInfrastructure() {
        ProactiveRunLeaseService leaseService = mock(ProactiveRunLeaseService.class);
        ConnectorHealthProbe connectorHealthProbe = mock(ConnectorHealthProbe.class);
        Clock clock = Clock.fixed(Instant.parse("2026-03-16T02:15:00Z"), ZoneOffset.UTC);
        ConnectorHealthScheduler scheduler = new ConnectorHealthScheduler(
                leaseService,
                connectorHealthProbe,
                clock,
                "0 */15 * * * *",
                Duration.ofMinutes(2),
                "node-a");

        ProactiveRunLease lease = new ProactiveRunLease();
        lease.setId(11L);
        when(leaseService.findLatestScheduledAt("connector-health:global")).thenReturn(Optional.of(LocalDateTime.of(2026, 3, 16, 10, 0)));
        when(leaseService.acquire("connector-health:global", LocalDateTime.of(2026, 3, 16, 10, 15), "node-a", Duration.ofMinutes(2)))
                .thenReturn(Optional.of(lease));

        scheduler.scan();

        InOrder order = inOrder(leaseService, connectorHealthProbe);
        order.verify(leaseService).acquire("connector-health:global", LocalDateTime.of(2026, 3, 16, 10, 15), "node-a", Duration.ofMinutes(2));
        order.verify(connectorHealthProbe).probeDueConnectors(lease);
    }
}
