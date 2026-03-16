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

import java.util.Objects;

/**
 * 会话置顶操作请求 - 支持置顶/取消置顶。
 *
 * <p>使用 Boolean 类型明确操作意图，避免歧义。</p>
 *
 * @param pinned true=置顶, false=取消置顶
 * @author Assistant Agent Team
 * @since 1.0.0
 */
public record ChatThreadPinRequest(Boolean pinned) {

	/**
	 * 业务校验方法。
	 *
	 * @return 校验通过返回 null，否则返回错误信息
	 */
	public String validate() {
		if (Objects.isNull(pinned)) {
			return "置顶状态不能为空";
		}
		return null;
	}

	/**
	 * 置顶操作快速创建。
	 *
	 * @return 置顶请求
	 */
	public static ChatThreadPinRequest pin() {
		return new ChatThreadPinRequest(true);
	}

	/**
	 * 取消置顶操作快速创建。
	 *
	 * @return 取消置顶请求
	 */
	public static ChatThreadPinRequest unpin() {
		return new ChatThreadPinRequest(false);
	}
}
