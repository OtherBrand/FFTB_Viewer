package fft_battleground.dump;

import java.sql.Timestamp;
import java.text.DecimalFormat;
import java.text.NumberFormat;
import java.text.SimpleDateFormat;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.function.Function;
import java.util.function.ToIntFunction;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import javax.transaction.Transactional;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.google.common.collect.BiMap;
import com.google.common.collect.HashBiMap;
import com.google.common.math.Quantiles;

import fft_battleground.discord.WebhookManager;
import fft_battleground.dump.model.GlobalGilPageData;
import fft_battleground.dump.model.PrestigeTableEntry;
import fft_battleground.dump.reports.model.AllegianceLeaderboard;
import fft_battleground.dump.reports.model.AllegianceLeaderboardEntry;
import fft_battleground.dump.reports.model.AllegianceLeaderboardWrapper;
import fft_battleground.dump.reports.model.BotLeaderboard;
import fft_battleground.dump.reports.model.ExpLeaderboardEntry;
import fft_battleground.dump.reports.model.LeaderboardBalanceData;
import fft_battleground.dump.reports.model.LeaderboardBalanceHistoryEntry;
import fft_battleground.dump.reports.model.LeaderboardData;
import fft_battleground.dump.reports.model.PlayerLeaderboard;
import fft_battleground.exception.CacheMissException;
import fft_battleground.model.BattleGroundTeam;
import fft_battleground.model.Images;
import fft_battleground.repo.BattleGroundCacheEntryKey;
import fft_battleground.repo.model.BalanceHistory;
import fft_battleground.repo.model.GlobalGilHistory;
import fft_battleground.repo.model.PlayerRecord;
import fft_battleground.repo.model.TeamInfo;
import fft_battleground.repo.repository.BattleGroundCacheEntryRepo;
import fft_battleground.repo.repository.GlobalGilHistoryRepo;
import fft_battleground.repo.repository.MatchRepo;
import fft_battleground.repo.repository.PlayerRecordRepo;
import fft_battleground.repo.repository.PlayerSkillRepo;
import fft_battleground.tournament.ChampionService;

import lombok.Getter;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;

@Service
@Slf4j
public class DumpReportsService {


	private static final int HIGHEST_PLAYERS = 10;
	private static final int TOP_PLAYERS = 100;
	private static final int PERCENTILE_THRESHOLD = 10;

	@Autowired
	private DumpService dumpService;
	
	@Autowired
	private ChampionService championService;

	@Autowired
	private PlayerRecordRepo playerRecordRepo;

	@Autowired
	private GlobalGilHistoryRepo globalGilHistoryRepo;

	@Autowired
	private MatchRepo matchRepo;

	@Autowired
	private PlayerSkillRepo playerSkillRepo;
	
	@Autowired
	private BattleGroundCacheEntryRepo battleGroundCacheEntryRepo;

	@Autowired
	private Images images;
	
	@Autowired
	private WebhookManager errorWebhookManager;

	@Getter private Cache<String, PlayerLeaderboard> leaderboardCache = buildCache(BattleGroundCacheEntryKey.LEADERBOARD);
	@Getter private Cache<String, BotLeaderboard> botLeaderboardCache = buildCache(BattleGroundCacheEntryKey.BOT_LEADERBOARD);
	@Getter private Cache<String, Map<Integer, Double>> betPercentilesCache = buildCache(BattleGroundCacheEntryKey.BET_PERCENTILES);
	@Getter private Cache<String, Map<Integer, Double>> fightPercentilesCache = buildCache(BattleGroundCacheEntryKey.FIGHT_PERCENTILES);
	@Getter private Cache<String, AllegianceLeaderboardWrapper> allegianceLeaderboardCache = buildCache(BattleGroundCacheEntryKey.ALLEGIANCE_LEADERBOARD);
	
	protected static <T> Cache<String, T> buildCache(BattleGroundCacheEntryKey key) {
		Cache<String, T> cache = Caffeine.newBuilder().expireAfterWrite(key.getTimeValue(), key.getTimeUnit()).maximumSize(1).build();
		return cache;
	}
	
	public <T> T readCache(Cache<String, T> cache, String key) {
		T result = null;
		synchronized(cache) {
			result = cache.getIfPresent(key);
		}
		
		return result;
	}
	
	public <T> void writeToCache(Cache<String, T> cache, String key, T value) {
		synchronized(cache) {
			cache.put(key, value);
		}
		
		return;
	}

	public GlobalGilPageData getGlobalGilData() {
		GlobalGilPageData data = null;
		GlobalGilHistory todaysData = this.globalGilHistoryRepo.getFirstGlobalGilHistory();
		List<GlobalGilHistory> historyByDay = this.globalGilHistoryRepo
				.getGlobalGilHistoryByCalendarTimeType(ChronoUnit.DAYS);
		List<GlobalGilHistory> historyByWeek = this.globalGilHistoryRepo
				.getGlobalGilHistoryByCalendarTimeType(ChronoUnit.WEEKS);
		List<GlobalGilHistory> historyByMonth = this.globalGilHistoryRepo
				.getGlobalGilHistoryByCalendarTimeType(ChronoUnit.MONTHS);

		data = new GlobalGilPageData(todaysData, historyByDay, historyByWeek, historyByMonth);

		return data;
	}

