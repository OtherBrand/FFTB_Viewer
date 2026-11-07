package fft_battleground.elo;

import fft_battleground.model.BattleGroundTeam;

/**
 * Pure ELO update math (R3b), unit-pinned by EloMathTest:
 *   S = 1 pick won / 0 lost / 0.5 tie
 *   E = 0.7*p_advisor(your team) + 0.3*p_field,
 *       p_field = ELO-standard expectation 1/(1+10^(-(avgOwn - avgOpp)/400))
 *   dR = K*(S - E), K = 40 for the first 10 matches, then 24
 * Stake-agnostic by construction: no bet amount enters any formula.
 */
public final class EloMath {

	public static final double STARTING_ELO = 1500.0;
	public static final double PROVISIONAL_K = 40.0;
	public static final double ESTABLISHED_K = 24.0;
	public static final int K_SWITCH_MATCHES = 10;
	public static final double ADVISOR_WEIGHT = 0.7;
	public static final double FIELD_WEIGHT = 0.3;

	private EloMath() {}

	/** Expected score for a side, blending the advisor's probability with the field's ELO expectation. */
	public static double expectedScore(double pAdvisorSide, double avgOwnElo, double avgOpposingElo) {
		double pField = fieldExpectation(avgOwnElo, avgOpposingElo);
		return clamp(ADVISOR_WEIGHT * pAdvisorSide + FIELD_WEIGHT * pField, 0.01, 0.99);
	}

	/** Standard ELO expectation curve (the "logistic" of the ELO world, scaled so 400 points ≈ 10:1). */
	public static double fieldExpectation(double avgOwnElo, double avgOpposingElo) {
		return 1.0 / (1.0 + Math.pow(10.0, -(avgOwnElo - avgOpposingElo) / 400.0));
	}

	/** K factor: provisional bettors move fast (first 10 matches), established move slow. */
	public static double kFactor(int matchesPlayed) {
		return matchesPlayed < K_SWITCH_MATCHES ? PROVISIONAL_K : ESTABLISHED_K;
	}

	/** Actual score for a side given the match winner; a null winner is a tie (0.5). */
	public static double actualScore(BattleGroundTeam side, BattleGroundTeam winner) {
		if(winner == null) {
			return 0.5;
		}
		return side == winner ? 1.0 : 0.0;
	}

	public static double ratingDelta(double expected, double actual, int matchesPlayed) {
		return kFactor(matchesPlayed) * (actual - expected);
	}

	public static double clamp(double value, double low, double high) {
		return Math.max(low, Math.min(high, value));
	}

	/**
	 * Zero-sum normalization (calibration fix, 2026-10-25): raw deltas leak points
	 * because a side's bettors share one expectation while bettor counts differ
	 * (and pot-implied probabilities are not complementary). Rescale so total
	 * gains equal total losses exactly — the population mean pins to 1500 and the
	 * bell curve self-maintains. Sides present on only one sign pass through raw
	 * (rare single-sided matches).
	 */
	public static void zeroSumNormalize(double[] deltas) {
		double positives = 0.0, negatives = 0.0;
		for(double delta : deltas) {
			if(delta > 0) {
				positives += delta;
			} else if(delta < 0) {
				negatives += -delta;
			}
		}
		if(positives <= 0.0 && negatives <= 0.0) {
			return; // no deltas at all
		}
		if(positives <= 0.0 || negatives <= 0.0) {
			//one-signed deltas (pathological winner data): conservation is absolute —
			//no counterparty means no transfer, so nothing moves at all
			java.util.Arrays.fill(deltas, 0.0);
			return;
		}
		double scale = Math.min(positives, negatives) / Math.max(positives, negatives);
		boolean scalePositives = positives > negatives;
		for(int i = 0; i < deltas.length; i++) {
			if(scalePositives && deltas[i] > 0) {
				deltas[i] *= scale;
			} else if(!scalePositives && deltas[i] < 0) {
				deltas[i] *= scale;
			}
		}
	}

}
