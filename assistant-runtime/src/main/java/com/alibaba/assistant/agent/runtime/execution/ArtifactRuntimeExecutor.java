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
package com.alibaba.assistant.agent.runtime.execution;

import com.alibaba.assistant.agent.execution.flow.DAGFlowExecutor;
import com.alibaba.assistant.agent.execution.flow.FlowContext;
import com.alibaba.assistant.agent.execution.flow.FlowExecutionListener;
import com.alibaba.assistant.agent.execution.flow.FlowExecutionResult;
import com.alibaba.assistant.agent.execution.model.StepDefinition;
import com.alibaba.assistant.agent.execution.model.StepResult;
import com.alibaba.assistant.agent.execution.model.StepStatus;
import com.alibaba.assistant.agent.runtime.agent.AssistantStateKeys;
import com.alibaba.assistant.agent.runtime.compiler.RuntimeArtifact;
import com.alibaba.assistant.agent.runtime.context.RuntimeSpaceResolver;
import com.alibaba.assistant.agent.runtime.planner.ToolExecutor;
import com.alibaba.assistant.agent.runtime.registry.PublishedToolDescriptor;
import com.alibaba.assistant.agent.runtime.task.AgentTaskProjector;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.agent.tools.ToolContextConstants;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 运行时执行器。
 *
 * <p>负责执行已经发布的动作或工作流，并把执行过程中的事件同时写入事件流、任务投影和持久化记录。
 * 这是企业业务真正落地到执行引擎的核心入口。</p>
 */
@Component
public class ArtifactRuntimeExecutor {


    private final DAGFlowExecutor dagFlowExecutor;

    private final CredentialBroker credentialBroker;

    private final ExecutionRuntimePersistenceRecorder persistenceRecorder;

    private final ExecutionEventStreamRegistry executionEventStreamRegistry;

    private final AgentTaskProjector agentTaskProjector;

    private final RuntimeSpaceResolver runtimeSpaceResolver;

    private final ObjectMapper objectMapper;

    public ArtifactRuntimeExecutor(DAGFlowExecutor dagFlowExecutor) {
        this(dagFlowExecutor, null, null, null, null, null, new ObjectMapper());
    }

    public ArtifactRuntimeExecutor(
            DAGFlowExecutor dagFlowExecutor,
            @Nullable CredentialBroker credentialBroker,
            ObjectMapper objectMapper) {
        this(dagFlowExecutor, credentialBroker, null, null, null, null, objectMapper);
    }

    public ArtifactRuntimeExecutor(
            DAGFlowExecutor dagFlowExecutor,
            @Nullable CredentialBroker credentialBroker,
            @Nullable ExecutionRuntimePersistenceRecorder persistenceRecorder,
            ObjectMapper objectMapper) {
        this(dagFlowExecutor, credentialBroker, persistenceRecorder, null, null, null, objectMapper);
    }

    public ArtifactRuntimeExecutor(
            DAGFlowExecutor dagFlowExecutor,
            @Nullable CredentialBroker credentialBroker,
            @Nullable ExecutionRuntimePersistenceRecorder persistenceRecorder,
            @Nullable ExecutionEventStreamRegistry executionEventStreamRegistry,
            @Nullable AgentTaskProjector agentTaskProjector,
            ObjectMapper objectMapper) {
        this(dagFlowExecutor, credentialBroker, persistenceRecorder, executionEventStreamRegistry,
                agentTaskProjector, null, objectMapper);
    }

    @Autowired
    public ArtifactRuntimeExecutor(
            DAGFlowExecutor dagFlowExecutor,
            @Nullable CredentialBroker credentialBroker,
            @Nullable ExecutionRuntimePersistenceRecorder persistenceRecorder,
            @Nullable ExecutionEventStreamRegistry executionEventStreamRegistry,
            @Nullable AgentTaskProjector agentTaskProjector,
            @Nullable RuntimeSpaceResolver runtimeSpaceResolver,
            ObjectMapper objectMapper) {
        this.dagFlowExecutor = dagFlowExecutor;
        this.credentialBroker = credentialBroker;
        this.persistenceRecorder = persistenceRecorder;
        this.executionEventStreamRegistry = executionEventStreamRegistry;
        this.agentTaskProjector = agentTaskProjector;
        this.runtimeSpaceResolver = runtimeSpaceResolver;
        this.objectMapper = objectMapper;
    }

    /**
     * 直接执行已发布的运行时工作流。
     */
    public Map<String, Object> execute(
            PublishedToolDescriptor descriptor,
            Map<String, Object> arguments,
            @Nullable ToolContext toolContext) {
        if (descriptor == null || descriptor.artifact() == null) {
            return Map.of("success", false, "error", "Published artifact descriptor is missing");
        }
        String runId = UUID.randomUUID().toString();
        FlowContext flowContext = buildFlowContext(descriptor, arguments, toolContext, runId);
        return executeInternal(descriptor, flowContext, false);
    }