	@SneakyThrows
	public Double percentageOfGlobalGil(Integer balance) {
		Double percentage = new Double(0);
		if (balance != null) {
			GlobalGilHistory todaysData = this.globalGilHistoryRepo.getFirstGlobalGilHistory();
			percentage = ((new Double(balance) / new Double(todaysData.getGlobal_gil_count())));
		}
		return percentage;
	}

	public Integer getLeaderboardPosition(String player) {
		String lowercasePlayer = StringUtils.lowerCase(player);
		Integer position = this.dumpService.getLeaderboard().get(lowercasePlayer);
		return position;
	}

	public BotLeaderboard getBotLeaderboard() throws CacheMissException {
		BotLeaderboard botLeaderboard = null;
		botLeaderboard = this.readCache(this.botLeaderboardCache, BattleGroundCacheEntryKey.BOT_LEADERBOARD.getKey());
		if (botLeaderboard == null) {
			throw new CacheMissException(BattleGroundCacheEntryKey.BOT_LEADERBOARD);
		}

		return botLeaderboard;
	}
	
	public BotLeaderboard writeBotLeaderboardToCaches() {
		BotLeaderboard botLeaderboard = null;
		try {
			log.warn("bot leaderboard cache was busted, creating new value");
			botLeaderboard = this.generateBotLeaderboard();
			this.writeToCache(this.botLeaderboardCache, BattleGroundCacheEntryKey.BOT_LEADERBOARD.getKey(), botLeaderboard);
			this.battleGroundCacheEntryRepo.writeCacheEntry(botLeaderboard, BattleGroundCacheEntryKey.BOT_LEADERBOARD.getKey());
		} catch(Exception e) {
			log.error("Error writing to bot cache", e);
			this.errorWebhookManager.sendException(e, "exception generating new bot leaderboard");
		}
		return botLeaderboard;
	}

	protected BotLeaderboard generateBotLeaderboard() {
		BotLeaderboard leaderboard = null;
		Map<String, Integer> botBalances = new TreeMap<String, Integer>(this.dumpService.getBotCache().parallelStream()
				.filter(botName -> this.dumpService.getBalanceCache().containsKey(botName))
				.collect(Collectors.toMap(Function.identity(), bot -> this.dumpService.getBalanceCache().get(bot))));
		leaderboard = new BotLeaderboard(botBalances);
		return leaderboard;
	}

	public Map<String, Integer> getTopPlayers(Integer count) {
		BiMap<String, Integer> topPlayers = HashBiMap.create();
		topPlayers.putAll(this.dumpService.getLeaderboard().keySet().parallelStream()
				.filter(player -> !this.dumpService.getBotCache().contains(player))
				.filter(player -> this.playerRecordRepo.findById(StringUtils.lowerCase(player)).isPresent())
				.filter(player -> {
					Date lastActive = this.playerRecordRepo.findById(StringUtils.lowerCase(player)).get()
							.getLastActive();
					boolean result = lastActive != null && this.isPlayerActiveInLastMonth(lastActive);
					return result;
				}).collect(Collectors.toMap(Function.identity(),
						player -> this.dumpService.getLeaderboard().get(player))));
		Set<Integer> topValues = topPlayers.values().stream().sorted().limit(count).collect(Collectors.toSet());

		BiMap<Integer, String> topPlayersInverseMap = topPlayers.inverse();
		Map<String, Integer> leaderboardWithoutBots = topValues.stream()
				.collect(Collectors.toMap(rank -> topPlayersInverseMap.get(rank), Function.identity()));
		return leaderboardWithoutBots;
	}

	public PlayerLeaderboard getLeaderboard() throws CacheMissException {
		PlayerLeaderboard leaderboard = this.readCache(this.leaderboardCache, BattleGroundCacheEntryKey.LEADERBOARD.getKey());
		if (leaderboard == null) {
			throw new CacheMissException(BattleGroundCacheEntryKey.LEADERBOARD);
		}
		return leaderboard;
	}
	
	public PlayerLeaderboard writeLeaderboard() {
		log.warn("Leaderboard cache was busted, creating new value");
		PlayerLeaderboard leaderboard = null;
		try {
			leaderboard = this.generatePlayerLeaderboardData();
			this.writeToCache(this.leaderboardCache, BattleGroundCacheEntryKey.LEADERBOARD.getKey(), leaderboard);
			this.battleGroundCacheEntryRepo.writeCacheEntry(leaderboard, BattleGroundCacheEntryKey.LEADERBOARD.getKey());
		} catch(Exception e) {
			log.error("Error writing to bot cache", e);
			this.errorWebhookManager.sendException(e, "exception generating new player leaderboard");
		}
		return leaderboard;
	}

