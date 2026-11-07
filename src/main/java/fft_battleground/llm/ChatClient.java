package fft_battleground.llm;

/**
 * R7 Phase 0 (RELEASE-PLAN): hand-rolled LLM client — deliberately NOT a
 * framework; Java 11 + RestTemplate is the whole stack until R6 (Java 17)
 * reopens library options. Implementations must be cheap to no-op: the
 * kill switch (fft_battleground.llm.enabled, default false) keeps shadow
 * mode dark until the owner wires a real endpoint.
 */
import lombok.Data;

public interface ChatClient {

	/**
	 * One chat completion. Returns null when the client is disabled or the
	 * daily cost cap is exhausted — callers treat null as "skip this cycle,"
	 * never an error.
	 */
	ChatResult chat(String systemPrompt, String userPrompt);

	boolean isEnabled();

	@Data
	class ChatResult {
		private final String content;
		private final String model;
		private final long promptTokens;
		private final long completionTokens;
		private final long latencyMs;
		private final double costEstimate;
		private final boolean tokenCountsEstimated;
	}
}