    /**
     * 恢复一个已经暂停的运行时工作流。
     */
    public Map<String, Object> resume(PublishedToolDescriptor descriptor, FlowContext flowContext) {
        if (descriptor == null || descriptor.artifact() == null || flowContext == null) {
            return Map.of("success", false, "error", "Published artifact descriptor is missing");
        }
        if (!StringUtils.hasText(flowContext.getRunId())) {
            flowContext.setRunId(UUID.randomUUID().toString());
        }
        return executeInternal(descriptor, flowContext, true);
    }

    private Map<String, Object> executeInternal(
            PublishedToolDescriptor descriptor,
            FlowContext flowContext,
            boolean resumed) {
        String runId = flowContext.getRunId();
        RuntimeExecutionEventCollector executionEventCollector = new RuntimeExecutionEventCollector(
                runId,
                descriptor,
                flowContext,
                flowContext.getThreadId(),
                executionEventStreamRegistry,
                agentTaskProjector);
        if (resumed) {
            executionEventCollector.recordRunResumed();
        }
        else {
            executionEventCollector.recordRunStarted();
        }
        flowContext.addExecutionListener(executionEventCollector);
        resolveStepCredentials(descriptor, flowContext);
        FlowExecutionResult flowResult = dagFlowExecutor.execute(descriptor.artifact().getFlowDefinition(), flowContext);
        if ("WAITING_APPROVAL".equalsIgnoreCase(flowResult.getLifecycleStatus())
                && !StringUtils.hasText(flowResult.getApprovalRequestId())
                && StringUtils.hasText(flowResult.getPausedStepId())) {
            flowResult.setApprovalRequestId(runId + ":" + flowResult.getPausedStepId());
        }
        executionEventCollector.recordRunTerminal(flowResult);
        List<ExecutionEvent> executionEvents = executionEventCollector.hasStepEvents()
                ? executionEventCollector.events()
                : buildExecutionEvents(runId, descriptor, flowResult, flowContext, resumed);
        if (persistenceRecorder != null) {
            persistenceRecorder.record(descriptor, flowContext, flowResult, executionEvents);
        }

        Map<String, Object> payload = appendExecutionMeta(new LinkedHashMap<>(), descriptor, flowContext);
        payload.put("success", flowResult.isSuccess());
        payload.put("runId", runId);
        payload.put("artifactCode", descriptor.artifact().getArtifactCode());
        payload.put("artifactType", descriptor.artifact().getArtifactType().name());
        payload.put("lifecycleStatus", flowResult.getLifecycleStatus());
        payload.put("pausedStepId", flowResult.getPausedStepId());
        payload.put("approvalRequestId", flowResult.getApprovalRequestId());
        payload.put("finalOutputs", flowResult.getFinalOutputs());
        payload.put("stepStatuses", flowResult.getStepStatuses());
        payload.put("stepResults", flowResult.getStepResults());
        payload.put("executionEvents", executionEvents);
        payload.put("durationMs", flowResult.getDurationMs());
        payload.put("error", flowResult.getErrorMessage());
        return payload;
    }

