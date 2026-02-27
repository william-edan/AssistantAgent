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
package com.alibaba.assistant.agent.execution.model;

/**
 * Structured error detail for execution responses.
 *
 * @author Assistant Agent Team
 * @since 1.0.0
 */
public class ErrorDetail {

	private String code;

	private String message;

	private String field;

	private Object detail;

	private boolean retryable;

	private String suggestion;

	public ErrorDetail() {
	}

	public static ErrorDetail of(String code, String message) {
		ErrorDetail error = new ErrorDetail();
		error.setCode(code);
		error.setMessage(message);
		error.setRetryable(false);
		return error;
	}

	public static ErrorDetail retryable(String code, String message) {
		ErrorDetail error = new ErrorDetail();
		error.setCode(code);
		error.setMessage(message);
		error.setRetryable(true);
		return error;
	}

	public String getCode() {
		return code;
	}

	public void setCode(String code) {
		this.code = code;
	}

	public String getMessage() {
		return message;
	}

	public void setMessage(String message) {
		this.message = message;
	}

	public String getField() {
		return field;
	}

	public void setField(String field) {
		this.field = field;
	}

	public Object getDetail() {
		return detail;
	}

	public void setDetail(Object detail) {
		this.detail = detail;
	}

	public boolean isRetryable() {
		return retryable;
	}

	public void setRetryable(boolean retryable) {
		this.retryable = retryable;
	}

	public String getSuggestion() {
		return suggestion;
	}

	public void setSuggestion(String suggestion) {
		this.suggestion = suggestion;
	}

}
