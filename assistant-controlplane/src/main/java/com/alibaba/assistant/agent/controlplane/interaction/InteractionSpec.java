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
package com.alibaba.assistant.agent.controlplane.interaction;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

@TableName("interaction_spec")
public class InteractionSpec {

	@TableId(type = IdType.AUTO)
	private Long id;
	private Long spaceId;
	private String interactionCode;
	private String slotSchemaJson;
	private String askStrategyJson;
	private String autoFillRulesJson;
	private String summaryLayoutJson;
	private String confirmationPolicyJson;
	private String editPolicyJson;
	private String status;
	private Integer version;
	private LocalDateTime createdAt;
	private LocalDateTime updatedAt;

	public Long getId() { return id; }
	public void setId(Long id) { this.id = id; }
	public Long getSpaceId() { return spaceId; }
	public void setSpaceId(Long spaceId) { this.spaceId = spaceId; }
	public String getInteractionCode() { return interactionCode; }
	public void setInteractionCode(String interactionCode) { this.interactionCode = interactionCode; }
	public String getSlotSchemaJson() { return slotSchemaJson; }
	public void setSlotSchemaJson(String slotSchemaJson) { this.slotSchemaJson = slotSchemaJson; }
	public String getAskStrategyJson() { return askStrategyJson; }
	public void setAskStrategyJson(String askStrategyJson) { this.askStrategyJson = askStrategyJson; }
	public String getAutoFillRulesJson() { return autoFillRulesJson; }
	public void setAutoFillRulesJson(String autoFillRulesJson) { this.autoFillRulesJson = autoFillRulesJson; }
	public String getSummaryLayoutJson() { return summaryLayoutJson; }
	public void setSummaryLayoutJson(String summaryLayoutJson) { this.summaryLayoutJson = summaryLayoutJson; }
	public String getConfirmationPolicyJson() { return confirmationPolicyJson; }
	public void setConfirmationPolicyJson(String confirmationPolicyJson) { this.confirmationPolicyJson = confirmationPolicyJson; }
	public String getEditPolicyJson() { return editPolicyJson; }
	public void setEditPolicyJson(String editPolicyJson) { this.editPolicyJson = editPolicyJson; }
	public String getStatus() { return status; }
	public void setStatus(String status) { this.status = status; }
	public Integer getVersion() { return version; }
	public void setVersion(Integer version) { this.version = version; }
	public LocalDateTime getCreatedAt() { return createdAt; }
	public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
	public LocalDateTime getUpdatedAt() { return updatedAt; }
	public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }

}
