package fft_battleground.event;

import java.util.Collection;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import fft_battleground.discord.WebhookManager;
import fft_battleground.event.annotate.BattleGroundEventAnnotator;
import fft_battleground.event.annotate.TeamInfoEventAnnotator;
import fft_battleground.event.annotate.UnitInfoEventAnnotator;
import fft_battleground.event.detector.model.BetEvent;
import fft_battleground.event.detector.model.BetInfoEvent;
import fft_battleground.event.detector.model.BettingBeginsEvent;
import fft_battleground.event.detector.model.BuySkillEvent;
import fft_battleground.event.detector.model.BuySkillRandomEvent;
import fft_battleground.event.detector.model.FightBeginsEvent;
import fft_battleground.event.detector.model.FightEntryEvent;
import fft_battleground.event.detector.model.GiftSkillEvent;
import fft_battleground.event.detector.model.HypeEvent;
import fft_battleground.event.detector.model.MatchInfoEvent;
import fft_battleground.event.detector.model.MusicEvent;
import fft_battleground.event.detector.model.PrestigeAscensionEvent;
import fft_battleground.event.detector.model.SkillDropEvent;
import fft_battleground.event.detector.model.SkillWinEvent;
import fft_battleground.event.detector.model.TeamInfoEvent;
import fft_battleground.event.detector.model.UnitInfoEvent;
import fft_battleground.event.detector.model.composite.OtherPlayerInvalidFightCombinationEvent;
import fft_battleground.event.detector.model.composite.OtherPlayerInvalidFightEntryClassEvent;
import fft_battleground.event.detector.model.composite.OtherPlayerInvalidFightEntrySexEvent;
import fft_battleground.event.detector.model.composite.OtherPlayerInvalidFightEntryTournamentStartedEvent;
import fft_battleground.event.detector.model.composite.OtherPlayerSkillOnCooldownEvent;
import fft_battleground.event.detector.model.composite.OtherPlayerUnownedSkillEvent;
import fft_battleground.event.detector.model.fake.TournamentStatusUpdateEvent;
import fft_battleground.event.model.BattleGroundEvent;
import fft_battleground.exception.BattleGroundException;
import fft_battleground.exception.DumpException;
import fft_battleground.exception.MissingEventTypeException;
import fft_battleground.exception.NotANumberBetException;
import fft_battleground.exception.TournamentApiException;
import fft_battleground.model.BattleGroundTeam;
import fft_battleground.model.ChatMessage;
import fft_battleground.music.MusicService;
import fft_battleground.scheduled.DumpScheduledTasksManagerImpl;
import fft_battleground.scheduled.tasks.DumpTournamentScheduledTask;
import fft_battleground.tournament.TournamentService;
import fft_battleground.tournament.model.Tournament;
import fft_battleground.tournament.tracker.TournamentTracker;
import fft_battleground.util.Router;
import lombok.Getter;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
public class EventParser extends Thread {
	
	private boolean logEvents = true;
	
	@Autowired
	private BlockingQueue<ChatMessage> eventParserMessageQueue;

	@Autowired
	private fft_battleground.wordcloud.WordIngestionService wordIngestionService;
	
	@Autowired
	private Router<BattleGroundEvent> eventRouter;
	
	@Autowired
	private Router<ChatMessage> messageSenderRouter;
	
	@Autowired
	private List<EventDetector<?>> detectors;
	
	@Autowired
	private TournamentService tournamentService;
	
	@Autowired
	private TournamentTracker tournamentTracker;
	
	@Autowired
	private DumpScheduledTasksManagerImpl dumpScheduledTasks;
	
	@Autowired
	private WebhookManager errorWebhookManager;
	
	@Autowired
	private BattleGroundEventAnnotator<BetEvent> betEventAnnotator;
	
	@Autowired
	private BattleGroundEventAnnotator<BetInfoEvent> betInfoEventAnnotator;
	
	@Autowired
	private BattleGroundEventAnnotator<PrestigeAscensionEvent> prestigeAscensionEventAnnotator;
	
	@Autowired
	private BattleGroundEventAnnotator<SkillDropEvent> skillDropEventAnnotator;
	
