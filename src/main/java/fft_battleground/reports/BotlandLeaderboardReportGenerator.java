package fft_battleground.reports;

import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Timer;
import java.util.HashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;

import javax.annotation.PreDestroy;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.tuple.MutablePair;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.commons.lang3.tuple.Triple;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;

import fft_battleground.discord.WebhookManager;
import fft_battleground.exception.CacheBuildException;
import fft_battleground.repo.model.Bots;
import fft_battleground.repo.repository.BattleGroundCacheEntryRepo;
import fft_battleground.repo.repository.BotsRepo;
import fft_battleground.repo.util.BattleGroundCacheEntryKey;
import fft_battleground.reports.model.BotlandLeaderboard;
import fft_battleground.reports.model.BotlandWinner;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;

@Component
@Slf4j
public class BotlandLeaderboardReportGenerator extends AbstractReportGenerator<BotlandLeaderboard> {
	private static final BattleGroundCacheEntryKey key = BattleGroundCacheEntryKey.BOTLAND_LEADERBOARD;
	private static final String reportName = "Botland Leaderboard";
	
	@Autowired
	private BotsRepo botsRepo;

	@Autowired
	private fft_battleground.botland.BetBotFactory betBotFactory;

	// shared across refreshes: creating a pool per generateReport() call leaked 8 threads per refresh
	private final ThreadPoolExecutor executor = (ThreadPoolExecutor) Executors.newFixedThreadPool(8);

	@PreDestroy
	private void shutdownExecutor() {
		this.executor.shutdown();
	}

	public BotlandLeaderboardReportGenerator(BattleGroundCacheEntryRepo battleGroundCacheEntryRepo, WebhookManager errorWebhookManager,
			Timer battlegroundCacheTimer) {
		super(key, reportName, battleGroundCacheEntryRepo, errorWebhookManager, battlegroundCacheTimer);
	}

