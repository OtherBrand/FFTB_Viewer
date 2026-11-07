package fft_battleground.repo;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.BlockingQueue;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import fft_battleground.botland.bot.model.BetResults;
import fft_battleground.botland.bot.util.BetterBetBot;
import fft_battleground.discord.WebhookManager;
import fft_battleground.dump.cache.DumpCacheManager;
import fft_battleground.dump.cache.map.AllegianceCache;
import fft_battleground.dump.cache.map.PrestigeSkillsCache;
import fft_battleground.dump.cache.map.UserSkillsCache;
import fft_battleground.event.BattleGroundEventBackPropagation;
import fft_battleground.event.detector.model.AllegianceEvent;
import fft_battleground.event.detector.model.BalanceEvent;
import fft_battleground.event.detector.model.BonusEvent;
import fft_battleground.event.detector.model.BuySkillEvent;
import fft_battleground.event.detector.model.BuySkillRandomEvent;
import fft_battleground.event.detector.model.ExpEvent;
import fft_battleground.event.detector.model.FightEntryEvent;
import fft_battleground.event.detector.model.GiftSkillEvent;
import fft_battleground.event.detector.model.LastActiveEvent;
import fft_battleground.event.detector.model.LevelUpEvent;
import fft_battleground.event.detector.model.PlayerSkillEvent;
import fft_battleground.event.detector.model.PortraitEvent;
import fft_battleground.event.detector.model.PrestigeAscensionEvent;
import fft_battleground.event.detector.model.PrestigeSkillsEvent;
import fft_battleground.event.detector.model.SkillWinEvent;
import fft_battleground.event.detector.model.SnubEvent;
import fft_battleground.event.detector.model.composite.OtherPlayerBalanceEvent;
import fft_battleground.event.detector.model.composite.OtherPlayerExpEvent;
import fft_battleground.event.detector.model.composite.OtherPlayerSnubEvent;
import fft_battleground.event.detector.model.fake.ClassBonusEvent;
import fft_battleground.event.detector.model.fake.GlobalGilHistoryUpdateEvent;
import fft_battleground.event.detector.model.fake.SkillBonusEvent;
import fft_battleground.event.model.BattleGroundEvent;
import fft_battleground.event.model.DatabaseResultsData;
import fft_battleground.event.model.PlayerSkillRefresh;
import fft_battleground.exception.AscensionException;
import fft_battleground.exception.BattleGroundDataIntegrityViolationException;
import fft_battleground.exception.IncorrectTypeException;
import fft_battleground.model.BattleGroundTeam;
import fft_battleground.repo.dao.RepoTransactionManager;
import fft_battleground.repo.util.BalanceUpdateSource;
import fft_battleground.util.GambleUtil;

import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;

@Service
@Slf4j
public class RepoManager extends Thread {

	@Autowired
	private RepoTransactionManager repoTransactionManager;

	@Autowired
	private fft_battleground.elo.EloService eloService;

	@Autowired
	private fft_battleground.elo.FighterEloService fighterEloService;

	@Autowired
	private fft_battleground.metrics.PipelineErrorReporter pipelineErrorReporter;

	@Autowired
	private fft_battleground.metrics.StatusService statusService;

	@Autowired
	private fft_battleground.tournament.TournamentService tournamentService;
	
	@Autowired
	private BlockingQueue<DatabaseResultsData> betResultsQueue;
	
	@Autowired
	private BattleGroundEventBackPropagation battleGroundEventBackPropagation;
	
	@Autowired
	private DumpCacheManager dumpCacheManager;
	
	@Autowired
	private WebhookManager errorWebhookManager;
	
	@Autowired
	private WebhookManager ascensionWebhookManager;
	
	@Autowired
	private AllegianceCache allegianceCache;
	
	@Autowired
	private UserSkillsCache userSkillsCache;
	
	@Autowired
	private PrestigeSkillsCache prestigeSkillsCache;
	
	public RepoManager() {
		this.setName("RepoManagerThread");
	}
	
	public RepoTransactionManager getRepoTransactionManager() {
		return this.repoTransactionManager;
	}
	
