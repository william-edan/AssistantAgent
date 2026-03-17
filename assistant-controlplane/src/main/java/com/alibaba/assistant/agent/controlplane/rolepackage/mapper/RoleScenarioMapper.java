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

import com.alibaba.assistant.agent.controlplane.rolepackage.RoleScenario;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

@Mapper
public interface RoleScenarioMapper extends BaseMapper<RoleScenario> {

    default List<RoleScenario> listByRolePackageId(Long rolePackageId) {
        if (rolePackageId == null) {
            return List.of();
        }
        LambdaQueryWrapper<RoleScenario> query = new LambdaQueryWrapper<>();
        query.eq(RoleScenario::getRolePackageId, rolePackageId);
        query.orderByAsc(RoleScenario::getSortOrder);
        query.orderByAsc(RoleScenario::getId);
        return selectList(query);
    }

    default int deleteByRolePackageId(Long rolePackageId) {
        if (rolePackageId == null) {
            return 0;
        }
        LambdaQueryWrapper<RoleScenario> query = new LambdaQueryWrapper<>();
        query.eq(RoleScenario::getRolePackageId, rolePackageId);
        return delete(query);
    }
}