	@Override
	public BotlandLeaderboard generateReport() throws CacheBuildException {
		BotlandLeaderboard leaderboard = null;
		
		Future<List<Bots>> botList = executor.submit(this.botsRepo::getBotsForToday); 
		Future<List<Bots>> botEntriesWithHighestKnownValueAllTime = executor.submit(this.botsRepo::highestKnownValueHistorical);
		Future<List<Bots>> botEntriesThatAreWinnersForDay = executor.submit(this.botsRepo::highestBalancePerDay);
		Future<List<Bots>> oldestEntriesPerBot = executor.submit(this.botsRepo::getOldestEntries);
		Future<List<Pair<String, Long>>> daysParticipatingPerBot = executor.submit(this.botsRepo::getDaysParticipatingPerBot);
		Future<List<Pair<String, Long>>> winsPerBot = executor.submit(this.botsRepo::getWinsPerBot);
		Future<List<Pair<String, Double>>> averageWinsPerDayPerBot = executor.submit(this.botsRepo::getAverageWinsPerDayPerBot);
		Future<List<Pair<String, Long>>> lossesPerBot = executor.submit(this.botsRepo::getLossesPerBot);
		Future<List<Pair<String, Double>>> averageLossesPerDayPerBot = executor.submit(this.botsRepo::getAverageLossesPerDayPerBot);
		Future<List<Pair<String, Double>>> averageEndDayBalancePerBot = executor.submit(this.botsRepo::getAverageEndDayBalancePerBot);
		Future<List<Pair<String, Double>>> averagePeakBalancePerBot = executor.submit(this.botsRepo::getAveragePeakBalancePerBot);
		Future<List<Triple<String, Date, Integer>>> averageWinRatePerBotPerDay = executor.submit(this.botsRepo::getDailyWinRatePerBot);
		try {
			//winner for past 100 days
			Map<String, List<Bots>> dateStringWinnerBotsMap = botEntriesThatAreWinnersForDay.get().stream()
					.sorted(Comparator.comparing(Bots::getUpdateDateTime).reversed())
					.filter(bot -> !StringUtils.equalsIgnoreCase(bot.getDateString(), this.botsRepo.currentDateString()))
					.limit(100)
					.collect(Collectors.groupingBy(Bots::getDateString));
			Function<String, BotlandWinner> botlandWinnerFunction = (key) -> new BotlandWinner(key, dateStringWinnerBotsMap.get(key).stream().map(Bots::getPlayer).collect(Collectors.toList()), 
					dateStringWinnerBotsMap.get(key).get(0).getBalance());
			Map<String, BotlandWinner> dateStringWinnersMap = dateStringWinnerBotsMap.keySet().stream()
					.filter(key -> !dateStringWinnerBotsMap.get(key).isEmpty())
					.collect(Collectors.toMap(Function.identity(), botlandWinnerFunction));

			//determine highest of all time for each bot
			Map<String, Integer> botHighestOfAllTimeMap = botEntriesWithHighestKnownValueAllTime.get().stream()
					.collect(Collectors.toMap(bot -> bot.getPlayer(), bot -> bot.getHighestKnownValue(), (first, second) -> first));
			
			//determine number of wins per bot
			Map<String, Long> botWinCountMap = botEntriesThatAreWinnersForDay.get().stream()
					.collect(Collectors.groupingBy(Bots::getPlayer, Collectors.counting()));
			
			//determine oldest entry per bot
			Map<String, Bots> oldestBotEntryMap = oldestEntriesPerBot.get().stream()
					.collect(Collectors.toMap(Bots::getPlayer, Function.identity(), (first, second) -> first));
			
			Map<String, Long> winCountsPerBot = winsPerBot.get().stream().collect(Collectors.toMap(Pair::getLeft, Pair::getRight, (first, second) -> first));
			Map<String, Long> lossCountsPerBot = lossesPerBot.get().stream().collect(Collectors.toMap(Pair::getLeft, Pair::getRight, (first, second) -> first));
			Map<String, Double> averageBalancePerBot = averageEndDayBalancePerBot.get().stream().collect(Collectors.toMap(Pair::getLeft, Pair::getRight, (first, second) -> first));
			Map<String, Double> averagePeakBalancePerBotMap = averagePeakBalancePerBot.get().stream().collect(Collectors.toMap(Pair::getLeft, Pair::getRight, (first, second) -> first));
			
			Map<String, Double> averageWinsPerDay = averageWinsPerDayPerBot.get().stream().collect(Collectors.toMap(Pair::getLeft, Pair::getRight, (first, second) -> first));
			Map<String, Double> averageLossesPerDay = averageLossesPerDayPerBot.get().stream().collect(Collectors.toMap(Pair::getLeft, Pair::getRight, (first, second) -> first));
			
			//days participating must be calculated by number of days the application was online and when the bot was online
			Map<String, Long> daysParticipatingPerBotMap = daysParticipatingPerBot.get().stream().collect(Collectors.toMap(Pair::getLeft, Pair::getRight, (first, second) -> first));
			
			//gorgeous collector that first creates a Map<String, Pair<String, Double>> and reduces it with averagingDouble to Map<String, Double>
			Map<String, Double> averageWinRatePerDay = averageWinRatePerBotPerDay.get().stream()
					.map(triple -> new MutablePair<String, Integer>(triple.getLeft(), triple.getRight()))
					.collect(Collectors.groupingBy(Pair<String, Integer>::getLeft, HashMap<String, Double>::new, 
							Collectors.averagingInt(Pair<String, Integer>::getRight)));
			
			//ghost bots (visible="false", e.g. the advisor) stay off the public leaderboard
			List<Bots> todaysBots = new java.util.ArrayList<>(botList.get());
			java.util.Set<String> hiddenBots = this.betBotFactory.getHiddenBotNames();
			todaysBots.removeIf(bot -> hiddenBots.contains(StringUtils.lowerCase(bot.getPlayer())));

			leaderboard = new BotlandLeaderboard(todaysBots, dateStringWinnersMap, botHighestOfAllTimeMap, botWinCountMap, oldestBotEntryMap, winCountsPerBot,
					lossCountsPerBot, averageBalancePerBot, averagePeakBalancePerBotMap, averageWinsPerDay, averageLossesPerDay, averageWinRatePerDay, daysParticipatingPerBotMap);
		} catch (InterruptedException|ExecutionException e) {
			String errorMessage = "Error building Botland Leaderboard cache";
			log.error("Error building Botland Leaderboard cache", e);
			throw new CacheBuildException(errorMessage, e);
		}
		
		return leaderboard;
	}

	@Override
	@SneakyThrows
	public BotlandLeaderboard deserializeJson(String json) {
		ObjectMapper mapper = new ObjectMapper();
		BotlandLeaderboard leaderboard = mapper.readValue(json,  BotlandLeaderboard.class);
		return leaderboard;
	}

}
