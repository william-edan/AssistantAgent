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
package com.alibaba.assistant.agent.slot.computed;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.Map;

/**
 * Function to calculate the difference between two dates.
 *
 * @author Assistant Agent Team
 * @since 1.0.0
 */
@Component
public class DateDiffFunction implements ComputedFunction {

	private static final Logger logger = LoggerFactory.getLogger(DateDiffFunction.class);

	private static final DateTimeFormatter[] DATE_FORMATTERS = { DateTimeFormatter.ofPattern("yyyy-MM-dd"),
			DateTimeFormatter.ofPattern("yyyy/MM/dd"), DateTimeFormatter.ofPattern("yyyyMMdd"),
			DateTimeFormatter.ISO_LOCAL_DATE };

	@Override
	public String getName() {
		return "date_diff";
	}

	@Override
	public Object execute(Map<String, Object> params, ComputationContext context) throws ComputationException {
		Object startParam = params.get("start");
		Object endParam = params.get("end");
		String unit = params.getOrDefault("unit", "days").toString().toLowerCase();
		boolean includeStart = Boolean.parseBoolean(params.getOrDefault("include_start", "true").toString());
		boolean includeEnd = Boolean.parseBoolean(params.getOrDefault("include_end", "true").toString());

		if (startParam == null || endParam == null) {
			throw new ComputationException("date_diff requires both 'start' and 'end' parameters");
		}

		String startDateStr = resolveValue(startParam, context);
		String endDateStr = resolveValue(endParam, context);

		if (startDateStr == null || endDateStr == null) {
			throw new ComputationException("Cannot resolve start or end date values");
		}

		if (startDateStr.equals(startParam.toString()) && !isValidDate(startDateStr)) {
			throw new ComputationException("Field '" + startParam + "' has not been collected yet");
		}
		if (endDateStr.equals(endParam.toString()) && !isValidDate(endDateStr)) {
			throw new ComputationException("Field '" + endParam + "' has not been collected yet");
		}

		try {
			LocalDate startDate = parseDate(startDateStr);
			LocalDate endDate = parseDate(endDateStr);

			long diff = calculateDifference(startDate, endDate, unit);

			if (includeStart && includeEnd) {
				diff += 1;
			}
			else if (!includeStart && !includeEnd) {
				diff -= 1;
			}

			logger.debug("DateDiffFunction#execute - start={}, end={}, unit={}, diff={}", startDate, endDate, unit,
					diff);

			return diff;
		}
		catch (DateTimeParseException e) {
			throw new ComputationException(
					"Invalid date format. Expected YYYY-MM-DD, got: start=" + startDateStr + ", end=" + endDateStr, e);
		}
		catch (Exception e) {
			throw new ComputationException("Failed to calculate date difference: " + e.getMessage(), e);
		}
	}

	@Override
	public boolean validate(Map<String, Object> params) {
		return params.containsKey("start") && params.containsKey("end");
	}

	private String resolveValue(Object param, ComputationContext context) {
		if (param == null) {
			return null;
		}
		String paramStr = param.toString();
		if (context.hasValue(paramStr)) {
			Object value = context.getValue(paramStr);
			return value != null ? value.toString() : null;
		}
		return paramStr;
	}

	private boolean isValidDate(String dateStr) {
		if (dateStr == null || dateStr.isEmpty()) {
			return false;
		}
		try {
			parseDate(dateStr);
			return true;
		}
		catch (DateTimeParseException e) {
			return false;
		}
	}

	private LocalDate parseDate(String dateStr) throws DateTimeParseException {
		for (DateTimeFormatter formatter : DATE_FORMATTERS) {
			try {
				return LocalDate.parse(dateStr, formatter);
			}
			catch (DateTimeParseException e) {
				// Try next formatter
			}
		}
		throw new DateTimeParseException("Cannot parse date: " + dateStr, dateStr, 0);
	}

	private long calculateDifference(LocalDate start, LocalDate end, String unit) {
		switch (unit) {
			case "days":
				return ChronoUnit.DAYS.between(start, end);
			case "weeks":
				return ChronoUnit.WEEKS.between(start, end);
			case "months":
				return ChronoUnit.MONTHS.between(start, end);
			case "years":
				return ChronoUnit.YEARS.between(start, end);
			default:
				return ChronoUnit.DAYS.between(start, end);
		}
	}

}
