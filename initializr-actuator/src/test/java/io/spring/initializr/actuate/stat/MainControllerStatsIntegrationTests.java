/*
 * Copyright 2012-2016 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.spring.initializr.actuate.stat;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.Before;
import org.junit.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.RequestEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.util.Base64Utils;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.HttpClientErrorException;

import groovy.json.JsonSlurper;
import io.spring.initializr.actuate.stat.MainControllerStatsIntegrationTests.StatsMockController;
import io.spring.initializr.actuate.stat.MainControllerStatsIntegrationTests.StatsMockController.Content;
import io.spring.initializr.web.AbstractFullStackInitializrIntegrationTests;

/**
 * Integration tests for stats processing.
 *
 * @author Stephane Nicoll
 */
@Import(StatsMockController.class)
@ActiveProfiles({ "test-default", "test-custom-stats" })
public class MainControllerStatsIntegrationTests
		extends AbstractFullStackInitializrIntegrationTests {

	@Autowired
	private StatsMockController statsMockController;

	@Autowired
	private StatsProperties statsProperties;

	private final JsonSlurper slurper = new JsonSlurper();

	@Before
	public void setup() {
		this.statsMockController.stats.clear();
		// Make sure our mock is going to be invoked with the stats
		this.statsProperties.getElastic().setUri( "http://localhost:" + port + "/elastic");
	}

	@Test
	public void simpleProject() {
		downloadArchive("/starter.zip?groupId=com.foo&artifactId=bar&dependencies=web");
		assertEquals("No stat got generated", 1, statsMockController.stats.size());
		Content content = statsMockController.stats.get(0);

		@SuppressWarnings("unchecked")
		Map<String,Object> json = (Map<String, Object>) slurper.parseText(content.json);
		assertEquals("com.foo", json.get("groupId"));
		assertEquals("bar", json.get("artifactId"));
		@SuppressWarnings("unchecked")
		List<String> list = (List<String>) json.get("dependencies");
		assertEquals(1, list.size());
		assertEquals("web", list.get(0));
	}

	@Test
	public void authorizationHeaderIsSet() {
		downloadArchive("/starter.zip");
		assertEquals("No stat got generated", 1, statsMockController.stats.size());
		Content content = statsMockController.stats.get(0);

		String authorization = content.authorization;
		assertNotNull("Authorization header must be set", authorization);
		assertTrue("Wrong value for authorization header", authorization.startsWith("Basic "));
		String token = authorization.substring("Basic ".length(), authorization.length());
		String[] data = new String(Base64Utils.decodeFromString(token)).split(":");
		assertEquals("Wrong user from $token", "test-user", data[0]);
		assertEquals("Wrong password $token", "test-password", data[1]);
	}

	@Test
	public void requestIpNotSetByDefault() {
		downloadArchive("/starter.zip?groupId=com.foo&artifactId=bar&dependencies=web");
		assertEquals("No stat got generated", 1, statsMockController.stats.size());
		Content content = statsMockController.stats.get(0);

		@SuppressWarnings("unchecked")
		Map<String,Object> json = (Map<String, Object>) slurper.parseText(content.json);
		assertFalse("requestIp property should not be set", json.containsKey("requestIp"));
	}

	@Test
	public void requestIpIsSetWhenHeaderIsPresent() throws Exception {
		RequestEntity<?> request = RequestEntity.get(new URI(createUrl("/starter.zip")))
				.header("X-FORWARDED-FOR", "10.0.0.123").build();
		getRestTemplate().exchange(request, String.class);
		assertEquals("No stat got generated", 1, statsMockController.stats.size());
		Content content = statsMockController.stats.get(0);

		@SuppressWarnings("unchecked")
		Map<String,Object> json = (Map<String, Object>) slurper.parseText(content.json);
		assertEquals("Wrong requestIp", "10.0.0.123", json.get("requestIp"));
	}

	@Test
	public void requestIpv4IsNotSetWhenHeaderHasGarbage() throws Exception {
		RequestEntity<?> request = RequestEntity.get(new URI(createUrl("/starter.zip")))
				.header("x-forwarded-for", "foo-bar").build();
		getRestTemplate().exchange(request, String.class);
		assertEquals("No stat got generated", 1, statsMockController.stats.size());
		Content content = statsMockController.stats.get(0);

		@SuppressWarnings("unchecked")
		Map<String,Object> json = (Map<String, Object>) slurper.parseText(content.json);
		assertFalse("requestIpv4 property should not be set if value is not a valid IPv4",
				json.containsKey("requestIpv4"));
	}

	@Test
	public void requestCountryIsNotSetWhenHeaderIsSetToXX() throws Exception {
		RequestEntity<?> request = RequestEntity.get(new URI(createUrl("/starter.zip")))
				.header("cf-ipcountry", "XX").build();
		getRestTemplate().exchange(request, String.class);
		assertEquals("No stat got generated", 1, statsMockController.stats.size());
		Content content = statsMockController.stats.get(0);

		@SuppressWarnings("unchecked")
		Map<String,Object> json = (Map<String, Object>) slurper.parseText(content.json);
		assertFalse("requestCountry property should not be set if value is set to xx",
				json.containsKey("requestCountry"));
	}

	@Test
	public void invalidProjectSillHasStats() {
		try {
			downloadArchive("/starter.zip?type=invalid-type");
			fail("Should have failed to generate project with invalid type");
		} catch (HttpClientErrorException ex) {
			assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
		}
		assertEquals("No stat got generated", 1, statsMockController.stats.size());
		Content content = statsMockController.stats.get(0);

		@SuppressWarnings("unchecked")
		Map<String,Object> json = (Map<String, Object>) slurper.parseText(content.json);
		assertEquals("com.example", json.get("groupId"));
		assertEquals("demo", json.get("artifactId"));
		assertEquals(true, json.get("invalid"));
		assertEquals(true, json.get("invalidType"));
		assertNotNull(json.get("errorMessage"));
		assertTrue(((String) json.get("errorMessage")).contains("invalid-type"));
	}

	@Test
	public void errorPublishingStatsDoesNotBubbleUp() {
		this.statsProperties.getElastic()
				.setUri("http://localhost:" + port + "/elastic-error");
		downloadArchive("/starter.zip");
		assertEquals("No stat should be available", 0, statsMockController.stats.size());
	}

	@RestController
	static class StatsMockController {

		private final List<Content> stats = new ArrayList<>();

		@RequestMapping(path = "/elastic/test/my-entity", method = RequestMethod.POST)
		void handleProjectRequestDocument(RequestEntity<String> input) {
			String authorization = input.getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
			Content content = new Content(authorization, input.getBody());
			this.stats.add(content);
		}

		@RequestMapping(path = "/elastic-error/test/my-entity", method = RequestMethod.POST)
		void handleExpectedError() {
			throw new IllegalStateException("Expected exception");
		}

		static class Content {

			public Content(String authorization, String body) {
				this.authorization = authorization;
				json = body;
			}

			String authorization;

			String json;

		}

	}

}
