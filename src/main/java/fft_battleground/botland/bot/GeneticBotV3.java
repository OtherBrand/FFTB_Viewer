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
import fft_battleground.botland.bot.model.ScoreBreakdown;
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
	/**
	 * The policy path's minimum stake (owner rule, Oct 23 2026): it always
	 * bets — never sits out, never dust. The bots are part of the show:
	 * the bet is the proof of life. Mirrors Rust POLICY_MIN_STAKE.
	 */
	private static final int POLICY_MIN_STAKE = 100;

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

	/**
	 * Personality is OPTIONAL for V3 entries (Oct 23 2026 prod incident: the
	 * inherited required-param check killed every genebotV3 init at its
	 * first betting cycle, primary included — nothing bet). A present
	 * personality param is still honored; absent means no personality
	 * messages, betting unaffected. generatePersonalityResponse is already
	 * null-safe downstream.
	 */
	/**
	 * The inverse twin bets the opposite side of what the base scorer
	 * narrates, so its inherited rationale text lies in chat ("Betting on
	 * Yellow" while betting White — deployer observation, Oct 24 2026).
	 * Silence the mirror: the canary's bets speak for it.
	 */
	@Override
	public fft_battleground.botland.personality.model.PersonalityResponse generatePersonalityResponse() {
		if (this.inverse || this.explain) {
			//inverse: the mirror's bets speak for it; explain: the advisor is data-only, no output
			return null;
		}
		return super.generatePersonalityResponse();
	}

	@Override
	public String readPersonalityParam(BotParameterReader reader) {
		return reader.readStringParam(fft_battleground.botland.bot.util.BotContainsPersonality.PERSONALITY_PARAM)
				.orElse(null);
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

		// Stage 2 — value: best-EV side wins. Owner rule (Oct 23 2026): the
		// policy path ALWAYS bets — below minEdge or a dust-sized ladder
		// stake, the bet is the floor (100, bounded by bankroll) on the
		// best-EV side. No sit-outs, no 1-gil bets: the bet is the proof
		// of life for the stream.
		double evPick = pPick * oddsPick - (1.0 - pPick);
		double evOther = pOther * oddsOther - (1.0 - pOther);
		boolean betPickSide = evPick >= evOther;
		double ev = betPickSide ? evPick : evOther;
		double theta = clamp(policy.geneOrZero(PolicyGeneAttributes::minEdge) / 100.0, -1.0, 1.0);

		int budget = Math.min(this.currentAmountToBetWith, GambleUtil.MAX_BET);
		if (budget <= 0) {
			return null; // genuinely broke — nothing to bet with
		}
		double floor = Math.min(POLICY_MIN_STAKE, budget);

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
		long rounded = Math.round(fraction * budget);
		long stake = ev < theta ? Math.round(floor) : Math.max(Math.round(floor), Math.min(budget, rounded));
		return new Bet(team, (int) stake, this.isBotSubscriber);
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

	/** Confidence percentile 1..100 from the score margin (shared by both
	 * modes). Package-visible for the Rust-parity test. */
	static int confidencePercentile(float winnerScore, float loserScore) {
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
	protected Double computeModelWinProb() {
		if (this.genes == null || this.leftScore == null || this.rightScore == null) {
			return null;
		}
		PolicyGeneAttributes policy = this.genes.getPolicyGeneAttributes();
		if (policy == null) {
			return null;
		}
		float winnerScore = Math.max(this.leftScore, this.rightScore);
		float loserScore = Math.min(this.leftScore, this.rightScore);
		int pct = confidencePercentile(winnerScore, loserScore);
		// same calibration read policyDetermineBet uses for pModelPick
		return clamp(PolicyGeneAttributes.anchorAt(policy.calAnchors(), pct) / 100.0, 0.02, 0.98);
	}

	@Override
	protected Double scoreTeamAttributes(BattleGroundTeam actualTeam) throws BotConfigException {
		Double score = 0d;
		BattleGroundTeam side = actualTeam == this.left ? BattleGroundTeam.LEFT : BattleGroundTeam.RIGHT;
		// One color term; V3 files carry no LEFT/RIGHT side genes.
		Double sideColor = this.genes.getSideGeneAttributes().getAttributeByTeam(actualTeam);
		Double mapTerm = this.scoreMap(matchInfo, side);
		if (this.explain) {
			this.explainGeneTerms.add(new ScoreBreakdown.GeneTerm("SideColor", actualTeam, sideColor));
			this.explainGeneTerms.add(new ScoreBreakdown.GeneTerm("Map", actualTeam, mapTerm));
		}
		score += sideColor;
		score += mapTerm;

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
				.mapToDouble(gene -> {
					double value = this.genes.getMissingGeneAttributes().getGene(gene);
					if (this.explain) {
						this.explainGeneTerms.add(new ScoreBreakdown.GeneTerm(gene, actualTeam, value));
					}
					return value;
				})
				.sum();

		BraveFaithAttributes braveFaithAttributes = this.genes.getBraveFaithAttributes();
		double braveSum = 0d, faithSum = 0d, raidBossSum = 0d;
		for (Unit unit : units) {
			double faithTerm = unit.getFaith() * braveFaithAttributes.faith();
			double braveTerm = unit.getBrave() * braveFaithAttributes.brave();
			double raidBossTerm = unit.isRaidBoss() ? braveFaithAttributes.raidBoss() : 0d;
			score += faithTerm;
			score += braveTerm;
			score += raidBossTerm;
			braveSum += braveTerm;
			faithSum += faithTerm;
			raidBossSum += raidBossTerm;
		}
		if (this.explain && !units.isEmpty()) {
			this.explainGeneTerms.add(new ScoreBreakdown.GeneTerm("Brave", actualTeam, braveSum));
			this.explainGeneTerms.add(new ScoreBreakdown.GeneTerm("Faith", actualTeam, faithSum));
			this.explainGeneTerms.add(new ScoreBreakdown.GeneTerm("RaidBoss", actualTeam, raidBossSum));
		}
		return score;
	}
}
