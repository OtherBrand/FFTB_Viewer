package fft_battleground.botland.personality;

import java.util.Map;

import fft_battleground.botland.personality.model.PersonalityResponse;
import fft_battleground.model.BattleGroundTeam;
import fft_battleground.util.GambleUtil;

public abstract class PersonalityModule {

	public PersonalityResponse getPersonalityResponse(String botName, Float leftScore, BattleGroundTeam leftTeam, Float rightScore, BattleGroundTeam rightTeam, 
			Map<Integer, Integer> percentiles) {
		//after a failed evaluation the scores are null — skip the flavor, never NPE
		//(FactsPersonality killed the betting timer this way, 2026-10-26)
		if(leftScore == null || rightScore == null) {
			return null;
		}
		Integer percentile = null;
		if(percentiles != null && percentiles.size() > 0) {
			percentile = GambleUtil.calculatePercentile(leftScore, rightScore, percentiles);
		}
		String personality = this.personalityString(botName, leftScore, leftTeam, rightScore, rightTeam, percentiles, percentile);
		boolean display = this.displayResponse(botName, leftScore, leftTeam, rightScore, rightTeam, percentiles, percentile);
		PersonalityResponse response = new PersonalityResponse(personality, display);
		return response;
	}
	
	protected abstract String personalityString(String botName, Float leftScore, BattleGroundTeam leftTeam, Float rightScore, BattleGroundTeam rightTeam, 
			Map<Integer, Integer> percentiles, Integer percentile);
	
	protected boolean displayResponse(String botName, Float leftScore, BattleGroundTeam leftTeam, Float rightScore,
			BattleGroundTeam rightTeam, Map<Integer, Integer> percentiles, Integer percentile) {
		return false;
	}
}
