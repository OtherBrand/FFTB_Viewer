package fft_battleground.controller;

import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

import fft_battleground.controller.response.model.tournaments.TournamentHistoryModels.ForecastStats;
import fft_battleground.controller.response.model.tournaments.TournamentHistoryModels.ReignRecord;
import fft_battleground.controller.response.model.tournaments.TournamentHistoryModels.TournamentDetail;
import fft_battleground.controller.response.model.tournaments.TournamentHistoryModels.TournamentSummary;
import fft_battleground.exception.TournamentApiException;
import fft_battleground.tournament.TournamentHistoryService;
import fft_battleground.controller.response.model.tournaments.TournamentHistoryModels.UnitRecord;
import fft_battleground.util.GenericResponse;
import springfox.documentation.annotations.ApiIgnore;

/**
 * Tournament + champion views (frozen contract 2026-10-25). Degradations are
 * by design: no per-match unit lineups (trainer artifact is the v2 unlock),
 * optional-absent owner/level/fallen/reignStats, avgBackerElo null for
 * historical reigns.
 */
@Controller
@ApiIgnore
public class TournamentHistoryController {

	@Autowired
	private TournamentHistoryService tournamentHistoryService;

	@ApiIgnore
	@GetMapping("/tournaments/recent")
	public @ResponseBody ResponseEntity<GenericResponse<List<TournamentSummary>>> recentTournaments(
			@RequestParam(name = "page", required = false, defaultValue = "0") Integer page,
			@RequestParam(name = "pageSize", required = false, defaultValue = "5") Integer pageSize) {
		int boundedSize = Math.min(Math.max(pageSize != null ? pageSize : 5, 1), 25);
		return GenericResponse.createGenericResponseEntity(
				this.tournamentHistoryService.recentSummaries(Math.max(page != null ? page : 0, 0), boundedSize));
	}

	@ApiIgnore
	@GetMapping("/tournaments/{id}")
	public @ResponseBody ResponseEntity<GenericResponse<TournamentDetail>> tournamentDetail(@PathVariable("id") Long id) {
		try {
			TournamentDetail detail = this.tournamentHistoryService.detail(id);
			if(detail == null) {
				return GenericResponse.<TournamentDetail>createGenericResponseEntity(null, "tournament not found", HttpStatus.NOT_FOUND);
			}
			return GenericResponse.createGenericResponseEntity(detail);
		} catch(TournamentApiException e) {
			return GenericResponse.<TournamentDetail>createGenericResponseEntity(null, "tournament api unavailable", HttpStatus.SERVICE_UNAVAILABLE);
		}
	}

	@ApiIgnore
	@GetMapping("/champions/reigns")
	public @ResponseBody ResponseEntity<GenericResponse<List<ReignRecord>>> championReigns() {
		return GenericResponse.createGenericResponseEntity(this.tournamentHistoryService.reigns());
	}

	@ApiIgnore
	@GetMapping("/champions/reigns/{id}/roster")
	public @ResponseBody ResponseEntity<GenericResponse<List<UnitRecord>>> reignRoster(@PathVariable("id") String reignId) {
		try {
			List<UnitRecord> roster = this.tournamentHistoryService.reignRoster(reignId);
			if(roster == null) {
				return GenericResponse.<List<UnitRecord>>createGenericResponseEntity(null, "reign not found", HttpStatus.NOT_FOUND);
			}
			return GenericResponse.createGenericResponseEntity(roster);
		} catch(TournamentApiException e) {
			return GenericResponse.<List<UnitRecord>>createGenericResponseEntity(null, "tournament api unavailable", HttpStatus.SERVICE_UNAVAILABLE);
		}
	}

	@ApiIgnore
	@GetMapping("/forecast/stats")
	public @ResponseBody ResponseEntity<GenericResponse<ForecastStats>> forecastStats() {
		return GenericResponse.createGenericResponseEntity(this.tournamentHistoryService.forecastStats());
	}

}
