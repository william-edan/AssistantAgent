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
package com.alibaba.assistant.agent.controlplane.space;

import com.alibaba.assistant.agent.controlplane.space.mapper.PlatformSpaceMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Optional;

/**
 * 平台空间服务。
 *
 * <p>负责解析和查询平台空间，是运行时空间推断、发布范围控制和执行记录归属的基础服务。</p>
 */
@Service
public class PlatformSpaceService extends ServiceImpl<PlatformSpaceMapper, PlatformSpace> {

    private static final String STATUS_ACTIVE = "active";

    private static final String DEFAULT_ENVIRONMENT = "prod";

    private static final String DEFAULT_RUNTIME_SPACE_CODE = "default";

    /**
     * 按空间编码和环境查找启用中的空间。
     */
    public Optional<PlatformSpace> findActiveByCode(String spaceCode, String environment) {
        if (!StringUtils.hasText(spaceCode)) {
            return Optional.empty();
        }

        LambdaQueryWrapper<PlatformSpace> query = new LambdaQueryWrapper<>();
        query.eq(PlatformSpace::getSpaceCode, spaceCode);
        query.eq(PlatformSpace::getEnvironment, normalizeEnvironment(environment));
        query.eq(PlatformSpace::getStatus, STATUS_ACTIVE);
        query.orderByDesc(PlatformSpace::getId);
        return Optional.ofNullable(getOne(query, false));
    }

    /**
     * 解析运行时默认空间。
     * 优先使用约定的 default 空间；如果不存在且当前环境仅有一个启用空间，则回退到该唯一空间。
     */
    public Optional<PlatformSpace> resolveDefaultRuntimeSpace(@Nullable String environment) {
        String normalizedEnvironment = normalizeEnvironment(environment);
        Optional<PlatformSpace> defaultSpace = findActiveByCode(DEFAULT_RUNTIME_SPACE_CODE, normalizedEnvironment);
        if (defaultSpace.isPresent()) {
            return defaultSpace;
        }

        LambdaQueryWrapper<PlatformSpace> query = new LambdaQueryWrapper<>();
        query.eq(PlatformSpace::getEnvironment, normalizedEnvironment);
        query.eq(PlatformSpace::getStatus, STATUS_ACTIVE);
        query.orderByDesc(PlatformSpace::getId);
        query.last("LIMIT 2");
        List<PlatformSpace> candidates = list(query);
        return candidates.size() == 1 ? Optional.of(candidates.get(0)) : Optional.empty();
    }

    String normalizeEnvironment(@Nullable String environment) {
        return StringUtils.hasText(environment) ? environment.trim() : DEFAULT_ENVIRONMENT;
    }

}
