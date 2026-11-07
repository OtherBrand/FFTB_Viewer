package fft_battleground.botland.bot;

import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import org.apache.commons.lang3.math.NumberUtils;

import fft_battleground.botland.bot.exception.BotConfigException;
import fft_battleground.botland.bot.exception.MissingGeneException;
import fft_battleground.botland.bot.exception.MissingPlayerMetadataException;
import fft_battleground.botland.bot.exception.MissingTeamInfoException;
import fft_battleground.botland.bot.genetic.GeneFileCache;
import fft_battleground.botland.bot.genetic.model.BotGenome;
import fft_battleground.botland.bot.genetic.model.BraveFaithAttributes;
import fft_battleground.botland.bot.genetic.model.GeneTrainerV2BotData;
import fft_battleground.botland.bot.genetic.model.MapGeneAttributes;
import fft_battleground.botland.bot.genetic.model.PlayerDataGeneAttributes;
import fft_battleground.botland.bot.genetic.model.PotGeneAttributes;
import fft_battleground.botland.bot.genetic.model.SideGeneAttributes;
import fft_battleground.botland.bot.model.Bet;
import fft_battleground.botland.bot.util.BetterBetBot;
import fft_battleground.botland.bot.util.BotCanBetBelowMinimum;
import fft_battleground.botland.bot.util.BotCanInverse;
import fft_battleground.botland.bot.util.BotContainsPersonality;
import fft_battleground.botland.bot.util.BotParameterReader;
import fft_battleground.botland.bot.util.BotUsesGeneFile;
import fft_battleground.botland.bot.model.ScoreBreakdown;
import fft_battleground.botland.model.BotParam;
import fft_battleground.discord.WebhookManager;
import fft_battleground.event.detector.model.BetEvent;
import fft_battleground.event.detector.model.MatchInfoEvent;
import fft_battleground.event.detector.model.TeamInfoEvent;
import fft_battleground.model.BattleGroundTeam;
import fft_battleground.repo.model.PlayerRecord;
import fft_battleground.tournament.classifier.UnitAttributeClassifier;
import fft_battleground.tournament.classifier.V2UnitAttributeClassifier;
import fft_battleground.tournament.model.Unit;
import fft_battleground.util.GambleUtil;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class GeneticBotV2 
extends BetterBetBot
implements BotCanBetBelowMinimum, BotContainsPersonality, BotCanInverse, BotUsesGeneFile {
	private static final String USE_BET_GENES_PARAM = "use_bet_genes";
	private static final String EXPLAIN_PARAM = "explain";
	private static final String COMPOSITION_ONLY_PARAM = "compositionOnly";
	
	private String name;
	protected BotGenome genes;
	private String filename;
	@Getter private boolean canBetBelowMinimum;
	@Getter private boolean useBetGenes;
	@Getter protected boolean explain;
	/** explain-mode only: score fighters + teamAttributes, skip bet/pot sections (the advisor redesign) */
	@Getter protected boolean compositionOnly;

	//explain-mode capture (Team Advisor): per-side section values + gene terms, recorded
	//alongside the normal evaluation. Left side evaluates first, so its generator resets the capture
	protected final List<ScoreBreakdown.GeneTerm> explainGeneTerms = new ArrayList<>();
	protected Double lastLeftFighters, lastLeftTeamAttributes, lastLeftTeamBets, lastLeftPot;
	protected Double lastRightFighters, lastRightTeamAttributes, lastRightTeamBets, lastRightPot;
	
	protected GeneFileCache<GeneTrainerV2BotData> geneFileCache;
	protected Collection<String> bots;
	protected WebhookManager noisyWebhookManager;
	
	protected UnitAttributeClassifier unitAttributeClassifier = new V2UnitAttributeClassifier();
	
	public GeneticBotV2(Integer currentAmountToBetWith, BattleGroundTeam left, BattleGroundTeam right,
			GeneFileCache<GeneTrainerV2BotData> geneFileCache, Collection<String> bots, WebhookManager noisyWebhookManager) {
		super(currentAmountToBetWith, left, right);

		this.geneFileCache = geneFileCache;
		this.bots = bots;
		this.noisyWebhookManager = noisyWebhookManager;
	}

	@Override
	public void initParams(Map<String, BotParam> map) throws BotConfigException {
		BotParameterReader reader = new BotParameterReader(map);
		this.filename = this.readGeneFileParameter(reader);
		this.personalityName = this.readPersonalityParam(reader);
		this.inverse = this.readInverseParameter(reader);
		this.canBetBelowMinimum = this.readCanBetBelowMinimumParameter(reader);
		this.useBetGenes = reader.readOptionalBooleanParam(USE_BET_GENES_PARAM);
		this.explain = reader.readOptionalBooleanParam(EXPLAIN_PARAM);
		boolean compositionOnly = reader.readOptionalBooleanParam(COMPOSITION_ONLY_PARAM);
		if(compositionOnly && !this.explain) {
			log.warn("compositionOnly is only meaningful with explain=true; ignoring for bot {}", this.name);
			compositionOnly = false;
		}
		this.compositionOnly = compositionOnly;
		
		GeneTrainerV2BotData botData = this.geneFileCache.getGeneData(this.filename);
		this.genes = botData.getGenome();
		super.percentiles = botData.getPercentileMap();
	}

	@Override
	public void init() {
		
	}

	@Override
	public String getName() {
		return this.name;
	}

	@Override
	public void setName(String name) {
		this.name = name;
	}

	@Override
	protected Float generateLeftScore() throws BotConfigException {
		Double score = 0d;
		if(this.explain) {
			this.explainGeneTerms.clear();
		}
		Double fighters = this.scoreFighters(this.teamData.getLeftTeamData());
		Double teamAttributes = this.scoreTeamAttributes(this.left);
		Double teamBets = this.compositionOnly ? null : this.scoreTeamBets(this.getBetsBySide().getLeft());
		Double pot = this.compositionOnly ? null : this.scorePot(this.getBetsBySide().getLeft(), this.getBetsBySide().getRight());
		if(this.explain) {
			this.lastLeftFighters = fighters;
			this.lastLeftTeamAttributes = teamAttributes;
			this.lastLeftTeamBets = teamBets;
			this.lastLeftPot = pot;
		}
		score+= fighters;
		score+= teamAttributes;
		if(!this.compositionOnly) {
			score+= teamBets;
			score+= pot;
		}
		return score.floatValue();
	}

	@Override
	protected Float generateRightScore() throws BotConfigException {
		Double score = 0d;
		Double fighters = this.scoreFighters(this.teamData.getRightTeamData());
		Double teamAttributes = this.scoreTeamAttributes(this.right);
		Double teamBets = this.compositionOnly ? null : this.scoreTeamBets(this.getBetsBySide().getRight());
		Double pot = this.compositionOnly ? null : this.scorePot(this.getBetsBySide().getRight(), this.getBetsBySide().getLeft());
		if(this.explain) {
			this.lastRightFighters = fighters;
			this.lastRightTeamAttributes = teamAttributes;
			this.lastRightTeamBets = teamBets;
			this.lastRightPot = pot;
		}
		score+= fighters;
		score+= teamAttributes;
		if(!this.compositionOnly) {
			score+= teamBets;
			score+= pot;
		}
		return score.floatValue();
	}

	public String getGeneFileName() {
		return this.filename;
	}

	@Override
	protected boolean isEvaluationOnly() {
		return this.explain;
	}

	/** Explain-mode capture of the latest evaluation; null when the bot is not in explain mode. */
	public ScoreBreakdown buildScoreBreakdown() {
		if(!this.explain) {
			return null;
		}
		ScoreBreakdown breakdown = new ScoreBreakdown();
		breakdown.setLeftScore(this.leftScore);
		breakdown.setRightScore(this.rightScore);
		breakdown.setLeftFighters(this.lastLeftFighters);
		breakdown.setLeftTeamAttributes(this.lastLeftTeamAttributes);
		breakdown.setLeftTeamBets(this.lastLeftTeamBets);
		breakdown.setLeftPot(this.lastLeftPot);
		breakdown.setRightFighters(this.lastRightFighters);
		breakdown.setRightTeamAttributes(this.lastRightTeamAttributes);
		breakdown.setRightTeamBets(this.lastRightTeamBets);
		breakdown.setRightPot(this.lastRightPot);
		breakdown.setPModelWinProb(this.computeModelWinProb());
		breakdown.setGeneTerms(new ArrayList<>(this.explainGeneTerms));
		breakdown.setComputedAt(System.currentTimeMillis());
		return breakdown;
	}

	/** Calibrated win probability for the score-argmax side; null when the genome provides no calibration anchors. */
	protected Double computeModelWinProb() {
		return null;
	}

	@Override
	protected Bet generateBetAmount(Float leftScore, Float rightScore, BattleGroundTeam chosenTeam) {
		float winnerScore = 0;
		float loserScore = 0;
		if(leftScore >= rightScore) {
			winnerScore = leftScore;
			loserScore = rightScore;
		} else if(rightScore > leftScore) {
			winnerScore = rightScore;
			loserScore = leftScore;
		}
		
		double scoreRatio = ((double) (winnerScore - loserScore + 1)) / ((double) winnerScore + 1);
		int ratioRounded = (int) Math.round(scoreRatio * 100);
		if(ratioRounded < 1) {
			//log.warn("ratio was less than 1! The ratio is {}", ratioRounded);
			ratioRounded = 1;
		}
		if(ratioRounded > 99) {
			//log.warn("ratio is greater than 99!  The ratio is {}", ratioRounded);
			ratioRounded = 99;
		}
		
		int floorBet = this.isBotSubscriber ? GambleUtil.SUBSCRIBER_MINIMUM_BET : GambleUtil.MINIMUM_BET;
		int betAmount = this.currentAmountToBetWith <= floorBet ? floorBet : this.currentAmountToBetWith;
		if(this.useBetGenes) {
			betAmount = this.genes.getBetGeneAttributes().getValue(ratioRounded);
		} else {
			betAmount = 1000;
		}
		betAmount = NumberUtils.min(this.currentAmountToBetWith, betAmount);
		
		Bet bet = new Bet(chosenTeam, betAmount, this.isBotSubscriber);
		
		return bet; 
	}
	
	protected Double scoreTeamAttributes(BattleGroundTeam actualTeam) throws BotConfigException {
		Double score = 0d;
		BattleGroundTeam side = actualTeam == this.left ? BattleGroundTeam.LEFT : BattleGroundTeam.RIGHT;
		SideGeneAttributes sideGeneAttributes = this.genes.getSideGeneAttributes();
		score+= sideGeneAttributes.getAttributeByTeam(actualTeam);
		score+= sideGeneAttributes.getAttributeByTeam(side);
		score+= this.scoreMap(matchInfo, side);
		
		List<Unit> units = side == BattleGroundTeam.LEFT ? this.unitsBySide.getLeft() : this.unitsBySide.getRight();
		score+= units.stream().map(unitAttributeClassifier::getUnitGeneAbilityElements)
				.flatMap(attributeList -> attributeList.stream())
				.filter(gene -> {
					boolean containsGene = this.genes.getMissingGeneAttributes().containsGene(gene);
					if(!containsGene) {
						log.warn("genefile is missing entry for attribute {}", gene);
					}
					return containsGene;
				})
				.mapToDouble(this.genes.getMissingGeneAttributes()::getGene)
				.sum();
		
		BraveFaithAttributes braveFaithAttributes = this.genes.getBraveFaithAttributes();
		for(Unit unit: units) {
			score+= unit.getFaith() * (braveFaithAttributes.faith() + braveFaithAttributes.braveFaith());
			score+= unit.getBrave() * (braveFaithAttributes.brave() + braveFaithAttributes.braveFaith());
			score+= unit.isRaidBoss() ? braveFaithAttributes.raidBoss() : 0d;
		}
		return score;
	}
	
	protected Double scoreMap(MatchInfoEvent matchInfo, BattleGroundTeam side) throws MissingGeneException {
		MapGeneAttributes mapGeneAttributes = this.genes.getMapGeneAttributes();
		double geneValue = 0;
		if(this.matchInfo != null && this.matchInfo.getMapNumber() != null) {
			int mapNumber = this.matchInfo.getMapNumber();
			Double gene = mapGeneAttributes.mapGene(side, mapNumber)
					.orElseThrow(this.createMissingMapGeneException(mapNumber, side));
			geneValue = gene.doubleValue();
		} else {
			this.noisyWebhookManager.sendMessage("Missing map data for bot: " + this.getName());
		}
		return geneValue;
	}
	
	protected Supplier<MissingGeneException> createMissingMapGeneException(int mapNumber, BattleGroundTeam side) {
		String message = MessageFormat.format("Cannot find gene for map {0} and side {1}", mapNumber, side);
		Supplier<MissingGeneException> supplier = () -> new MissingGeneException(message);
		return supplier;
	}
	
	protected Double scoreFighters(TeamInfoEvent teamInfoEvent) throws MissingTeamInfoException, MissingPlayerMetadataException {
		Double score = 0d;
		PlayerDataGeneAttributes playerDataGeneAttributes = this.genes.getPlayerDataGeneAttributes();
		if(teamInfoEvent == null) {
			throw new MissingTeamInfoException("Missing team info data in GeneticBotV2");
		} else if(teamInfoEvent.getMetaData() == null) {
			throw new MissingPlayerMetadataException("Missing player metadata from team info data in GeneticBotV2");
		}
		score += teamInfoEvent.getMetaData().stream()
				.filter(metadata -> metadata != null)
				.mapToDouble((metadata) -> {
					double wins = metadata.getFightWins() != null ? metadata.getFightWins().doubleValue() : 0;
					double losses = metadata.getFightLosses() != null ? metadata.getFightLosses().doubleValue() : 0;
					double fightRatio = (wins + 1d) / (wins + losses + 1d);
					return fightRatio * playerDataGeneAttributes.fightWinRatio();
		}).sum();
		
		int missingUnitMetadata = 4 - teamInfoEvent.getMetaData().size();
		if(missingUnitMetadata > 0) {
			score+= missingUnitMetadata * playerDataGeneAttributes.missingFightWinRatio() * playerDataGeneAttributes.fightWinRatio();
		}
		
		return score;
	}
	
	protected Double scoreTeamBets(List<BetEvent> bets) {
		Double score = 0d;
		//score bet ratios
		List<PlayerRecord> betEventMetadata = bets.stream().map(BetEvent::getMetadata).collect(Collectors.toList());
		PlayerDataGeneAttributes playerGenes = this.genes.getPlayerDataGeneAttributes();
		score += betEventMetadata.stream()
				.filter(metadata -> metadata != null)
				.mapToDouble((metadata) -> {
					double wins = metadata.getWins() != null ? metadata.getWins().doubleValue() : 0;
					double losses = metadata.getLosses() != null ? metadata.getLosses().doubleValue() : 0;
					double betRatio = (wins + 1d) / (wins + losses + 1d);
					return betRatio * playerGenes.betWinRatio();
		}).sum();
		
		//score subscribers
		score+= betEventMetadata.stream().filter(Objects::nonNull)
				.filter(PlayerRecord::isSubscriber).count() * playerGenes.subscriber();
		
		//score robots vs humans
		try {
		score+= betEventMetadata.stream().map(PlayerRecord::getPlayer)
				.mapToDouble(player -> this.bots.contains(player) ? playerGenes.robot() : playerGenes.human())
				.sum();
		} catch(NullPointerException e) {
			log.error("Null Pointer Exception getting player and robot genes", e);
		}
		
		return score;
	}
	
	protected Double scorePot(List<BetEvent> myTeamBet, List<BetEvent> otherTeamBet) {
		PotGeneAttributes potGenes = this.genes.getPotGeneAttributes();
		
		int myTeamSum = myTeamBet.stream().map(BetEvent::getBetAmount).mapToInt(Integer::valueOf).sum();
		int otherTeamSum = otherTeamBet.stream().mapToInt(BetEvent::getBetAmountInteger).sum();
		double odds = this.calculateOddsForTeam(myTeamSum, otherTeamSum);
		
		
		Double score = 0d;
		score+= myTeamSum * potGenes.potAmount();
		score+= myTeamBet.size() * potGenes.betCount();
		score+= odds * potGenes.odds();
		return score;
	}
	
	protected double calculateOddsForTeam(int yourTeamTotalValue, int theirTeamTotalValue) {
		if (yourTeamTotalValue == 0 || theirTeamTotalValue == 0) {
			return 0d;
		} else {
			double result =  ((double)theirTeamTotalValue)/((double)yourTeamTotalValue);
			return result;
		}
	}

}
