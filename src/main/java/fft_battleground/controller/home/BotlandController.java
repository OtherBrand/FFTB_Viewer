package fft_battleground.controller.home;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.apache.commons.lang3.StringUtils;

import javax.servlet.http.HttpServletRequest;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import fft_battleground.botland.BetBotFactory;
import fft_battleground.botland.bot.exception.BotConfigException;
import fft_battleground.botland.bot.genetic.GeneFileCache;
import fft_battleground.botland.bot.genetic.model.GeneTrainerV2BotData;
import fft_battleground.botland.bot.genetic.model.ResultData;
import fft_battleground.botland.personality.PersonalityModuleFactory;
import fft_battleground.controller.response.model.botland.BotlandData;
import fft_battleground.controller.response.model.botland.BotProgressData;
import fft_battleground.exception.CacheMissException;
import fft_battleground.metrics.AccessTracker;
import fft_battleground.repo.model.BotHourlyData;
import fft_battleground.repo.model.Bots;
import fft_battleground.repo.repository.BotsHourlyDataRepo;
import fft_battleground.repo.repository.BotsRepo;
import fft_battleground.repo.repository.BalanceHistoryRepo;
import fft_battleground.reports.BotlandLeaderboardReportGenerator;
import fft_battleground.reports.model.BotlandLeaderboard;
import fft_battleground.reports.model.BotlandLeaderboardEntry;
import fft_battleground.util.GenericElementOrdering;
import fft_battleground.util.GenericResponse;
import springfox.documentation.annotations.ApiIgnore;

@RequestMapping("/")
@Controller
public class BotlandController extends AbstractHomeController {

	@Autowired
	private BetBotFactory betBotFactory;
	
	@Autowired
	private BotlandLeaderboardReportGenerator botlandLeaderboardReportGenerator;
	
	@Autowired
	private PersonalityModuleFactory personalityModuleFactory;
	
	@Autowired
	private BotsRepo botsRepo;
	
	@Autowired
	private BotsHourlyDataRepo botsHourlyDataRepo;

	@Autowired
	private BalanceHistoryRepo balanceHistoryRepo;

	@Autowired
	public BotlandController(AccessTracker accessTracker) {
		super(accessTracker);
	}
	
	@ApiIgnore
	@GetMapping("/botland")
	public @ResponseBody ResponseEntity<GenericResponse<BotlandData>> botland(@RequestParam(name="refresh", required=false, defaultValue="false") Boolean refresh, 
			@RequestHeader(value = "User-Agent") String userAgent, Model model, HttpServletRequest request) {
		if(!refresh) {
			this.logAccess("botland", userAgent, request);
		}
		List<Bots> botData = this.botsRepo.getBotsForToday();
		//ghost bots (visible="false", e.g. the advisor) stay off the public roster
		Set<String> hiddenBots = this.betBotFactory.getHiddenBotNames();
		botData.removeIf(bot -> hiddenBots.contains(StringUtils.lowerCase(bot.getPlayer())));
		Collections.sort(botData, Collections.reverseOrder());
		
		List<String> botNames = botData.parallelStream().map(bots -> bots.getPlayer()).collect(Collectors.toList());
		Map<String, List<GenericElementOrdering<BotHourlyData>>> botHourlyDataMap = this.botsHourlyDataRepo.getOrderedBotHourlyDataForBots(botNames);
		
		Map<String, String> botResponses = this.personalityModuleFactory.getLastBotPersonalityResponses();

		//filter the config inventory through a copy — the factory's map is cached and shared
		Map<String, fft_battleground.botland.model.BotData> botConfigData = this.betBotFactory.getBotDataMap().entrySet().stream()
				.filter(entry -> !hiddenBots.contains(StringUtils.lowerCase(entry.getKey())))
				.collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));

		BotlandData data = new BotlandData(botData, this.betBotFactory.getIrcName(), this.betBotFactory.getPrimaryBotName(), botConfigData,
				botHourlyDataMap, botResponses);
		return GenericResponse.createGenericResponseEntity(data);
	}

	//real-account progress for the botland progress tab: SQL rollup of the player's full balance_history
	//(all update types) rather than bet-level rows. Daily = all days; hourly = trailing 7 days
	@ApiIgnore
	@GetMapping("/botland/progress")
	public @ResponseBody ResponseEntity<GenericResponse<BotProgressData>> botlandProgress(@RequestParam(name="player", required=true) String player) {
		BotProgressData data = new BotProgressData(
				this.balanceHistoryRepo.getDailyBalanceBuckets(player),
				this.balanceHistoryRepo.getHourlyBalanceBuckets(player));
		return GenericResponse.createGenericResponseEntity(data);
	}

	@GetMapping("/botlandleaderboard")
	@ApiIgnore
	public ResponseEntity<GenericResponse<BotlandLeaderboard>> getBotlandLeaderboard() throws CacheMissException {
		return GenericResponse.createGenericResponseEntity(this.botlandLeaderboardReportGenerator.getReport());
	}
	
	@GetMapping("/botlandleaderboard/{botName}")
	@ApiIgnore
	public ResponseEntity<GenericResponse<BotlandLeaderboardEntry>> getBotlandLeaderboard(@PathVariable("botName") String botName) throws CacheMissException {
		return GenericResponse.createGenericResponseEntity(this.botlandLeaderboardReportGenerator.getReport().getLeaderboardEntryForPlayer(botName));
	}
	
	@GetMapping("/genefile/{genefile}")
	public ResponseEntity<String> getGeneFile(@PathVariable("genefile") String genefile) throws BotConfigException, JsonProcessingException {
		GeneFileCache<ResultData> v1Cache = this.betBotFactory.getGeneFileCache();
		GeneFileCache<GeneTrainerV2BotData> v2Cache = this.betBotFactory.getGeneFileV2Cache();
		GeneFileCache<GeneTrainerV2BotData> v3Cache = this.betBotFactory.getGeneFileV3Cache();
		if(v1Cache.hasFile(genefile)) {
			ResultData genefileData = v1Cache.getGeneData(genefile);
			ObjectMapper mapper = new ObjectMapper();
			String json = mapper.writeValueAsString(genefileData);
			return new ResponseEntity<String>(json, HttpStatus.OK);
		} else if(v2Cache.hasFile(genefile)) {
			GeneTrainerV2BotData data = v2Cache.getGeneData(genefile);
			ObjectMapper mapper = new ObjectMapper();
			String json = mapper.writeValueAsString(data);
			return new ResponseEntity<String>(json, HttpStatus.OK);
		} else if(v3Cache.hasFile(genefile)) {
			GeneTrainerV2BotData data = v3Cache.getGeneData(genefile);
			ObjectMapper mapper = new ObjectMapper();
			String json = mapper.writeValueAsString(data);
			return new ResponseEntity<String>(json, HttpStatus.OK);
		} else {
			return new ResponseEntity<String>("", HttpStatus.NOT_FOUND);
		}
	}
}
