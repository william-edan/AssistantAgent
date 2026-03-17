/*
 * Copyright 2024-2025 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 */
package com.alibaba.assistant.agent.execution.persistence.mapper;

import com.alibaba.assistant.agent.execution.persistence.ProactiveRunLease;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.Optional;

@Mapper
public interface ProactiveRunLeaseMapper extends BaseMapper<ProactiveRunLease> {

    default ProactiveRunLease findByTaskKeyAndScheduledAt(String taskKey, LocalDateTime scheduledAt) {
        if (!StringUtils.hasText(taskKey) || scheduledAt == null) {
            return null;
        }
        LambdaQueryWrapper<ProactiveRunLease> query = new LambdaQueryWrapper<>();
        query.eq(ProactiveRunLease::getTaskKey, taskKey.trim());
        query.eq(ProactiveRunLease::getScheduledAt, scheduledAt);
        query.orderByDesc(ProactiveRunLease::getId);
        return selectOne(query);
    }

    default Optional<LocalDateTime> findLatestScheduledAt(String taskKey) {
        if (!StringUtils.hasText(taskKey)) {
            return Optional.empty();
        }
        LambdaQueryWrapper<ProactiveRunLease> query = new LambdaQueryWrapper<>();
        query.eq(ProactiveRunLease::getTaskKey, taskKey.trim());
        query.orderByDesc(ProactiveRunLease::getScheduledAt);
        ProactiveRunLease lease = selectOne(query);
        return Optional.ofNullable(lease != null ? lease.getScheduledAt() : null);
    }

    @Update("""
            UPDATE proactive_run_lease
            SET lease_owner = #{leaseOwner},
                lease_until = #{leaseUntil},
                run_status = 'LEASED',
                updated_at = CURRENT_TIMESTAMP
            WHERE id = #{id}
              AND (lease_owner IS NULL OR lease_owner = #{leaseOwner} OR lease_until IS NULL OR lease_until <= #{now})
              AND run_status NOT IN ('COMPLETED', 'FAILED')
            """)
    int acquireLease(
            @Param("id") Long id,
            @Param("leaseOwner") String leaseOwner,
            @Param("leaseUntil") LocalDateTime leaseUntil,
            @Param("now") LocalDateTime now);

    @Update("""
            UPDATE proactive_run_lease
            SET lease_until = #{leaseUntil},
                updated_at = CURRENT_TIMESTAMP
            WHERE id = #{id}
              AND lease_owner = #{leaseOwner}
              AND run_status = 'LEASED'
              AND (lease_until IS NULL OR lease_until >= #{now})
            """)
    int heartbeat(
            @Param("id") Long id,
            @Param("leaseOwner") String leaseOwner,
            @Param("leaseUntil") LocalDateTime leaseUntil,
            @Param("now") LocalDateTime now);
}
