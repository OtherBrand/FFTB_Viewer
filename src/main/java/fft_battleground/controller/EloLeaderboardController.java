package fft_battleground.controller;

import java.util.ArrayList;
import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

import fft_battleground.controller.response.model.EloLeaderboardEntry;
import fft_battleground.repo.model.BettorElo;
import fft_battleground.repo.model.FighterElo;
import fft_battleground.repo.repository.BettorEloRepo;
import fft_battleground.repo.repository.FighterEloRepo;
import fft_battleground.util.GenericResponse;
import springfox.documentation.annotations.ApiIgnore;

/**
 * Global ELO leaderboards (Bettor + Fighter). Ranked lists exclude
 * provisional players (<20 matches — the ranked query enforces it) and
 * include bots flagged is_bot for the UI to badge or filter.
 */
@Controller
@RequestMapping("/elo")
@ApiIgnore
public class EloLeaderboardController {

	protected static final int DEFAULT_LIMIT = 10000; // serve everyone by default (owner ask 2026-10-31)

	@Autowired
	private BettorEloRepo bettorEloRepo;

	@Autowired
	private FighterEloRepo fighterEloRepo;

	@ApiIgnore
	@GetMapping("/leaderboard")
	public @ResponseBody ResponseEntity<GenericResponse<List<EloLeaderboardEntry>>> bettorLeaderboard(
			@RequestParam(name = "limit", required = false) Integer limit,
			@RequestParam(name = "minMatches", required = false) Integer minMatches) {
		List<BettorElo> ranked = this.bettorEloRepo.findRanked(this.effectiveMinMatches(minMatches),
				PageRequest.of(0, this.boundedLimit(limit)));
		List<EloLeaderboardEntry> entries = new ArrayList<>(ranked.size());
		for(int i = 0; i < ranked.size(); i++) {
			entries.add(this.bettorEntry(i + 1, ranked.get(i)));
		}
		return GenericResponse.createGenericResponseEntity(entries);
	}

	@ApiIgnore
	@GetMapping("/fighterLeaderboard")
	public @ResponseBody ResponseEntity<GenericResponse<List<EloLeaderboardEntry>>> fighterLeaderboard(
			@RequestParam(name = "limit", required = false) Integer limit,
			@RequestParam(name = "minMatches", required = false) Integer minMatches) {
		List<FighterElo> ranked = this.fighterEloRepo.findRanked(this.effectiveMinMatches(minMatches),
				PageRequest.of(0, this.boundedLimit(limit)));
		List<EloLeaderboardEntry> entries = new ArrayList<>(ranked.size());
		for(int i = 0; i < ranked.size(); i++) {
			entries.add(this.fighterEntry(i + 1, ranked.get(i)));
		}
		return GenericResponse.createGenericResponseEntity(entries);
	}

	protected int boundedLimit(Integer limit) {
		if(limit == null || limit <= 0) {
			return DEFAULT_LIMIT; // everyone: the 'engaged' rule is the provisional boundary, not a row cap
		}
		return Math.min(limit, 50000);
	}

	/** the provisional boundary (>=20 matches) IS the owner's 'engaged' threshold; the
	 *  param exists for future flexibility, not because the default needs adjusting */
	protected int effectiveMinMatches(Integer minMatches) {
		return minMatches != null && minMatches >= 0 ? minMatches : 20;
	}

	protected EloLeaderboardEntry bettorEntry(int rank, BettorElo row) {
		EloLeaderboardEntry entry = new EloLeaderboardEntry();
		entry.setRank(rank);
		entry.setPlayer(row.getPlayer());
		entry.setElo(row.getElo());
		entry.setWins(row.getPickWins());
		entry.setLosses(row.getPickLosses());
		entry.setMatches(row.getMatches());
		entry.setBot(row.isBot());
		return entry;
	}

	protected EloLeaderboardEntry fighterEntry(int rank, FighterElo row) {
		EloLeaderboardEntry entry = new EloLeaderboardEntry();
		entry.setRank(rank);
		entry.setPlayer(row.getPlayer());
		entry.setElo(row.getElo());
		entry.setWins(row.getFightWins());
		entry.setLosses(row.getFightLosses());
		entry.setMatches(row.getMatches());
		entry.setBot(row.isBot());
		return entry;
	}

}
