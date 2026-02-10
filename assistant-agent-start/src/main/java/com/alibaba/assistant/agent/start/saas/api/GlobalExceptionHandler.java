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
package com.alibaba.assistant.agent.start.saas.api;

import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Global exception handling for SaaS APIs.
 *
 * @author Assistant Agent Team
 * @since 1.0.0
 */
@RestControllerAdvice(basePackages = "com.alibaba.assistant.agent.start.saas")
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /**
     * Handle illegal argument.
     *
     * @param ex exception
     * @return response
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ApiResponse<Void> handleIllegalArgument(IllegalArgumentException ex) {
        log.warn("GlobalExceptionHandler#handleIllegalArgument - reason=invalid argument, error={}", ex.getMessage());
        return ApiResponse.error(1, ex.getMessage());
    }

    /**
     * Handle validation exceptions.
     *
     * @param ex exception
     * @return response
     */
    @ExceptionHandler({
            MethodArgumentNotValidException.class,
            BindException.class,
            ConstraintViolationException.class,
            HttpMessageNotReadableException.class
    })
    public ApiResponse<Void> handleValidation(Exception ex) {
        log.warn("GlobalExceptionHandler#handleValidation - reason=request validation failed, error={}",
                ex.getMessage());
        return ApiResponse.error(1, "invalid request");
    }

    /**
     * Handle unknown exception.
     *
     * @param ex exception
     * @return response
     */
    @ExceptionHandler(Exception.class)
    public ApiResponse<Void> handleUnknown(Exception ex) {
        log.error("GlobalExceptionHandler#handleUnknown - reason=unexpected error, error={}", ex.getMessage(), ex);
        return ApiResponse.error(1, "internal error");
    }
}
