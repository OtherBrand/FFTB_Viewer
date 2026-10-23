package fft_battleground.botland.bot.genetic.model;

import java.io.Serializable;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

/**
 * The V3 policy section's curve genes (gene-trainer-3, settle.rs): the
 * two-stage value bettor's transfer functions, ported Oct 23 2026.
 * Calibration anchors (`cal0`..`cal11`) map the confidence percentile to
 * a model win probability (value = p × 100); `marketTrust` blends that
 * probability with the pot-implied one (modulated by `trustPotScale`
 * against pot size); `minEdge` is the sit-out threshold (value/100 → EV);
 * edge anchors (`edge0`..`edge15`) map normalized edge (x-axis saturates
 * at EV 2.0) to a stake fraction (value = fraction × 100). The GA shapes
 * these curves; reads clamp at use so wild values stay sane.
 */
@Data
@NoArgsConstructor
@EqualsAndHashCode(callSuper = true)
public class PolicyGeneAttributes
extends GeneAttributes
implements Serializable {
	private static final long serialVersionUID = 1L;

	public static final int CAL_ANCHORS = 12;
	public static final int EDGE_ANCHORS = 16;
	private static final String MARKET_TRUST_KEY = "marketTrust";
	private static final String TRUST_POT_SCALE_KEY = "trustPotScale";
	private static final String MIN_EDGE_KEY = "minEdge";

	public Double cal(int i) {
		return this.attributeNameMap.get("cal" + i);
	}

	public Double marketTrust() {
		return this.attributeNameMap.get(MARKET_TRUST_KEY);
	}

	public Double trustPotScale() {
		return this.attributeNameMap.get(TRUST_POT_SCALE_KEY);
	}

	public Double minEdge() {
		return this.attributeNameMap.get(MIN_EDGE_KEY);
	}

	public Double edge(int i) {
		return this.attributeNameMap.get("edge" + i);
	}

	/** The calibration anchors in index order (missing entries read 0). */
	public double[] calAnchors() {
		double[] out = new double[CAL_ANCHORS];
		for (int i = 0; i < CAL_ANCHORS; i++) {
			Double v = this.cal(i);
			out[i] = v == null ? 0d : v;
		}
		return out;
	}

	/** The edge-ladder anchors in index order (missing entries read 0). */
	public double[] edgeAnchors() {
		double[] out = new double[EDGE_ANCHORS];
		for (int i = 0; i < EDGE_ANCHORS; i++) {
			Double v = this.edge(i);
			out[i] = v == null ? 0d : v;
		}
		return out;
	}

	public double geneOrZero(java.util.function.Function<PolicyGeneAttributes, Double> reader) {
		Double v = reader.apply(this);
		return v == null ? 0d : v;
	}

	/**
	 * Linear interpolation over `anchors`, indexed by x ∈ [0, 100] — the
	 * shape-free transfer function the GA edits. Mirrors Rust `anchor_at`.
	 */
	public static double anchorAt(double[] anchors, double x) {
		double clamped = Math.max(0d, Math.min(100d, x));
		double scaled = clamped / 100.0 * (anchors.length - 1);
		int lo = (int) Math.floor(scaled);
		int hi = Math.min(lo + 1, anchors.length - 1);
		double t = scaled - lo;
		return anchors[lo] + (anchors[hi] - anchors[lo]) * t;
	}

	/**
	 * Parimutuel implied probability from a profit-multiplier price: a
	 * price of o per gil staked breaks even at p = 1/(1+o). Mirrors Rust
	 * `implied_probability`.
	 */
	public static double impliedProbability(double odds) {
		if (odds > 0d) {
			return Math.max(0.02d, Math.min(0.98d, 1.0d / (1.0d + odds)));
		}
		return 0.5d;
	}
}
