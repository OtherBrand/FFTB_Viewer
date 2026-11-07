package fft_battleground.botland.bot.model;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import fft_battleground.model.BattleGroundTeam;
import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * Explain-mode capture of a gene bot's evaluation (live Team Advisor): the
 * four score sections per side, individual gene terms, and the calibrated
 * model win probability. Pure instrumentation — the scoring math is
 * unchanged; the scoreXxx methods record what they already computed.
 */
@Data
public class ScoreBreakdown {

	@Data
	@AllArgsConstructor
	public static class GeneTerm {
		private String name;
		private BattleGroundTeam team;
		private double value;
	}

	private Float leftScore;
	private Float rightScore;
	private Double leftFighters;
	private Double leftTeamAttributes;
	private Double leftTeamBets;
	private Double leftPot;
	private Double rightFighters;
	private Double rightTeamAttributes;
	private Double rightTeamBets;
	private Double rightPot;
	/** [0.02, 0.98] for the score-argmax side; null when the genome carries no calibration anchors */
	private Double pModelWinProb;
	private List<GeneTerm> geneTerms;
	private long computedAt;

	public List<GeneTerm> topGenes(int count) {
		List<GeneTerm> sorted = new ArrayList<>(this.geneTerms);
		sorted.sort(Comparator.comparingDouble((GeneTerm term) -> Math.abs(term.getValue())).reversed());
		return new ArrayList<>(sorted.subList(0, Math.min(count, sorted.size())));
	}

}
