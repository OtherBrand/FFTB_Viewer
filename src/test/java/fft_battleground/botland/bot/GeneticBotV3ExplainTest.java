package fft_battleground.botland.bot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.commons.lang3.tuple.Pair;
import org.junit.jupiter.api.Test;

import fft_battleground.botland.bot.genetic.GeneFileV3Cache;
import fft_battleground.botland.bot.model.ScoreBreakdown;
import fft_battleground.botland.bot.model.TeamData;
import fft_battleground.botland.model.BotParam;
import fft_battleground.event.detector.model.BetEvent;
import fft_battleground.event.detector.model.MatchInfoEvent;
import fft_battleground.event.detector.model.TeamInfoEvent;
import fft_battleground.model.BattleGroundTeam;

/**
 * Explain-mode instrumentation (live Team Advisor) against the real V3
 * genome: the four recorded sections must reproduce the published scores,
 * the calibrated win probability must stay in its clamped bounds, and the
 * capture must stay off for non-explain bots.
 */
public class GeneticBotV3ExplainTest {

	private GeneticBotV3 buildBot(boolean explain) throws Exception {
		return this.buildBot(explain, false);
	}

	private GeneticBotV3 buildBot(boolean explain, boolean compositionOnly) throws Exception {
		GeneFileV3Cache cache = new GeneFileV3Cache();
		GeneticBotV3 bot = new GeneticBotV3(1000, BattleGroundTeam.YELLOW, BattleGroundTeam.WHITE, cache, Set.of(), null);
		Map<String, BotParam> params = new HashMap<>();
		params.put("geneFile", new BotParam("geneFile", "winner10-23-2026.txt", ""));
		params.put("betMode", new BotParam("betMode", "policy", ""));
		if(explain) {
			params.put("explain", new BotParam("explain", "true", ""));
		}
		if(compositionOnly) {
			params.put("compositionOnly", new BotParam("compositionOnly", "true", ""));
		}
		bot.initParams(params);
		bot.setName("genebotV3-advisor-test");

		TeamInfoEvent leftInfo = new TeamInfoEvent(BattleGroundTeam.YELLOW, List.of());
		leftInfo.setMetaData(List.of());
		TeamInfoEvent rightInfo = new TeamInfoEvent(BattleGroundTeam.WHITE, List.of());
		rightInfo.setMetaData(List.of());
		bot.setTeamData(new TeamData(leftInfo, rightInfo));
		bot.setMatchInfo(new MatchInfoEvent(Pair.of(BattleGroundTeam.YELLOW, BattleGroundTeam.WHITE),
				Pair.of(1, "Test Map"), List.of()));
		BetEvent leftBet = new BetEvent("testbettor1", BattleGroundTeam.YELLOW, "100", "100", null, false);
		BetEvent rightBet = new BetEvent("testbettor2", BattleGroundTeam.WHITE, "150", "150", null, false);
		bot.setBetsBySide(Pair.of(List.of(leftBet), List.of(rightBet)));
		bot.setUnitsBySide(Pair.of(List.of(), List.of()));
		return bot;
	}

	@Test
	public void explainSectionsSumToScores() throws Exception {
		GeneticBotV3 bot = this.buildBot(true);
		bot.call();

		ScoreBreakdown breakdown = bot.buildScoreBreakdown();
		assertNotNull(breakdown);
		assertNotNull(breakdown.getLeftScore());
		assertNotNull(breakdown.getRightScore());

		double leftSum = breakdown.getLeftFighters() + breakdown.getLeftTeamAttributes()
				+ breakdown.getLeftTeamBets() + breakdown.getLeftPot();
		double rightSum = breakdown.getRightFighters() + breakdown.getRightTeamAttributes()
				+ breakdown.getRightTeamBets() + breakdown.getRightPot();
		assertEquals(breakdown.getLeftScore().doubleValue(), leftSum, 0.01);
		assertEquals(breakdown.getRightScore().doubleValue(), rightSum, 0.01);
	}

	@Test
	public void modelWinProbWithinBoundsAndTopGenesCapped() throws Exception {
		GeneticBotV3 bot = this.buildBot(true);
		bot.call();

		ScoreBreakdown breakdown = bot.buildScoreBreakdown();
		assertNotNull(breakdown);
		// winner10-23-2026.txt carries policyGeneAttributes, so the calibration read is available
		assertNotNull(breakdown.getPModelWinProb());
		assertTrue(breakdown.getPModelWinProb() >= 0.02, "pModelWinProb below clamp: " + breakdown.getPModelWinProb());
		assertTrue(breakdown.getPModelWinProb() <= 0.98, "pModelWinProb above clamp: " + breakdown.getPModelWinProb());

		// at minimum the SideColor and Map terms are captured for both sides
		assertFalse(breakdown.getGeneTerms().isEmpty());
		assertTrue(breakdown.topGenes(6).size() <= 6);
	}

	@Test
	public void nonExplainBotBuildsNoBreakdown() throws Exception {
		GeneticBotV3 bot = this.buildBot(false);
		bot.call();
		assertNull(bot.buildScoreBreakdown());
	}

	@Test
	public void compositionOnlyAdvisorsScoreJustFightersAndAttributes() throws Exception {
		GeneticBotV3 bot = this.buildBot(true, true);
		bot.call();

		ScoreBreakdown breakdown = bot.buildScoreBreakdown();
		assertNotNull(breakdown);
		//bet/pot sections are absent (null), not zero
		assertNull(breakdown.getLeftTeamBets(), "composition advisors skip the bets section");
		assertNull(breakdown.getLeftPot(), "composition advisors skip the pot section");
		//score invariant reduces to the two composition sections
		assertEquals(breakdown.getLeftScore().doubleValue(),
				breakdown.getLeftFighters() + breakdown.getLeftTeamAttributes(), 0.01);
		assertEquals(breakdown.getRightScore().doubleValue(),
				breakdown.getRightFighters() + breakdown.getRightTeamAttributes(), 0.01);
		//win probability still computes on composition scores
		assertNotNull(breakdown.getPModelWinProb());
	}

}
