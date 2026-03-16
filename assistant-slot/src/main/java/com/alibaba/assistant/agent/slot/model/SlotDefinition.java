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
package com.alibaba.assistant.agent.slot.model;

import java.util.List;
import java.util.Map;

/**
 * Slot definition for capability parameters.
 *
 * @author Assistant Agent Team
 * @since 1.0.0
 */
public class SlotDefinition {

    private String name;

    private String type;

    private String title;

    private String description;

    private String aiHint;

    private SlotPriority priority;

    private boolean required;

    private SlotAskMode askMode;

    private Object defaultValue;

    private SlotOptions options;

    private SlotAutoSelect autoSelect;

    private List<String> dependsOn;

    private List<String> inferredFrom;

    private String conditionalRequired;

    private Map<String, Object> validation;

    private ComputedFieldConfig computed;

    private String uiComponent;

    private SlotDisplayConfig displayConfig;

    private boolean submit = true;

    public SlotDefinition() {
        this.priority = SlotPriority.CORE;
        this.required = false;
        this.askMode = SlotAskMode.AUTO;
        this.submit = true;
    }

    public boolean hasOptions() {
        return options != null && ((options.getValues() != null && !options.getValues().isEmpty())
                || (options.getEnumMapping() != null && !options.getEnumMapping().isEmpty())
                || options.getToolConfig() != null
                || options.getApiConfig() != null);
    }

    public boolean needsDynamicResolution() {
        return options != null && (options.getSource() == SlotOptions.SourceType.TOOL
                || options.getSource() == SlotOptions.SourceType.API
                || options.getSource() == SlotOptions.SourceType.SEARCH
                || options.getSource() == SlotOptions.SourceType.API_SEARCH);
    }

    public boolean isComputed() {
        return computed != null && computed.isEnabled();
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getAiHint() {
        return aiHint;
    }

    public void setAiHint(String aiHint) {
        this.aiHint = aiHint;
    }

    public SlotPriority getPriority() {
        return priority;
    }

    public void setPriority(SlotPriority priority) {
        this.priority = priority;
    }

    public boolean isRequired() {
        return required;
    }

    public void setRequired(boolean required) {
        this.required = required;
    }

    public SlotAskMode getAskMode() {
        return askMode;
    }

    public void setAskMode(SlotAskMode askMode) {
        this.askMode = askMode;
    }

    public Object getDefaultValue() {
        return defaultValue;
    }

    public void setDefaultValue(Object defaultValue) {
        this.defaultValue = defaultValue;
    }

    public SlotOptions getOptions() {
        return options;
    }

    public void setOptions(SlotOptions options) {
        this.options = options;
    }

    public SlotAutoSelect getAutoSelect() {
        return autoSelect;
    }

    public void setAutoSelect(SlotAutoSelect autoSelect) {
        this.autoSelect = autoSelect;
    }

    public List<String> getDependsOn() {
        return dependsOn;
    }

    public void setDependsOn(List<String> dependsOn) {
        this.dependsOn = dependsOn;
    }

    public List<String> getInferredFrom() {
        return inferredFrom;
    }

    public void setInferredFrom(List<String> inferredFrom) {
        this.inferredFrom = inferredFrom;
    }

    public String getConditionalRequired() {
        return conditionalRequired;
    }

    public void setConditionalRequired(String conditionalRequired) {
        this.conditionalRequired = conditionalRequired;
    }

    public Map<String, Object> getValidation() {
        return validation;
    }

    public void setValidation(Map<String, Object> validation) {
        this.validation = validation;
    }

    public ComputedFieldConfig getComputed() {
        return computed;
    }

    public void setComputed(ComputedFieldConfig computed) {
        this.computed = computed;
    }

    public String getUiComponent() {
        return uiComponent;
    }

    public void setUiComponent(String uiComponent) {
        this.uiComponent = uiComponent;
    }

    public SlotDisplayConfig getDisplayConfig() {
        return displayConfig;
    }

    public void setDisplayConfig(SlotDisplayConfig displayConfig) {
        this.displayConfig = displayConfig;
    }

    public boolean isSubmit() {
        return submit;
    }

    public void setSubmit(boolean submit) {
        this.submit = submit;
    }

}
