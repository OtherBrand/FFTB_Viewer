package fft_battleground.util;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * GambleUtil pure functions, unit-pinned. cleanString is the documented
 * case-trap (the capital-V crediting bug lived in exactly its lowercase
 * semantics); the odds/amount math feeds both the sim and the ELO pot
 * fallbacks.
 */
public class GambleUtilTest {

	@Test
	public void cleanStringLowercasesStripsCommasAndTrims() {
		assertEquals("genebotv3-10-23-26-policy", GambleUtil.cleanString("genebotV3-10-23-26-policy"));
		assertEquals("1234567", GambleUtil.cleanString("1,234,567"));
		assertEquals("padded", GambleUtil.cleanString("  padded  "));
	}

	@Test
	public void bettingOddsIsTheInversePotRatio() {
		assertEquals(2.0f, GambleUtil.bettingOdds(100, 200), 1e-6f);
		assertEquals(0.5f, GambleUtil.bettingOdds(200, 100), 1e-6f);
		//zero own pot guards to 1.0 rather than dividing by zero
		assertEquals(1.0f, GambleUtil.bettingOdds(0, 500), 1e-6f);
	}

	@Test
	public void bettingPercentageIsOwnShareOfThePot() {
		assertEquals(1f / 3f, GambleUtil.bettingPercentage(100, 200), 1e-6f);
		assertEquals(0.5f, GambleUtil.bettingPercentage(150, 150), 1e-6f);
	}

	@Test
	public void amountUpdatePaysOddsOnWinAndStakesOnLoss() {
		assertEquals(Integer.valueOf(300), GambleUtil.getAmountUpdateFromBet(100, 3.0f, true));
		assertEquals(Integer.valueOf(-100), GambleUtil.getAmountUpdateFromBet(100, 3.0f, false));
	}

	@Test
	public void minimumsBySubscription() {
		assertEquals(GambleUtil.MINIMUM_BET, GambleUtil.getMinimumBetForBettor(false));
		assertEquals(GambleUtil.SUBSCRIBER_MINIMUM_BET, GambleUtil.getMinimumBetForBettor(true));
	}

}
