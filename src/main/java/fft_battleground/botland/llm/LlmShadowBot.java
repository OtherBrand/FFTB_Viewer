package fft_battleground.botland.llm;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import fft_battleground.model.BattleGroundTeam;
import lombok.extern.slf4j.Slf4j;

/**
 * R7 Phase 1 shadow mode (RELEASE-PLAN): serialize the live match state, ask
 * the LLM for a strict-JSON verdict {pick, confidence, top_reasons}, persist
 * to llm_predictions. NO betting, NO chat output — predictions accumulate for
 * the owner-gated accuracy review. The whole path no-ops (silently, one debug
 * line) while the kill switch is dark or the daily cap is spent.
 */
@Service
@Slf4j
public class LlmShadowBot {

	private static final String SYSTEM_PROMPT =
			"You are an expert analyst of Final Fantasy Tactics Battleground, a Twitch tournament where"
			+ " viewers bet fictional gil on automated FFT battles. You will be shown a match as JSON:"
			+ " two teams of up to four units with class, brave, faith, zodiac sign, skills and gear,"
			+ " the pot totals and implied win shares, the map, and the champion streak. Predict which"
			+ " team wins the fight. Answer with STRICT JSON only, no prose, no markdown:"
			+ " {\"pick\": \"<LEFT|RIGHT>\", \"confidence\": <0-100>, \"top_reasons\": [\"<three short reasons>\"]}"
			+ " where LEFT/RIGHT refer to the JSON's left/right teams.";

	@Autowired
	private ChatClient chatClient;

	@Autowired
	private MatchStateSerializer serializer;

	@Autowired
	private LlmPredictionRepo llmPredictionRepo;

	private final ObjectMapper mapper = new ObjectMapper();

	public void evaluateShadowPrediction(String matchStateJson, Long tournamentId,
			BattleGroundTeam left, BattleGroundTeam right) {
		if(!this.chatClient.isEnabled()) {
			return; // dark by default — one clean no-op line in the journal-less quiet
		}
		try {
			ChatClient.ChatResult result = this.chatClient.chat(SYSTEM_PROMPT, matchStateJson);
			if(result == null) {
				log.debug("llm shadow prediction skipped (no result — cap or failure)");
				return;
			}
			Verdict verdict = this.parseVerdict(result.getContent(), left, right);
			LlmPrediction prediction = new LlmPrediction();
			prediction.setTournamentId(tournamentId);
			prediction.setLeftTeam(left != null ? left.name() : null);
			prediction.setRightTeam(right != null ? right.name() : null);
			prediction.setPick(verdict.pick);
			prediction.setConfidence(verdict.confidence);
			prediction.setReasons(verdict.reasons);
			prediction.setModel(result.getModel());
			prediction.setPromptTokens(result.getPromptTokens());
			prediction.setCompletionTokens(result.getCompletionTokens());
			prediction.setLatencyMs(result.getLatencyMs());
			prediction.setCostEstimate(result.getCostEstimate());
			prediction.setMatchStateJson(matchStateJson);
			this.llmPredictionRepo.saveAndFlush(prediction);
			log.info("llm shadow prediction stored: tournament={} pick={} confidence={} model={}",
					tournamentId, verdict.pick, verdict.confidence, result.getModel());
		} catch(Exception e) {
			//shadow mode NEVER touches the betting path — log and move on
			log.warn("llm shadow prediction failed (non-fatal): {}", e.getMessage());
		}
	}

	/**
	 * Strict-JSON parse with tolerant fallback: models occasionally wrap output in
	 * markdown fences or prose — strip to the outermost {...} before parsing; an
	 * unparseable verdict still persists (pick=null) so the sample stays honest
	 * about failure rate.
	 */
	public Verdict parseVerdict(String content, BattleGroundTeam left, BattleGroundTeam right) throws Exception {
		Verdict verdict = new Verdict();
		if(content == null || content.isBlank()) {
			return verdict;
		}
		String json = content.trim();
		int start = json.indexOf('{');
		int end = json.lastIndexOf('}');
		if(start < 0 || end <= start) {
			return verdict;
		}
		json = json.substring(start, end + 1);
		JsonNode root = this.mapper.readTree(json);
		String pick = root.path("pick").asText(null);
		if(pick != null) {
			pick = pick.trim().toUpperCase();
			if("LEFT".equals(pick)) {
				verdict.pick = left != null ? left.name() : "LEFT";
			} else if("RIGHT".equals(pick)) {
				verdict.pick = right != null ? right.name() : "RIGHT";
			}
		}
		int confidence = root.path("confidence").asInt(-1);
		if(confidence >= 0 && confidence <= 100) {
			verdict.confidence = confidence;
		}
		JsonNode reasons = root.path("top_reasons");
		if(reasons.isArray() && reasons.size() > 0) {
			verdict.reasons = this.mapper.writeValueAsString(reasons);
		}
		return verdict;
	}

	public static class Verdict {
		String pick;
		Integer confidence;
		String reasons;
	}
}