    private FlowContext buildFlowContext(
            PublishedToolDescriptor descriptor,
            Map<String, Object> arguments,
            @Nullable ToolContext toolContext,
            String runId) {
        Map<String, Object> safeArguments = arguments != null ? new LinkedHashMap<>(arguments) : new LinkedHashMap<>();
        if (isInternalDependencyCall(toolContext, safeArguments)) {
            safeArguments.put(ToolExecutor.INTERNAL_DEPENDENCY_CALL_KEY, true);
        }
        Long resolvedSpaceId = firstNonNull(
                descriptor != null && descriptor.artifact() != null ? descriptor.artifact().getSpaceId() : null,
                asLong(readContextValue(toolContext, AssistantStateKeys.SPACE_ID, "space_id", "spaceId")),
                asLong(safeArguments.get(AssistantStateKeys.SPACE_ID)),
                asLong(safeArguments.get("space_id")),
                asLong(safeArguments.get("spaceId")));
        String resolvedSpaceCode = firstNonBlank(
                readContextText(toolContext, AssistantStateKeys.SPACE_CODE),
                readContextText(toolContext, "space_code"),
                readContextText(toolContext, "spaceCode"),
                asText(safeArguments.get(AssistantStateKeys.SPACE_CODE)),
                asText(safeArguments.get("space_code")),
                asText(safeArguments.get("spaceCode")));
        String resolvedSpaceEnvironment = firstNonBlank(
                readContextText(toolContext, AssistantStateKeys.SPACE_ENVIRONMENT),
                readContextText(toolContext, "space_environment"),
                readContextText(toolContext, "spaceEnvironment"),
                readContextText(toolContext, "environment"),
                asText(safeArguments.get(AssistantStateKeys.SPACE_ENVIRONMENT)),
                asText(safeArguments.get("space_environment")),
                asText(safeArguments.get("spaceEnvironment")),
                asText(safeArguments.get("environment")));
        if ((resolvedSpaceId == null || !StringUtils.hasText(resolvedSpaceCode)) && runtimeSpaceResolver != null) {
            Map<String, Object> spaceContext = new LinkedHashMap<>();
            if (toolContext != null && toolContext.getContext() != null) {
                spaceContext.putAll(toolContext.getContext());
            }
            spaceContext.putAll(safeArguments);
            RuntimeSpaceResolver.ResolvedSpace resolvedSpace = runtimeSpaceResolver.resolve(spaceContext);
            resolvedSpaceId = firstNonNull(resolvedSpaceId, resolvedSpace.spaceId());
            resolvedSpaceCode = firstNonBlank(resolvedSpaceCode, resolvedSpace.spaceCode());
            resolvedSpaceEnvironment = firstNonBlank(resolvedSpaceEnvironment, resolvedSpace.environment());
        }
        if (resolvedSpaceId != null) {
            safeArguments.putIfAbsent(AssistantStateKeys.SPACE_ID, resolvedSpaceId);
            safeArguments.putIfAbsent("space_id", resolvedSpaceId);
            safeArguments.putIfAbsent("spaceId", resolvedSpaceId);
        }
        if (StringUtils.hasText(resolvedSpaceCode)) {
            safeArguments.putIfAbsent(AssistantStateKeys.SPACE_CODE, resolvedSpaceCode);
            safeArguments.putIfAbsent("space_code", resolvedSpaceCode);
            safeArguments.putIfAbsent("spaceCode", resolvedSpaceCode);
        }
        if (StringUtils.hasText(resolvedSpaceEnvironment)) {
            safeArguments.putIfAbsent(AssistantStateKeys.SPACE_ENVIRONMENT, resolvedSpaceEnvironment);
            safeArguments.putIfAbsent("space_environment", resolvedSpaceEnvironment);
            safeArguments.putIfAbsent("spaceEnvironment", resolvedSpaceEnvironment);
            safeArguments.putIfAbsent("environment", resolvedSpaceEnvironment);
        }
        FlowContext flowContext = new FlowContext(safeArguments);
        flowContext.setRunId(runId);
        flowContext.setSystemCode(firstNonBlank(
                descriptor.executionSystemCode(),
                readContextText(toolContext, AssistantStateKeys.SYSTEM_CODE),
                readContextText(toolContext, "systemCode"),
                asText(safeArguments.get(AssistantStateKeys.SYSTEM_CODE)),
                asText(safeArguments.get("systemCode"))));
        flowContext.setAssistantUid(firstNonBlank(
                readContextText(toolContext, AssistantStateKeys.ASSISTANT_UID),
                readContextText(toolContext, "assistantUid"),
                asText(safeArguments.get(AssistantStateKeys.ASSISTANT_UID)),
                asText(safeArguments.get("assistantUid"))));
        flowContext.setThreadId(firstNonBlank(
                readContextText(toolContext, AssistantStateKeys.THREAD_ID),
                readContextText(toolContext, "thread_id"),
                asText(safeArguments.get(AssistantStateKeys.THREAD_ID)),
                asText(safeArguments.get("thread_id"))));
        return flowContext;
    }

    private boolean isInternalDependencyCall(@Nullable ToolContext toolContext, Map<String, Object> arguments) {
        if (arguments != null && asBoolean(arguments.get(ToolExecutor.INTERNAL_DEPENDENCY_CALL_KEY))) {
            return true;
        }
        if (toolContext == null || toolContext.getContext() == null) {
            return false;
        }
        return asBoolean(toolContext.getContext().get(ToolExecutor.INTERNAL_DEPENDENCY_CALL_KEY));
    }

    private boolean isInternalDependencyCall(@Nullable FlowContext flowContext) {
        if (flowContext == null || flowContext.getInitialInputs().isEmpty()) {
            return false;
        }
        return asBoolean(flowContext.getInitialInputs().get(ToolExecutor.INTERNAL_DEPENDENCY_CALL_KEY));
    }

