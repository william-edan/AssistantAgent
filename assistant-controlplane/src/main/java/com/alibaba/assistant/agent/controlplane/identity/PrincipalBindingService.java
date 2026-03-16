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
package com.alibaba.assistant.agent.controlplane.identity;

import com.alibaba.assistant.agent.controlplane.identity.mapper.PrincipalBindingMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.Optional;

/**
 * 主体绑定服务。
 *
 * <p>负责读取 {@code principal_binding} 表中的绑定关系，
 * 用于把平台主体映射到某个连接器下的真实外部主体。</p>
 */
@Service
public class PrincipalBindingService extends ServiceImpl<PrincipalBindingMapper, PrincipalBinding> {

	private static final String STATUS_ACTIVE = "active";

	/**
	 * 查找指定平台主体在某个空间和连接器下优先级最高的有效绑定。
	 *
	 * @param spaceId 空间 ID
	 * @param connectorId 连接器 ID
	 * @param platformPrincipalId 平台主体 ID
	 * @return 最匹配的一条有效绑定
	 */
    public Optional<PrincipalBinding> findHighestPriorityActiveBinding(
            Long spaceId, Long connectorId, String platformPrincipalId) {
		if (spaceId == null || connectorId == null || !StringUtils.hasText(platformPrincipalId)) {
			return Optional.empty();
		}

        LambdaQueryWrapper<PrincipalBinding> query = new LambdaQueryWrapper<>();
        query.eq(PrincipalBinding::getSpaceId, spaceId);
        query.eq(PrincipalBinding::getConnectorId, connectorId);
        query.eq(PrincipalBinding::getPlatformPrincipalId, platformPrincipalId);
        query.eq(PrincipalBinding::getStatus, STATUS_ACTIVE);
        query.orderByAsc(PrincipalBinding::getPriority);
        query.orderByAsc(PrincipalBinding::getId);
        return Optional.ofNullable(getOne(query, false));
    }

}