	@Autowired
	private BattleGroundEventAnnotator<SkillWinEvent> skillWinEventAnnotator;
	
	@Autowired
	private BattleGroundEventAnnotator<BuySkillEvent> buySkillEventAnnotator;
	
	@Autowired
	private BattleGroundEventAnnotator<BuySkillRandomEvent> buySkillRandomEventAnnotator;
	
	@Autowired
	private BattleGroundEventAnnotator<GiftSkillEvent> giftSkillEventAnnotator;
	
	@Autowired
	private TeamInfoEventAnnotator teamInfoEventAnnotator;
	
	@Autowired
	private UnitInfoEventAnnotator unitInfoEventAnnotator;
	
	@Autowired
	private BattleGroundEventAnnotator<FightEntryEvent> fightEntryEventAnnotator;
	
	@Autowired
	private MusicService musicService;
	
	private ExecutorService eventTimer = Executors.newFixedThreadPool(1);
	private ExecutorService tournamentTrackerTimer = Executors.newFixedThreadPool(1);
	
	public EventParser() {
		this.setName("EvntParsrThrd");
	}
	
	@Getter private Tournament currentTournament = null;
	private MatchInfoEvent previousMatchEvent = null;
	
	@Override
	@SneakyThrows
	public void run() {
		this.handleRouterData();
	}
	
	protected void handleRouterData() {
		log.info("Starting parser");
		while(true) {
			ChatMessage message;
			try {
				message = eventParserMessageQueue.take();
				log.debug("{}", message);
				//get events from detectors
				List<BattleGroundEvent> events = this.getEventsFromChatMessage(message);
				for(BattleGroundEvent event : events) {
					//log and handle each event
					try {
						this.logEvent(event, message);
						this.handleBattleGroundEvent(event);
						this.sendEventToDetectorAudit(event);
					} catch(MissingEventTypeException e) {
						log.error("Missing event type for event of type: {}", event.getClass().toString(), e);
					}
				}
				//word cloud ingestion (R3a): the drop path — messages no detector claimed are user chatter
				if(events.isEmpty() && this.wordIngestionService != null) {
					this.wordIngestionService.ingest(message.getUsername(), message.getMessage(), message.getEmoteCodes());
				}
			} catch (InterruptedException e) {
				log.warn("Interruption exception in EventParser", e);
			}
			
				
		}
	}
	
	protected List<BattleGroundEvent> getEventsFromChatMessage(ChatMessage message) {
		List<BattleGroundEvent> events = new LinkedList<>();
		for(EventDetector<? extends BattleGroundEvent> detector : this.detectors) {
			BattleGroundEvent event = null;
			try {
				event = detector.detect(message);
			} catch(Exception e) {
				String errorMessage = "exception found while running detector " + detector.getClass().getCanonicalName();
				log.warn(errorMessage, e);
				this.errorWebhookManager.sendWarningException(e, errorMessage);
			}
			if(event != null) {
				event.setEventTime(message.getMessageTime()); // this sets the event time to when the message was read, not parsed
				events.add(event);
			}
		}
		
		return events;
	}
	
