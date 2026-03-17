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
package com.alibaba.assistant.agent.controlplane.rolepackage.mapper;

import com.alibaba.assistant.agent.controlplane.rolepackage.RolePackage;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Optional;

@Mapper
public interface RolePackageMapper extends BaseMapper<RolePackage> {

    default Optional<RolePackage> selectLatest(RolePackage lookup) {
        if (lookup == null) {
            return Optional.empty();
        }
        return selectLatest(
                lookup.getSpaceId(),
                lookup.getAgentAppCode(),
                lookup.getRoleCode(),
                lookup.getVersion());
    }

    default Optional<RolePackage> selectLatest(Long spaceId, String agentAppCode, String roleCode, String version) {
        if (spaceId == null || !StringUtils.hasText(agentAppCode) || !StringUtils.hasText(roleCode)) {
            return Optional.empty();
        }
        LambdaQueryWrapper<RolePackage> query = new LambdaQueryWrapper<>();
        query.eq(RolePackage::getSpaceId, spaceId);
        query.eq(RolePackage::getAgentAppCode, agentAppCode.trim());
        query.eq(RolePackage::getRoleCode, roleCode.trim());
        if (StringUtils.hasText(version)) {
            query.eq(RolePackage::getVersion, version.trim());
        }
        query.orderByDesc(RolePackage::getId);
        return Optional.ofNullable(selectOne(query));
    }

    default List<RolePackage> selectVersions(RolePackage lookup) {
        if (lookup == null) {
            return List.of();
        }
        return selectVersions(lookup.getSpaceId(), lookup.getAgentAppCode(), lookup.getRoleCode());
    }

    default List<RolePackage> selectVersions(Long spaceId, String agentAppCode, String roleCode) {
        if (spaceId == null || !StringUtils.hasText(agentAppCode) || !StringUtils.hasText(roleCode)) {
            return List.of();
        }
        LambdaQueryWrapper<RolePackage> query = new LambdaQueryWrapper<>();
        query.eq(RolePackage::getSpaceId, spaceId);
        query.eq(RolePackage::getAgentAppCode, agentAppCode.trim());
        query.eq(RolePackage::getRoleCode, roleCode.trim());
        query.orderByDesc(RolePackage::getId);
        return selectList(query);
    }

    default List<RolePackage> listByAgentApp(Long spaceId, String agentAppCode) {
        if (spaceId == null || !StringUtils.hasText(agentAppCode)) {
            return List.of();
        }
        LambdaQueryWrapper<RolePackage> query = new LambdaQueryWrapper<>();
        query.eq(RolePackage::getSpaceId, spaceId);
        query.eq(RolePackage::getAgentAppCode, agentAppCode.trim());
        query.orderByAsc(RolePackage::getRoleCode);
        query.orderByDesc(RolePackage::getId);
        return selectList(query);
    }

    default List<RolePackage> listPublished() {
        LambdaQueryWrapper<RolePackage> query = new LambdaQueryWrapper<>();
        query.eq(RolePackage::getStatus, "published");
        query.orderByAsc(RolePackage::getSpaceId);
        query.orderByAsc(RolePackage::getAgentAppCode);
        query.orderByAsc(RolePackage::getRoleCode);
        query.orderByDesc(RolePackage::getId);
        return selectList(query);
    }
}
