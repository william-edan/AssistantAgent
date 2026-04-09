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
package com.alibaba.assistant.agent.start.car.model;

import java.math.BigDecimal;

/**
 * 新增车辆费用请求。
 *
 * @param carName 车辆名称
 * @param carId 车辆ID
 * @param types 费用类型
 * @param title 费用主题
 * @param feeTime 费用日期
 * @param amount 费用金额
 * @param handledName 经手人姓名
 * @param handled 经手人ID
 * @param fileIds 附件ID列表（逗号分隔）
 * @param content 备注
 */
public record CarFeeAddRequest(
        String carName,
        String carId,
        String types,
        String title,
        String feeTime,
        BigDecimal amount,
        String handledName,
        String handled,
        String fileIds,
        String content) {
}