	@Override
	public void run() {
		while(true) {
			try {
				DatabaseResultsData newResults = this.betResultsQueue.take();
				if(newResults instanceof BetResults) {
					this.handleBetResult((BetResults) newResults);
				} else if(newResults instanceof OtherPlayerBalanceEvent) {
					this.handleOtherPlayerBalanceEvent((OtherPlayerBalanceEvent) newResults);
				} else if(newResults instanceof LevelUpEvent) {
					this.handleLevelUpEvent((LevelUpEvent) newResults);
				} else if(newResults instanceof OtherPlayerExpEvent) {
					this.handleOtherPlayerExpEvent((OtherPlayerExpEvent) newResults);
				} else if(newResults instanceof AllegianceEvent) {
					this.handleAllegianceEvent((AllegianceEvent) newResults);
				} else if(newResults instanceof PrestigeSkillsEvent) {
					this.handlePrestigeSkillsEvent((PrestigeSkillsEvent) newResults);
				} else if(newResults instanceof PlayerSkillEvent) {
					this.handlePlayerSkillEvent((PlayerSkillEvent) newResults);
				} else if(newResults instanceof PlayerSkillRefresh) {
					this.handlePlayerSkillRefresh((PlayerSkillRefresh) newResults);
				} else if(newResults instanceof BuySkillEvent) {
					this.handleBuySkillEvent((BuySkillEvent) newResults);
				} else if(newResults instanceof SkillWinEvent) {
					this.handleSkillWinEvent((SkillWinEvent) newResults);
				} else if(newResults instanceof PortraitEvent) {
					this.handlePortraitEvent((PortraitEvent) newResults);
				} else if(newResults instanceof LastActiveEvent) {
					this.handleLastActiveSkillEvent((LastActiveEvent) newResults);
				} else if(newResults instanceof GiftSkillEvent) {
					this.handleGiftSkillEvent((GiftSkillEvent) newResults);
				} else if(newResults instanceof GlobalGilHistoryUpdateEvent) {
					this.handleGlobalGilHistoryUpdateEvent((GlobalGilHistoryUpdateEvent) newResults);
				} else if(newResults instanceof PrestigeAscensionEvent) {
					this.handlePrestigeAscensionEvent((PrestigeAscensionEvent) newResults);
				} else if(newResults instanceof FightEntryEvent) {
					this.handleFightEntryEvent((FightEntryEvent)newResults);
				} else if(newResults instanceof BuySkillRandomEvent) {
					this.handleBuySkillRandomEvent((BuySkillRandomEvent) newResults);
				} else if(newResults instanceof ClassBonusEvent) {
					this.handleClassBonusEvent((ClassBonusEvent) newResults);
				} else if(newResults instanceof SkillBonusEvent) {
					this.handleSkillBonusEvent((SkillBonusEvent) newResults);
				} else if(newResults instanceof SnubEvent) {
					this.handleSnubEvent((SnubEvent) newResults);
				} else if(newResults instanceof OtherPlayerSnubEvent) {
					this.handleOtherPlayerSnubEvent((OtherPlayerSnubEvent) newResults);
				} else if(newResults instanceof BonusEvent) {
					this.handleBonusEvent((BonusEvent) newResults);
				}
			} catch (InterruptedException e) {
				log.error("error in RepoManager", e);
			} catch(IncorrectTypeException e) {
				log.error("A value in the repo was incorrectly typed with error: {}", e.getMessage(), e);
				errorWebhookManager.sendException(e);
			} catch(BattleGroundDataIntegrityViolationException bgdive) {
				log.warn(bgdive.getMessage(), bgdive);
				errorWebhookManager.sendException(bgdive, bgdive.getMessage());
			} catch(DataIntegrityViolationException e) {
				String errorMessage = "A soft deleted value was not properly updated, and is still soft deleted despite attempting to undelete it";
				log.error(errorMessage, e);
				errorWebhookManager.sendException(e, errorMessage);;
			} catch(AscensionException ae) { 
				log.error(ae.getMessage(), ae);
				this.errorWebhookManager.sendException(ae);
			} catch(Exception e) {
				log.error("error in RepoManager", e);
			} 
		}
	}

