package fft_battleground.event;

import java.util.List;
import java.util.Set;
import java.util.Timer;
import java.util.Vector;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.google.common.collect.Sets;

import fft_battleground.botland.BetBotFactory;
import fft_battleground.botland.BotLand;
import fft_battleground.botland.bot.model.BetResults;
import fft_battleground.discord.WebhookManager;
import fft_battleground.dump.cache.map.LastFightActiveCache;
import fft_battleground.event.detector.model.BadBetEvent;
import fft_battleground.event.detector.model.BalanceEvent;
import fft_battleground.event.detector.model.BetEvent;
import fft_battleground.event.detector.model.BetInfoEvent;
import fft_battleground.event.detector.model.BettingBeginsEvent;
import fft_battleground.event.detector.model.BettingEndsEvent;
import fft_battleground.event.detector.model.FightEntryEvent;
import fft_battleground.event.detector.model.MatchInfoEvent;
import fft_battleground.event.detector.model.ResultEvent;
import fft_battleground.event.detector.model.TeamInfoEvent;
import fft_battleground.event.detector.model.UnitInfoEvent;
import fft_battleground.event.detector.model.fake.FightResultEvent;
import fft_battleground.event.model.BattleGroundEvent;
import fft_battleground.event.model.DatabaseResultsData;
import fft_battleground.model.BattleGroundTeam;
import fft_battleground.model.ChatMessage;
import fft_battleground.scheduled.DumpScheduledTasksManagerImpl;
import fft_battleground.scheduled.tasks.DumpMatchScheduledTask;
import fft_battleground.tournament.TournamentService;
import fft_battleground.util.Router;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
public class EventManager extends Thread {
	
	@Autowired
	private BlockingQueue<BattleGroundEvent> eventManagerQueue;
	
	@Autowired
	private Router<ChatMessage> messageSenderRouter;
	
	@Autowired
	private Router<DatabaseResultsData> betResultsRouter;
	
	@Autowired
	private Router<BattleGroundEvent> websocketEventRouter;
	
	@Autowired
	private BetBotFactory betBotFactory;
	
	@Autowired
	private DumpScheduledTasksManagerImpl dumpScheduledTasks;
	
	@Autowired
	private TournamentService tournamentService;
	
	@Autowired
	private WebhookManager errorWebhookManager;
	
	@Autowired
	private LastFightActiveCache lastFightActiveCache;
	
	@Value("${irc.username}") 
	private String botUsername;
	
	private Timer bettingTimer = new Timer();
	private ScheduledExecutorService globalGilTimer = Executors.newScheduledThreadPool(1);
	protected long bettingDelay = 33 * 1000;
	
	public EventManager() {
		this.setName("EventManagerThread");
	}
	
	private BotLand botLand;
	
