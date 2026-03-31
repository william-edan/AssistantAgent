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
package com.alibaba.assistant.agent.start.profile.protocol;

import com.alibaba.assistant.agent.api.protocol.FrontendEvent;
import com.alibaba.assistant.agent.api.protocol.ProtocolPayloadSupport;
import com.alibaba.assistant.agent.api.protocol.ProtocolStrategy;
import com.alibaba.assistant.agent.start.profile.tool.ProfileQueryTool;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 个人档案查询前端协议策略。
 *
 * <p>该策略用于把 {@code profile_query} 工具的结果复用到现有前端可见消息协议，
 * 避免修改核心 Agent 流程或前端协议适配主链路。</p>
 */
@Component
@Profile("migration")
@Order(125)
public class ProfileQueryProtocolStrategy implements ProtocolStrategy {

    private final ProtocolPayloadSupport payloadSupport;

    public ProfileQueryProtocolStrategy(ProtocolPayloadSupport payloadSupport) {
        this.payloadSupport = payloadSupport;
    }

    /**
     * 判断当前工具输出是否由个人档案查询工具产生。
     *
     * @param normalizedToolName 归一化后的工具名
     * @param payload 工具返回负载
     * @return 命中返回 true
     */
    @Override
    public boolean supports(String normalizedToolName, Map<String, Object> payload) {
        return ProfileQueryTool.TOOL_NAME.equals(normalizedToolName);
    }

    /**
     * 将个人档案查询结果转换为前端可见消息事件。
     *
     * @param threadId 线程 ID
     * @param normalizedToolName 归一化后的工具名
     * @param payload 工具返回负载
     * @param state 当前线程状态
     * @return 前端事件列表
     */
    @Override
    public List<FrontendEvent> adapt(
            String threadId,
            String normalizedToolName,
            Map<String, Object> payload,
            Map<String, Object> state) {
        return payloadSupport.adaptVisibleMessage(threadId, normalizePayload(payload), null);
    }

    /**
     * 将个人档案查询结果投影为线程快照，保证刷新后仍能恢复最后一条可见消息。
     *
     * @param normalizedToolName 归一化后的工具名
     * @param payload 工具返回负载
     * @param state 当前线程状态
     * @return 线程快照
     */
    @Override
    public Map<String, Object> projectThreadState(
            String normalizedToolName,
            Map<String, Object> payload,
            Map<String, Object> state) {
        Map<String, Object> normalizedPayload = normalizePayload(payload);
        return payloadSupport.projectReplyState(
                normalizedPayload,
                String.valueOf(normalizedPayload.getOrDefault("message", "")),
                state);
    }

    /**
     * 统一补齐 message 字段，兼容工具当前返回的 reply 语义。
     *
     * @param payload 原始工具负载
     * @return 标准化后的负载
     */
    private Map<String, Object> normalizePayload(Map<String, Object> payload) {
        Map<String, Object> normalizedPayload = payload != null ? new LinkedHashMap<>(payload) : new LinkedHashMap<>();
        if (!StringUtils.hasText(String.valueOf(normalizedPayload.getOrDefault("message", "")))) {
            Object reply = normalizedPayload.get("reply");
            if (reply != null) {
                normalizedPayload.put("message", String.valueOf(reply));
            }
        }
        return normalizedPayload;
    }
}