	protected void handleBetResult(BetResults newResults) {
		log.info("found new bet results: {}", newResults.betResultsInfo());
		log.info("The bot thinks that: {} won.", BattleGroundTeam.getTeamName(newResults.getWinningTeam()));
		log.info("The two teams were: {} and {}", newResults.getLeftTeam(), newResults.getRightTeam());
		
		assert(newResults.getTeamData().getLeftTeamData() != null);
		assert(newResults.getTeamData().getRightTeamData() != null);
		
		try {
			//update player data
			this.updatePlayerData(newResults);

			//update bot data
			this.updateBotData(newResults);
		} catch( NullPointerException e) {
			log.error("There was an error updating player data due to a NullPointException, skipping match data update", e);
		}

		//bet ELO: stake-agnostic per-match updates; isolated so ELO can never break the results path
		try {
			this.updateBettorElo(newResults);
			if(this.statusService != null) {
				this.statusService.markEloGraded();
			}
		} catch(Exception e) {
			log.error("error updating bettor ELO, skipping", e);
			if(this.pipelineErrorReporter != null) {
				this.pipelineErrorReporter.reportPipelineFailure("bettor-elo-grading", "match update failed", e);
			}
		}

		//fighter ELO: same trigger, rosters from team metadata; same isolation
		try {
			this.fighterEloService.updateForMatch(newResults, this.currentTournamentId());
		} catch(Exception e) {
			log.error("error updating fighter ELO, skipping", e);
			if(this.pipelineErrorReporter != null) {
				this.pipelineErrorReporter.reportPipelineFailure("fighter-elo-grading", "match update failed", e);
			}
		}
	}

	protected Long currentTournamentId() {
		try {
			return this.tournamentService.getCurrentTournament() != null ? this.tournamentService.getCurrentTournament().getID() : null;
		} catch(Exception e) {
			return null;
		}
	}

	protected void updateBettorElo(BetResults newResults) {
		//advisor v2: p_advisor loads the oracle's logged verdict for this tournament
		//(pot-implied fallback when none exists); tournament id from the live aggregate
		this.eloService.updateForMatch(newResults, this.currentTournamentId());
	}
	
	protected void handlePortraitEvent(PortraitEvent event) throws IncorrectTypeException, BattleGroundDataIntegrityViolationException {
		this.repoTransactionManager.updatePlayerPortrait(event);
	}
	
	protected void handleSkillWinEvent(SkillWinEvent event) throws BattleGroundDataIntegrityViolationException {
		for(PlayerSkillEvent skillEvent: event.getSkillEvents()) {
			this.repoTransactionManager.updatePlayerSkills(skillEvent);
		}
	}
	
	protected void handleOtherPlayerBalanceEvent(OtherPlayerBalanceEvent event) throws BattleGroundDataIntegrityViolationException {
		if(event != null && event.getOtherPlayerBalanceEvents() != null) {
			for(BalanceEvent balanceEvent : event.getOtherPlayerBalanceEvents()) {
				this.repoTransactionManager.updatePlayerAmount(balanceEvent);
				this.repoTransactionManager.addEntryToBalanceHistory(balanceEvent);
				this.dumpCacheManager.updateBalanceCache(balanceEvent);
			}
		}
	}
	
	protected void handleLevelUpEvent(LevelUpEvent event) throws BattleGroundDataIntegrityViolationException {
		this.repoTransactionManager.updatePlayerLevel(event);
		if(event.getSkill() != null && event.getSkill().getSkills().size() > 0) {
			this.repoTransactionManager.updatePlayerSkills(event.getSkill());
		}
	}
	
	protected void handleOtherPlayerExpEvent(OtherPlayerExpEvent event) throws BattleGroundDataIntegrityViolationException {
		for(ExpEvent expEvent : event.getExpEvents()) {
			this.repoTransactionManager.updatePlayerLevel((LevelUpEvent) expEvent);
		}
	}
	
	protected void handleAllegianceEvent(AllegianceEvent event) throws BattleGroundDataIntegrityViolationException {
		this.repoTransactionManager.updatePlayerAllegiance(event);
		String player = GambleUtil.cleanString(event.getPlayer());
		this.allegianceCache.put(player, event.getTeam());
	}
	
	protected void handlePrestigeSkillsEvent(PrestigeSkillsEvent event) throws BattleGroundDataIntegrityViolationException {
		this.repoTransactionManager.updatePrestigeSkills(event);
	}
	
	protected void handlePlayerSkillEvent(PlayerSkillEvent event) throws BattleGroundDataIntegrityViolationException {
		this.repoTransactionManager.updatePlayerSkills(event);
	}
	
	protected void handlePlayerSkillRefresh(PlayerSkillRefresh event) {
		try {
			this.repoTransactionManager.clearPlayerSkillsForPlayer(event.getPlayer());
			this.repoTransactionManager.updatePlayerSkills(event.getPlayerSkillEvent());
			if(event.getPrestigeSkillEvent() != null) {
				this.repoTransactionManager.updatePrestigeSkills(event.getPrestigeSkillEvent());
			}
		} catch(Exception e) {
			log.error("Error processing Ascension refresh for player {}", event.getPlayer());
			this.errorWebhookManager.sendException(e, "error processing player skill refresh");
		}
	}
	
