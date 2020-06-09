package fft_battleground.dump;

import java.io.BufferedReader;
import java.io.StringReader;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TimerTask;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.stream.Collectors;

import javax.annotation.PostConstruct;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.annotation.CacheConfig;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import com.google.common.collect.MapDifference.ValueDifference;
import com.google.common.collect.Maps;

import fft_battleground.botland.model.BalanceType;
import fft_battleground.botland.model.BalanceUpdateSource;
import fft_battleground.botland.model.BattleGroundEventType;
import fft_battleground.botland.model.SkillType;
import fft_battleground.dump.model.Music;
import fft_battleground.event.model.BalanceEvent;
import fft_battleground.event.model.BattleGroundEvent;
import fft_battleground.event.model.ExpEvent;
import fft_battleground.event.model.GlobalGilHistoryUpdateEvent;
import fft_battleground.event.model.LastActiveEvent;
import fft_battleground.event.model.OtherPlayerBalanceEvent;
import fft_battleground.event.model.OtherPlayerExpEvent;
import fft_battleground.model.BattleGroundTeam;
import fft_battleground.repo.PlayerRecordRepo;
import fft_battleground.repo.model.GlobalGilHistory;
import fft_battleground.repo.model.PlayerRecord;
import fft_battleground.util.GambleUtil;
import fft_battleground.util.Router;
import lombok.Getter;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;

@Service
@Slf4j
@CacheConfig(cacheNames = {"music"})
public class DumpService {
	
	private static final String DUMP_HIGH_SCORE_URL = "http://www.fftbattleground.com/fftbg/highscores.txt";
	private static final String DUMP_PLAYLIST_URL = "http://www.fftbattleground.com/fftbg/playlist.xml";
	
	// Wed Jan 01 00:00:00 EDT 2020
	public static final String dateActiveFormatString = "EEE MMM dd HH:mm:ss z yyyy";
	
	@Autowired
	@Getter private DumpDataProvider dumpDataProvider;
	
	@Autowired
	private DumpResourceManager dumpResourceManager;
	
	@Autowired
	private DumpScheduledTasks dumpScheduledTasks;
	
	@Autowired
	private DumpReportsService dumpReportsService;
	
	@Autowired
	private PlayerRecordRepo playerRecordRepo;
	
	@Autowired
	private Router<BattleGroundEvent> eventRouter;
	
	@Getter private Map<String, Integer> balanceCache = new ConcurrentHashMap<>();
	@Getter private Map<String, ExpEvent> expCache = new HashMap<>();
	@Getter private Map<String, Date> lastActiveCache = new HashMap<>();
	@Getter private Map<String, String> portraitCache = new HashMap<>();
	@Getter private Map<String, BattleGroundTeam> allegianceCache = new HashMap<>();
	@Getter private Map<String, List<String>> userSkillsCache = new HashMap<>();
	@Getter private Map<String, List<String>> prestigeSkillsCache = new HashMap<>();
	@Getter private Set<String> botCache;
	
	@Getter private Map<String, Integer> leaderboard = new HashMap<>();
	
	public DumpService() {}
	