	protected void handleBattleGroundEvent(BattleGroundEvent event) throws MissingEventTypeException {
		try {
			if(event != null) {
				if(event.getEventType() == null) {
					throw new MissingEventTypeException("missing event type for class " + event.getClass().toString());
				}
				switch(event.getEventType()) {
				case BET:
					BetEvent betEvent = (BetEvent) event;
					try {
						this.betEventAnnotator.annotateEvent(betEvent);
						this.eventRouter.sendDataToQueues(event);
					} catch(NotANumberBetException e) {
						log.error("Error parsing bet {}", e, betEvent.getBetText());
						this.errorWebhookManager.sendMessage("Bad bet found: " + betEvent.getBetText() + " ignoring for now.");
					}
					break;
				case BET_INFO:
					this.betInfoEventAnnotator.annotateEvent((BetInfoEvent) event);
					this.eventRouter.sendDataToQueues(event);
					break;
				case FIGHT_BEGINS:
					FightBeginsEvent fightEvent = (FightBeginsEvent) event;
					SkillDropEvent skillDropEvent = fightEvent.generateSkillDropEvent();
					this.eventRouter.sendDataToQueues(fightEvent);
					this.skillDropEventAnnotator.annotateEvent(skillDropEvent);
					this.eventRouter.sendDataToQueues(skillDropEvent);
					break;
				case FIGHT_ENTRY:
					FightEntryEvent fightEntryEvent = (FightEntryEvent) event;
					this.fightEntryEventAnnotator.annotateEvent(fightEntryEvent);
					this.eventRouter.sendDataToQueues(fightEntryEvent);
					break;		
				case OTHER_PLAYER_INVALID_FIGHT_CLASS:
					OtherPlayerInvalidFightEntryClassEvent invalidFightEntryClassEvents = (OtherPlayerInvalidFightEntryClassEvent) event;
					this.sendAllEventsToEventRouter(invalidFightEntryClassEvents.getEvents());
					break;
				case OTHER_PLAYER_INVALID_FIGHT_COMBINATION:
					OtherPlayerInvalidFightCombinationEvent invalidFightEntryFightCombinationEvents = (OtherPlayerInvalidFightCombinationEvent) event;
					this.sendAllEventsToEventRouter(invalidFightEntryFightCombinationEvents.getEvents());
					break;
				case OTHER_PLAYER_UNOWNED_SKILL:
					OtherPlayerUnownedSkillEvent otherPlayerUnownedSkillEvent = (OtherPlayerUnownedSkillEvent) event;
					this.sendAllEventsToEventRouter(otherPlayerUnownedSkillEvent.getUnownedSkillEvents());
					break;
				case OTHER_PLAYER_SKILL_ON_COOLDOWN:
					OtherPlayerSkillOnCooldownEvent otherPlayerSkillOnCooldownEvent = (OtherPlayerSkillOnCooldownEvent) event;
					this.sendAllEventsToEventRouter(otherPlayerSkillOnCooldownEvent.getEvents());
					break;
				case OTHER_PLAYER_INVALID_SEX_EVENT:
					OtherPlayerInvalidFightEntrySexEvent invalidFightEntrySexEvent = (OtherPlayerInvalidFightEntrySexEvent) event;
					this.sendAllEventsToEventRouter(invalidFightEntrySexEvent.getInvalidSexEvents());;
					break;
				case OTHER_PLAYER_INVALID_FIGHT_ENTRY_TOURNAMENT_STARTED:
					OtherPlayerInvalidFightEntryTournamentStartedEvent tournamentStartedEvent = (OtherPlayerInvalidFightEntryTournamentStartedEvent) event;
					this.sendAllEventsToEventRouter(tournamentStartedEvent.getEvents());
					break;
				case PRESTIGE_ASCENSION:
					this.prestigeAscensionEventAnnotator.annotateEvent((PrestigeAscensionEvent) event);
					this.eventRouter.sendDataToQueues(event);
					break;
				case SKILL_WIN: case RISER_SKILL_WIN:
					SkillWinEvent skillWinEvent = (SkillWinEvent) event;
					this.skillWinEventAnnotator.annotateEvent(skillWinEvent);
					break;
				case BUY_SKILL:
					BuySkillEvent buySkillEvent = (BuySkillEvent) event;
					this.buySkillEventAnnotator.annotateEvent(buySkillEvent);
					break;
				case BUY_SKILL_RANDOM:
					BuySkillRandomEvent buySkillRandomevent = (BuySkillRandomEvent) event;
					this.buySkillRandomEventAnnotator.annotateEvent(buySkillRandomevent);
					break;
				case GIFT_SKILL:
					GiftSkillEvent giftSkillEvent = (GiftSkillEvent) event;
					this.giftSkillEventAnnotator.annotateEvent(giftSkillEvent);
					break;
				case SKILL_DROP:
					this.skillDropEventAnnotator.annotateEvent((SkillDropEvent) event);
					this.eventRouter.sendDataToQueues(event);
					break;
				case MUSIC:
					MusicEvent musicEvent = (MusicEvent) event;
					this.musicService.addOccurence(musicEvent);
					this.eventRouter.sendDataToQueues(musicEvent);
					break;
				case HYPE:
					HypeEvent hypeEvent = (HypeEvent) event;
					this.eventRouter.sendDataToQueues(hypeEvent);
					break;
				case BETTING_ENDS:
					this.handleBettingEndsEvent(event);
					break;
				case BETTING_BEGINS:
					this.handleBettingBeginsEvent(event);
					break;
				case TEAM_INFO:
					TeamInfoEvent teamInfoEvent = (TeamInfoEvent) event;
					this.teamInfoEventAnnotator.setCurrentTournament(currentTournament);
					this.teamInfoEventAnnotator.annotateEvent(teamInfoEvent);
					break;
				case UNIT_INFO:
					UnitInfoEvent unitEvent = (UnitInfoEvent) event;
					this.unitInfoEventAnnotator.setCurrentTournament(currentTournament);
					this.unitInfoEventAnnotator.annotateEvent(unitEvent);
					break;
				default:
					if(event != null) {
						this.eventRouter.sendDataToQueues(event);
					}
					break;
				}
			}
		} catch(DumpException e) {
			log.error("Error found in Event Parser", e);
			this.errorWebhookManager.sendShutdownNotice(e, "Critical error in Event Parser thread");
		} catch(MissingEventTypeException e) {
			throw e;
		} catch(Exception e) {
			log.error("Error found in Event Parser", e);
			this.errorWebhookManager.sendShutdownNotice(e, "Critical error in Event Parser thread");
		}
	}
	
