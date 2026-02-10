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
package com.alibaba.assistant.agent.start.saas.app.service;

import com.alibaba.assistant.agent.start.saas.app.model.ResolvedCapabilityInfo;
import com.alibaba.assistant.agent.start.saas.app.model.SlotCollectionResult;
import com.alibaba.assistant.agent.start.saas.context.SaaSTenantContextHolder;
import com.alibaba.assistant.agent.start.saas.controller.dto.GuardedChatRequest;
import com.alibaba.assistant.agent.start.saas.controller.dto.GuardedChatResponse;
import com.alibaba.assistant.agent.start.saas.domain.model.ExecutionStatus;
import com.alibaba.assistant.agent.start.saas.infrastructure.connector.ConnectorInvokeContext;
import com.alibaba.assistant.agent.start.saas.infrastructure.connector.ResolvedConnectorAuth;
import com.alibaba.assistant.agent.start.saas.infrastructure.mybatis.dataobject.ActionExecutionDO;
import com.alibaba.assistant.agent.start.saas.infrastructure.mybatis.dataobject.ConversationSessionDO;
import com.alibaba.assistant.agent.start.saas.infrastructure.mybatis.dataobject.ConnectorAuthDO;
import com.alibaba.assistant.agent.start.saas.infrastructure.mybatis.dataobject.ConnectorDO;
import com.alibaba.assistant.agent.start.saas.infrastructure.mybatis.dataobject.UserBindingDO;
import com.alibaba.assistant.agent.start.saas.infrastructure.mybatis.mapper.ActionExecutionMapper;
import com.alibaba.assistant.agent.start.saas.infrastructure.mybatis.mapper.ConnectorAuthMapper;
import com.alibaba.assistant.agent.start.saas.infrastructure.mybatis.mapper.ConnectorMapper;
import com.alibaba.assistant.agent.start.saas.infrastructure.mybatis.mapper.UserBindingMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Guarded action gateway service.
 *
 * @author Assistant Agent Team
 * @since 1.0.0
 */
@Service
public class ActionGatewayService {

    private static final Logger log = LoggerFactory.getLogger(ActionGatewayService.class);

    private final CapabilityAppService capabilityAppService;

    private final ActionExecutionMapper actionExecutionMapper;

    private final ConnectorMapper connectorMapper;

    private final ConnectorAuthMapper connectorAuthMapper;

    private final UserBindingMapper userBindingMapper;

    private final ConversationSessionService conversationSessionService;

    private final SlotCollectionService slotCollectionService;

    private final ConnectorAuthorizationService connectorAuthorizationService;

    private final CapabilityWorkflowService capabilityWorkflowService;

    private final ObjectMapper objectMapper;

    public ActionGatewayService(CapabilityAppService capabilityAppService, ActionExecutionMapper actionExecutionMapper,
            ConnectorMapper connectorMapper, ConnectorAuthMapper connectorAuthMapper, UserBindingMapper userBindingMapper,
            ConversationSessionService conversationSessionService, SlotCollectionService slotCollectionService,
            ConnectorAuthorizationService connectorAuthorizationService, CapabilityWorkflowService capabilityWorkflowService,
            ObjectMapper objectMapper) {
        this.capabilityAppService = capabilityAppService;
        this.actionExecutionMapper = actionExecutionMapper;
        this.connectorMapper = connectorMapper;
        this.connectorAuthMapper = connectorAuthMapper;
        this.userBindingMapper = userBindingMapper;
        this.conversationSessionService = conversationSessionService;
        this.slotCollectionService = slotCollectionService;
        this.connectorAuthorizationService = connectorAuthorizationService;
        this.capabilityWorkflowService = capabilityWorkflowService;
        this.objectMapper = objectMapper;
    }

