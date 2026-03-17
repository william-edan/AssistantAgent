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
package com.alibaba.assistant.agent.controlplane.rolepackage;

import java.util.Map;

/**
 * Resolves proactive-task execution subjects into runtime arguments.
 */
public interface SubjectResolverCapability {

    /**
     * Whether the capability can resolve the given published proactive task.
     *
     * @param task published proactive task
     * @return {@code true} when the capability can resolve the task subject
     */
    boolean supports(RoleProactiveTaskQueryService.PublishedRoleProactiveTask task);

    /**
     * Resolve the proactive execution subject into artifact arguments.
     *
     * @param task published proactive task
     * @return runtime arguments to merge into artifact execution input
     */
    Map<String, Object> resolveSubjectArguments(RoleProactiveTaskQueryService.PublishedRoleProactiveTask task);
}
