package fft_battleground.botland.bot;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import fft_battleground.botland.bot.genetic.GeneFileV3Cache;
import fft_battleground.botland.bot.genetic.model.PolicyGeneAttributes;

/**
 * Differential parity of the advisor's probability math against the Rust
 * trainer (gene-trainer-3, settle.rs) — the layer nobody can see drift
 * from either side alone. Expected values below are pinned to the Rust
 * implementations: anchor_at, implied_probability, and
 * confidence_percentile (verified against its unit tests and the live
 * `eval --dump-p` output for the deployed genome).
 */
public class PolicyMathParityTest {

	private static final double EPS = 1e-4;

	// --- anchorAt ↔ Rust anchor_at -------------------------------------

	@Test
	public void anchorAtFlatCurveReadsConstant() {
		double[] anchors = new double[PolicyGeneAttributes.CAL_ANCHORS];
		java.util.Arrays.fill(anchors, 50d);
		for (double x : new double[] { 0, 1, 37.5, 99, 100 }) {
			assertEquals(50d, PolicyGeneAttributes.anchorAt(anchors, x), EPS);
		}
	}

	@Test
	public void anchorAtInterpolatesLinearly() {
		// Two anchors [0, 100]: x maps straight through.
		double[] ramp = { 0d, 100d };
		assertEquals(0d, PolicyGeneAttributes.anchorAt(ramp, 0), EPS);
		assertEquals(25d, PolicyGeneAttributes.anchorAt(ramp, 25), EPS);
		assertEquals(50d, PolicyGeneAttributes.anchorAt(ramp, 50), EPS);
		assertEquals(100d, PolicyGeneAttributes.anchorAt(ramp, 100), EPS);
		// x clamps into [0, 100].
		assertEquals(100d, PolicyGeneAttributes.anchorAt(ramp, 150), EPS);
	}

	// --- impliedProbability ↔ Rust implied_probability ------------------

	@Test
	public void impliedProbabilityMatchesRust() {
		assertEquals(1d / 3d, PolicyGeneAttributes.impliedProbability(2.0), 1e-9);
		assertEquals(2d / 3d, PolicyGeneAttributes.impliedProbability(0.5), 1e-9);
		// Non-positive odds are a coin flip by convention.
		assertEquals(0.5d, PolicyGeneAttributes.impliedProbability(0.0), 1e-9);
		// Clamps at the sanity bounds.
		assertEquals(0.02d, PolicyGeneAttributes.impliedProbability(99.0), 1e-9);
		assertEquals(0.98d, PolicyGeneAttributes.impliedProbability(0.001), 1e-9);
	}

	// --- confidencePercentile ↔ Rust confidence_percentile --------------

	@Test
	public void confidencePercentileMatchesRust() {
		assertEquals(100, GeneticBotV3.confidencePercentile(80f, 0f));
		assertEquals(100, GeneticBotV3.confidencePercentile(0f, 0f));
		assertEquals(100, GeneticBotV3.confidencePercentile(9f, 0f));
		assertEquals(18, GeneticBotV3.confidencePercentile(10f, 9f));
		assertEquals(50, GeneticBotV3.confidencePercentile(100f, 50f));
		assertEquals(64, GeneticBotV3.confidencePercentile(33.5f, 12.25f));
	}

	// --- Golden values: the deployed genome through the Java read path --
	// Rust `eval --dump-p` on winner10-23-2026.txt (policy mode) produced
	// pct=1 → p_model 0.409335 and pct=100 → p_model 0.265847; both come
	// from this genome's calibration anchors via the same interpolation.

	@Test
	public void deployedGenomeCalibrationMatchesRustDump()
			throws fft_battleground.botland.bot.exception.BotConfigException {
		GeneFileV3Cache cache = new GeneFileV3Cache();
		PolicyGeneAttributes policy = cache.getGeneData("winner10-23-2026.txt").getGenome().getPolicyGeneAttributes();
		double[] cal = policy.calAnchors();
		assertEquals(PolicyGeneAttributes.CAL_ANCHORS, cal.length);
		// pct = 100 reads the last anchor: cal11 = 26.584663.
		assertEquals(0.265847d, PolicyGeneAttributes.anchorAt(cal, 100) / 100d, EPS);
		// pct = 1 interpolates 0.0909 of the way from cal0 to cal1.
		assertEquals(0.409335d, PolicyGeneAttributes.anchorAt(cal, 1) / 100d, EPS);
	}
}