	protected PlayerLeaderboard generatePlayerLeaderboardData() {
		Map<String, Integer> topPlayers = this.getTopPlayers(TOP_PLAYERS);
		List<LeaderboardData> allPlayers = topPlayers.keySet().parallelStream()
				.map(player -> this.collectPlayerLeaderboardDataByPlayer(player)).filter(result -> result != null)
				.sorted().collect(Collectors.toList());
		Collections.reverse(allPlayers);
		for (int i = 0; i < allPlayers.size(); i++) {
			allPlayers.get(i).setRank(i + 1);
		}

		List<LeaderboardData> highestPlayers = allPlayers.parallelStream()
				.filter(leaderboardData -> leaderboardData.getRank() <= HIGHEST_PLAYERS).collect(Collectors.toList());
		List<LeaderboardData> topPlayersList = allPlayers.parallelStream()
				.filter(leaderboardData -> leaderboardData.getRank() > HIGHEST_PLAYERS
						&& leaderboardData.getRank() <= TOP_PLAYERS)
				.collect(Collectors.toList());
		PlayerLeaderboard leaderboard = new PlayerLeaderboard(highestPlayers, topPlayersList);

		return leaderboard;
	}

	@SneakyThrows
	protected LeaderboardData collectPlayerLeaderboardDataByPlayer(String player) {
		NumberFormat myFormat = NumberFormat.getInstance();
		myFormat.setGroupingUsed(true);
		SimpleDateFormat dateFormat = new SimpleDateFormat("MM-dd-yyyy");
		DecimalFormat decimalFormat = new DecimalFormat("##.#########");

		LeaderboardData data = null;
		Integer gil = this.dumpService.getBalanceFromCache(player);
		Date lastActive = this.dumpService.getLastActiveDateFromCache(player);

		String gilString = myFormat.format(gil);
		String percentageOfGlobalGil = decimalFormat.format(this.percentageOfGlobalGil(gil) * (double) 100);
		String activeDate = dateFormat.format(lastActive);
		data = new LeaderboardData(player, gilString, activeDate);
		data.setPercentageOfGlobalGil(percentageOfGlobalGil);

		return data;
	}

	public List<ExpLeaderboardEntry> generateExpLeaderboardData() {
		List<ExpLeaderboardEntry> results = new ArrayList<>();
		for (int rank = 1; rank <= TOP_PLAYERS; rank++) {
			ExpLeaderboardEntry result = null;
			String player = this.dumpService.getExpRankLeaderboardByRank().get(rank);
			Optional<PlayerRecord> maybePlayer = this.playerRecordRepo.findById(player);
			if (maybePlayer.isPresent() && this.isPlayerActiveInLastMonth(maybePlayer.get().getLastActive())) {
				Short level = maybePlayer.get().getLastKnownLevel();
				Short exp = maybePlayer.get().getLastKnownRemainingExp();
				SimpleDateFormat format = new SimpleDateFormat("MM-dd-yyyy");
				String lastActive = format.format(maybePlayer.get().getLastActive());

				Integer prestigeLevel = 0;
				List<String> prestigeSkills = this.dumpService.getPrestigeSkillsCache().get(player);
				if (prestigeSkills != null) {
					prestigeLevel = prestigeSkills.size();
				}

				result = new ExpLeaderboardEntry(rank, player, level, exp, prestigeLevel, lastActive);
				results.add(result);
			}
		}

		return results;
	}

	@SneakyThrows
	public List<PrestigeTableEntry> generatePrestigeTable() {
		List<PrestigeTableEntry> results = this.dumpService.getPrestigeSkillsCache().keySet().parallelStream()
				.filter(player -> this.dumpService.getPrestigeSkillsCache().get(player) != null)
				.filter(player -> this.dumpService.getPrestigeSkillsCache().get(player).size() != 0)
				.map(player -> new PrestigeTableEntry(player,
						this.dumpService.getPrestigeSkillsCache().get(player).size()))
				.collect(Collectors.toList());
		SimpleDateFormat format = new SimpleDateFormat("MM-dd-yyyy");
		results.stream().forEach(prestigeTableEntry -> prestigeTableEntry
				.setLastActive(format.format(this.dumpService.getLastActiveCache().get(prestigeTableEntry.getName()))));
		Collections.sort(results);

		return results;
	}

	public Integer getBetPercentile(Double ratio) throws CacheMissException {
		Map<Integer, Double> betPercentiles = this.readCache(this.betPercentilesCache, BattleGroundCacheEntryKey.BET_PERCENTILES.getKey());
		if (betPercentiles == null) {
			throw new CacheMissException(BattleGroundCacheEntryKey.BET_PERCENTILES);
		}

		Integer result = null;
		for (Map.Entry<Integer, Double> entry: betPercentiles.entrySet()) {
			Double currentPercentile = entry.getValue();
			try {
				if (ratio < currentPercentile) {
					Integer key = Integer.valueOf(entry.getKey());
					result = key - 1;
					break;
				}
			}catch(NullPointerException e) {
				log.error("NullPointerException caught", e);
			} catch(ClassCastException e) {
				log.error("ClassCast exception caught", e);
			}
		}

		return result;
	}
	
	public Map<Integer, Double> writeBetPercentile() {
		log.warn("The Bet Percentiles cache was busted.  Rebuilding");
		Map<Integer, Double> betPercentiles = null;
		try {
			betPercentiles = this.calculateBetPercentiles();
			this.writeToCache(this.betPercentilesCache, BattleGroundCacheEntryKey.BET_PERCENTILES.getKey(), betPercentiles);
			this.battleGroundCacheEntryRepo.writeCacheEntry(betPercentiles, BattleGroundCacheEntryKey.BET_PERCENTILES.getKey());
			log.warn("Bet Percentiles rebuild complete");
		} catch(Exception e) {
			log.error("Error in building bet percentiles", e);
			this.errorWebhookManager.sendException(e, "Error in building bet percentiles");
		}
		
		return betPercentiles;
	}