	@PostConstruct
	@Transactional
	@SneakyThrows
	private void setUpCaches() {
		log.info("loading player data cache");
		
		List<PlayerRecord> playerRecords = this.playerRecordRepo.findAll();
		playerRecords.parallelStream().filter(playerRecord -> playerRecord.getLastKnownAmount() == null).forEach(playerRecord -> playerRecord.setLastKnownAmount(GambleUtil.MINIMUM_BET));
		log.info("finished loading player cache");
		
		log.info("started loading balance cache");
		this.balanceCache = new ConcurrentHashMap<>(playerRecords.parallelStream().collect(Collectors.toMap(PlayerRecord::getPlayer, PlayerRecord::getLastKnownAmount)));
		log.info("finished loading balance cache");
		playerRecords.parallelStream().filter(playerRecord -> playerRecord.getLastKnownLevel() == null).forEach(playerRecord -> playerRecord.setLastKnownLevel((short) 1));
		this.expCache = playerRecords.parallelStream().map(playerRecord -> new ExpEvent(playerRecord.getPlayer(), playerRecord.getLastKnownLevel(), playerRecord.getLastKnownRemainingExp()))
							.collect(Collectors.toMap(ExpEvent::getPlayer, Function.identity()));
		log.info("finished loading exp cache");
		
		playerRecords.parallelStream().filter(playerRecord -> playerRecord.getLastActive() == null).forEach(playerRecord -> {
			try {
				SimpleDateFormat dateFormatter = new SimpleDateFormat(dateActiveFormatString);
				playerRecord.setLastActive(dateFormatter.parse("Wed Jan 01 00:00:00 EDT 2020"));
			} catch (ParseException e) {
				log.error("error parsing date for lastActive", e);
			}
		});
		this.lastActiveCache = playerRecords.parallelStream().collect(Collectors.toMap(PlayerRecord::getPlayer, PlayerRecord::getLastActive));
		log.info("finished loading last active cache");
		
		playerRecords.parallelStream().filter(playerRecord -> playerRecord.getPortrait() == null).forEach(playerRecord -> playerRecord.setPortrait(""));
		this.portraitCache = playerRecords.parallelStream().collect(Collectors.toMap(PlayerRecord::getPlayer, PlayerRecord::getPortrait));
		log.info("finished loading portrait cache");
		
		playerRecords.parallelStream().filter(playerRecord -> playerRecord.getAllegiance() == null).forEach(playerRecord -> playerRecord.setAllegiance(BattleGroundTeam.NONE));
		this.allegianceCache = playerRecords.parallelStream().collect(Collectors.toMap(PlayerRecord::getPlayer, PlayerRecord::getAllegiance));
		log.info("finished loading allegiance cache");
		
		playerRecords.parallelStream().filter(playerRecord -> playerRecord.getPlayerSkills() == null).forEach(playerRecord -> playerRecord.setPlayerSkills(new ArrayList<>()));
		this.userSkillsCache = playerRecords.parallelStream().collect(Collectors.toMap(PlayerRecord::getPlayer, 
				playerRecord -> playerRecord.getPlayerSkills().stream().filter(playerSkill -> playerSkill.getSkillType() == SkillType.USER)
				.map(playerSkill -> playerSkill.getSkill()).collect(Collectors.toList())
				));
		log.info("finished loading user skills cache");
		
		this.prestigeSkillsCache = playerRecords.parallelStream().collect(Collectors.toMap(PlayerRecord::getPlayer, 
				playerRecord -> playerRecord.getPlayerSkills().stream().filter(playerSkill -> playerSkill.getSkillType() == SkillType.PRESTIGE)
				.map(playerSkill -> playerSkill.getSkill()).collect(Collectors.toList())
				));
		log.info("finished loading prestige skills cache");
		
		this.botCache = this.dumpDataProvider.getBots();
		log.info("finished loading bot cache");
		
		//run this at startup so leaderboard data works properly
		log.info("pre-cache leaderboard data");
		this.getHighScoreDump();
		
		//run this at startup so the leaderboard caches are pre-loaded (and don't cause lag for the rest of the machine
		this.dumpReportsService.getBotLeaderboard();
		this.dumpReportsService.getLeaderboard();
		log.info("leaderboard data cache complete");
		
		//this.dumpScheduledTasks.runAllUpdates();
		
		log.info("player data cache load complete");
	}
	
