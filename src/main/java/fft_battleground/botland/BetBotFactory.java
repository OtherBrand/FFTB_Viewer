package fft_battleground.botland;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.Timer;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.apache.commons.lang3.StringUtils;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

import fft_battleground.botland.bot.DataBetBot;
import fft_battleground.botland.bot.GeneticBot;
import fft_battleground.botland.bot.GeneticBotV2;
import fft_battleground.botland.bot.GeneticBotV3;
import fft_battleground.botland.bot.ArbitraryBot;
import fft_battleground.botland.bot.BetCountBot;
import fft_battleground.botland.bot.BraveFaithBot;
import fft_battleground.botland.bot.OddsBot;
import fft_battleground.botland.bot.ReferenceBot;
import fft_battleground.botland.bot.TeamValueBot;
import fft_battleground.botland.bot.UnitAwareBot;
import fft_battleground.botland.bot.exception.BotConfigException;
import fft_battleground.botland.bot.genetic.GeneFileCache;
import fft_battleground.botland.bot.genetic.GeneFileV1Cache;
import fft_battleground.botland.bot.genetic.GeneFileV2Cache;
import fft_battleground.botland.bot.genetic.GeneFileV3Cache;
import fft_battleground.botland.bot.genetic.model.GeneTrainerV2BotData;
import fft_battleground.botland.bot.util.BetterBetBot;
import fft_battleground.botland.model.BotData;
import fft_battleground.botland.model.BotNames;
import fft_battleground.botland.personality.PersonalityModule;
import fft_battleground.botland.personality.PersonalityModuleFactory;
import fft_battleground.discord.WebhookManager;
import fft_battleground.dump.cache.map.BalanceCache;
import fft_battleground.dump.cache.set.BotCache;
import fft_battleground.event.BattleGroundEventBackPropagation;
import fft_battleground.event.detector.model.BetEvent;
import fft_battleground.event.detector.model.BettingBeginsEvent;
import fft_battleground.event.detector.model.UnitInfoEvent;
import fft_battleground.model.ChatMessage;
import fft_battleground.repo.model.Bots;
import fft_battleground.repo.repository.BotBetDataRepo;
import fft_battleground.repo.repository.BotsRepo;
import fft_battleground.repo.repository.PlayerRecordRepo;
import fft_battleground.tournament.TournamentService;
import fft_battleground.util.Router;

import lombok.Data;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

@Component
@Data
@Slf4j
public class BetBotFactory {
	private static final String BOT_DATA_CACHE_KEY = "botDataCacheKey";
	
	@Value("${irc.username}")
	private String ircName;
	
	@Value("${botfactory.primaryBotName}")
	private String primaryBotName;
	
	@Value("${botfactory.botIsSubscriber}")
	private Boolean isBotSubscriber;
	
	@Value("${botfactory.botFile}")
	private String botFile;
	
	@Value("${enableBetting}")
	private boolean enableBetting;
	
	@Value("${enablePersonality}")
	private boolean enablePersonality;
	
	@Autowired
	private PersonalityModuleFactory personalityModuleFactory;
	
	@Autowired
	private TournamentService tournamentService;
	
	@Autowired
	private Router<ChatMessage> messageSenderRouter;
	
	@Autowired
	private PlayerRecordRepo playerRecordRepo;
	
	@Autowired
	private BotsRepo botsRepo;
	
	@Autowired
	private BotBetDataRepo botBetDataRepo;
	
	@Autowired
	private BattleGroundEventBackPropagation battleGroundEventBackPropagation;
	
	@Autowired
	private WebhookManager errorWebhookManager;
	
	@Autowired
	private WebhookManager noisyWebhookManager;
	
	@Autowired
	private BalanceCache balanceCache;
	
	@Autowired
	private BotCache botCache;
	
	@Autowired
	@Getter private GeneFileV1Cache geneFileCache;
	
	@Autowired
	@Getter private GeneFileCache<GeneTrainerV2BotData> geneFileV2Cache;

	@Getter private GeneFileCache<GeneTrainerV2BotData> geneFileV3Cache;
	
	private Timer botlandTimer = new Timer();
	private Cache<String, Map<String, BotData>> botDataCache;
	
