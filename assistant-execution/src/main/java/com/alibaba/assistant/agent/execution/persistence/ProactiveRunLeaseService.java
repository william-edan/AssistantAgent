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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Optional;

@Service
public class ProactiveRunLeaseService {

    private final ProactiveRunLeaseMapper proactiveRunLeaseMapper;
    private final Clock clock;

    @Autowired
    public ProactiveRunLeaseService(ProactiveRunLeaseMapper proactiveRunLeaseMapper) {
        this(proactiveRunLeaseMapper, Clock.systemDefaultZone());
    }

        public ProactiveRunLeaseService(ProactiveRunLeaseMapper proactiveRunLeaseMapper, Clock clock) {
        this.proactiveRunLeaseMapper = proactiveRunLeaseMapper;
        this.clock = clock;
    }

    public Optional<ProactiveRunLease> acquire(
            String taskKey,
            LocalDateTime scheduledAt,
            String leaseOwner,
            Duration leaseDuration) {
        if (!StringUtils.hasText(taskKey) || scheduledAt == null || !StringUtils.hasText(leaseOwner) || leaseDuration == null) {
            return Optional.empty();
        }
        ProactiveRunLease lease = proactiveRunLeaseMapper.findByTaskKeyAndScheduledAt(taskKey.trim(), scheduledAt);
        if (lease == null) {
            ProactiveRunLease created = new ProactiveRunLease();
            created.setTaskKey(taskKey.trim());
            created.setDedupKey(taskKey.trim() + "@" + scheduledAt);
            created.setScheduledAt(scheduledAt);
            created.setRunStatus("PENDING");
            proactiveRunLeaseMapper.insert(created);
            lease = proactiveRunLeaseMapper.findByTaskKeyAndScheduledAt(taskKey.trim(), scheduledAt);
        }
        if (lease == null || lease.getId() == null) {
            return Optional.empty();
        }
        LocalDateTime now = LocalDateTime.now(clock);
        LocalDateTime leaseUntil = now.plus(leaseDuration);
        if (proactiveRunLeaseMapper.acquireLease(lease.getId(), leaseOwner.trim(), leaseUntil, now) <= 0) {
            return Optional.empty();
        }
        lease.setLeaseOwner(leaseOwner.trim());
        lease.setLeaseUntil(leaseUntil);
        lease.setRunStatus("LEASED");
        return Optional.of(lease);
    }

    public boolean heartbeat(Long leaseId, String leaseOwner, Duration leaseDuration) {
        if (leaseId == null || !StringUtils.hasText(leaseOwner) || leaseDuration == null) {
            return false;
        }
        LocalDateTime now = LocalDateTime.now(clock);
        return proactiveRunLeaseMapper.heartbeat(leaseId, leaseOwner.trim(), now.plus(leaseDuration), now) > 0;
    }

    public Optional<LocalDateTime> findLatestScheduledAt(String taskKey) {
        // 快速定位：主动任务扫描的“游标时间”统一从这里读取。
        return proactiveRunLeaseMapper.findLatestScheduledAt(taskKey);
    }

    public void markSucceeded(ProactiveRunLease lease, String runId) {
        if (lease == null || lease.getId() == null) {
            return;
        }
        lease.setRunStatus("COMPLETED");
        lease.setRunId(runId);
        proactiveRunLeaseMapper.updateById(lease);
    }

    public void markFailed(ProactiveRunLease lease, String errorMessage) {
        if (lease == null || lease.getId() == null) {
            return;
        }
        lease.setRunStatus("FAILED");
        lease.setLastError(errorMessage);
        proactiveRunLeaseMapper.updateById(lease);
    }
}