	protected void logEvent(BattleGroundEvent event, ChatMessage message) throws MissingEventTypeException {
		if(this.logEvents && event != null) {
			if(event.getEventType() == null) {
				MissingEventTypeException exception = new MissingEventTypeException("missing event type for class " + event.getClass().toString());
				throw exception;
			}
			
			log.info("Found event: {} with data: {} from chatMessage {}", event.getEventType().getEventStringName(), event.toString(), message);
		}
	}
	
	protected void handleBettingEndsEvent(BattleGroundEvent event) throws DumpException {
		this.eventRouter.sendDataToQueues(event);
		
		if(this.currentTournament != null) {
			List<BattleGroundEvent> finalBets = this.tournamentService.getRealBetInfoFromLatestPotFile(this.currentTournament.getID());
			log.info("Sending final bet data with {} entries", finalBets.size());
			log.info("The final bet event data: {}", finalBets);
			for(BattleGroundEvent betInfoEvent: finalBets) {
				try {
					this.betInfoEventAnnotator.annotateEvent((BetInfoEvent) betInfoEvent);
				} catch(BattleGroundException e) {
					log.error("Error parsing bet {}", e, betInfoEvent);
				}
			}
			this.eventRouter.sendAllDataToQueues(finalBets);
		}
	}
	
	protected void handleBettingBeginsEvent(BattleGroundEvent event) throws DumpException, TournamentApiException {
		BettingBeginsEvent bettingBeginsEvent = (BettingBeginsEvent) event;
		this.eventRouter.sendDataToQueues(bettingBeginsEvent);
		if( (bettingBeginsEvent.getTeam1() == BattleGroundTeam.RED && bettingBeginsEvent.getTeam2() == BattleGroundTeam.BLUE)
			|| (bettingBeginsEvent.getTeam2() == BattleGroundTeam.RED && bettingBeginsEvent.getTeam1() == BattleGroundTeam.BLUE)
			|| (currentTournament == null) 
			|| (currentTournament.getWinnersCount() == null)
			|| (currentTournament.getWinnersCount() >= 7) ) {
			this.currentTournament = this.tournamentService.createNewCurrentTournament();
			this.startEventUpdate();
		} else {
			if(currentTournament.getWinnersCount() < 7) {
				this.currentTournament.setWinnersCount(currentTournament.getWinnersCount() + 1);
			}
		}
		if(this.currentTournament == null) {
			this.currentTournament = this.tournamentService.createNewCurrentTournament();
		}
		if(this.currentTournament != null) {
			List<BattleGroundEvent> tournamentRelatedEvents = this.currentTournament.getEventsFromTournament(bettingBeginsEvent.getTeam1(), bettingBeginsEvent.getTeam2());
			this.handleTournamentEvents(tournamentRelatedEvents);
		} else {
			log.error("Contacting the tournament Service has failed!");
		}
		
		this.createAndSendTournamentTracker(bettingBeginsEvent, currentTournament);		
	}
	