    private Map<String, Object> appendExecutionMeta(
            Map<String, Object> target,
            PublishedToolDescriptor descriptor,
            @Nullable FlowContext flowContext) {
        Map<String, Object> payload = target != null ? target : new LinkedHashMap<>();
        if (isInternalDependencyCall(flowContext)) {
            payload.put("internal", true);
        }
        putIfHasText(payload, "toolType", descriptor != null ? descriptor.toolType() : null);
        putIfHasText(payload, "visibility", descriptor != null ? descriptor.visibility() : null);
        putIfHasText(payload, "invocationPolicy", descriptor != null ? descriptor.invocationPolicy() : null);
        return payload;
    }

    private void putIfHasText(Map<String, Object> target, String key, @Nullable String value) {
        if (target != null && StringUtils.hasText(key) && StringUtils.hasText(value)) {
            target.put(key, value);
        }
    }

    private void resolveStepCredentials(PublishedToolDescriptor descriptor, FlowContext flowContext) {
        if (credentialBroker == null || descriptor == null || descriptor.artifact() == null || flowContext == null) {
            return;
        }
        RuntimeArtifact artifact = descriptor.artifact();
        if (artifact.getSteps().isEmpty()) {
            return;
        }
        String platformPrincipalId = resolvePlatformPrincipalId(flowContext);
        if (!StringUtils.hasText(platformPrincipalId)) {
            return;
        }
        String platformPrincipalType = resolvePlatformPrincipalType(flowContext);
        Long spaceId = resolveSpaceId(artifact, flowContext);
        String agentAppCode = resolveFlowInput(flowContext,
                AssistantStateKeys.AGENT_APP_CODE,
                "agent_app_code",
                "agentAppCode");
        String rolePackageCode = resolveFlowInput(flowContext,
                AssistantStateKeys.ROLE_PACKAGE_CODE,
                "role_package_code",
                "rolePackageCode");
        String rolePackageVersion = resolveFlowInput(flowContext,
                AssistantStateKeys.ROLE_PACKAGE_VERSION,
                "role_package_version",
                "rolePackageVersion");
        String scenarioCode = resolveFlowInput(flowContext,
                AssistantStateKeys.ROLE_SCENARIO_CODE,
                "role_scenario_code",
                "roleScenarioCode");
        String executionSubjectType = resolveFlowInput(flowContext,
                AssistantStateKeys.EXECUTION_SUBJECT_TYPE,
                "execution_subject_type",
                "executionSubjectType");
        String executionSubjectId = resolveFlowInput(flowContext,
                AssistantStateKeys.EXECUTION_SUBJECT_ID,
                "execution_subject_id",
                "executionSubjectId");
        for (Map.Entry<String, RuntimeArtifact.StepBinding> entry : artifact.getSteps().entrySet()) {
            String stepId = entry.getKey();
            RuntimeArtifact.StepBinding stepBinding = entry.getValue();
            if (stepBinding == null) {
                continue;
            }
            Long connectorId = stepBinding.connectorId() != null
                    ? stepBinding.connectorId()
                    : stepBinding.action() != null ? stepBinding.action().connectorId() : null;
            if (connectorId == null) {
                continue;
            }
            ResolvedCredentialLease lease = credentialBroker.resolve(new CredentialResolutionRequest(
                    spaceId,
                    connectorId,
                    resolveCandidateAuthProfileCodes(stepBinding),
                    platformPrincipalId,
                    platformPrincipalType,
                    List.of(),
                    flowContext.getRunId(),
                    stepId,
                    agentAppCode,
                    rolePackageCode,
                    rolePackageVersion,
                    scenarioCode,
                    executionSubjectType,
                    executionSubjectId));
            flowContext.putStepRequestHeaders(stepId, lease.headers());
            if (StringUtils.hasText(lease.baseUrl())) {
                flowContext.putStepBaseUrl(stepId, lease.baseUrl());
            }
        }
    }

    private String resolvePlatformPrincipalId(FlowContext flowContext) {
        return firstNonBlank(
                resolveFlowInput(flowContext,
                        AssistantStateKeys.PLATFORM_PRINCIPAL_ID,
                        "platform_principal_id",
                        "platformPrincipalId"),
                flowContext.getAssistantUid());
    }

    private String resolvePlatformPrincipalType(FlowContext flowContext) {
        String explicitType = resolveFlowInput(flowContext,
                AssistantStateKeys.PLATFORM_PRINCIPAL_TYPE,
                "platform_principal_type",
                "platformPrincipalType");
        if (StringUtils.hasText(explicitType)) {
            return explicitType;
        }
        return StringUtils.hasText(flowContext.getAssistantUid()) ? "local_user" : null;
    }

    private String resolveFlowInput(FlowContext flowContext, String... keys) {
        if (flowContext == null || flowContext.getInitialInputs().isEmpty()) {
            return null;
        }
        for (String key : keys) {
            if (!StringUtils.hasText(key)) {
                continue;
            }
            Object value = flowContext.getInitialInputs().get(key);
            String text = asText(value);
            if (StringUtils.hasText(text)) {
                return text;
            }
        }
        return null;
    }