	@Override
	public void run() {
		int currentAmount = 1;
		List<BetEvent> currentBets = new Vector<>();
		Set<UnitInfoEvent> currentUnits = Sets.newConcurrentHashSet();
		boolean bettingCurrently = false;
		//BetBot previousBetBot = null;
		//BetBot currentBetBot = null;
		while(true) {
			try {
				BattleGroundEvent event = this.eventManagerQueue.take();
				switch(event.getEventType()) {
					case FIGHT_BEGINS:
						this.handleFight(event);
						break;
					case FIGHT_ENTRY: case DONT_FIGHT:
						FightEntryEvent fightEntryEvent = (FightEntryEvent) event;
						this.betResultsRouter.sendDataToQueues((DatabaseResultsData) event);
						this.lastFightActiveCache.put(fightEntryEvent.getPlayer(), event.getEventTime());
						break;
					case BETTING_BEGINS:
						bettingCurrently = true;
						sendBalanceRequest(); //start the WIN/LOSS chain of events
						bettingCurrently = true;
						currentBets = new Vector<>();
						//currentUnits = Sets.newConcurrentHashSet();
						if(event instanceof BettingBeginsEvent) {
							BettingBeginsEvent beginEvent = (BettingBeginsEvent) event;
							this.botLand = this.betBotFactory.createBotLand(currentAmount, currentBets, currentUnits, beginEvent);
							this.bettingTimer.schedule(this.botLand, this.bettingDelay);
						}
						break;
					case BETTING_ENDS:
						bettingCurrently = false;
						BettingEndsEvent bettingEndsEvent = (BettingEndsEvent) event;
						currentUnits = Sets.newConcurrentHashSet();
						if(this.botLand != null) {
							this.botLand.setBettingEndsEvent(bettingEndsEvent);
						}
						if(bettingEndsEvent.getTeam1() == BattleGroundTeam.RED && bettingEndsEvent.getTeam2() == BattleGroundTeam.BLUE) {
							DumpMatchScheduledTask task = this.dumpScheduledTasks.globalGilUpdateTask();
							this.globalGilTimer.schedule(task, 5L, TimeUnit.SECONDS);
						}
						break;
					case BALANCE:
						BalanceEvent balanceEvent = (BalanceEvent) event;
						currentAmount = balanceEvent.getAmount();
						if(this.botLand != null) {
							this.botLand.setCurrentAmountToBetWith(currentAmount);
						}
						break;
					case BET:
						if(bettingCurrently && event instanceof BetEvent  && this.botLand != null) {
							this.botLand.addBet((BetEvent) event);
						}
						break;
					case BET_INFO:
						if(event instanceof BetInfoEvent && this.botLand != null) {
							BetInfoEvent betInfoEvent = (BetInfoEvent) event;
							if(betInfoEvent.getTeam() != null) {
								this.botLand.addBetInfo(betInfoEvent);
							}
						}
						break;
					case BAD_BET:
						if(bettingCurrently && event instanceof BadBetEvent && this.botLand != null) {
							this.botLand.removeBet((BadBetEvent)event);
							
							BadBetEvent badBetEvent = (BadBetEvent) event;
							boolean isBadBotBet = badBetEvent.getPlayers().stream().filter(player -> StringUtils.equalsAnyIgnoreCase(player, this.botUsername)).count() > 0;
							if(isBadBotBet) {
								log.error("Bad bot bet");
								this.errorWebhookManager.sendMessage("The bot was caught making a bad bet");
							}
						}
						
						break;
					case RESULT:
						ResultEvent resultEvent = (ResultEvent) event;
						if(this.botLand != null && event instanceof ResultEvent) {
							BetResults aftermathOfBotBet = this.botLand.createCollector().getResult((ResultEvent) event);
							this.betResultsRouter.sendDataToQueues(aftermathOfBotBet);
							this.updateCurrentTournamentWithWinnerDate(aftermathOfBotBet);
							FightResultEvent fightResultEvent = new FightResultEvent(resultEvent, aftermathOfBotBet.getLosingTeam());
							this.websocketEventRouter.sendDataToQueues(fightResultEvent);
							if(fightResultEvent != null) {
								log.info("Found event: {} with data: {}", fightResultEvent.getEventType().getEventStringName(), fightResultEvent.toString());
							}
						}
						break;
					case OTHER_PLAYER_BALANCE: case LEVEL_UP: case OTHER_PLAYER_EXP: case ALLEGIANCE:
					case BUY_SKILL: case SKILL_WIN: case PORTRAIT:  case PRESTIGE_SKILLS:
					case LAST_ACTIVE: case GIFT_SKILL: case GLOBAL_GIL_COUNT_UPDATE: case PRESTIGE_ASCENSION:
					case BUY_SKILL_RANDOM: case CLASS_BONUS: case SKILL_BONUS: case SNUB: case OTHER_PLAYER_SNUB:
					case BONUS:
						this.betResultsRouter.sendDataToQueues((DatabaseResultsData) event);
						break;
					case MATCH_INFO:
						if(this.botLand != null) {
							this.botLand.setMatchInfo((MatchInfoEvent) event);
						}
						break;
					case TEAM_INFO:
						TeamInfoEvent teamInfoEvent = (TeamInfoEvent) event;
						if(this.botLand != null) {
							this.botLand.addTeamInfo(teamInfoEvent);
						}
						break;
					case UNIT_INFO:
						if(event instanceof UnitInfoEvent && this.botLand != null) {
							UnitInfoEvent unitInfoEvent = (UnitInfoEvent) event;
							this.botLand.addUnitInfo(unitInfoEvent);
							currentUnits.add(unitInfoEvent);
						}
						break;
					case SKILL_DROP: case PLAYER_SKILL: default:
						break;
				}
				assert(currentAmount != 0);
			} catch (InterruptedException e) {
				log.error("Error found handling event", e);
			}
		}
	}
	
	private void updateCurrentTournamentWithWinnerDate(BetResults betResults) {
		this.tournamentService.getCurrentTournament().addWinData(betResults.getWinningTeam(), betResults.getLosingTeam());
	}
	
	public void sendScheduledMessage(String message, Long waitTime) {
		this.bettingTimer.schedule(new MessageSenderTask(this.messageSenderRouter, message), waitTime);
	}
	
	public void sendMessage(String message) {
		this.messageSenderRouter.sendDataToQueues(new ChatMessage(message));
	}
	
	private void handleFight(BattleGroundEvent event) {
		return;
	}
	
	private void sendBalanceRequest() {
		final String balanceRequest = "!bal";
		this.sendMessage(balanceRequest);
	}

}