    /**
     * Guard and execute conversation request.
     *
     * @param tenantId tenant id
     * @param sessionId session id
     * @param request request
     * @return response
     */
    @Transactional(rollbackFor = Exception.class)
    public GuardedChatResponse guardAndExecute(String tenantId, String sessionId, GuardedChatRequest request) {
        return runWithTenant(tenantId, () -> {
            ActionExecutionDO existed = actionExecutionMapper.selectOne(Wrappers.lambdaQuery(ActionExecutionDO.class)
                    .eq(ActionExecutionDO::getRequestId, request.getRequestId()));
            if (existed != null) {
                log.info("ActionGatewayService#guardAndExecute - reason=idempotent hit, tenantId={}, requestId={}",
                        tenantId, request.getRequestId());
                return toResponse(existed, "idempotent");
            }

            long start = System.currentTimeMillis();
            Optional<ResolvedCapabilityInfo> resolvedOptional = capabilityAppService.resolvePublishedVersion(
                    tenantId, request.getCapabilityId(), request.getCapabilityVersion());
            if (resolvedOptional.isEmpty()) {
                return rejectAndRecord(tenantId, sessionId, request, start,
                        "CAPABILITY_NOT_REGISTERED", "capability is not published or not found");
            }
            ResolvedCapabilityInfo resolved = resolvedOptional.get();

            ConnectorDO connector = connectorMapper.selectById(resolved.getConnectorId());
            if (connector == null || !"ACTIVE".equalsIgnoreCase(connector.getStatus())) {
                return rejectAndRecord(tenantId, sessionId, request, start,
                        "CONNECTOR_NOT_READY", "connector not found or inactive");
            }

            ConnectorAuthDO auth = connectorAuthMapper.selectOne(Wrappers.lambdaQuery(ConnectorAuthDO.class)
                    .eq(ConnectorAuthDO::getConnectorId, connector.getId()));
            if (auth == null || !"ACTIVE".equalsIgnoreCase(auth.getStatus())) {
                return rejectAndRecord(tenantId, sessionId, request, start,
                        "CONNECTOR_AUTH_REQUIRED", "connector auth is not configured");
            }

            UserBindingDO userBinding = null;
            if ("DELEGATED_USER".equalsIgnoreCase(resolved.getExecutionMode())) {
                userBinding = userBindingMapper.selectOne(Wrappers.lambdaQuery(UserBindingDO.class)
                        .eq(UserBindingDO::getPlatformUserId, request.getExecutorUserId())
                        .eq(UserBindingDO::getSystemCode, connector.getConnectorCode())
                        .eq(UserBindingDO::getStatus, "ACTIVE"));
                if (userBinding == null) {
                    return rejectAndRecord(tenantId, sessionId, request, start,
                            "USER_BINDING_REQUIRED", "delegated execution requires active user binding");
                }
            }

            ConversationSessionDO session = conversationSessionService.loadOrCreate(
                    tenantId, sessionId, resolved.getCapabilityId(), resolved.getVersionNo());
            Map<String, Object> slotSnapshot = conversationSessionService.getSlotSnapshot(session);
            SlotCollectionResult slotResult = slotCollectionService.collect(
                    resolved.getSlotSchemaJson(), resolved.getInputSchemaJson(), slotSnapshot, request.getInput());

            if (!slotResult.getMissingSlots().isEmpty()) {
                String missingMessage = "required slots missing: " + String.join(",", slotResult.getMissingSlots());
                conversationSessionService.markCollecting(session, slotResult.getMergedInput(), "SLOT_MISSING", missingMessage);
                return collectingAndRecord(tenantId, sessionId, request, resolved, start, slotResult.getMergedInput(),
                        slotResult.getMissingSlots(), missingMessage);
            }

            ResolvedConnectorAuth resolvedAuth = connectorAuthorizationService.resolve(connector, auth, userBinding);
            ConnectorInvokeContext context = buildInvokeContext(
                    tenantId, sessionId, request, resolved, connector, auth, resolvedAuth, userBinding, slotResult.getMergedInput());

            Map<String, Object> output;
            try {
                output = capabilityWorkflowService.execute(context, slotResult.getMergedInput());
            }
            catch (Exception ex) {
                log.error("ActionGatewayService#guardAndExecute - reason=workflow invoke failed, tenantId={}, capabilityId={}, error={}",
                        tenantId, resolved.getCapabilityId(), ex.getMessage(), ex);
                conversationSessionService.markFailed(session, slotResult.getMergedInput(),
                        "CONNECTOR_INVOKE_FAILED", ex.getMessage());
                return rejectAndRecord(tenantId, sessionId, request, start,
                        "CONNECTOR_INVOKE_FAILED", "connector invocation failed");
            }

            conversationSessionService.markDone(session, slotResult.getMergedInput());
            ActionExecutionDO execution = new ActionExecutionDO();
            execution.setTenantId(tenantId);
            execution.setSessionId(sessionId);
            execution.setRequestId(request.getRequestId());
            execution.setCapabilityId(resolved.getCapabilityId());
            execution.setCapabilityVersion(resolved.getVersionNo());
            execution.setExecutorUserId(request.getExecutorUserId());
            execution.setExecutionMode(resolved.getExecutionMode());
            execution.setInputJson(toJsonSafe(slotResult.getMergedInput()));
            execution.setOutputJson(toJsonSafe(output));
            execution.setStatus(ExecutionStatus.DONE.name());
            execution.setErrorCode(null);
            execution.setErrorMessage(null);
            execution.setCostMs(System.currentTimeMillis() - start);
            actionExecutionMapper.insert(execution);

            GuardedChatResponse response = toResponse(execution, "ok");
            response.setMessage("executed");
            response.setCollectedInput(slotResult.getMergedInput());
            response.setOutput(output);
            return response;
        });
    }