    private List<String> resolveCandidateAuthProfileCodes(RuntimeArtifact.StepBinding stepBinding) {
        Set<String> codes = new LinkedHashSet<>();
        addJsonArrayValues(codes, stepBinding.allowedAuthProfilesJson());
        if (stepBinding.action() != null) {
            addJsonArrayValues(codes, stepBinding.action().allowedAuthProfilesJson());
            if (StringUtils.hasText(stepBinding.action().defaultAuthProfileCode())) {
                codes.add(stepBinding.action().defaultAuthProfileCode().trim());
            }
        }
        return List.copyOf(codes);
    }

    private void addJsonArrayValues(Set<String> target, String jsonArrayText) {
        if (target == null || !StringUtils.hasText(jsonArrayText)) {
            return;
        }
        try {
            List<String> values = objectMapper.readValue(jsonArrayText, new TypeReference<List<String>>() {
            });
            if (values == null) {
                return;
            }
            for (String value : values) {
                if (StringUtils.hasText(value)) {
                    target.add(value.trim());
                }
            }
        }
        catch (Exception ignored) {
            // 容忍脏配置，避免执行阶段因历史数据中断。
        }
    }

    private List<ExecutionEvent> buildExecutionEvents(
            String runId,
            PublishedToolDescriptor descriptor,
            FlowExecutionResult flowResult,
            FlowContext flowContext,
            boolean resumed) {
        if (descriptor == null || descriptor.artifact() == null || flowResult == null) {
            return List.of();
        }
        List<ExecutionEvent> events = new ArrayList<>();
        long sequence = 1L;
        events.add(new ExecutionEvent(
                runId,
                descriptor.artifact().getArtifactCode(),
                descriptor.artifact().getArtifactType().name(),
                null,
                sequence++,
                resumed ? ExecutionEventType.RUN_RESUMED : ExecutionEventType.RUN_STARTED,
                ExecutionLifecycleStatus.RUNNING,
                Instant.now(),
                appendExecutionMeta(new LinkedHashMap<>(Map.of("source", "artifact-runtime")), descriptor, flowContext)));

        Map<String, StepResult> stepResults = flowResult.getStepResults() != null
                ? flowResult.getStepResults()
                : Map.of();
        Map<String, StepStatus> stepStatuses = flowResult.getStepStatuses() != null
                ? flowResult.getStepStatuses()
                : Map.of();
        for (Map.Entry<String, StepResult> entry : stepResults.entrySet()) {
            String stepId = entry.getKey();
            StepResult stepResult = entry.getValue();
            String stepName = resolveStepName(descriptor, stepId);
            StepStatus stepStatus = stepStatuses.get(stepId);
            if (stepStatus == null) {
                continue;
            }
            if (stepStatus == StepStatus.WAITING_APPROVAL) {
                Map<String, Object> waitingPayload = appendExecutionMeta(new LinkedHashMap<>(), descriptor, flowContext);
                if (StringUtils.hasText(stepName)) {
                    waitingPayload.put("stepName", stepName);
                }
                if (StringUtils.hasText(flowResult.getApprovalRequestId())) {
                    waitingPayload.put("approvalRequestId", flowResult.getApprovalRequestId());
                }
                events.add(new ExecutionEvent(
                        runId,
                        descriptor.artifact().getArtifactCode(),
                        descriptor.artifact().getArtifactType().name(),
                        stepId,
                        sequence++,
                        ExecutionEventType.STEP_WAITING_APPROVAL,
                        ExecutionLifecycleStatus.WAITING_APPROVAL,
                        Instant.now(),
                        waitingPayload));
                continue;
            }

            Map<String, Object> startedPayload = appendExecutionMeta(new LinkedHashMap<>(), descriptor, flowContext);
            if (StringUtils.hasText(stepName)) {
                startedPayload.put("stepName", stepName);
            }
            events.add(new ExecutionEvent(
                    runId,
                    descriptor.artifact().getArtifactCode(),
                    descriptor.artifact().getArtifactType().name(),
                    stepId,
                    sequence++,
                    ExecutionEventType.STEP_STARTED,
                    ExecutionLifecycleStatus.RUNNING,
                    Instant.now(),
                    startedPayload));

            if (stepStatus == StepStatus.COMPLETED || stepStatus == StepStatus.SKIPPED) {
                Map<String, Object> completedPayload = appendExecutionMeta(new LinkedHashMap<>(), descriptor, flowContext);
                if (StringUtils.hasText(stepName)) {
                    completedPayload.put("stepName", stepName);
                }
                if (stepResult != null && stepResult.getOutputs() != null && !stepResult.getOutputs().isEmpty()) {
                    completedPayload.put("outputs", stepResult.getOutputs());
                }
                events.add(new ExecutionEvent(
                        runId,
                        descriptor.artifact().getArtifactCode(),
                        descriptor.artifact().getArtifactType().name(),
                        stepId,
                        sequence++,
                        ExecutionEventType.STEP_COMPLETED,
                        stepStatus == StepStatus.SKIPPED ? ExecutionLifecycleStatus.SKIPPED : ExecutionLifecycleStatus.COMPLETED,
                        Instant.now(),
                        completedPayload));
            }
            else {
                Map<String, Object> failedPayload = appendExecutionMeta(new LinkedHashMap<>(), descriptor, flowContext);
                if (StringUtils.hasText(stepName)) {
                    failedPayload.put("stepName", stepName);
                }
                if (stepResult != null && StringUtils.hasText(stepResult.getErrorMessage())) {
                    failedPayload.put("error", stepResult.getErrorMessage());
                }
                events.add(new ExecutionEvent(
                        runId,
                        descriptor.artifact().getArtifactCode(),
                        descriptor.artifact().getArtifactType().name(),
                        stepId,
                        sequence++,
                        ExecutionEventType.STEP_FAILED,
                        ExecutionLifecycleStatus.FAILED,
                        Instant.now(),
                        failedPayload));
            }
        }

        if (!"WAITING_APPROVAL".equalsIgnoreCase(flowResult.getLifecycleStatus())) {
            Map<String, Object> terminalPayload = appendExecutionMeta(new LinkedHashMap<>(), descriptor, flowContext);
            if (flowResult.getFinalOutputs() != null && !flowResult.getFinalOutputs().isEmpty()) {
                terminalPayload.put("finalOutputs", flowResult.getFinalOutputs());
            }
            if (StringUtils.hasText(flowResult.getErrorMessage())) {
                terminalPayload.put("error", flowResult.getErrorMessage());
            }
            events.add(new ExecutionEvent(
                    runId,
                    descriptor.artifact().getArtifactCode(),
                    descriptor.artifact().getArtifactType().name(),
                    null,
                    sequence,
                    flowResult.isSuccess() ? ExecutionEventType.RUN_COMPLETED : ExecutionEventType.RUN_FAILED,
                    flowResult.isSuccess() ? ExecutionLifecycleStatus.COMPLETED : ExecutionLifecycleStatus.FAILED,
                    Instant.now(),
                    terminalPayload));
        }
        return List.copyOf(events);
    }

