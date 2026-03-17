/*
 * Copyright 2024-2025 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 */
package com.alibaba.assistant.agent.runtime.proactive;

import com.alibaba.assistant.agent.execution.persistence.ProactiveRunLeaseService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;

@Component
@ConditionalOnBean(ConnectorHealthProbe.class)
public class ConnectorHealthScheduler {

    private static final String TASK_KEY = "connector-health:global";

    private final ProactiveRunLeaseService leaseService;
    private final ConnectorHealthProbe connectorHealthProbe;
    private final Clock clock;
    private final String cronExpression;
    private final Duration leaseDuration;
    private final String leaseOwner;

    @Autowired
    public ConnectorHealthScheduler(
            ProactiveRunLeaseService leaseService,
            ConnectorHealthProbe connectorHealthProbe) {
        this(leaseService, connectorHealthProbe, Clock.systemDefaultZone(), "0 */15 * * * *", Duration.ofMinutes(2), "default-node");
    }

    public ConnectorHealthScheduler(
            ProactiveRunLeaseService leaseService,
            ConnectorHealthProbe connectorHealthProbe,
            Clock clock,
            String cronExpression,
            Duration leaseDuration,
            String leaseOwner) {
        this.leaseService = leaseService;
        this.connectorHealthProbe = connectorHealthProbe;
        this.clock = clock;
        this.cronExpression = cronExpression;
        this.leaseDuration = leaseDuration;
        this.leaseOwner = leaseOwner;
    }

    @Scheduled(fixedDelayString = "${assistant.runtime.proactive.connector-health-delay-ms:60000}")
    public void scan() {
        LocalDateTime now = LocalDateTime.ofInstant(clock.instant(), ZoneId.systemDefault());
        LocalDateTime cursor = leaseService.findLatestScheduledAt(TASK_KEY).orElse(now.minusMinutes(30));
        LocalDateTime scheduledAt = CronExpression.parse(cronExpression).next(cursor);
        if (scheduledAt != null && !scheduledAt.isAfter(now)) {
            leaseService.acquire(TASK_KEY, scheduledAt, leaseOwner, leaseDuration)
                    .ifPresent(connectorHealthProbe::probeDueConnectors);
        }
    }
}