	public Collection<BattleGroundEvent> getUpdatesFromDumpService() {
		Collection<BattleGroundEvent> data = new LinkedList<BattleGroundEvent>();
		
		log.info("updating balance cache");
		Map<String, Integer> newBalanceDataFromDump = this.getHighScoreDump();
		Map<String, ValueDifference<Integer>> balanceDelta = Maps.difference(this.balanceCache, newBalanceDataFromDump).entriesDiffering();
		OtherPlayerBalanceEvent otherPlayerBalance= new OtherPlayerBalanceEvent(BattleGroundEventType.OTHER_PLAYER_BALANCE, new ArrayList<BalanceEvent>());
		//find differences in balance
		for(String key: balanceDelta.keySet()) {
			BalanceEvent newEvent = new BalanceEvent(key, balanceDelta.get(key).rightValue(), BalanceType.DUMP, BalanceUpdateSource.DUMP);
			otherPlayerBalance.getOtherPlayerBalanceEvents().add(newEvent);
			//update cache with new data
			this.balanceCache.put(key, balanceDelta.get(key).rightValue());
		}
		data.add(otherPlayerBalance);
		
		
		//find missing players
		for(String key: newBalanceDataFromDump.keySet()) {
			if(!this.balanceCache.containsKey(key)) {
				BalanceEvent newEvent = new BalanceEvent(key, newBalanceDataFromDump.get(key), BalanceType.DUMP, BalanceUpdateSource.DUMP);
				otherPlayerBalance.getOtherPlayerBalanceEvents().add(newEvent);
				this.balanceCache.put(key, newBalanceDataFromDump.get(key));
			}
		}
		log.info("balance cache update complete");
		
		log.info("updating exp cache");
		Map<String, ExpEvent> newExpDataFromDump = this.dumpDataProvider.getHighExpDump();
		Map<String, ValueDifference<ExpEvent>> expDelta = Maps.difference(this.expCache, newExpDataFromDump).entriesDiffering();
		OtherPlayerExpEvent expEvents = new OtherPlayerExpEvent(new ArrayList<ExpEvent>());
		//find difference in xp
		for(String key: expDelta.keySet()) {
			ExpEvent newEvent = expDelta.get(key).rightValue();
			expEvents.getExpEvents().add(newEvent);
			this.expCache.put(key, newEvent);
		}
		data.add(expEvents);
		
		//find missing players
		for(String key : newExpDataFromDump.keySet()) {
			if(!this.expCache.containsKey(key)) {
				ExpEvent newEvent = newExpDataFromDump.get(key);
				expEvents.getExpEvents().add(newEvent);
				this.expCache.put(key, newEvent);
			}
		}
		log.info("exp cache update complete");
		
		log.info("updating last active cache");
		Map<String, Date> newLastActiveFromDump = this.dumpDataProvider.getLastActiveDump();
		Map<String, ValueDifference<Date>> lastActiveDelta = Maps.difference(this.lastActiveCache, newLastActiveFromDump).entriesDiffering();
		//find difference in last Active
		for(String key: lastActiveDelta.keySet()) {
			LastActiveEvent newEvent = new LastActiveEvent(key, lastActiveDelta.get(key).rightValue());
			data.add(newEvent);
			//update cache with new data
			this.lastActiveCache.put(key, lastActiveDelta.get(key).rightValue());
		}
		
		//find missing players
		for(String key: newLastActiveFromDump.keySet()) {
			if(!this.lastActiveCache.containsKey(key)) {
				LastActiveEvent newEvent = new LastActiveEvent(key, newLastActiveFromDump.get(key));
				data.add(newEvent);
				this.lastActiveCache.put(key, newLastActiveFromDump.get(key));
			}
		}
		log.info("last active cache update complete");
		
		return data;
	}
	
	public GlobalGilHistory recalculateGlobalGil() {
		Long globalGilCount = this.balanceCache.keySet().stream().map(player -> this.balanceCache.get(player)).mapToLong(balanceInt -> balanceInt.longValue()).sum();
		Integer globalPlayerCount = this.balanceCache.keySet().size();
		
		SimpleDateFormat sdf = new SimpleDateFormat(GlobalGilHistory.dateFormatString);
		String currentDateString = sdf.format(new Date());
		GlobalGilHistory globalGilHistory = new GlobalGilHistory(currentDateString, globalGilCount, globalPlayerCount);
		
		return globalGilHistory;
	}
	
	public Date getLastActiveDateFromCache(String player) {
		Date date = this.lastActiveCache.get(player);
		return date;
	}
	
	public Integer getBalanceFromCache(String player) {
		Integer balance = this.balanceCache.get(player);
		return balance;
	}
	
	public Set<String> getBotNames() {
		Set<String> botNames = this.botCache;
		return botNames;
	}
	
	public TimerTask getDataUpdateTask() {
		return new GenerateDataUpdateFromDump(this.eventRouter, this);
	}
	
	public TimerTask getGlobalGilUpdateTask() {
		return new UpdateGlobalGilCount(this.eventRouter, this);
	}
	
	public void updateBalanceCache(BalanceEvent event) {
		this.balanceCache.put(event.getPlayer(), event.getAmount());
	}

