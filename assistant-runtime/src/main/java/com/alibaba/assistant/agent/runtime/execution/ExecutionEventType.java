/*
 * Copyright 2024-2025 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 */
package com.alibaba.assistant.agent.runtime.execution;

public enum ExecutionEventType {
    RUN_STARTED,
    STEP_STARTED,
    STEP_PROGRESS,
    STEP_WAITING_APPROVAL,
    STEP_COMPLETED,
    STEP_FAILED,
    RUN_COMPLETED,
    RUN_FAILED,
    RUN_RESUMED
}
