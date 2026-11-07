package fft_battleground.repo.repository;

import java.util.Date;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.apache.commons.lang3.StringUtils;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import fft_battleground.repo.model.BotHourlyData;
import fft_battleground.util.GenericElementOrdering;

@Repository
public interface BotsHourlyDataRepo extends JpaRepository<BotHourlyData, String> {

	@Query("SELECT botHourlyData FROM BotHourlyData botHourlyData WHERE botHourlyData.player = :player")
	public List<BotHourlyData> getBotHourlyDataForBot(@Param("player") String player);
	
	@Query("SELECT botHourlyData FROM BotHourlyData botHourlyData WHERE lower(botHourlyData.player) = lower(:player) AND botHourlyData.hourValue = :hour_value")
	public BotHourlyData getBotHourlyDataForBotAndCurrentTime(@Param("player") String player, @Param("hour_value") Integer hourValue);

	//players with any hourly update since the cutoff (lowercased) — feeds the roster persistence self-check
	@Query("SELECT DISTINCT lower(botHourlyData.player) FROM BotHourlyData botHourlyData WHERE botHourlyData.updateDateTime >= :cutoff")
	public List<String> getRecentlyUpdatedPlayers(@Param("cutoff") Date cutoff);
	
	@Query("SELECT botHourlyData FROM BotHourlyData botHourlyData WHERE botHourlyData.player IN (:playerList)")
	public List<BotHourlyData> getBotHourlyDataForBotList(@Param("playerList") List<String> bots);
	
	public default List<BotHourlyData> getOrderedBotHourlyDataForBot(String bot) {
		int startHour = BotHourlyData.getHourValueForCurrentTime();
		 
		List<BotHourlyData> botHourlyDataForBot = this.getBotHourlyDataForBot(bot);
		Map<Integer, BotHourlyData> hourIntHourlyDataMap = botHourlyDataForBot.parallelStream().collect(Collectors.toMap(BotHourlyData::getHourValue, Function.identity()));
		List<BotHourlyData> orderedData = this.getHourOrdering(startHour).stream().map(hourInt -> hourIntHourlyDataMap.get(hourInt)).collect(Collectors.toList());
		
		return orderedData;
	}
	
	public default Map<String, List<GenericElementOrdering<BotHourlyData>>> getOrderedBotHourlyDataForBots(List<String> bots) {
		Map<String, List<GenericElementOrdering<BotHourlyData>>> results = new HashMap<>();
		List<BotHourlyData> allBotData = this.getBotHourlyDataForBotList(bots);
		
		Function<String, List<BotHourlyData>> botNameFilter = botName -> allBotData.parallelStream().filter(botHourlyData -> StringUtils.equalsIgnoreCase(botHourlyData.getPlayer(), botName))
																.collect(Collectors.toList());
		Map<String, List<BotHourlyData>> botDataMap = bots.parallelStream().collect(Collectors.toMap(Function.identity(), botNameFilter));
		List<Integer> hourOrdering = this.getHourOrdering(BotHourlyData.getHourValueForCurrentTime());
		for(String bot : botDataMap.keySet()) {
			List<BotHourlyData> botHourlyDataForBot = botDataMap.get(bot);
			Map<Integer, BotHourlyData> hourIntHourlyDataMap = botHourlyDataForBot.parallelStream().collect(Collectors.toMap(BotHourlyData::getHourValue, Function.identity()));
			List<GenericElementOrdering<BotHourlyData>> orderedDataForBot = new LinkedList<>();
			for(int i = 0; i < hourOrdering.size(); i++) {
				Integer hourInt = hourOrdering.get(i);
				orderedDataForBot.add(new GenericElementOrdering<BotHourlyData>(i, hourIntHourlyDataMap.get(hourInt)));
			}
			results.put(bot, orderedDataForBot);
		}
		
		return results;
	}
	
	public default List<Integer> getHourOrdering(int startHour) {
		List<Integer> firstHourList = IntStream.range(startHour, 24).boxed().collect(Collectors.toList());
		List<Integer> secondHourList = IntStream.range(0, startHour).boxed().collect(Collectors.toList());
		
		List<Integer> results = new LinkedList<>();
		results.addAll(firstHourList);
		results.addAll(secondHourList);
		
		return results;
	}
	
	
}