	@SneakyThrows
	public Map<String, Integer> getHighScoreDump() {
		Map<String, Integer> data = new HashMap<>();
		
		Resource resource = new UrlResource(DUMP_HIGH_SCORE_URL);
		try(BufferedReader highScoreReader = this.dumpResourceManager.openDumpResource(resource)) {
			String line;
			highScoreReader.readLine(); //ignore the header
			
			while((line = highScoreReader.readLine()) != null) {
				Integer position = Integer.valueOf(StringUtils.substringBefore(line, ". "));
				String username = StringUtils.trim(StringUtils.lowerCase(StringUtils.substringBetween(line, ". ", ":")));
				Integer value = Integer.valueOf(StringUtils.replace(StringUtils.substringBetween(line, ": ", "G"), ",", ""));
				data.put(username, value);
				this.leaderboard.put(username, position);
			}
		}
		
		return data;
	}

	@SneakyThrows
	public Collection<Music> getPlaylist() {
		Set<Music> musicSet = new HashSet<>();
		
		Resource resource = new UrlResource(DUMP_PLAYLIST_URL);
		StringBuilder xmlData = new StringBuilder();
		String line;
		try(BufferedReader musicReader = this.dumpResourceManager.openDumpResource(resource)) {
			while((line = musicReader.readLine()) != null) {
				xmlData.append(line);
			}
		}
		
		DocumentBuilderFactory dbFactory = DocumentBuilderFactory.newInstance();
		DocumentBuilder dBuilder = dbFactory.newDocumentBuilder();
		Document doc = dBuilder.parse(new InputSource(new StringReader(xmlData.toString())));
		doc.getDocumentElement().normalize();
		
		NodeList leafs = doc.getElementsByTagName("leaf");
		for(int i = 0; i < leafs.getLength(); i++) {
			Node node = leafs.item(i);
			if(node.getNodeType() == Node.ELEMENT_NODE) {
				Element element = (Element) node;
				String name = element.getAttribute("uri");
				name = StringUtils.substringAfterLast(name, "/");
				name = StringUtils.substringBefore(name, ".mp3");
				name = URLDecoder.decode(name, StandardCharsets.UTF_8.toString());
				musicSet.add(new Music(name, element.getAttribute("id"), element.getAttribute("duration")));
			}
		}
		
		Collection<Music> musicList = musicSet.stream().collect(Collectors.toList()).stream().sorted().collect(Collectors.toList());
		
		return musicList;
	}
	
	
}

@Slf4j
class GenerateDataUpdateFromDump extends TimerTask {

	private Router<BattleGroundEvent> routerRef;
	private DumpService dumpServiceRef;
	
	public GenerateDataUpdateFromDump(Router<BattleGroundEvent> routerRef, DumpService dumpServiceRef) {
		this.routerRef = routerRef;
		this.dumpServiceRef = dumpServiceRef;
	}
	
	@Override
	public void run() {
		log.debug("updating data from dump");
		Collection<BattleGroundEvent> events = this.dumpServiceRef.getUpdatesFromDumpService();
		events.stream().forEach(event -> log.info("Found event from Dump: {} with data: {}", event.getEventType().getEventStringName(), event.toString()));
		this.routerRef.sendAllDataToQueues(events);
		return;
	}
	
}

@Slf4j
class UpdateGlobalGilCount extends TimerTask {
	private Router<BattleGroundEvent> routerRef;
	private DumpService dumpServiceRef;
	
	public UpdateGlobalGilCount(Router<BattleGroundEvent> routerRef, DumpService dumpServiceRef) {
		this.routerRef = routerRef;
		this.dumpServiceRef = dumpServiceRef;
	}
	
	@Override
	public void run() {
		log.debug("updating global gil count");
		GlobalGilHistory globalGilCount = this.dumpServiceRef.recalculateGlobalGil();
		BattleGroundEvent globalGilCountEvent = new GlobalGilHistoryUpdateEvent(globalGilCount);
		log.info("Found event from Dump: {} with data: {}", globalGilCountEvent.getEventType().getEventStringName(), globalGilCount.toString());
		this.routerRef.sendDataToQueues(globalGilCountEvent);
	}
}