	public Integer getFightPercentile(Double ratio) throws CacheMissException {
		Map<Integer, Double> fightPercentiles = null;
		fightPercentiles = this.readCache(this.fightPercentilesCache, BattleGroundCacheEntryKey.FIGHT_PERCENTILES.getKey());
		if (fightPercentiles == null) {
			throw new CacheMissException(BattleGroundCacheEntryKey.FIGHT_PERCENTILES);
		}

		Integer result = null;
		for (int i = 0; result == null && i <= 100; i++) {
			Double currentPercentile = fightPercentiles.get(i);
			if (ratio < currentPercentile) {
				result = i - 1;
			}
		}

		return result;
	}
	
	public Map<Integer, Double> writeFightPercentile() {
		log.warn("The Fight Percentiles cache was busted.  Rebuilding");
		Map<Integer, Double> fightPercentiles = null;
		try {
			fightPercentiles = this.calculateFightPercentiles();
			this.writeToCache(this.fightPercentilesCache, BattleGroundCacheEntryKey.FIGHT_PERCENTILES.getKey(), fightPercentiles);
			this.battleGroundCacheEntryRepo.writeCacheEntry(fightPercentiles, BattleGroundCacheEntryKey.FIGHT_PERCENTILES.getKey());
			log.warn("Fight Percentiles rebuild complete");
		} catch(Exception e) {
			log.error("Error in building fight percentiles", e);
			this.errorWebhookManager.sendException(e, "Error in building fight percentiles");
		}
		
		return fightPercentiles;
	}

	public AllegianceLeaderboardWrapper getAllegianceData() throws CacheMissException {
		List<AllegianceLeaderboard> allegianceLeaderboard = null;
		AllegianceLeaderboardWrapper wrapper = this.readCache(this.allegianceLeaderboardCache, BattleGroundCacheEntryKey.ALLEGIANCE_LEADERBOARD.getKey());
		if (wrapper == null) {
			throw new CacheMissException(BattleGroundCacheEntryKey.ALLEGIANCE_LEADERBOARD);
		}
		if(wrapper != null) {
			allegianceLeaderboard = wrapper.getLeaderboards();
		}

		return wrapper;
	}
	
	public AllegianceLeaderboardWrapper writeAllegianceWrapper() {
		log.warn("Allegiance Leaderboard cache busted.  Rebuilding.");
		AllegianceLeaderboardWrapper wrapper = null;
		try {
			List<AllegianceLeaderboard> allegianceLeaderboard = this.generateAllegianceData();
			
			wrapper = new AllegianceLeaderboardWrapper(allegianceLeaderboard);
			this.writeToCache(this.allegianceLeaderboardCache, BattleGroundCacheEntryKey.ALLEGIANCE_LEADERBOARD.getKey(), wrapper);
			this.battleGroundCacheEntryRepo.writeCacheEntry(wrapper, BattleGroundCacheEntryKey.ALLEGIANCE_LEADERBOARD.getKey());
		log.warn("Allegiance Leaderboard cache rebuild complete");
		} catch(Exception e) {
			
		}
		
		return wrapper;
	}

