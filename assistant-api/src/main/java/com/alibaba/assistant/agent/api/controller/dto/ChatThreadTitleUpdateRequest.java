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
package com.alibaba.assistant.agent.api.controller.dto;

import org.springframework.util.StringUtils;

/**
 * 会话标题更新请求 - 手动校验版本。
 *
 * <p>使用 Java 17 Record 实现不可变请求对象，
 * 由于项目未引入 Jakarta Validation，采用手动校验。</p>
 *
 * @param title 新标题（必填，1-200字符）
 * @author Assistant Agent Team
 * @since 1.0.0
 */
public record ChatThreadTitleUpdateRequest(String title) {

	/**
	 * 业务校验方法 - 在 Controller 中调用。
	 *
	 * @return 校验通过返回 null，否则返回错误信息
	 */
	public String validate() {
		if (!StringUtils.hasText(title)) {
			return "标题不能为空";
		}
		if (title.length() > 200) {
			return "标题长度不能超过200字符";
		}
		return null;
	}

	/**
	 * 快速创建工厂方法。
	 *
	 * @param title 标题
	 * @return 请求实例
	 */
	public static ChatThreadTitleUpdateRequest of(String title) {
		return new ChatThreadTitleUpdateRequest(title);
	}
}
