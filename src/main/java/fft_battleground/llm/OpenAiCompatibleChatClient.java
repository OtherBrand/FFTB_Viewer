package fft_battleground.llm;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import com.google.common.util.concurrent.AtomicDouble;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * OpenAI-compatible chat-completions adapter (R7 Phase 0): works against any
 * endpoint speaking the /chat/completions dialect (OpenAI, OpenRouter, vLLM,
 * ollama's compat layer). Properties:
 *   fft_battleground.llm.enabled        — kill switch, default FALSE (shadow stays dark)
 *   fft_battleground.llm.baseUrl        — e.g. https://api.openai.com/v1
 *   fft_battleground.llm.model
 *   fft_battleground.llm.apiKey         — server-side secret, never in git
 *   fft_battleground.llm.maxDailyCost   — dollars/day guard, calls skipped past it
 *   fft_battleground.llm.costPerMillionPrompt / Completion — price table for the guard
 *   fft_battleground.llm.temperature    — default 0 (deterministic-ish predictions)
 * Cost accounting resets by date; every call logs model/tokens/latency/cost/daily-total.
 */
@Component
public class OpenAiCompatibleChatClient implements ChatClient {

	private static final Logger log = LoggerFactory.getLogger(OpenAiCompatibleChatClient.class);

	@Value("${fft_battleground.llm.enabled:false}")
	private boolean enabled;

	@Value("${fft_battleground.llm.baseUrl:}")
	private String baseUrl;

	@Value("${fft_battleground.llm.model:}")
	private String model;

	@Value("${fft_battleground.llm.apiKey:}")
	private String apiKey;

	@Value("${fft_battleground.llm.maxDailyCost:5.0}")
	private double maxDailyCost;

	@Value("${fft_battleground.llm.costPerMillionPrompt:0.0}")
	private double costPerMillionPrompt;

	@Value("${fft_battleground.llm.costPerMillionCompletion:0.0}")
	private double costPerMillionCompletion;

	@Value("${fft_battleground.llm.temperature:0.0}")
	private double temperature;

	private final RestTemplate restTemplate;
	private final ObjectMapper mapper = new ObjectMapper();
	private final ConcurrentHashMap<String, AtomicDouble> dailySpend = new ConcurrentHashMap<>();

	public OpenAiCompatibleChatClient() {
		//LLM calls run long: generous read timeout, quick connect fail
		this.restTemplate = fft_battleground.util.HttpClientFactory.createRestTemplate();
	}

	@Override
	public boolean isEnabled() {
		return this.enabled && !org.apache.commons.lang3.StringUtils.isBlank(this.baseUrl)
				&& !org.apache.commons.lang3.StringUtils.isBlank(this.model);
	}

	@Override
	public ChatResult chat(String systemPrompt, String userPrompt) {
		if(!this.isEnabled()) {
			return null;
		}
		double spentToday = this.todaySpend().get();
		if(spentToday >= this.maxDailyCost) {
			log.warn("llm daily cost cap reached ({}/{}), skipping call", spentToday, this.maxDailyCost);
			return null;
		}

		long start = System.currentTimeMillis();
		try {
			HttpHeaders headers = new HttpHeaders();
			headers.setContentType(MediaType.APPLICATION_JSON);
			headers.setBearerAuth(this.apiKey);

			Map<String, Object> body = new HashMap<>();
			body.put("model", this.model);
			body.put("temperature", this.temperature);
			body.put("messages", List.of(
					Map.of("role", "system", "content", systemPrompt),
					Map.of("role", "user", "content", userPrompt)));

			String response = this.restTemplate.postForObject(
					this.baseUrl + "/chat/completions", new HttpEntity<>(body, headers), String.class);
			ChatResult result = this.parse(response, System.currentTimeMillis() - start);
			if(result != null) {
				this.recordCost(result);
			}
			return result;
		} catch(Exception e) {
			log.error("llm call failed after {}ms: {}", System.currentTimeMillis() - start, e.getMessage());
			return null;
		}
	}

	protected ChatResult parse(String response, long latency) throws Exception {
		if(response == null || response.isEmpty()) {
			return null;
		}
		JsonNode root = this.mapper.readTree(response);
		JsonNode choice = root.path("choices").path(0);
		String content = choice.path("message").path("content").asText(null);
		if(content == null) {
			log.warn("llm response had no message content: {}", response.substring(0, Math.min(300, response.length())));
			return null;
		}
		JsonNode usage = root.path("usage");
		long promptTokens = usage.path("prompt_tokens").asLong(-1);
		long completionTokens = usage.path("completion_tokens").asLong(-1);
		boolean estimated = promptTokens < 0;
		if(estimated) {
			//tolerant fallback: ~4 chars per token when the endpoint omits usage
			promptTokens = content.length() / 4;
			completionTokens = content.length() / 4;
		}
		double cost = this.costOf(promptTokens, completionTokens);
		return new ChatResult(content, root.path("model").asText(this.model),
				promptTokens, completionTokens, latency, cost, estimated);
	}

	protected double costOf(long promptTokens, long completionTokens) {
		return promptTokens / 1_000_000.0 * this.costPerMillionPrompt
				+ completionTokens / 1_000_000.0 * this.costPerMillionCompletion;
	}

	protected void recordCost(ChatResult result) {
		double today = this.todaySpend().addAndGet(result.getCostEstimate());
		log.info("llm call: model={} promptTokens={} completionTokens={} latencyMs={} cost={}/{} today=${}",
				result.getModel(), result.getPromptTokens(), result.getCompletionTokens(),
				result.getLatencyMs(), result.getCostEstimate(), result.isTokenCountsEstimated() ? "(estimated)" : "",
				String.format("%.4f", today));
	}

	protected AtomicDouble todaySpend() {
		return this.dailySpend.computeIfAbsent(LocalDate.now().toString(), key -> new AtomicDouble(0.0));
	}
}
