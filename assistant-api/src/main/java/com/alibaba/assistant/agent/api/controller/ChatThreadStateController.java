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

import com.alibaba.assistant.agent.api.controller.dto.ChatThreadStateResponse;
import com.alibaba.assistant.agent.api.security.AuthenticatedUserContext;
import com.alibaba.assistant.agent.api.service.ChatThreadStateService;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

import java.security.Principal;

/**
 * 线程状态控制器。
 *
 * <p>对前端暴露线程当前状态快照，
 * 供页面刷新恢复、未完成会话恢复和当前卡片恢复使用。</p>
 */
@RestController
@Profile("migration")
@RequestMapping("/api/chat")
public class ChatThreadStateController {

	private final ChatThreadStateService chatThreadStateService;

	public ChatThreadStateController(ChatThreadStateService chatThreadStateService) {
		this.chatThreadStateService = chatThreadStateService;
	}

	@GetMapping("/threads/{threadId}/state")
	public ResponseEntity<ChatThreadStateResponse> getThreadState(
			@PathVariable String threadId,
			Principal principal) {
		AuthenticatedUserContext authenticatedUser = requireAuthenticatedUser(principal);
		return ResponseEntity.ok(ChatThreadStateResponse.ok(
				chatThreadStateService.getThreadState(threadId, authenticatedUser.userId())));
	}

	private AuthenticatedUserContext requireAuthenticatedUser(Principal principal) {
		if (principal instanceof Authentication authentication
				&& authentication.getPrincipal() instanceof AuthenticatedUserContext authenticatedUser
				&& StringUtils.hasText(authenticatedUser.userId())) {
			return authenticatedUser;
		}
		throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "unauthenticated_user");
	}

}
