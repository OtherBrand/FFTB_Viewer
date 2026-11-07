package fft_battleground.controller;

import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import javax.servlet.http.HttpServletRequest;

import org.apache.commons.lang3.StringUtils;
import org.hibernate.Hibernate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.annotation.CacheConfig;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

import fft_battleground.dump.service.BalanceHistoryServiceImpl;
import fft_battleground.exception.TournamentApiException;
import fft_battleground.repo.model.BalanceHistory;
import fft_battleground.repo.model.PlayerRecord;
import fft_battleground.repo.model.PlayerSkills;
import fft_battleground.repo.model.PrestigeSkills;
import fft_battleground.repo.repository.BalanceHistoryRepo;
import fft_battleground.repo.repository.PlayerRecordRepo;
import fft_battleground.repo.util.BalanceType;
import fft_battleground.repo.util.BalanceUpdateSource;
import fft_battleground.reports.model.LeaderboardBalanceData;
import fft_battleground.reports.model.LeaderboardBalanceHistoryEntry;
import fft_battleground.tournament.TournamentService;
import fft_battleground.util.GenericResponse;
import io.swagger.annotations.ApiOperation;
import lombok.extern.slf4j.Slf4j;
import springfox.documentation.annotations.ApiIgnore;

@Controller
@RequestMapping("/api/players")
@Slf4j
@CacheConfig(cacheNames = {"botBalanceHistory", "playerBalanceHistory"})
@ApiIgnore
public class PlayerRecordController {

	protected static final int ELO_HISTORY_WINDOW = 200;

	@Autowired
	private PlayerRecordRepo playerRecordRepo;
	
	@Autowired
	private BalanceHistoryRepo balanceHistoryRepo;

	@Autowired
	private fft_battleground.repo.repository.EloHistoryRepo eloHistoryRepo;

	@Autowired
	private fft_battleground.repo.repository.FighterEloHistoryRepo fighterEloHistoryRepo;
	
	@Autowired
	private TournamentService tournamentService;
	
	@Autowired
	private BalanceHistoryServiceImpl balanceHistoryUtil;
	
	@ApiIgnore
	@GetMapping("/playerRecord/{playerName}")
	@Transactional(propagation=Propagation.REQUIRED)
	public ResponseEntity<GenericResponse<PlayerRecord>> getPlayerData(@PathVariable("playerName") String playerName, HttpServletRequest request) throws TournamentApiException {
		log.info("Player api called for player {}", playerName);
		String idString = StringUtils.lowerCase(playerName);
		Optional<PlayerRecord> maybePlayer =  this.playerRecordRepo.findById(idString);
		if(maybePlayer.isPresent()) {
			//map metadata
			Hibernate.initialize(maybePlayer.get().getPlayerSkills());
			Hibernate.initialize(maybePlayer.get().getPrestigeSkills());
			for(PlayerSkills playerSkill : maybePlayer.get().getPlayerSkills()) {
				playerSkill.setMetadata(this.tournamentService.getCurrentTips().getPlayerSkillMetadata(playerSkill.getSkill()));
			}
			for(PrestigeSkills prestigeSkill : maybePlayer.get().getPrestigeSkills()) {
				prestigeSkill.setMetadata(this.tournamentService.getCurrentTips().getPlayerSkillMetadata(prestigeSkill.getSkill()));
			}
			
			return GenericResponse.createGenericResponseEntity(maybePlayer.get());
		} else {
			return GenericResponse.<PlayerRecord>createGenericResponseEntity(null, "Player could not be found", HttpStatus.NOT_FOUND);
		}
	}
	//ELO history for the player-page card (R4 contract): ascending rating-after points, trailing window
	@ApiIgnore
	@GetMapping("/eloHistory")
	public @ResponseBody ResponseEntity<GenericResponse<List<fft_battleground.controller.response.model.EloHistoryPoint>>>
	getEloHistory(@RequestParam(name="player", required=true) String player) {
		String id = StringUtils.lowerCase(StringUtils.trim(player));
		List<fft_battleground.repo.model.EloHistory> history = this.eloHistoryRepo.findByPlayerOrderByCreateDateTimeAsc(id);
		//cap to the trailing points so the chart payload stays bounded
		List<fft_battleground.repo.model.EloHistory> window = history.size() > ELO_HISTORY_WINDOW
				? history.subList(history.size() - ELO_HISTORY_WINDOW, history.size()) : history;
		List<fft_battleground.controller.response.model.EloHistoryPoint> points = window.stream()
				.map(entry -> new fft_battleground.controller.response.model.EloHistoryPoint(
						entry.getCreateDateTime().getTime(), entry.getRatingAfter()))
				.collect(Collectors.toList());
		return GenericResponse.createGenericResponseEntity(points);
	}

	//fighter ELO history (R4 follow-up): same contract as the bettor chart
	@ApiIgnore
	@GetMapping("/fighterEloHistory")
	public @ResponseBody ResponseEntity<GenericResponse<List<fft_battleground.controller.response.model.EloHistoryPoint>>>
	getFighterEloHistory(@RequestParam(name="player", required=true) String player) {
		String id = StringUtils.lowerCase(StringUtils.trim(player));
		List<fft_battleground.repo.model.FighterEloHistory> history =
				this.fighterEloHistoryRepo.findByPlayerOrderByCreateDateTimeAsc(id);
		List<fft_battleground.repo.model.FighterEloHistory> window = history.size() > ELO_HISTORY_WINDOW
				? history.subList(history.size() - ELO_HISTORY_WINDOW, history.size()) : history;
		List<fft_battleground.controller.response.model.EloHistoryPoint> points = window.stream()
				.map(entry -> new fft_battleground.controller.response.model.EloHistoryPoint(
						entry.getCreateDateTime().getTime(), entry.getRatingAfter()))
				.collect(Collectors.toList());
		return GenericResponse.createGenericResponseEntity(points);
	}

	@ApiIgnore
	@GetMapping("/balanceHistory")	public @ResponseBody ResponseEntity<GenericResponse<LeaderboardBalanceData>>
	getBalanceHistory(@RequestParam(name="player", required=true) String player, @RequestParam(name="count", required=true) Integer count) {
		LinkedList<BalanceHistory> balanceHistories = new LinkedList<>(this.balanceHistoryRepo.getTournamentBalanceHistoryFromPastWeek(player));
		
		if(balanceHistories.size() < count) {
			for(int i = 0; i < balanceHistories.size() - count; i++) {
				balanceHistories.push(new BalanceHistory(player, 0, BalanceType.DUMP, BalanceUpdateSource.NONE));
			}
		}
		
		List<LeaderboardBalanceHistoryEntry> entries = Arrays.asList(new LeaderboardBalanceHistoryEntry[] {new LeaderboardBalanceHistoryEntry(player, balanceHistories)});
		
		LeaderboardBalanceData data = this.balanceHistoryUtil.getLabelsAndSetRelevantBalanceHistories(entries, count);
		
		return GenericResponse.createGenericResponseEntity(data);
	}
	
	@ApiOperation(value="Returns a list of all players recorded in the Viewer database")
	@GetMapping("/playerList")
	public ResponseEntity<GenericResponse<List<String>>> playerNames() {
		List<String> playerNames = this.playerRecordRepo.findPlayerNames().stream().map(player -> StringUtils.trim(StringUtils.lowerCase(player)))
				.collect(Collectors.toSet()).stream().sorted().collect(Collectors.toList());
		return GenericResponse.createGenericResponseEntity(playerNames);
	}
	
}
