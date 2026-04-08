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
package com.alibaba.assistant.agent.start.reward.node;

import com.alibaba.assistant.agent.start.reward.client.RewardsClient;
import com.alibaba.assistant.agent.start.reward.model.RewardAddRequest;
import com.alibaba.assistant.agent.start.reward.model.RewardAddResult;
import com.alibaba.assistant.agent.start.reward.model.RewardCategoryRecord;
import com.alibaba.assistant.agent.start.reward.model.RewardNodeResult;
import com.alibaba.assistant.agent.start.reward.model.RewardUserRecord;
import com.alibaba.assistant.agent.start.reward.model.RewardWorkflowContext;
import com.alibaba.assistant.agent.start.reward.service.DataAgentService;
import com.alibaba.assistant.agent.start.reward.util.AmountParseUtil;
import com.alibaba.assistant.agent.start.reward.util.CategoryMatchUtil;
import com.alibaba.assistant.agent.start.reward.util.DataAgentResultParser;
import com.alibaba.assistant.agent.start.reward.util.RewardErrorMessageUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 奖惩自动执行节点。
 */
public class RewardAutoExecuteNode {

    private static final Logger log = LoggerFactory.getLogger(RewardAutoExecuteNode.class);

    private final DataAgentService dataAgentService;

    private final RewardsClient rewardsClient;

    private final DataAgentResultParser resultParser;

    private final int batchConcurrency;

    public RewardAutoExecuteNode(
            DataAgentService dataAgentService,
            RewardsClient rewardsClient,
            DataAgentResultParser resultParser) {
        this(dataAgentService, rewardsClient, resultParser, 4);
    }

    public RewardAutoExecuteNode(
            DataAgentService dataAgentService,
            RewardsClient rewardsClient,
            DataAgentResultParser resultParser,
            int batchConcurrency) {
        this.dataAgentService = dataAgentService;
        this.rewardsClient = rewardsClient;
        this.resultParser = resultParser;
        this.batchConcurrency = Math.max(1, batchConcurrency);
    }

    public Mono<RewardNodeResult> handle(RewardWorkflowContext context) {
        String prompt = buildBirthdayPrompt(context.userInput());
        BigDecimal amount = context.intentResult() != null && context.intentResult().amount() != null
                ? context.intentResult().amount()
                : AmountParseUtil.parse(context.userInput()).orElse(null);
        if (amount == null) {
            return Mono.just(errorResult("自动执行模式未识别到有效金额"));
        }
        log.info("RewardAutoExecuteNode#handle - prompt={}, amount={}", prompt, amount);
        return dataAgentService.query(prompt)
                .map(resultParser::parseUserList)
                .flatMap(users -> users.isEmpty()
                        ? Mono.error(new IllegalStateException("DataAgent 未返回有效员工列表"))
                        : Mono.just(users))
                .zipWith(rewardsClient.listCategories(context.toolContext()))
                .flatMap(tuple -> executeBatch(context, amount, tuple.getT1(), tuple.getT2()))
                .onErrorResume(error -> Mono.just(errorResult(
                        RewardErrorMessageUtil.resolveMessage(error, "员工奖惩处理失败"))));
    }

    private Mono<RewardNodeResult> executeBatch(
            RewardWorkflowContext context,
            BigDecimal amount,
            List<RewardUserRecord> users,
            List<RewardCategoryRecord> categories) {
        RewardCategoryRecord category = CategoryMatchUtil.match(context.userInput(), categories)
                .orElseThrow(() -> new IllegalStateException("未匹配到可用奖惩分类"));
        LocalDate rewardDate = context.intentResult() != null && context.intentResult().rewardDate() != null
                ? context.intentResult().rewardDate()
                : LocalDate.now();
        String remark = context.intentResult() != null && StringUtils.hasText(context.intentResult().remark())
                ? context.intentResult().remark()
                : "自动发放生日奖励";
        return Flux.fromIterable(users)
                .flatMap(user -> rewardsClient.addReward(new RewardAddRequest(
                                category.id(),
                                context.intentResult().types(),
                                user.uname(),
                                user.uid(),
                                amount,
                                rewardDate,
                                remark), context.toolContext())
                        .map(result -> new BatchItemResult(user.uname(), true, resolveSuccessMessage(result)))
                        .onErrorResume(error -> Mono.just(new BatchItemResult(
                                user.uname(),
                                false,
                                RewardErrorMessageUtil.resolveMessage(
                                        error,
                                        "奖惩保存失败")))),
                        batchConcurrency)
                .collectList()
                .map(results -> aggregateResult(category, users.size(), results));
    }