    private String resolveStepName(PublishedToolDescriptor descriptor, String stepId) {
        if (descriptor == null || descriptor.artifact() == null || !StringUtils.hasText(stepId)) {
            return null;
        }
        if (descriptor.artifact().getSteps().containsKey(stepId)
                && StringUtils.hasText(descriptor.artifact().getSteps().get(stepId).stepName())) {
            return descriptor.artifact().getSteps().get(stepId).stepName();
        }
        if (descriptor.artifact().getFlowDefinition() == null || descriptor.artifact().getFlowDefinition().getSteps() == null) {
            return null;
        }
        StepDefinition stepDefinition = descriptor.artifact().getFlowDefinition().getSteps().get(stepId);
        return stepDefinition != null && StringUtils.hasText(stepDefinition.getName())
                ? stepDefinition.getName()
                : null;
    }

    private String readContextText(@Nullable ToolContext toolContext, String key) {
        Object value = readContextValue(toolContext, key);
        return asText(value);
    }

    private Object readContextValue(@Nullable ToolContext toolContext, String... keys) {
        if (toolContext == null || toolContext.getContext() == null || keys == null || keys.length == 0) {
            return null;
        }
        for (String key : keys) {
            if (!StringUtils.hasText(key)) {
                continue;
            }
            Object direct = toolContext.getContext().get(key);
            if (direct != null) {
                return direct;
            }
            Object rawState = toolContext.getContext().get(ToolContextConstants.AGENT_STATE_CONTEXT_KEY);
            if (rawState instanceof OverAllState state) {
                Object value = state.value(key, Object.class).orElse(null);
                if (value != null) {
                    return value;
                }
            }
        }
        return null;
    }

    private Long resolveSpaceId(@Nullable RuntimeArtifact artifact, @Nullable FlowContext flowContext) {
        Long resolvedSpaceId = firstNonNull(
                artifact != null ? artifact.getSpaceId() : null,
                flowContext != null ? asLong(flowContext.getInitialInputs().get(AssistantStateKeys.SPACE_ID)) : null,
                flowContext != null ? asLong(flowContext.getInitialInputs().get("space_id")) : null,
                flowContext != null ? asLong(flowContext.getInitialInputs().get("spaceId")) : null);
        if (resolvedSpaceId != null || runtimeSpaceResolver == null || flowContext == null) {
            return resolvedSpaceId;
        }
        return runtimeSpaceResolver.resolve(flowContext.getInitialInputs()).spaceId();
    }