	public BetBotFactory(@Value("${botlandCacheDuration}") long botlandCacheDuration) {
		this.botDataCache = Caffeine.newBuilder()
				  .expireAfterWrite(botlandCacheDuration, TimeUnit.MINUTES)
				  .maximumSize(1)
				  .build();
		this.geneFileCache = new GeneFileV1Cache();
		this.geneFileV2Cache = new GeneFileV2Cache();
		this.geneFileV3Cache = new GeneFileV3Cache();
	}
	
	public BotLand createBotLand(Integer currentAmountToBetWith, List<BetEvent> otherPlayerBets, Set<UnitInfoEvent> currentUnits, BettingBeginsEvent beginEvent) {
		BotLand land = new BotLand(currentAmountToBetWith, beginEvent, otherPlayerBets, currentUnits, this.tournamentService.getCurrentTournament());
		
		land.setChatMessageRouterRef(this.getMessageSenderRouter());
		land.setBotsRepo(this.botsRepo);
		land.setBotBetDataRepo(this.botBetDataRepo);
		land.setBattleGroundEventBackPropagationRef(this.battleGroundEventBackPropagation);
		land.setIrcName(this.ircName);
		land.setEnableBetting(this.enableBetting);
		land.setEnablePersonality(this.enablePersonality);
		land.setPersonalityModuleFactoryRef(this.personalityModuleFactory);
		land.setBotlandTimerRef(this.botlandTimer);
		land.setErrorWebhookManager(this.errorWebhookManager);
		land.setNoisyWebhookManager(this.noisyWebhookManager);
		land.setBalanceCacheRef(this.balanceCache.getMap());
		
		this.attachBots(land, currentAmountToBetWith, otherPlayerBets, beginEvent);
		
		return land;
	}
	
	public synchronized Map<String, BotData> getBotDataMap() {
		Map<String, BotData> botData = this.botDataCache.getIfPresent(BOT_DATA_CACHE_KEY);
		if(botData == null) {
			botData = this.getBotDataMapFromXmlFile();
			this.botDataCache.put(BOT_DATA_CACHE_KEY, botData);
		}
		
		return botData;
	}
	
	private void attachBots(BotLand botland, Integer currentAmountToBetWith, List<BetEvent> otherPlayerBets, BettingBeginsEvent beginEvent) {
		List<BotData> botData = this.getBotConfig();
		String botDateString = this.botsRepo.currentDateString();
		List<BetterBetBot> secondaryBots = this.createSecondaryBots(otherPlayerBets, beginEvent, botData, botDateString);
		
		Optional<BotData> maybePrimaryBotData = botData.stream().filter(botDataEntry -> StringUtils.equalsIgnoreCase(botDataEntry.getName(), this.primaryBotName)).findFirst();
		BetterBetBot primaryBot = null;
		if(!maybePrimaryBotData.isPresent() ) {
			log.error("No primary bot configuration found!");
		} else {
			try {
				primaryBot = this.createBot(otherPlayerBets, beginEvent, maybePrimaryBotData.get(), botDateString);
			} catch (BotConfigException e) {
				log.error("primary bot was unabled to be created, please fix!", e);
			}
			log.info("primary bot is {}", primaryBot.getName());
		}
		
		botland.setPrimaryBot(primaryBot);
		botland.setSubordinateBots(secondaryBots);
		
		return;
	}
	
	private Map<String, BotData> getBotDataMapFromXmlFile() {
		Map<String, BotData> botDataMap = this.getBotConfig().stream().collect(Collectors.toMap(BotData::getName, Function.identity()));
		return botDataMap;
	}
	
	private List<BotData> getBotConfig() {
		SecondaryBotConfig botConfig = new SecondaryBotConfig(this.botFile);
		List<BotData> botData = botConfig.parseXmlFile();
		
		return botData;
	}
	
	
	private List<BetterBetBot> createSecondaryBots(List<BetEvent> otherPlayerBets, BettingBeginsEvent beginEvent, List<BotData> botData, String botDateString) {
		List<BetterBetBot> secondaryBots = new ArrayList<>();
		for(BotData data: botData) {
			BetterBetBot newBot;
			try {
				newBot = this.createBot(otherPlayerBets, beginEvent, data, botDateString);
				secondaryBots.add(newBot);
			} catch (BotConfigException e) {} //only add bot if the exception is not thrown
			
		}
		return secondaryBots;
	}
	
