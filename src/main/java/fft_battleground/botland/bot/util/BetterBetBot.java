package fft_battleground.botland.bot.util;

import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

import org.apache.commons.lang3.tuple.Pair;

import fft_battleground.botland.bot.exception.BotConfigException;
import fft_battleground.botland.bot.model.Bet;
import fft_battleground.botland.bot.model.BetType;
import fft_battleground.botland.bot.model.TeamData;
import fft_battleground.botland.model.BotParam;
import fft_battleground.botland.personality.PersonalityModule;
import fft_battleground.botland.personality.model.PersonalityResponse;
import fft_battleground.event.detector.model.BetEvent;
import fft_battleground.event.detector.model.MatchInfoEvent;
import fft_battleground.model.BattleGroundTeam;
import fft_battleground.repo.repository.PlayerRecordRepo;
import fft_battleground.tournament.model.Unit;
import fft_battleground.util.GambleUtil;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Data
public abstract class BetterBetBot
implements Callable<Bet> {
	protected boolean isBotSubscriber = true;
	
	protected String personalityName;
	protected PersonalityModule personalityModule;
	protected Map<Integer, Integer> percentiles = Collections.emptyMap();
	
	protected boolean inverse = false;
	
	protected Integer currentAmountToBetWith;
	protected List<BetEvent> otherPlayerBets;
	protected BattleGroundTeam left;
	protected BattleGroundTeam right;

	protected MatchInfoEvent matchInfo;
	protected TeamData teamData;
	protected PlayerRecordRepo playerRecordRepoRef;
	
	protected Pair<List<BetEvent>, List<BetEvent>> betsBySide;
	protected Pair<List<Unit>, List<Unit>> unitsBySide;
	protected Bet result;
	
	protected Date startTime;
	protected String dateFormat;
	
	protected Float leftScore;
	protected Float rightScore;
	
	public BetterBetBot(Integer currentAmountToBetWith, BattleGroundTeam left, BattleGroundTeam right) {
		this.currentAmountToBetWith = currentAmountToBetWith;
		this.left = left;
		this.right = right;
		
		this.startTime = new Date();
		
		assert(this.currentAmountToBetWith.intValue() != 0);
		this.teamData = new TeamData();
	}

	@Override
	public Bet call() throws Exception {
		// TODO Auto-generated method stub
		this.init();
		Bet bet =  this.determineBet();
		this.ensureBetIsWithinParameters(bet);
		this.result = bet;
		return bet;
	}
	
	public PersonalityResponse generatePersonalityResponse() {
		PersonalityResponse result = null;
		if(this.personalityModule != null) {
			result = this.personalityModule.getPersonalityResponse(this.getName(), this.leftScore, this.left, this.rightScore, this.right, this.percentiles);
		}
		
		return result;
	}
	
	/**
	 * Calculate a bet using two scoring algorithms.
	 * 
	 * @return
	 * @throws BotConfigException 
	 */
	protected Bet determineBet() throws BotConfigException {
		this.leftScore = this.generateLeftScore();
		this.rightScore = this.generateRightScore();
		
		BattleGroundTeam winningTeam = this.determineWinningTeam(this.leftScore, this.rightScore);
		if((this instanceof BotCanInverse) && this.inverse) {
			winningTeam = this.inverse(winningTeam);
		}
		Bet result = this.generateBetAmount(this.leftScore, this.rightScore, winningTeam);
		
		return result;
	}
	
	protected void ensureBetIsWithinParameters(Bet bet) {
		if(bet == null) {
			//bet = new Bet(this.left, GambleUtil.MINIMUM_BET);
			if(!this.isEvaluationOnly()) {
				log.warn("The bot {} provided a null bet", this.getName());
			}
		} else if (bet.getType() == BetType.VALUE) {
			if(bet.getAmount() == null) {
				bet.setAmount(GambleUtil.getMinimumBetForBettor(this.isBotSubscriber));
			} else if (bet.getAmount() > GambleUtil.MAX_BET) {
				log.warn("The bot {} provided a bet over the maximum value", this.getName());
				bet.setAmount(GambleUtil.MAX_BET);
			} else if (bet.getAmount() < GambleUtil.getMinimumBetForBettor(this.isBotSubscriber)) {
				//evaluation-only stakes are advisory (their result has no consumer), so the warn is noise
				if(!this.isEvaluationOnly()) {
					log.warn("The bot {} provided a bet under the minimum value", this.getName());
				}
				if(this instanceof BotCanBetBelowMinimum) {
					BotCanBetBelowMinimum bot = (BotCanBetBelowMinimum) this;
					bet.setAmount(bot.handleMinimumBet(this.isBotSubscriber));
				} else {
					bet.setAmount(GambleUtil.getMinimumBetForBettor(this.isBotSubscriber));
				}

			}
		}
	}

	/** Evaluation-only bots (explain mode) never bet for real: parameter warnings on their stakes are journal noise. */
	protected boolean isEvaluationOnly() {
		return false;
	}
	
	/**
	 * Uses scores to determine a winner
	 * 
	 * @param leftScore
	 * @param rightScore
	 * @return
	 */
	protected BattleGroundTeam determineWinningTeam(Float leftScore, Float rightScore) {
		log.info("Bot {}: The leftScore is {} and the rightScore is {}", this.getName(), leftScore, rightScore);
		BattleGroundTeam winningTeam = null;
		if(leftScore > rightScore) {
			winningTeam = this.left;
		} else if(leftScore < rightScore) {
			winningTeam = this.right;
		} else {
			winningTeam = this.left; //just pick one
		}
		
		return winningTeam;
	}
	
	protected BattleGroundTeam inverse(BattleGroundTeam realWinningTeam) {
		if(realWinningTeam == this.left) {
			return this.right;
		} else {
			return this.left;
		}
	}
	
	public abstract void initParams(Map<String, BotParam> map) throws BotConfigException;
	
	public abstract void init() throws BotConfigException;
	
	public abstract String getName();
	
	public abstract void setName(String name);

	protected abstract Float generateLeftScore() throws BotConfigException;

	protected abstract Float generateRightScore() throws BotConfigException;

	protected abstract Bet generateBetAmount(Float leftScore, Float rightScore, BattleGroundTeam chosenTeam) throws BotConfigException;
}