    private GuardedChatResponse collectingAndRecord(String tenantId, String sessionId, GuardedChatRequest request,
            ResolvedCapabilityInfo resolved, long startTime, Map<String, Object> mergedInput, List<String> missingSlots,
            String message) {
        ActionExecutionDO collecting = new ActionExecutionDO();
        collecting.setTenantId(tenantId);
        collecting.setSessionId(sessionId);
        collecting.setRequestId(request.getRequestId());
        collecting.setCapabilityId(resolved.getCapabilityId());
        collecting.setCapabilityVersion(resolved.getVersionNo());
        collecting.setExecutorUserId(request.getExecutorUserId());
        collecting.setExecutionMode(resolved.getExecutionMode());
        collecting.setInputJson(toJsonSafe(mergedInput));
        collecting.setOutputJson("{}");
        collecting.setStatus(ExecutionStatus.COLLECTING.name());
        collecting.setErrorCode("SLOT_MISSING");
        collecting.setErrorMessage(message);
        collecting.setCostMs(System.currentTimeMillis() - startTime);
        actionExecutionMapper.insert(collecting);

        GuardedChatResponse response = toResponse(collecting, "collecting");
        response.setMessage(message);
        response.setMissingSlots(missingSlots);
        response.setCollectedInput(mergedInput);
        return response;
    }

    private GuardedChatResponse rejectAndRecord(String tenantId, String sessionId, GuardedChatRequest request, long startTime,
            String errorCode, String errorMessage) {
        ActionExecutionDO rejected = new ActionExecutionDO();
        rejected.setTenantId(tenantId);
        rejected.setSessionId(sessionId);
        rejected.setRequestId(request.getRequestId());
        rejected.setCapabilityId(request.getCapabilityId());
        rejected.setCapabilityVersion(request.getCapabilityVersion() == null ? 0 : request.getCapabilityVersion());
        rejected.setExecutorUserId(request.getExecutorUserId());
        rejected.setExecutionMode("UNKNOWN");
        rejected.setInputJson(toJsonSafe(request.getInput()));
        rejected.setOutputJson("{}");
        rejected.setStatus(ExecutionStatus.REJECTED.name());
        rejected.setErrorCode(errorCode);
        rejected.setErrorMessage(errorMessage);
        rejected.setCostMs(System.currentTimeMillis() - startTime);
        actionExecutionMapper.insert(rejected);

        GuardedChatResponse response = toResponse(rejected, "rejected");
        response.setMessage(errorMessage);
        return response;
    }

    private ConnectorInvokeContext buildInvokeContext(String tenantId, String sessionId, GuardedChatRequest request,
            ResolvedCapabilityInfo resolved, ConnectorDO connector, ConnectorAuthDO auth, ResolvedConnectorAuth resolvedAuth,
            UserBindingDO userBinding, Map<String, Object> mergedInput) {
        ConnectorInvokeContext context = new ConnectorInvokeContext();
        context.setTenantId(tenantId);
        context.setSessionId(sessionId);
        context.setRequest(request);
        context.setCapability(resolved);
        context.setConnector(connector);
        context.setConnectorAuth(auth);
        context.setStepInput(mergedInput);
        context.setAuthHeaders(resolvedAuth.getHeaders());
        context.setAuthCookies(resolvedAuth.getCookies());
        context.setDelegatedExternalUserId(userBinding == null ? null : userBinding.getExternalUserId());
        return context;
    }

    private GuardedChatResponse toResponse(ActionExecutionDO record, String message) {
        GuardedChatResponse response = new GuardedChatResponse();
        response.setExecutionId(record.getId());
        response.setResolvedCapabilityId(record.getCapabilityId());
        response.setResolvedVersion(record.getCapabilityVersion());
        response.setStatus(record.getStatus());
        response.setErrorCode(record.getErrorCode());
        response.setMessage(message);
        return response;
    }

    private String toJsonSafe(Object value) {
        if (value == null) {
            return "{}";
        }
        try {
            return objectMapper.writeValueAsString(value);
        }
        catch (JsonProcessingException ex) {
            return "{}";
        }
    }

    private <T> T runWithTenant(String tenantId, Supplier<T> supplier) {
        String old = SaaSTenantContextHolder.getTenantId();
        SaaSTenantContextHolder.setTenantId(tenantId);
        try {
            return supplier.get();
        }
        finally {
            if (old == null || old.isBlank()) {
                SaaSTenantContextHolder.clear();
            }
            else {
                SaaSTenantContextHolder.setTenantId(old);
            }
        }
    }
}