	protected void handleGlobalGilHistoryUpdateEvent(GlobalGilHistoryUpdateEvent newResults) {
		this.repoTransactionManager.updateGlobalGilHistory(newResults.getGlobalGilHistory());
	}
	
	protected void handlePrestigeAscensionEvent(final PrestigeAscensionEvent event) throws AscensionException {
		String id = GambleUtil.cleanString(event.getPrestigeSkillsEvent().getPlayer());
		if(event.getCurrentBalance() != null) {
			this.repoTransactionManager.generateSimulatedBalanceEvent(event.getPrestigeSkillsEvent().getPlayer(), (-1) * event.getCurrentBalance(), BalanceUpdateSource.PRESTIGE);
		}
		
		//use Timer to force update player skill.  May delay events behind this propagation
		try {
			this.handlePrestigeSkillsEvent(event.getPrestigeSkillsEvent());
			int prestigeBefore = this.prestigeSkillsCache.get(id) != null ? this.prestigeSkillsCache.get(id).size() : 0;
			this.ascensionWebhookManager.sendAscensionMessage(id, prestigeBefore, prestigeBefore + 1);
			List<String> userSkills = new ArrayList<>();
			Set<String> prestigeSkills = new HashSet<>(this.prestigeSkillsCache.get(id));
			if(prestigeSkills != null && event.getPrestigeSkillsEvent() != null && event.getPrestigeSkillsEvent().getSkills() != null && event.getPrestigeSkillsEvent().getSkills().size() > 0) {
				prestigeSkills.add(event.getPrestigeSkillsEvent().getSkills().get(0));
			}
			
			List<String> uniquePrestigeSkillList = new ArrayList<>(prestigeSkills);
			
			this.userSkillsCache.put(id, userSkills);
			this.prestigeSkillsCache.put(id, uniquePrestigeSkillList);
			
			PlayerSkillRefresh refresh = new PlayerSkillRefresh(id, userSkills, uniquePrestigeSkillList, event);
			this.handlePlayerSkillRefresh(refresh);
		} catch (Exception e) {
			throw new AscensionException(e, id);
		}
	}
	
	@SneakyThrows
	protected void handleBuySkillEvent(BuySkillEvent event) {
		for(PlayerSkillEvent playerSkillEvent : event.getSkillEvents()) {
			this.handlePlayerSkillEvent(playerSkillEvent);
			BattleGroundEvent balanceUpdate = this.repoTransactionManager.generateSimulatedBalanceEvent(playerSkillEvent.getPlayer(), BuySkillEvent.skillBuyBalanceUpdate, BalanceUpdateSource.BUYSKILL);
			if(balanceUpdate != null) {
				this.battleGroundEventBackPropagation.SendUnitThroughTimer(balanceUpdate);
			}
		}
	}
	
	@SneakyThrows
	private void handleBuySkillRandomEvent(BuySkillRandomEvent event) {
		this.handlePlayerSkillEvent(event.getSkillEvent());
		BattleGroundEvent balanceUpdate = this.repoTransactionManager.generateSimulatedBalanceEvent(event.getPlayer(), event.getSkillBuyBalanceUpdate(), BalanceUpdateSource.BUYSKILL);
		if(balanceUpdate != null) {
			this.battleGroundEventBackPropagation.SendUnitThroughTimer(balanceUpdate);
		}
	}
	
	protected void handleLastActiveSkillEvent(LastActiveEvent event) throws BattleGroundDataIntegrityViolationException {
		this.repoTransactionManager.updatePlayerLastActive(event);
	}
	
	private void handleFightEntryEvent(FightEntryEvent newResults) throws BattleGroundDataIntegrityViolationException {
		this.repoTransactionManager.updateLastFightActive(newResults);
	}
	
	private void handleGiftSkillEvent(GiftSkillEvent newResults) {
		for(int i = 0; i < newResults.getGiftSkills().size(); i++) {
			try {
				this.repoTransactionManager.updatePlayerSkills(newResults.getGiftSkills().get(i).getPlayerSkillEvent());
				this.repoTransactionManager.generateSimulatedBalanceEvent(newResults.getGiftSkills().get(i).getGivingPlayer(), newResults.getCost(), BalanceUpdateSource.GIFTSKILL);
			} catch(BattleGroundDataIntegrityViolationException bgdive) {
				log.warn(bgdive.getMessage(), bgdive);
				errorWebhookManager.sendException(bgdive, bgdive.getMessage());
			}
		}
	}
	
