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
package com.alibaba.assistant.agent.api.controller;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ControlPlaneErrorResponseAdviceCoverageTest {

    private static final String CONTROLLER_PACKAGE = "com.alibaba.assistant.agent.api.controller";

    @Test
    void shouldCoverAllControlPlaneControllers() throws Exception {
        RestControllerAdvice advice = ControlPlaneErrorResponseAdvice.class.getAnnotation(RestControllerAdvice.class);
        assertNotNull(advice);

        Set<Class<?>> coveredControllers = new LinkedHashSet<>(Arrays.asList(advice.assignableTypes()));
        Set<Class<?>> controlPlaneControllers = findControlPlaneControllers();
        Set<String> missingControllers = controlPlaneControllers.stream()
                .filter(controller -> !coveredControllers.contains(controller))
                .map(Class::getSimpleName)
                .collect(Collectors.toCollection(TreeSet::new));

        assertTrue(missingControllers.isEmpty(), () -> "Missing control-plane advice coverage: " + missingControllers);
    }

    private Set<Class<?>> findControlPlaneControllers() throws ClassNotFoundException {
        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(RestController.class));

        Set<Class<?>> controllers = new LinkedHashSet<>();
        for (var beanDefinition : scanner.findCandidateComponents(CONTROLLER_PACKAGE)) {
            Class<?> controllerClass = Class.forName(beanDefinition.getBeanClassName());
            RequestMapping requestMapping = controllerClass.getAnnotation(RequestMapping.class);
            if (requestMapping == null) {
                continue;
            }
            if (startsWithControlPlane(requestMapping.path()) || startsWithControlPlane(requestMapping.value())) {
                controllers.add(controllerClass);
            }
        }
        return controllers;
    }

    private boolean startsWithControlPlane(String[] paths) {
        if (paths == null) {
            return false;
        }
        for (String path : paths) {
            if (path != null && path.startsWith("/api/controlplane")) {
                return true;
            }
        }
        return false;
    }
}