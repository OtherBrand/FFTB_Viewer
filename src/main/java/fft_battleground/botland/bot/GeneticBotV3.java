package fft_battleground.botland.bot;

import java.util.Collection;
import java.util.List;
import java.util.Map;

import org.apache.commons.lang3.math.NumberUtils;

import fft_battleground.botland.bot.exception.BotConfigException;
import fft_battleground.botland.bot.genetic.GeneFileCache;
import fft_battleground.botland.bot.genetic.model.BraveFaithAttributes;
import fft_battleground.botland.bot.genetic.model.GeneTrainerV2BotData;
import fft_battleground.botland.bot.genetic.model.PolicyGeneAttributes;
import fft_battleground.botland.bot.model.Bet;
import fft_battleground.botland.bot.util.BotParameterReader;
import fft_battleground.discord.WebhookManager;
import fft_battleground.model.BattleGroundTeam;
import fft_battleground.botland.model.BotParam;
import fft_battleground.tournament.model.Unit;
import fft_battleground.util.GambleUtil;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

/**
 * V3 gene bot: the Rust trainer's clean-semantics scorer (gene-trainer-3,
 * fftb-sim). Differences from GeneticBotV2 mirror how V3 genomes are
 * trained:
 * - brave multiplies the Brave gene and faith the Faith gene (V2 multiplied
 *   the Faith gene into both terms; V3 files carry no BraveFaith shared
 *   multiplier gene);
 * - one team-color side term (V3 files carry no LEFT/RIGHT side genes, so
 *   V2's side+color double-add degenerates to color-only);
 * - the confidence percentile spans the full 1..100 bet table (V2 clamped
 *   at 99, leaving 1-bet unreachable);
 * - exact score ties sit the match out (V2 always picked left).
 * Fight/bet win ratios keep the viewer's Laplace-smoothed live form.
 *
 * Bet modes (Botland.xml param `betMode`, Oct 23 2026):
 * - `ladder` (default): side by score argmax, stake from the confidence
 *   percentile's bet-table gene — the classic path.
 * - `policy`: the two-stage value bettor (gene-trainer-3 settle.rs): the
 *   score margin becomes a win probability through the calibration curve
 *   genes, blended with the live pot-implied probability (marketTrust,
 *   modulated by pot size via trustPotScale); side by best expected value
 *   — the strength pick can flip when the price is wrong; sit out below
 *   the minEdge threshold; stake through the edge-ladder curve genes.
 */