	private void handleClassBonusEvent(ClassBonusEvent newResults) throws BattleGroundDataIntegrityViolationException {
		this.repoTransactionManager.updateClassBonus(newResults);
	}
	
	private void handleSkillBonusEvent(SkillBonusEvent newResults) throws BattleGroundDataIntegrityViolationException {
		this.repoTransactionManager.updateSkillBonus(newResults);
	}
	
	private void handleSnubEvent(SnubEvent newResults) throws BattleGroundDataIntegrityViolationException {
		this.repoTransactionManager.updateSnub(newResults);
	}
	
	private void handleOtherPlayerSnubEvent(OtherPlayerSnubEvent newResults) {
		for(SnubEvent event: newResults.getSnubEvents()) {
			try {
				this.repoTransactionManager.updateSnub(event);
			} catch(BattleGroundDataIntegrityViolationException bgdive) {
				log.warn(bgdive.getMessage(), bgdive);
				errorWebhookManager.sendException(bgdive, bgdive.getMessage());
			}
		}
	}
	
	private void handleBonusEvent(BonusEvent event) throws BattleGroundDataIntegrityViolationException {
		this.handleClassBonusEvent(event.getClassBonusEvent());
		this.handleSkillBonusEvent(event.getSkillBonusEvent());
	}

	
	protected void updatePlayerData(BetResults newResults) {
		
		Float leftTeamOdds = GambleUtil.bettingOdds(newResults.getFullBettingData().getTeam1Amount(), newResults.getFullBettingData().getTeam2Amount());
		Float rightTeamOdds = GambleUtil.bettingOdds(newResults.getFullBettingData().getTeam2Amount(), newResults.getFullBettingData().getTeam1Amount());
		//store player records first
		if(newResults.getWinningTeam() == newResults.getLeftTeam()) {
			this.repoTransactionManager.reportAsWin(newResults.getBets().getLeft(), leftTeamOdds);
		} else if(newResults.getWinningTeam() == newResults.getRightTeam()){
			this.repoTransactionManager.reportAsLoss(newResults.getBets().getLeft(), leftTeamOdds);
		}
		
		if(newResults.getWinningTeam() == newResults.getLeftTeam()) {
			this.repoTransactionManager.reportAsLoss(newResults.getBets().getRight(), rightTeamOdds);
		} else if(newResults.getWinningTeam() == newResults.getRightTeam()){
			this.repoTransactionManager.reportAsWin(newResults.getBets().getRight(), rightTeamOdds);
		}
		
		//now update the fight stats for the players
		if(newResults.getWinningTeam() == newResults.getLeftTeam()) {
			this.repoTransactionManager.reportAsFightWin(newResults.getTeamData().getLeftTeamData());
		} else if(newResults.getWinningTeam() == newResults.getRightTeam()){
			this.repoTransactionManager.reportAsFightLoss(newResults.getTeamData().getLeftTeamData());
			
		}
		
		if(newResults.getWinningTeam() == newResults.getLeftTeam()) {
			this.repoTransactionManager.reportAsFightLoss(newResults.getTeamData().getRightTeamData());
		} else if(newResults.getWinningTeam() == newResults.getRightTeam()){
			this.repoTransactionManager.reportAsFightWin(newResults.getTeamData().getRightTeamData());
		}
	}
	
	protected void updateBotData(BetResults newResults) {
		Float leftTeamOdds = GambleUtil.bettingOdds(newResults.getFullBettingData().getTeam1Amount(), newResults.getFullBettingData().getTeam2Amount());
		Float rightTeamOdds = GambleUtil.bettingOdds(newResults.getFullBettingData().getTeam2Amount(), newResults.getFullBettingData().getTeam1Amount());
		
		for(BetterBetBot bot: newResults.getBotsWithResults()) {
			if(bot.getResult().getTeam() == newResults.getWinningTeam()) {
				Float winningOdds = newResults.getWinningTeam() == newResults.getLeftTeam() ? leftTeamOdds : rightTeamOdds;
				this.repoTransactionManager.reportBotWin(bot, winningOdds);
			} else {
				this.repoTransactionManager.reportBotLoss(bot);
			}
		}
	}

}
