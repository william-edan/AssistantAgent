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
package com.alibaba.assistant.agent.start.reward.model;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 奖惩保存请求。
 *
 * @param rewardsCate 奖惩分类
 * @param types 奖惩类型
 * @param uname 员工姓名
 * @param uid 员工 ID
 * @param cost 金额
 * @param rewardsTime 奖惩日期
 * @param remark 备注
 */
public record RewardAddRequest(
        String rewardsCate,
        Integer types,
        String uname,
        Long uid,
        BigDecimal cost,
        LocalDate rewardsTime,
        String remark) {
}