	private BetterBetBot createBot(List<BetEvent> otherPlayerBets, BettingBeginsEvent beginEvent, BotData botData, String botDateString) throws BotConfigException {
		Bots botDataFromDatabase = this.botsRepo.getBotByDateStringAndName(botDateString, botData.getName());
		if(botDataFromDatabase == null) {
			botDataFromDatabase = this.botsRepo.addNewBotForToday(botData.getName(), this.isBotSubscriber);
			log.info("creating new bot data entry for: {} with dateString {}", botDataFromDatabase.getPlayer(), botDataFromDatabase.getDateString());
		}
		
		Integer currentAmountToBetWith = botDataFromDatabase.getBalance();
		
		BetterBetBot betBot = this.findBestFitBot(currentAmountToBetWith, beginEvent, botData);
		
		if(betBot != null) {
			try {
				betBot.initParams(botData.getParams());
			} catch (BotConfigException e1) {
				log.error("exception initializing bot {}", botData.getName(), e1);
				throw e1;
			}
			betBot.setName(botData.getName());
			betBot.setPlayerRecordRepoRef(this.playerRecordRepo);
			betBot.setDateFormat(botDateString);
			betBot.setBotSubscriber(this.isBotSubscriber);
			if(betBot.getPersonalityName() != null) {
				PersonalityModule module;
				try {
					module = this.personalityModuleFactory.getPersonalityModuleByName(betBot.getPersonalityName());
					betBot.setPersonalityModule(module);
				} catch (BotConfigException e) {}
			}
		}
		
		return betBot;
		
	}
	
	private BetterBetBot findBestFitBot(Integer currentAmountToBetWith, BettingBeginsEvent beginEvent, BotData botData) {
		BetterBetBot betBot = null;
		BotNames currentBot = BotNames.parseBotname(botData.getClassname());
		switch(currentBot) {
		case BETCOUNT:
			betBot = new BetCountBot(currentAmountToBetWith, beginEvent.getTeam1(), beginEvent.getTeam2());
			break;
		case DATA:
			betBot = new DataBetBot(currentAmountToBetWith, beginEvent.getTeam1(), beginEvent.getTeam2());
			break;
		case ODDS:
			betBot = new OddsBot(currentAmountToBetWith, beginEvent.getTeam1(), beginEvent.getTeam2());
			break;
		case ARBITRARY:
			betBot = new ArbitraryBot(currentAmountToBetWith, beginEvent.getTeam1(), beginEvent.getTeam2());
			break;
		case GENE:
			betBot = new GeneticBot(currentAmountToBetWith, beginEvent.getTeam1(), beginEvent.getTeam2(), this.geneFileCache);
			break;
		case GENE_V2:
			betBot = new GeneticBotV2(currentAmountToBetWith, beginEvent.getTeam1(), beginEvent.getTeam2(), this.geneFileV2Cache,
					this.botCache.getSet(), this.noisyWebhookManager);
			break;
		case GENE_V3:
			betBot = new GeneticBotV3(currentAmountToBetWith, beginEvent.getTeam1(), beginEvent.getTeam2(), this.geneFileV3Cache,
					this.botCache.getSet(), this.noisyWebhookManager);
			break;
		case BRAVEFAITH:
			betBot = new BraveFaithBot(currentAmountToBetWith, beginEvent.getTeam1(), beginEvent.getTeam2());
			break;
		case TEAMVALUE:
			betBot = new TeamValueBot(currentAmountToBetWith, beginEvent.getTeam1(), beginEvent.getTeam2());
			break;
		case UNIT:
			betBot = new UnitAwareBot(currentAmountToBetWith, beginEvent.getTeam1(), beginEvent.getTeam2(), this.geneFileCache);
			break;
		case REFERENCE:
			betBot = new ReferenceBot(currentAmountToBetWith, beginEvent.getTeam1(), beginEvent.getTeam2());
			break;
		default:
			log.error("botData with data: {} failed", botData);
			break;
		}
		
		return betBot;
	}

}
