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

import com.alibaba.assistant.agent.controlplane.rolepackage.RoleProactiveTaskQueryService;
import com.alibaba.assistant.agent.execution.persistence.ProactiveRunLeaseService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;

@Component
public class RoleProactiveTaskScheduler {

    private final RoleProactiveTaskQueryService queryService;
    private final ProactiveRunLeaseService leaseService;
    private final RoleProactiveTaskDispatcher dispatcher;
    private final Clock clock;
    private final Duration recoveryWindow;
    private final Duration leaseDuration;
    private final int maxCatchUpRuns;
    private final String leaseOwner;

    @Autowired
    public RoleProactiveTaskScheduler(
            RoleProactiveTaskQueryService queryService,
            ProactiveRunLeaseService leaseService,
            RoleProactiveTaskDispatcher dispatcher) {
        this(queryService, leaseService, dispatcher, Clock.systemDefaultZone(), Duration.ofMinutes(15), Duration.ofMinutes(2), 4, "default-node");
    }

    public RoleProactiveTaskScheduler(
            RoleProactiveTaskQueryService queryService,
            ProactiveRunLeaseService leaseService,
            RoleProactiveTaskDispatcher dispatcher,
            Clock clock,
            Duration recoveryWindow,
            Duration leaseDuration,
            int maxCatchUpRuns,
            String leaseOwner) {
        this.queryService = queryService;
        this.leaseService = leaseService;
        this.dispatcher = dispatcher;
        this.clock = clock;
        this.recoveryWindow = recoveryWindow;
        this.leaseDuration = leaseDuration;
        this.maxCatchUpRuns = maxCatchUpRuns;
        this.leaseOwner = leaseOwner;
    }

    @Scheduled(fixedDelayString = "${assistant.runtime.proactive.scan-fixed-delay-ms:30000}")
    public void scanDueTasks() {
        LocalDateTime now = LocalDateTime.ofInstant(clock.instant(), ZoneId.systemDefault());
        for (RoleProactiveTaskQueryService.PublishedRoleProactiveTask task : queryService.listPublishedTasks()) {
            CronExpression cron = CronExpression.parse(task.cronExpr());
            LocalDateTime cursor = leaseService.findLatestScheduledAt(task.taskKey()).orElse(now.minus(recoveryWindow));
            int dispatched = 0;
            LocalDateTime next = cron.next(cursor);
            while (next != null && !next.isAfter(now) && dispatched < maxCatchUpRuns) {
                leaseService.acquire(task.taskKey(), next, leaseOwner, leaseDuration).ifPresent(lease -> dispatcher.dispatch(task, lease));
                next = cron.next(next);
                dispatched++;
            }
        }
    }
}



