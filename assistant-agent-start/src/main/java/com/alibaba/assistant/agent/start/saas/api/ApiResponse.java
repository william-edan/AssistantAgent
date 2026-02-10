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

import java.time.Instant;

/**
 * Unified API response model.
 *
 * @param <T> data type
 * @author Assistant Agent Team
 * @since 1.0.0
 */
public class ApiResponse<T> {

    private int code;

    private String msg;

    private long time;

    private T data;

    /**
     * Build success response.
     *
     * @param data response body
     * @param <T> data type
     * @return response
     */
    public static <T> ApiResponse<T> success(T data) {
        ApiResponse<T> response = new ApiResponse<>();
        response.setCode(0);
        response.setMsg("success");
        response.setTime(Instant.now().getEpochSecond());
        response.setData(data);
        return response;
    }

    /**
     * Build error response.
     *
     * @param code error code
     * @param msg error message
     * @param <T> data type
     * @return response
     */
    public static <T> ApiResponse<T> error(int code, String msg) {
        ApiResponse<T> response = new ApiResponse<>();
        response.setCode(code);
        response.setMsg(msg);
        response.setTime(Instant.now().getEpochSecond());
        return response;
    }

    public int getCode() {
        return code;
    }

    public void setCode(int code) {
        this.code = code;
    }

    public String getMsg() {
        return msg;
    }

    public void setMsg(String msg) {
        this.msg = msg;
    }

    public long getTime() {
        return time;
    }

    public void setTime(long time) {
        this.time = time;
    }

    public T getData() {
        return data;
    }

    public void setData(T data) {
        this.data = data;
    }
}