    private boolean asBoolean(Object value) {
        if (value instanceof Boolean booleanValue) {
            return booleanValue;
        }
        String text = asText(value);
        return "true".equalsIgnoreCase(text);
    }

    private Long asLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        String text = asText(value);
        if (!StringUtils.hasText(text)) {
            return null;
        }
        try {
            return Long.parseLong(text);
        }
        catch (NumberFormatException ignored) {
            return null;
        }
    }

    private String asText(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        return StringUtils.hasText(text) ? text : null;
    }

    private <T> T firstNonNull(T... values) {
        if (values == null) {
            return null;
        }
        for (T value : values) {
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (StringUtils.hasText(value)) {
                return value;
            }
        }
        return null;
    }

    private final class RuntimeExecutionEventCollector implements FlowExecutionListener {

        private final String runId;

        private final PublishedToolDescriptor descriptor;

        private final FlowContext flowContext;

        private final String threadId;

        private final ExecutionEventStreamRegistry executionEventStreamRegistry;

        private final AgentTaskProjector agentTaskProjector;

        private final List<ExecutionEvent> events = new ArrayList<>();

        private long sequence = 1L;

        private RuntimeExecutionEventCollector(
                String runId,
                PublishedToolDescriptor descriptor,
                FlowContext flowContext,
                String threadId,
                @Nullable ExecutionEventStreamRegistry executionEventStreamRegistry,
                @Nullable AgentTaskProjector agentTaskProjector) {
            this.runId = runId;
            this.descriptor = descriptor;
            this.flowContext = flowContext;
            this.threadId = threadId;
            this.executionEventStreamRegistry = executionEventStreamRegistry;
            this.agentTaskProjector = agentTaskProjector;
        }

        void recordRunStarted() {
            record(new ExecutionEvent(
                    runId,
                    descriptor.artifact().getArtifactCode(),
                    descriptor.artifact().getArtifactType().name(),
                    null,
                    sequence++,
                    ExecutionEventType.RUN_STARTED,
                    ExecutionLifecycleStatus.RUNNING,
                    Instant.now(),
                    appendExecutionMeta(new LinkedHashMap<>(Map.of("source", "artifact-runtime")), descriptor, flowContext)));
        }

        void recordRunResumed() {
            record(new ExecutionEvent(
                    runId,
                    descriptor.artifact().getArtifactCode(),
                    descriptor.artifact().getArtifactType().name(),
                    null,
                    sequence++,
                    ExecutionEventType.RUN_RESUMED,
                    ExecutionLifecycleStatus.RUNNING,
                    Instant.now(),
                    appendExecutionMeta(new LinkedHashMap<>(Map.of("source", "artifact-runtime")), descriptor, flowContext)));
        }

        void recordRunTerminal(FlowExecutionResult flowResult) {
            if ("WAITING_APPROVAL".equalsIgnoreCase(flowResult.getLifecycleStatus())) {
                return;
            }
            Map<String, Object> terminalPayload = appendExecutionMeta(new LinkedHashMap<>(), descriptor, flowContext);
            if (flowResult.getFinalOutputs() != null && !flowResult.getFinalOutputs().isEmpty()) {
                terminalPayload.put("finalOutputs", flowResult.getFinalOutputs());
            }
            if (StringUtils.hasText(flowResult.getErrorMessage())) {
                terminalPayload.put("error", flowResult.getErrorMessage());
            }
            record(new ExecutionEvent(
                    runId,
                    descriptor.artifact().getArtifactCode(),
                    descriptor.artifact().getArtifactType().name(),
                    null,
                    sequence++,
                    flowResult.isSuccess() ? ExecutionEventType.RUN_COMPLETED : ExecutionEventType.RUN_FAILED,
                    flowResult.isSuccess() ? ExecutionLifecycleStatus.COMPLETED : ExecutionLifecycleStatus.FAILED,
                    Instant.now(),
                    terminalPayload));
        }

        boolean hasStepEvents() {
            return events.stream().anyMatch(event -> StringUtils.hasText(event.stepId()));
        }

        List<ExecutionEvent> events() {
            return List.copyOf(events);
        }

        @Override
        public void onStepStarted(StepDefinition step, FlowContext context) {
            Map<String, Object> payload = appendExecutionMeta(new LinkedHashMap<>(), descriptor, flowContext);
            if (StringUtils.hasText(step.getName())) {
                payload.put("stepName", step.getName());
            }
            record(new ExecutionEvent(
                    runId,
                    descriptor.artifact().getArtifactCode(),
                    descriptor.artifact().getArtifactType().name(),
                    step.getStepId(),
                    sequence++,
                    ExecutionEventType.STEP_STARTED,
                    ExecutionLifecycleStatus.RUNNING,
                    Instant.now(),
                    payload));
        }

        @Override
        public void onStepProgress(StepDefinition step, Map<String, Object> progressPayload, FlowContext context) {
            Map<String, Object> payload = appendExecutionMeta(new LinkedHashMap<>(), descriptor, flowContext);
            if (StringUtils.hasText(step.getName())) {
                payload.put("stepName", step.getName());
            }
            if (progressPayload != null && !progressPayload.isEmpty()) {
                payload.putAll(progressPayload);
            }
            record(new ExecutionEvent(
                    runId,
                    descriptor.artifact().getArtifactCode(),
                    descriptor.artifact().getArtifactType().name(),
                    step.getStepId(),
                    sequence++,
                    ExecutionEventType.STEP_PROGRESS,
                    ExecutionLifecycleStatus.RUNNING,
                    Instant.now(),
                    payload));
        }

        @Override
        public void onStepCompleted(StepDefinition step, StepResult result, FlowContext context) {
            Map<String, Object> payload = appendExecutionMeta(new LinkedHashMap<>(), descriptor, flowContext);
            if (StringUtils.hasText(step.getName())) {
                payload.put("stepName", step.getName());
            }
            if (result.getOutputs() != null && !result.getOutputs().isEmpty()) {
                payload.put("outputs", result.getOutputs());
            }
            record(new ExecutionEvent(
                    runId,
                    descriptor.artifact().getArtifactCode(),
                    descriptor.artifact().getArtifactType().name(),
                    step.getStepId(),
                    sequence++,
                    ExecutionEventType.STEP_COMPLETED,
                    ExecutionLifecycleStatus.COMPLETED,
                    Instant.now(),
                    payload));
        }

        @Override
        public void onStepFailed(StepDefinition step, StepResult result, FlowContext context) {
            Map<String, Object> payload = appendExecutionMeta(new LinkedHashMap<>(), descriptor, flowContext);
            if (StringUtils.hasText(step.getName())) {
                payload.put("stepName", step.getName());
            }
            if (StringUtils.hasText(result.getErrorMessage())) {
                payload.put("error", result.getErrorMessage());
            }
            record(new ExecutionEvent(
                    runId,
                    descriptor.artifact().getArtifactCode(),
                    descriptor.artifact().getArtifactType().name(),
                    step.getStepId(),
                    sequence++,
                    ExecutionEventType.STEP_FAILED,
                    ExecutionLifecycleStatus.FAILED,
                    Instant.now(),
                    payload));
        }

        @Override
        public void onStepSkipped(StepDefinition step, StepResult result, FlowContext context) {
            Map<String, Object> payload = appendExecutionMeta(new LinkedHashMap<>(), descriptor, flowContext);
            if (StringUtils.hasText(step.getName())) {
                payload.put("stepName", step.getName());
            }
            if (result.getOutputs() != null && !result.getOutputs().isEmpty()) {
                payload.put("outputs", result.getOutputs());
            }
            record(new ExecutionEvent(
                    runId,
                    descriptor.artifact().getArtifactCode(),
                    descriptor.artifact().getArtifactType().name(),
                    step.getStepId(),
                    sequence++,
                    ExecutionEventType.STEP_COMPLETED,
                    ExecutionLifecycleStatus.SKIPPED,
                    Instant.now(),
                    payload));
        }

        @Override
        public void onStepWaitingApproval(StepDefinition step, FlowContext context) {
            Map<String, Object> payload = appendExecutionMeta(new LinkedHashMap<>(), descriptor, flowContext);
            if (StringUtils.hasText(step.getName())) {
                payload.put("stepName", step.getName());
            }
            payload.put("approvalRequestId", runId + ":" + step.getStepId());
            record(new ExecutionEvent(
                    runId,
                    descriptor.artifact().getArtifactCode(),
                    descriptor.artifact().getArtifactType().name(),
                    step.getStepId(),
                    sequence++,
                    ExecutionEventType.STEP_WAITING_APPROVAL,
                    ExecutionLifecycleStatus.WAITING_APPROVAL,
                    Instant.now(),
                    payload));
        }

        private void record(ExecutionEvent event) {
            events.add(event);
            if (executionEventStreamRegistry != null && StringUtils.hasText(threadId)) {
                executionEventStreamRegistry.publish(threadId, event);
            }
            if (agentTaskProjector != null) {
                agentTaskProjector.recordExecutionEvent(descriptor, flowContext, event);
            }
        }
    }
}


