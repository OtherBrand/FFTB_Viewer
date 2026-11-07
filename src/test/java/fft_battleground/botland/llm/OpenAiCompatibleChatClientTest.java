package fft_battleground.botland.llm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * R7 Phase 0 (RELEASE-PLAN): hand-rolled client against a stubbed endpoint —
 * success shape, tolerant missing-usage fallback, disabled short-circuit,
 * daily cost cap, and per-call cost arithmetic.
 */
public class OpenAiCompatibleChatClientTest {

	private OpenAiCompatibleChatClient client(RestTemplate template, boolean enabled, double maxDailyCost) {
		OpenAiCompatibleChatClient client = new OpenAiCompatibleChatClient();
		ReflectionTestUtils.setField(client, "restTemplate", template);
		ReflectionTestUtils.setField(client, "enabled", enabled);
		ReflectionTestUtils.setField(client, "baseUrl", "https://llm.example/v1");
		ReflectionTestUtils.setField(client, "model", "test-model");
		ReflectionTestUtils.setField(client, "apiKey", "sk-test");
		ReflectionTestUtils.setField(client, "maxDailyCost", maxDailyCost);
		ReflectionTestUtils.setField(client, "costPerMillionPrompt", 1.0);
		ReflectionTestUtils.setField(client, "costPerMillionCompletion", 2.0);
		ReflectionTestUtils.setField(client, "temperature", 0.0);
		return client;
	}

	@Test
	public void parsesResponseWithUsageAndAccountsCost() {
		RestTemplate template = new RestTemplate();
		MockRestServiceServer server = MockRestServiceServer.bindTo(template).build();
		server.expect(org.springframework.test.web.client.match.MockRestRequestMatchers.method(HttpMethod.POST))
			.andRespond(org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess(
				"{\"model\":\"test-model\",\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"{\\\"pick\\\":\\\"left\\\"}\"}}],"
				+ "\"usage\":{\"prompt_tokens\":1000,\"completion_tokens\":500}}",
				MediaType.APPLICATION_JSON));
		OpenAiCompatibleChatClient client = this.client(template, true, 5.0);

		ChatClient.ChatResult result = client.chat("system", "user");

		assertNotNull(result);
		assertEquals("{\"pick\":\"left\"}", result.getContent());
		assertEquals(1000, result.getPromptTokens());
		assertEquals(500, result.getCompletionTokens());
		assertTrue(!result.isTokenCountsEstimated());
		// 1000/1M * $1 + 500/1M * $2 = $0.002
		assertEquals(0.002, result.getCostEstimate(), 1e-9);
		server.verify();
	}

	@Test
	public void tolerantFallbackWhenUsageOmitted() {
		RestTemplate template = new RestTemplate();
		MockRestServiceServer server = MockRestServiceServer.bindTo(template).build();
		server.expect(org.springframework.test.web.client.match.MockRestRequestMatchers.anything())
			.andRespond(org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess(
				"{\"choices\":[{\"message\":{\"content\":\"" + "x".repeat(120) + "\"}}]}", MediaType.APPLICATION_JSON));
		OpenAiCompatibleChatClient client = this.client(template, true, 5.0);

		ChatClient.ChatResult result = client.chat("system", "user");

		assertNotNull(result);
		assertTrue(result.isTokenCountsEstimated(), "char/4 estimate kicks in");
		assertTrue(result.getPromptTokens() > 0);
	}

	@Test
	public void disabledClientShortCircuitsWithoutCalling() {
		RestTemplate template = new RestTemplate();
		MockRestServiceServer server = MockRestServiceServer.bindTo(template).build();
		//no expectation registered: any request would fail the test
		OpenAiCompatibleChatClient client = this.client(template, false, 5.0);

		assertNull(client.chat("system", "user"));
		assertTrue(!client.isEnabled());
	}

	@Test
	public void dailyCostCapSkipsCalls() {
		RestTemplate template = new RestTemplate();
		MockRestServiceServer server = MockRestServiceServer.bindTo(template).build();
		OpenAiCompatibleChatClient client = this.client(template, true, 0.001);
		//pre-spend past the cap
		client.todaySpend().set(0.001);

		assertNull(client.chat("system", "user"), "cap exhausted -> skip, not error");
	}

	@Test
	public void endpointFailureReturnsNullNotException() {
		RestTemplate template = new RestTemplate();
		MockRestServiceServer server = MockRestServiceServer.bindTo(template).build();
		server.expect(org.springframework.test.web.client.match.MockRestRequestMatchers.anything())
			.andRespond(org.springframework.test.web.client.response.MockRestResponseCreators.withServerError());
		OpenAiCompatibleChatClient client = this.client(template, true, 5.0);

		assertNull(client.chat("system", "user"), "HTTP failure -> null (skip this cycle)");
	}
}