	@SneakyThrows
	protected void handleTournamentEvents(List<BattleGroundEvent> tournamentRelatedEvents) {
		boolean matchInfoFound = false;
		for(long i = 0; i < tournamentRelatedEvents.size(); i++) {
			BattleGroundEvent battleGroundEvent = tournamentRelatedEvents.get((int) i);
			if(battleGroundEvent != null) {
				switch(battleGroundEvent.getEventType()) {
				case TEAM_INFO:
					TeamInfoEvent teamInfoEvent = (TeamInfoEvent) battleGroundEvent;
					this.teamInfoEventAnnotator.setCurrentTournament(currentTournament);
					this.teamInfoEventAnnotator.annotateEvent(teamInfoEvent);
					break;
				case MATCH_INFO:
					MatchInfoEvent matchEvent = (MatchInfoEvent) battleGroundEvent;
					if(matchEvent != null && matchEvent.getMapName() != null && matchEvent.getMapNumber() != null) {
						if(previousMatchEvent == null) {
							this.previousMatchEvent = matchEvent;
							matchInfoFound = true;
						} else if(StringUtils.equals(matchEvent.getMapName(), this.previousMatchEvent.getMapName())) {
							matchInfoFound = false;
						} else {
							this.previousMatchEvent = matchEvent;
							matchInfoFound = true;
						}
					}
					break;
				case UNIT_INFO:
					UnitInfoEvent unitEvent = (UnitInfoEvent) battleGroundEvent;
					this.unitInfoEventAnnotator.setCurrentTournament(currentTournament);
					this.unitInfoEventAnnotator.annotateEvent(unitEvent);
					break;
				default:
					break;
					
				}
				
				this.eventRouter.sendDataToQueues(battleGroundEvent);
			}
		}
		
		if(!matchInfoFound) {
			this.sendScheduledMessage("!match ", 5*1000L);
		}
	}
	
	protected void sendScheduledMessage(String message, Long waitTime) {
		this.eventTimer.submit(new MessageSenderTask(this.messageSenderRouter, message));
	}
	
	protected void startEventUpdate() {
		List<DumpTournamentScheduledTask> tournamentTasks = this.dumpScheduledTasks.tournamentTasks();
		tournamentTasks.forEach(task -> this.eventTimer.submit(task));
	}
	
	protected void sendEventToDetectorAudit(BattleGroundEvent event) {
		//this.eventTimer.submit(() -> this.detectorAuditManager.addEvent(event.getEventType()));
	}
	
	protected void createAndSendTournamentTracker(BettingBeginsEvent bettingBeginsEvent, Tournament currentTournament) {
		EventParser eventParser = this;
		this.tournamentTrackerTimer.submit(new Runnable() {;
			@Override
			public void run() {
				TournamentStatusUpdateEvent tournamentStatusEvent;
				try {
					tournamentStatusEvent = eventParser.tournamentTracker.generateTournamentStatus(bettingBeginsEvent, eventParser.getCurrentTournament());
					eventParser.eventRouter.sendDataToQueues(tournamentStatusEvent);
				} catch (DumpException e) {
					log.error("Error generating tournament tracker", e);
					eventParser.errorWebhookManager.sendException(e);
				} catch (Throwable t) {
					//this executor is single-threaded: an uncaught throwable would otherwise be
					//swallowed into the uninspected Future and all later tracker tasks queue forever
					log.error("Error generating tournament tracker", t);
					eventParser.errorWebhookManager.sendException(t, "uncaught error generating tournament tracker");
				}
			}
		});
	}
	
	protected <T extends BattleGroundEvent> void sendAllEventsToEventRouter(Collection<T> events) {
		if(events != null) {
			for(T event : events) {
				this.eventRouter.sendDataToQueues(event);
			}
		}
	}
	
}
