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
package com.alibaba.assistant.agent.api.service;

import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 聊天线程状态展示与下一步动作提示的公共规则。
 */
public final class ChatThreadActionSupport {

    private ChatThreadActionSupport() {
    }

    public static boolean isTerminalStatus(String status) {
        String normalized = normalizeStatus(status);
        return "COMPLETED".equals(normalized)
                || "DONE".equals(normalized)
                || "FAILED".equals(normalized)
                || "ERROR".equals(normalized);
    }

    public static boolean canResume(String status, String phase, boolean unfinished) {
        if (!unfinished) {
            return false;
        }
        String normalizedStatus = normalizeStatus(status);
        String normalizedPhase = normalizeStatus(phase);
        return "WAITING_INPUT".equals(normalizedStatus)
                || "WAITING_CONFIRMATION".equals(normalizedStatus)
                || "WAITING_APPROVAL".equals(normalizedStatus)
                || "COLLECTING".equals(normalizedPhase)
                || "CONFIRMING".equals(normalizedPhase)
                || "WAITING_APPROVAL".equals(normalizedPhase);
    }

    public static Map<String, Object> nextAction(String threadId, String status, String phase, boolean unfinished) {
        if (!unfinished) {
            return Map.of();
        }
        String normalizedStatus = normalizeStatus(status);
        String normalizedPhase = normalizeStatus(phase);
        Map<String, Object> action = new LinkedHashMap<>();
        if ("WAITING_APPROVAL".equals(normalizedStatus) || "WAITING_APPROVAL".equals(normalizedPhase)) {
            action.put("actionType", "RESUME_SSE");
            action.put("endpoint", "/api/chat/resume_sse");
            action.put("label", "继续审批流程");
        }
        else if ("WAITING_CONFIRMATION".equals(normalizedStatus) || "CONFIRMING".equals(normalizedPhase)) {
            action.put("actionType", "RUN_SSE");
            action.put("endpoint", "/api/chat/run_sse");
            action.put("label", "确认并提交");
            action.put("suggestedMessage", "确认");
        }
        else if ("WAITING_INPUT".equals(normalizedStatus) || "COLLECTING".equals(normalizedPhase)) {
            action.put("actionType", "RUN_SSE");
            action.put("endpoint", "/api/chat/run_sse");
            action.put("label", "继续补充信息");
        }
        else {
            action.put("actionType", "VIEW_THREAD");
            action.put("endpoint", "/api/chat/threads/{threadId}/state");
            action.put("label", "查看当前进度");
        }
        if (StringUtils.hasText(threadId)) {
            action.put("threadId", threadId.trim());
        }
        return action;
    }

    public static String normalizeStatus(String value) {
        if (!StringUtils.hasText(value)) {
            return "";
        }
        return value.trim().toUpperCase();
    }
}