	@Transactional
	protected List<AllegianceLeaderboard> generateAllegianceData() {
		ThreadPoolExecutor executor = (ThreadPoolExecutor) Executors.newFixedThreadPool(20);

		Map<BattleGroundTeam, Map<String, Integer>> allegianceData = new ConcurrentHashMap<>();

		List<BattleGroundTeam> teams = BattleGroundTeam.coreTeams();
		teams.stream().forEach(team -> allegianceData.put(team, new ConcurrentHashMap<String, Integer>()));

		Map<String, Integer> highScoreDataFromDump = this.dumpService.getDumpDataProvider().getHighScoreDump();
		Map<String, Integer> topTenPlayers = this.getTopPlayers(HIGHEST_PLAYERS);

		Future<List<Optional<String>>> topTenFilter = executor
				.submit(new FunctionCallableListResult<String, Optional<String>>(highScoreDataFromDump.keySet(),
						(playerName -> {
							Optional<String> result = null;
							boolean isTopTenPlayer = topTenPlayers.containsKey(playerName);
							result = isTopTenPlayer ? Optional.<String>of(playerName) : Optional.<String>empty();
							return result;
						})));
		Future<List<Optional<String>>> activeFilter = executor
				.submit(new FunctionCallableListResult<String, Optional<String>>(highScoreDataFromDump.keySet(),
						(playerName -> {
							Optional<String> result = null;
							Date lastActive = this.dumpService.getLastActiveDateFromCache(playerName);
							boolean active = this.isPlayerActiveInLastMonth(lastActive);
							result = active ? Optional.<String>empty() : Optional.<String>of(playerName);
							return result;
						})));
		Future<List<Optional<String>>> botFilter = executor
				.submit(new FunctionCallableListResult<String, Optional<String>>(highScoreDataFromDump.keySet(),
						(playerName -> {
							Optional<String> result = null;
							boolean isBot = this.dumpService.getBotCache().contains(playerName);
							result = isBot ? Optional.<String>of(playerName) : Optional.<String>empty();
							return result;
						})));
		Future<List<Optional<String>>> noAllegianceFilter = executor
				.submit(new FunctionCallableListResult<String, Optional<String>>(highScoreDataFromDump.keySet(),
						(playerName -> {
							boolean hasAllegiance = teams
									.contains(this.dumpService.getAllegianceCache().get(playerName));
							Optional<String> result = hasAllegiance ? Optional.<String>empty()
									: Optional.<String>of(playerName);
							return result;
						})));

		Set<String> filteredPlayers = new HashSet<>();
		Function<Future<List<Optional<String>>>, List<String>> conversionFunction = (futureList -> {
			List<String> names = new ArrayList<String>();
			try {
				for (Optional<String> maybePlayer : futureList.get()) {
					if (maybePlayer.isPresent())
						names.add(maybePlayer.get());
				}
			} catch (InterruptedException | ExecutionException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
			return names;
		});
		filteredPlayers.addAll(conversionFunction.apply(topTenFilter));
		filteredPlayers.addAll(conversionFunction.apply(botFilter));
		filteredPlayers.addAll(conversionFunction.apply(activeFilter));
		filteredPlayers.addAll(conversionFunction.apply(noAllegianceFilter));
		filteredPlayers.parallelStream().forEach(playerName -> highScoreDataFromDump.remove(playerName));

		Calendar currentTimeOfCompletion = Calendar.getInstance();

		log.debug("The currentTime is {}", currentTimeOfCompletion);

		highScoreDataFromDump.keySet().parallelStream().forEach(playerName -> {
			BattleGroundTeam allegiance = this.dumpService.getAllegianceCache().get(playerName);
			allegianceData.get(allegiance).put(playerName, highScoreDataFromDump.get(playerName));
		});

		//apparently the database cache gets befuddled if too many threads try to run the query we use for the allegiance leaderboard
		Object playerRepoLock = new Object(); 
		
		// find player total
		// find top 5 players
		// determine the relative position of each team
		List<AllegianceLeaderboard> leaderboard = teams.parallelStream().map(team -> {
			Map<String, Integer> teamData = allegianceData.get(team);

			log.info("Starting initialization of PlayerRecords for {} team", team);

			// access player data from database using a projection
			List<PlayerRecord> teamPlayers = null;
			synchronized(playerRepoLock) {
				teamPlayers = this.playerRecordRepo.getPlayerDataForAllegiance(teamData.keySet())
					.parallelStream().filter(playerRecord -> playerRecord != null).collect(Collectors.toList());
			}

			// sort the players by gil balance.
			
			Collections.sort(teamPlayers, new Comparator<PlayerRecord>() {
				@Override 
				public int compare(PlayerRecord arg0, PlayerRecord arg1) { 
					int compare = arg0.getLastKnownAmount().compareTo(arg1.getLastKnownAmount());
					//lets reverse 
					switch(compare) { 
					case 1: 
						  compare = -1; break; 
					case -1:
						  compare = 1; 
						  break; 
					case 0: default: 
						  break; 
					} 
					return compare; 
				} 
			});
			
			teamPlayers = Collections.synchronizedList(teamPlayers);
			 
			// teamPlayers = Collections.synchronizedList(teamPlayers);
			log.info("Intialization of PlayerRecords for {} complete", team);

			Integer totalBetWins =(new CountingCallable<PlayerRecord>(teamPlayers, (playerRecord -> playerRecord.getWins()))).call();
			Integer totalBetLosses = (new CountingCallable<PlayerRecord>(teamPlayers, (playerRecord -> playerRecord.getLosses()))).call();
			Integer totalFightWins = (new CountingCallable<PlayerRecord>(teamPlayers, (playerRecord -> playerRecord.getFightWins()))).call();
			Integer totalFightLosses =(new CountingCallable<PlayerRecord>(teamPlayers, (playerRecord -> playerRecord.getFightLosses()))).call();
			Integer totalLevels = (new CountingCallable<PlayerRecord>(teamPlayers,(playerRecord -> playerRecord.getLastKnownLevel()))).call();
			Integer totalGil = (new CountingCallable<PlayerRecord>(teamPlayers,(playerRecord -> playerRecord.getLastKnownAmount()))).call();

			// perform the prestige lookup in this thread
			Integer numberOfPrestigeSkills = this.playerSkillRepo.getPrestigeSkillsCount(
					teamPlayers.stream().map(PlayerRecord::getPlayer).collect(Collectors.toList())
				);
			
			log.info("Allegiance data lookup complete for team {}", team);

			List<AllegianceLeaderboardEntry> top5Players = new ArrayList<>();
			for (int i = 0; i < 5; i++) {
				PlayerRecord currentPlayer = teamPlayers.get(i);
				AllegianceLeaderboardEntry entry = new AllegianceLeaderboardEntry(currentPlayer.getPlayer(),
						currentPlayer.getLastKnownAmount());
				entry.setPosition(i);
				top5Players.add(entry);
			}
			
			log.info("Top5 player leaderboard complete for team {}", team);

			String topPlayerPortrait = this.dumpService.getPortraitCache().get(top5Players.get(0).getName());
			String portraitUrl = this.images.getPortraitByName(topPlayerPortrait, team);
			if (StringUtils.isNotBlank(topPlayerPortrait)) {
				portraitUrl = this.images.getPortraitByName(topPlayerPortrait, team);
			}
			if (StringUtils.isBlank(topPlayerPortrait) || portraitUrl == null) {
				List<TeamInfo> playerTeamInfo = this.matchRepo.getLatestTeamInfoForPlayer(top5Players.get(0).getName(),
						PageRequest.of(0, 1));
				if (playerTeamInfo != null && playerTeamInfo.size() > 0) {
					portraitUrl = this.images.getPortraitLocationByTeamInfo(playerTeamInfo.get(0), team);
				} else {
					portraitUrl = this.images.getPortraitByName("Ramza");
				}
			}
			
			log.info("Portrait lookup complete for team {}", team);

			AllegianceLeaderboard data = new AllegianceLeaderboard(team, portraitUrl, top5Players);
			data.setBetWins(totalBetWins);
			data.setBetLosses(totalBetLosses);
			data.setFightWins(totalFightWins);
			data.setFightLosses(totalFightLosses);
			data.setTotalLevels(totalLevels);
			data.setTotalPrestiges(numberOfPrestigeSkills);
			data.setTotalGil(totalGil);
			data.setTotalPlayers(teamData.keySet().size());

			Integer gilPerPlayer = data.getTotalGil() / data.getTotalPlayers();
			data.setGilPerPlayer(gilPerPlayer);

			Double levelsPerPlayer = (double) data.getTotalLevels() / (double) data.getTotalPlayers();
			data.setTotalLevelsPerPlayer(levelsPerPlayer);

			Double betRatio = (data.getBetWins().doubleValue() + 1) / (data.getBetWins().doubleValue() + data.getBetLosses().doubleValue() + 1);
			Double fightRatio = (data.getFightWins().doubleValue() + 1) / (data.getFightWins().doubleValue() + data.getFightLosses().doubleValue() + 1);

			data.setBetRatio(betRatio);
			data.setFightRatio(fightRatio);

			log.info("Getting quantiles for team {}", team);
			
			Integer betQuantile;
			Integer fightQuantile;
			try {
				betQuantile = this.getBetPercentile(betRatio);
				fightQuantile = this.getFightPercentile(fightRatio);
			} catch (CacheMissException e) {
				log.error("Error pulling Quantile data", e);
				betQuantile = 0;
				fightQuantile = 0;
			}
			
			log.info("Team {} is done getting quantiles", team);

			data.setBetQuantile(betQuantile);
			data.setFightQuantile(fightQuantile);
			
			log.info("Team {} allegiance data is compiled and ready", team);

			return data;
		}).collect(Collectors.toList());

		// determine the leaderboard order
		Collections.sort(leaderboard, Collections.reverseOrder());
		for (int i = 0; i < leaderboard.size(); i++) {
			leaderboard.get(i).setPosition(i + 1);
		}

		// determine gil underdog
		BattleGroundTeam gilUnderdog = BattleGroundTeam.NONE;
		int currentPlayerGilRatioToBeat = 0;
		for (AllegianceLeaderboard board : leaderboard) {
			if (board.getGilPerPlayer() > currentPlayerGilRatioToBeat) {
				gilUnderdog = board.getTeam();
				currentPlayerGilRatioToBeat = board.getGilPerPlayer();
			}
		}
		for (AllegianceLeaderboard board : leaderboard) {
			if (board.getTeam() == gilUnderdog) {
				board.setGilUnderdog(true);
			}
		}

		// determine bet underdog
		BattleGroundTeam betWinUnderdog = BattleGroundTeam.NONE;
		double currentBetWinRatioToBeat = 0;
		for (AllegianceLeaderboard board : leaderboard) {
			if (board.getBetRatio() > currentPlayerGilRatioToBeat) {
				betWinUnderdog = board.getTeam();
				currentBetWinRatioToBeat = board.getBetRatio();
			}
		}
		for (AllegianceLeaderboard board : leaderboard) {
			if (board.getTeam() == betWinUnderdog) {
				board.setBetWinUnderdog(true);
			}
		}

		// determine fight underdog
		BattleGroundTeam fightWinUnderdog = BattleGroundTeam.NONE;
		double currentFightWinUnderdog = 0;
		for (AllegianceLeaderboard board : leaderboard) {
			if (board.getFightRatio() > currentFightWinUnderdog) {
				fightWinUnderdog = board.getTeam();
				currentFightWinUnderdog = board.getFightRatio();
			}
		}
		for (AllegianceLeaderboard board : leaderboard) {
			if (board.getTeam() == fightWinUnderdog) {
				board.setFightWinUnderdog(true);
			}
		}
		
		//get champion data
		Map<BattleGroundTeam, Integer> seasonChampionWins = this.championService.getSeasonChampWinsByAllegiance();
		for(BattleGroundTeam seasonChampionTeam : seasonChampionWins.keySet()) {
			boolean matchFound = false;
			for(int i = 0; i < leaderboard.size() && !matchFound; i++) {
				AllegianceLeaderboard board = leaderboard.get(i);
				if(board.getTeam() == seasonChampionTeam) {
					board.setCurrentSeasonFightWinsAsChampion(seasonChampionWins.get(seasonChampionTeam));
					matchFound = true;
				}
			}
		}

		log.info("Allegiance Leaderboard rebuild complete");
		
		return leaderboard;
	}

	// this time let's take it hour by hour, and then use my original date slice
	// algorithm
	@SneakyThrows
	public LeaderboardBalanceData getLabelsAndSetRelevantBalanceHistories(List<LeaderboardBalanceHistoryEntry> entries,
			Integer count) {
		SimpleDateFormat sdf = new SimpleDateFormat("dd-M-yyyy hh");

		// dynamically pick best hours to find entries for one week
		int hoursToTrack = 168;
		int hoursPerSlice = hoursToTrack / count;
		List<Date> dateSlices = new ArrayList<>();
		for (int i = 0; i < count; i++) {
			Calendar calendar = Calendar.getInstance();
			int hourSliceSize = i * hoursPerSlice * -1;
			calendar.add(Calendar.HOUR, hourSliceSize);
			Date dateSlice = calendar.getTime();
			sdf.parse(sdf.format(dateSlice));
			dateSlices.add(dateSlice);
		}

		Collections.sort(dateSlices);

		// simplify all dates to nearest hour
		for (LeaderboardBalanceHistoryEntry entry : entries) {
			for (BalanceHistory balanceHistory : entry.getBalanceHistory()) {
				String simplifiedDateString = sdf.format(balanceHistory.getCreate_timestamp());
				Date simplifiedDate = sdf.parse(simplifiedDateString);
				balanceHistory.setCreate_timestamp(new Timestamp(simplifiedDate.getTime()));
			}
		}

		// now lets extrapolate each player's balance history by hour
		List<LeaderboardBalanceHistoryEntry> extrapolatedData = new ArrayList<>();
		for (LeaderboardBalanceHistoryEntry entry : entries) {
			LeaderboardBalanceHistoryEntry extrapolatedEntry = new LeaderboardBalanceHistoryEntry(entry.getPlayerName(),
					new ArrayList<BalanceHistory>());
			Integer currentAmount = 0;
			for (int i = 0; i < hoursToTrack; i++) {
				Calendar calendar = Calendar.getInstance();
				calendar.add(Calendar.HOUR, (-1 * hoursToTrack) + i);

				// find nearest balanceHistory match
				boolean matchFound = false;
				for (int j = 0; j < entry.getBalanceHistory().size() && !matchFound; j++) {
					if (this.twoDatesMatchSameExactHour(calendar.getTime(),
							entry.getBalanceHistory().get(j).getCreate_timestamp())) {
						matchFound = true;
						currentAmount = entry.getBalanceHistory().get(j).getBalance();
					}
				}

				extrapolatedEntry.getBalanceHistory()
						.add(new BalanceHistory(entry.getPlayerName(), currentAmount, calendar.getTime()));
			}
			extrapolatedData.add(extrapolatedEntry);
		}

		// reduce balance history to appropriate values
		for (LeaderboardBalanceHistoryEntry entry : extrapolatedData) {
			List<BalanceHistory> truncatedHistory = new ArrayList<>();
			for (int i = 0; i < dateSlices.size(); i++) {
				Date currentSlice = dateSlices.get(i);
				boolean foundEntry = false;
				// search balance history for first entry with the correct hour
				Integer currentAmount = 0;
				for (int j = 0; j < entry.getBalanceHistory().size() && !foundEntry; j++) {
					Calendar currentSliceCalendar = Calendar.getInstance();
					currentSliceCalendar.setTime(currentSlice);
					Calendar currentBalanceHistoryDateCalendar = Calendar.getInstance();
					Date currentBalanceHistoryDate = entry.getBalanceHistory().get(j).getCreate_timestamp();
					currentBalanceHistoryDateCalendar.setTime(currentBalanceHistoryDate);
					if (this.twoDatesMatchSameExactHour(currentSlice, currentBalanceHistoryDate)) {
						foundEntry = true;
						truncatedHistory.add(entry.getBalanceHistory().get(j));
						currentAmount = entry.getBalanceHistory().get(j).getBalance();
					}
				}

				if (!foundEntry && this.twoDatesMatchSameExactHour(currentSlice, new Date())) {
					foundEntry = true;
					Optional<PlayerRecord> maybePlayer = this.playerRecordRepo
							.findById(StringUtils.lowerCase(entry.getPlayerName()));
					if (maybePlayer.isPresent()) {
						foundEntry = true;
						Date simplifiedDate = sdf.parse(sdf.format(new Date()));
						truncatedHistory.add(new BalanceHistory(entry.getPlayerName(),
								maybePlayer.get().getLastKnownAmount(), simplifiedDate));
					}
				}
				// if none found, create a valid blank entry
				if (!foundEntry) {
					log.warn("could not find an entry even with fully extrapolated data, something went wrong");
					truncatedHistory.add(new BalanceHistory(entry.getPlayerName(), currentAmount, currentSlice));
				}
				// reset foundEntry
				foundEntry = false;
			}
			entry.setBalanceHistory(truncatedHistory);
		}

		// smooth out zero results. 0 is not possible for balances, so must be caused by
		// missing data
		for (LeaderboardBalanceHistoryEntry entry : extrapolatedData) {
			this.smoothOutZeroes(entry);
		}

		LeaderboardBalanceData data = new LeaderboardBalanceData(dateSlices, extrapolatedData);

		return data;
	}

	/**
	 * Zero is not a valid result for balance, and must be caused by missing data.
	 * This function iterates over all balance history data backwards, setting any 0
	 * value to the first valid value that follows it.
	 * 
	 * @param extrapolatedData
	 */
	protected void smoothOutZeroes(LeaderboardBalanceHistoryEntry extrapolatedData) {
		// increment in reverse
		Integer previousAmount = null;
		List<BalanceHistory> balanceHistory = extrapolatedData.getBalanceHistory();
		for (int i = balanceHistory.size() - 1; i >= 0; i--) {
			BalanceHistory currentBalanceHistory = balanceHistory.get(i);
			if (previousAmount == null) {
				previousAmount = currentBalanceHistory.getBalance();
			} else if (currentBalanceHistory.getBalance().equals(0)) {
				currentBalanceHistory.setBalance(previousAmount);
			} else {
				previousAmount = currentBalanceHistory.getBalance();
			}
		}

		return;
	}

	protected boolean twoDatesMatchSameExactHour(Date currentSlice, Date currentBalanceHistoryDate) {
		Calendar currentSliceCalendar = Calendar.getInstance();
		currentSliceCalendar.setTime(currentSlice);
		Calendar currentBalanceHistoryDateCalendar = Calendar.getInstance();
		currentBalanceHistoryDateCalendar.setTime(currentBalanceHistoryDate);
		boolean result = currentSliceCalendar.get(Calendar.MONTH) == currentBalanceHistoryDateCalendar
				.get(Calendar.MONTH)
				&& currentSliceCalendar.get(Calendar.DAY_OF_MONTH) == currentBalanceHistoryDateCalendar
						.get(Calendar.DAY_OF_MONTH)
				&& currentSliceCalendar.get(Calendar.HOUR_OF_DAY) == currentBalanceHistoryDateCalendar
						.get(Calendar.HOUR_OF_DAY);

		return result;
	}

	protected Map<Integer, Double> calculateBetPercentiles() {
		Map<Integer, Double> percentiles = Quantiles.percentiles().indexes(IntStream.rangeClosed(0, 100).toArray())
				.compute(this.playerRecordRepo.findAll().stream()
						.filter(playerRecord -> playerRecord.getWins() != null && playerRecord.getLosses() != null)
						.filter(playerRecord -> playerRecord.getLastActive() != null
								&& this.isPlayerActiveInLastMonth(playerRecord.getLastActive()))
						.filter(playerRecord -> (playerRecord.getWins()
								+ playerRecord.getLosses()) > PERCENTILE_THRESHOLD)
						.map(playerRecord -> ((double) playerRecord.getWins() + 1)
								/ ((double) playerRecord.getWins() + playerRecord.getLosses() + 1))
						.collect(Collectors.toList()));
		return percentiles;
	}

	protected Map<Integer, Double> calculateFightPercentiles() {
		Map<Integer, Double> percentiles = Quantiles.percentiles().indexes(IntStream.rangeClosed(0, 100).toArray())
				.compute(this.playerRecordRepo.findAll().stream()
						.filter(playerRecord -> playerRecord.getFightWins() != null
								&& playerRecord.getFightLosses() != null)
						.filter(playerRecord -> playerRecord.getLastActive() != null
								&& this.isPlayerActiveInLastMonth(playerRecord.getLastActive()))
						.filter(playerRecord -> (playerRecord.getFightWins()
								+ playerRecord.getFightLosses()) > PERCENTILE_THRESHOLD)
						.map(playerRecord -> ((double) playerRecord.getFightWins() + 1)
								/ ((double) playerRecord.getFightWins() + playerRecord.getFightLosses() + 1))
						.collect(Collectors.toList()));
		return percentiles;
	}

	protected boolean isPlayerActiveInLastMonth(Date lastActiveDate) {
		if (lastActiveDate == null) {
			return false;
		}

		Calendar thirtyDaysAgo = Calendar.getInstance();
		thirtyDaysAgo.add(Calendar.DAY_OF_MONTH, -30); // 2020-01-25

		Date thirtyDaysAgoDate = thirtyDaysAgo.getTime();
		boolean isActive = lastActiveDate.after(thirtyDaysAgoDate);
		return isActive;
	}

}

class CountingCallable<T> implements Callable<Integer> {
	private ToIntFunction<T> countingFunction;
	private List<T> iteratedObject;

	public CountingCallable(List<T> iteratedObject, ToIntFunction<T> countingFunction) {
		this.iteratedObject = iteratedObject;
		this.countingFunction = countingFunction;
	}

	@Override
	public Integer call() {
		Integer result = this.iteratedObject.parallelStream().mapToInt(this.countingFunction).sum();
		return result;
	}
}

class FunctionCallableListResult<T, S> implements Callable<List<S>> {
	private Function<T, S> callingFunction;
	private Collection<T> iteratedObject;

	public FunctionCallableListResult(Collection<T> obj, Function<T, S> callingFunction) {
		this.iteratedObject = obj;
		this.callingFunction = callingFunction;
	}

	@Override
	public List<S> call() throws Exception {
		List<S> result = this.iteratedObject.parallelStream().map(this.callingFunction).collect(Collectors.toList());
		return result;
	}
}
