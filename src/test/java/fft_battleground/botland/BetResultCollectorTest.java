package fft_battleground.botland;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import fft_battleground.botland.bot.GeneticBotV2;
import fft_battleground.botland.bot.model.Bet;
import fft_battleground.event.detector.model.ResultEvent;
import fft_battleground.model.BattleGroundTeam;

/**
 * Ghost-bot rule: evaluation-only (explain) bots are excluded from the
 * collected results, so they never place sim bets or accrue leaderboard
 * stats. Visible bots with results still collect normally.
 */
public class BetResultCollectorTest {

	private static class ResultBot extends GeneticBotV2 {
		ResultBot(String name, boolean explain) {
			super(1000, BattleGroundTeam.YELLOW, BattleGroundTeam.WHITE, null, Set.of(), null);
			this.setName(name);
			this.explain = explain;
		}
	}

	@Test
	public void explainBotsAreExcludedFromResults() {
		BotlandHelper helper = new BotlandHelper(1000, BattleGroundTeam.YELLOW, BattleGroundTeam.WHITE,
				List.of(), Set.of(), null);
		ResultBot advisor = new ResultBot("advisor-bot", true);
		advisor.setResult(new Bet(BattleGroundTeam.YELLOW, 100, false));
		ResultBot normalBot = new ResultBot("normal-bot", false);
		normalBot.setResult(new Bet(BattleGroundTeam.WHITE, 100, false));

		BetResultCollector collector = new BetResultCollector(helper, null, List.of(advisor, normalBot));

		fft_battleground.botland.bot.model.BetResults results = collector.getResult(new ResultEvent(BattleGroundTeam.WHITE));

		assertEquals(1, results.getBotsWithResults().size());
		assertEquals("normal-bot", results.getBotsWithResults().get(0).getName());
	}

}
