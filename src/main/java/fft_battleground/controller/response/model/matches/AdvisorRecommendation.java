package fft_battleground.controller.response.model.matches;

import java.util.List;

import fft_battleground.botland.bot.model.ScoreBreakdown;
import fft_battleground.model.BattleGroundTeam;
import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * Live Team Advisor payload: the explain-mode bot's evaluation of the
 * current match, serialized for the live page's advisor card.
 */
@Data
public class AdvisorRecommendation {

	public static final String VERDICT_OK = "ok";
	public static final String VERDICT_TIE = "tie";

	private String advisorName;
	private String geneFile;
	private BattleGroundTeam team1;
	private BattleGroundTeam team2;
	/** score-argmax side; null on an exact-score tie (the bot sits those out too) */
	private BattleGroundTeam pick;
	/** 0-100, calibrated; null on ties or when the genome has no calibration anchors */
	private Integer winProbability;
	/** "ok" | "tie" */
	private String verdict;
	private Float leftScore;
	private Float rightScore;
	/** the four score sections: fighters, teamAttributes, teamBets, pot */
	private List<AdvisorFactor> factors;
	private List<ScoreBreakdown.GeneTerm> topGenes;
	private long computedAt;

	@Data
	@AllArgsConstructor
	public static class AdvisorFactor {
		private String label;
		private Double leftContribution;
		private Double rightContribution;
	}

}
