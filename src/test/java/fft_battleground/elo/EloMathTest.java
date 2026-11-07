package fft_battleground.elo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import fft_battleground.model.BattleGroundTeam;

/**
 * The ELO update rule, unit-pinned (R3b charter: these are the heart):
 * favorite-win small gain, underdog-win big gain, tie 0.5, provisional K
 * progression, and the field/advisor blend.
 */
public class EloMathTest {

	private static final double EPSILON = 1e-9;

	@Test
	public void fieldExpectationIsHalfAtEqualRatings() {
		assertEquals(0.5, EloMath.fieldExpectation(1500, 1500), EPSILON);
	}

	@Test
	public void fieldExpectationScalesByFourHundred() {
		//400 points up = 10:1 odds ≈ 0.909, the standard ELO curve
		assertEquals(0.909090909, EloMath.fieldExpectation(1900, 1500), 1e-6);
		assertEquals(0.090909090, EloMath.fieldExpectation(1500, 1900), 1e-6);
	}

	@Test
	public void expectedScoreBlendsAdvisorAndFieldSeventyThirty() {
		double blended = EloMath.expectedScore(1.0, 1500, 1500);
		assertEquals(0.7 * 1.0 + 0.3 * 0.5, blended, EPSILON);
	}

	@Test
	public void favoriteWinIsASmallGain() {
		//advisor 90% + even field => E ~ 0.78; winning gains only ~22% of K
		double expected = EloMath.expectedScore(0.9, 1500, 1500);
		double delta = EloMath.ratingDelta(expected, 1.0, 30);
		assertTrue(delta > 0 && delta < 0.3 * EloMath.PROVISIONAL_K,
				"favorite win should be a small gain, was " + delta);
	}

	@Test
	public void underdogWinIsABigGain() {
		double expected = EloMath.expectedScore(0.1, 1500, 1500);
		double delta = EloMath.ratingDelta(expected, 1.0, 30);
		assertTrue(delta > 0.7 * EloMath.kFactor(30),
				"underdog win should be a big gain, was " + delta);
	}

	@Test
	public void favoriteLosesBigOnAnUpset() {
		double expected = EloMath.expectedScore(0.9, 1500, 1500);
		double delta = EloMath.ratingDelta(expected, 0.0, 30);
		assertTrue(delta < -0.7 * EloMath.kFactor(30),
				"favorite loss should be a big drop, was " + delta);
	}

	@Test
	public void tieIsHalfAndHurtsTheFavorite() {
		double favoriteExpected = EloMath.expectedScore(0.9, 1500, 1500);
		double underdogExpected = EloMath.expectedScore(0.1, 1500, 1500);
		assertEquals(0.5, EloMath.actualScore(BattleGroundTeam.LEFT, null), EPSILON);
		assertTrue(EloMath.ratingDelta(favoriteExpected, 0.5, 30) < 0, "favorite tie should lose points");
		assertTrue(EloMath.ratingDelta(underdogExpected, 0.5, 30) > 0, "underdog tie should gain points");
	}

	@Test
	public void kProgressionFortyThenTwentyFour() {
		assertEquals(EloMath.PROVISIONAL_K, EloMath.kFactor(0), EPSILON);
		assertEquals(EloMath.PROVISIONAL_K, EloMath.kFactor(9), EPSILON);
		assertEquals(EloMath.ESTABLISHED_K, EloMath.kFactor(10), EPSILON);
		assertEquals(EloMath.ESTABLISHED_K, EloMath.kFactor(500), EPSILON);
	}

	@Test
	public void actualScoreFollowsTheWinner() {
		assertEquals(1.0, EloMath.actualScore(BattleGroundTeam.LEFT, BattleGroundTeam.LEFT), EPSILON);
		assertEquals(0.0, EloMath.actualScore(BattleGroundTeam.LEFT, BattleGroundTeam.RIGHT), EPSILON);
	}

}