@Slf4j
public class GeneticBotV3
extends GeneticBotV2 {

	private static final String BET_MODE_PARAM = "betMode";
	private static final String BET_MODE_POLICY = "policy";

	@Getter
	private boolean policyMode;

	public GeneticBotV3(Integer currentAmountToBetWith, BattleGroundTeam left, BattleGroundTeam right,
			GeneFileCache<GeneTrainerV2BotData> geneFileCache, Collection<String> bots, WebhookManager noisyWebhookManager) {
		super(currentAmountToBetWith, left, right, geneFileCache, bots, noisyWebhookManager);
	}

	@Override
	public void initParams(Map<String, BotParam> map) throws BotConfigException {
		super.initParams(map);
		this.policyMode = new BotParameterReader(map).readStringParam(BET_MODE_PARAM)
				.map(v -> BET_MODE_POLICY.equalsIgnoreCase(v.trim()))
				.orElse(false);
	}

	@Override
	protected Bet determineBet() throws BotConfigException {
		return this.policyMode ? this.policyDetermineBet() : this.ladderDetermineBet();
	}

	private Bet ladderDetermineBet() throws BotConfigException {
		this.leftScore = this.generateLeftScore();
		this.rightScore = this.generateRightScore();
		if (this.leftScore.equals(this.rightScore)) {
			return null; // exact tie: sit the match out (null bets tolerated upstream)
		}
		BattleGroundTeam winningTeam = this.determineWinningTeam(this.leftScore, this.rightScore);
		if (this.inverse) {
			winningTeam = this.inverse(winningTeam);
		}
		return this.generateBetAmount(this.leftScore, this.rightScore, winningTeam);
	}

	/**
	 * The policy path — a faithful port of Rust `policy_decide`. Stage 1:
	 * probabilities (model calibration curve + live pot-implied blend).
	 * Stage 2: best-EV side, minEdge sit-out, edge-ladder stake. All curve
	 * reads clamp at use; the genes stay free.
	 */
	private Bet policyDetermineBet() throws BotConfigException {
		this.leftScore = this.generateLeftScore();
		this.rightScore = this.generateRightScore();
		if (this.leftScore.equals(this.rightScore)) {
			return null;
		}

		PolicyGeneAttributes policy = this.genes.getPolicyGeneAttributes();
		if (policy == null) {
			throw new BotConfigException("betMode=policy requires a policyGeneAttributes section in the gene file");
		}

		float winnerScore = Math.max(this.leftScore, this.rightScore);
		float loserScore = Math.min(this.leftScore, this.rightScore);
		int pct = confidencePercentile(winnerScore, loserScore);

		// Stage 1 — model probability from the calibration curve.
		double pModelPick = clamp(PolicyGeneAttributes.anchorAt(policy.calAnchors(), pct) / 100.0, 0.02, 0.98);

		// The live market: current pot sums are the parimutuel prices.
		boolean pickLeft = this.leftScore > this.rightScore;
		int potLeft = this.sumOfBets(this.betsBySide.getLeft());
		int potRight = this.sumOfBets(this.betsBySide.getRight());
		double oddsPick = pickLeft ? profitMultiplier(potLeft, potRight) : profitMultiplier(potRight, potLeft);
		double oddsOther = pickLeft ? profitMultiplier(potRight, potLeft) : profitMultiplier(potLeft, potRight);
		double pImpliedPick = PolicyGeneAttributes.impliedProbability(oddsPick);
		double pImpliedOther = PolicyGeneAttributes.impliedProbability(oddsOther);

		// Market trust, modulated by pot size (big pots carry more — or less,
		// if trustPotScale says so; it is signed).
		double potNorm = Math.min(1.0, (potLeft + potRight) / 100_000.0);
		double mBase = clamp(policy.geneOrZero(PolicyGeneAttributes::marketTrust) / 100.0, 0.0, 1.0);
		double c = clamp(policy.geneOrZero(PolicyGeneAttributes::trustPotScale) / 100.0, -1.0, 1.0);
		double mEff = clamp(mBase + 0.5 * c * potNorm, 0.0, 1.0);

		double pPick = clamp((1.0 - mEff) * pModelPick + mEff * pImpliedPick, 0.02, 0.98);
		double pOther = clamp((1.0 - mEff) * (1.0 - pModelPick) + mEff * pImpliedOther, 0.02, 0.98);

		// Stage 2 — value: best-EV side wins; below minEdge: no bet.
		double evPick = pPick * oddsPick - (1.0 - pPick);
		double evOther = pOther * oddsOther - (1.0 - pOther);
		boolean betPickSide = evPick >= evOther;
		double ev = betPickSide ? evPick : evOther;
		double theta = clamp(policy.geneOrZero(PolicyGeneAttributes::minEdge) / 100.0, -1.0, 1.0);
		if (ev < theta) {
			return null;
		}

		BattleGroundTeam team = this.sideTeam(pickLeft);
		if (!betPickSide) {
			team = this.sideTeam(!pickLeft);
		}
		if (this.inverse) {
			team = this.inverse(team);
		}

		// Stake: edge ladder; the x-axis saturates at EV 2.0.
		double x = Math.min(Math.max(ev, 0.0) / 2.0, 1.0) * 100.0;
		double fraction = clamp(PolicyGeneAttributes.anchorAt(policy.edgeAnchors(), x) / 100.0, 0.0, 1.0);
		int budget = Math.min(this.currentAmountToBetWith, GambleUtil.MAX_BET);
		long rounded = Math.round(fraction * budget);
		int stake = (int) Math.max(1, Math.min(budget, rounded));
		return new Bet(team, stake, this.isBotSubscriber);
	}

	private BattleGroundTeam sideTeam(boolean left) {
		return left ? this.left : this.right;
	}

	private int sumOfBets(List<fft_battleground.event.detector.model.BetEvent> bets) {
		return bets.stream().mapToInt(fft_battleground.event.detector.model.BetEvent::getBetAmountInteger).sum();
	}

	/** Current-pot parimutuel profit multiplier for the `own` side. */
	private static double profitMultiplier(int ownPot, int otherPot) {
		return ownPot <= 0 ? 1.0 : (double) otherPot / (double) ownPot;
	}

	private static double clamp(double v, double lo, double hi) {
		return Math.max(lo, Math.min(hi, v));
	}

	/** Confidence percentile 1..100 from the score margin (shared by both modes). */
	private static int confidencePercentile(float winnerScore, float loserScore) {
		double scoreRatio = ((double) (winnerScore - loserScore + 1)) / ((double) winnerScore + 1);
		int ratioRounded = (int) Math.round(scoreRatio * 100);
		return Math.max(1, Math.min(100, ratioRounded));
	}

	@Override
	protected Bet generateBetAmount(Float leftScore, Float rightScore, BattleGroundTeam chosenTeam) {
		float winnerScore = Math.max(leftScore, rightScore);
		float loserScore = Math.min(leftScore, rightScore);
		double scoreRatio = ((double) (winnerScore - loserScore + 1)) / ((double) winnerScore + 1);
		int ratioRounded = (int) Math.round(scoreRatio * 100);
		if (ratioRounded < 1) {
			ratioRounded = 1;
		}
		if (ratioRounded > 100) {
			ratioRounded = 100; // full table: 1-bet is reachable in V3 files
		}
		int betAmount = this.isUseBetGenes()
				? this.genes.getBetGeneAttributes().getValue(ratioRounded)
				: 1000;
		betAmount = NumberUtils.min(this.currentAmountToBetWith, betAmount);
		return new Bet(chosenTeam, betAmount, this.isBotSubscriber);
	}

	@Override
	protected Double scoreTeamAttributes(BattleGroundTeam actualTeam) throws BotConfigException {
		Double score = 0d;
		BattleGroundTeam side = actualTeam == this.left ? BattleGroundTeam.LEFT : BattleGroundTeam.RIGHT;
		// One color term; V3 files carry no LEFT/RIGHT side genes.
		score += this.genes.getSideGeneAttributes().getAttributeByTeam(actualTeam);
		score += this.scoreMap(matchInfo, side);

		List<Unit> units = side == BattleGroundTeam.LEFT ? this.unitsBySide.getLeft() : this.unitsBySide.getRight();
		score += units.stream().map(this.unitAttributeClassifier::getUnitGeneAbilityElements)
				.flatMap(attributeList -> attributeList.stream())
				.filter(gene -> {
					boolean containsGene = this.genes.getMissingGeneAttributes().containsGene(gene);
					if (!containsGene) {
						log.warn("genefile is missing entry for attribute {}", gene);
					}
					return containsGene;
				})
				.mapToDouble(this.genes.getMissingGeneAttributes()::getGene)
				.sum();

		BraveFaithAttributes braveFaithAttributes = this.genes.getBraveFaithAttributes();
		for (Unit unit : units) {
			score += unit.getFaith() * braveFaithAttributes.faith();
			score += unit.getBrave() * braveFaithAttributes.brave();
			score += unit.isRaidBoss() ? braveFaithAttributes.raidBoss() : 0d;
		}
		return score;
	}
}
