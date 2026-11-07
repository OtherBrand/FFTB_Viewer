package fft_battleground.botland;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.mariuszgromada.math.mxparser.Argument;
import org.mariuszgromada.math.mxparser.Expression;

import fft_battleground.botland.model.BotData;
import fft_battleground.util.GambleUtil;
import lombok.extern.slf4j.Slf4j;

/**
 * Salvaged from the JUnit-4-era DumpTests that never ran under the JUnit 5
 * engine: the shipped Botland.xml must parse, and the roster's bet
 * expressions must evaluate against the mxparser argument set. (The old
 * testTournamentService network call was dropped — flaky by design.)
 */
@Slf4j
public class BotlandConfigTest {

	@Test
	public void botlandXmlLoadsAndParses() {
		SecondaryBotConfig config = new SecondaryBotConfig("Botland.xml");
		List<BotData> botData = config.parseXmlFile();
		assertNotNull(botData);
		assertTrue(botData.size() > 0);
		//the explain-roster advisor was retired 2026-10-25 (advisor v2 oracle);
		//the visible/ghost flag machinery remains for future ghost entries
		assertTrue(botData.stream().noneMatch(bot -> "genebotV3-advisor".equalsIgnoreCase(bot.getName())),
				"retired advisor must not ride the roster");
	}

	@Test
	public void betExpressionsEvaluate() {
		Argument leftScoreArg = new Argument("leftScore", 5f);
		Argument rightScoreArg = new Argument("rightScore", 10f);
		Argument minBet = new Argument("mnBet", GambleUtil.MINIMUM_BET);
		Argument maxBet = new Argument("mxBet", GambleUtil.MAX_BET);
		Argument balanceArg = new Argument("balance", 600);
		Argument percentileArg = new Argument("percentile", 50);

		Expression expression = new Expression("min(mxBet, mnBet + 10 * (max(leftScore, rightScore) - min(leftScore, rightScore)), balance)",
				leftScoreArg, rightScoreArg, minBet, maxBet, balanceArg, percentileArg);
		double result = expression.calculate();
		log.info("expression result: {}", result);
		assertTrue(!Double.isNaN(result));
		assertTrue(result > 0);
	}

}