    private RewardNodeResult aggregateResult(
            RewardCategoryRecord category,
            int totalUsers,
            List<BatchItemResult> results) {
        List<String> successUsers = results.stream()
                .filter(BatchItemResult::success)
                .map(BatchItemResult::uname)
                .toList();
        List<Map<String, Object>> failedUsers = results.stream()
                .filter(result -> !result.success())
                .map(result -> Map.<String, Object>of(
                        "uname", result.uname(),
                        "message", result.message()))
                .toList();
        int successCount = successUsers.size();
        int failedCount = failedUsers.size();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("kind", "RESULT");
        payload.put("success", failedCount == 0);
        payload.put("matchedCategoryId", category.id());
        payload.put("matchedCategoryName", category.name());
        payload.put("totalUsers", totalUsers);
        payload.put("successCount", successCount);
        payload.put("failedCount", failedCount);
        payload.put("successUsers", successUsers);
        payload.put("failedUsers", failedUsers);
        String summaryMessage = "共处理%d人，成功%d人，失败%d人".formatted(totalUsers, successCount, failedCount);
        payload.put("message", summaryMessage);
        if (failedCount > 0) {
            String failureDetail = buildFailureDetail(summaryMessage, failedUsers);
            payload.put("failedSummary", failureDetail);
            payload.put("error", failureDetail);
        }
        return new RewardNodeResult(failedCount == 0 ? "DONE" : "ERROR", true, payload);
    }

    private String buildBirthdayPrompt(String userInput) {
        if (StringUtils.hasText(userInput)
                && (userInput.contains("本月") || userInput.contains("这个月") || userInput.contains("当月"))) {
            return "查询本月生日的员工列表，返回姓名和用户ID";
        }
        return "查询生日员工列表，返回姓名和用户ID";
    }

    private RewardNodeResult errorResult(String message) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("kind", "RESULT");
        payload.put("success", false);
        payload.put("message", message);
        payload.put("error", message);
        return new RewardNodeResult("ERROR", true, payload);
    }

    private String buildFailureDetail(String summaryMessage, List<Map<String, Object>> failedUsers) {
        if (failedUsers == null || failedUsers.isEmpty()) {
            return summaryMessage;
        }
        String detail = failedUsers.stream()
                .limit(3)
                .map(user -> {
                    String uname = user.get("uname") != null ? String.valueOf(user.get("uname")) : "";
                    String message = user.get("message") != null ? String.valueOf(user.get("message")) : "";
                    if (StringUtils.hasText(uname) && StringUtils.hasText(message)) {
                        return uname + "(" + message + ")";
                    }
                    return StringUtils.hasText(uname) ? uname : message;
                })
                .filter(StringUtils::hasText)
                .reduce((left, right) -> left + "；" + right)
                .orElse("");
        return StringUtils.hasText(detail) ? summaryMessage + "；失败明细：" + detail : summaryMessage;
    }

    private String resolveSuccessMessage(RewardAddResult result) {
        if (result != null && StringUtils.hasText(result.message())) {
            return result.message();
        }
        if (result != null && StringUtils.hasText(result.rewardId())) {
            return "保存成功，记录ID=" + result.rewardId();
        }
        return "保存成功";
    }

    private record BatchItemResult(String uname, boolean success, String message) {
    }
}
