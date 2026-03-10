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
package com.alibaba.assistant.agent.api.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

/**
 * Security configuration for assistant API migration profile.
 *
 * @author Assistant Agent Team
 * @since 1.0.0
 */
@Configuration
@Profile("migration")
public class SecurityConfig {

	private static final List<String> LOCAL_DEV_ORIGIN_PATTERNS = List.of(
			"http://localhost:*",
			"http://127.0.0.1:*");

	@Bean
	public SecurityFilterChain assistantApiSecurityFilterChain(
			HttpSecurity http,
			TokenIntrospectionAuthenticationFilter tokenIntrospectionAuthenticationFilter) throws Exception {
		http.cors(Customizer.withDefaults())
				.csrf(AbstractHttpConfigurer::disable)
				.formLogin(AbstractHttpConfigurer::disable)
				.httpBasic(AbstractHttpConfigurer::disable)
				.sessionManagement(session ->
						session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
				.exceptionHandling(exceptions ->
						exceptions.authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
				.authorizeHttpRequests(authorize -> authorize
						.requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
						.requestMatchers("/api/chat/**").hasAuthority(AuthenticatedUserAuthorityMapper.PERMISSION_CHAT)
						.requestMatchers("/api/controlplane/**").hasAnyAuthority(
								AuthenticatedUserAuthorityMapper.PERMISSION_CONTROLPLANE,
								AuthenticatedUserAuthorityMapper.ROLE_CONTROLPLANE_ADMIN,
								AuthenticatedUserAuthorityMapper.ROLE_SPACE_ADMIN,
								AuthenticatedUserAuthorityMapper.ROLE_AGENT_APP_ADMIN)
						.anyRequest().permitAll())
				.addFilterBefore(tokenIntrospectionAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);
		return http.build();
	}

	@Bean
	public CorsConfigurationSource corsConfigurationSource() {
		CorsConfiguration configuration = new CorsConfiguration();
		configuration.setAllowedOriginPatterns(LOCAL_DEV_ORIGIN_PATTERNS);
		configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "OPTIONS"));
		configuration.setAllowedHeaders(List.of("*"));
		configuration.setExposedHeaders(List.of("Content-Type", "Cache-Control"));
		configuration.setAllowCredentials(true);
		configuration.setMaxAge(3600L);

		UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
		source.registerCorsConfiguration("/system/auth/**", configuration);
		source.registerCorsConfiguration("/api/chat/**", configuration);
		source.registerCorsConfiguration("/api/controlplane/**", configuration);
		return source;
	}

}
