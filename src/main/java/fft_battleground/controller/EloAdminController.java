package fft_battleground.controller;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

import fft_battleground.elo.EloRegradeService;
import fft_battleground.elo.EloRegradeService.RegradeResult;
import fft_battleground.util.GenericResponse;
import springfox.documentation.annotations.ApiIgnore;

/**
 * Post-outage ELO re-grade (owner directive 2026-10-25). Gated like the chat
 * injection endpoint: fft_battleground.eloBackfillKey must be set server-side
 * and matched (constant-time) by the X-Backfill-Key header — empty property
 * fails closed. The bettor tables should be rebuilt (dropped + recreated by
 * the WAR's ddl pass) BEFORE running the regrade.
 */
@Controller
@RequestMapping("/elo/admin")
@ApiIgnore
public class EloAdminController {

	@Value("${fft_battleground.eloBackfillKey:}")
	private String eloBackfillKey;

	@Autowired
	private EloRegradeService eloRegradeService;

	@Autowired
	private fft_battleground.repo.repository.BettorEloRepo bettorEloRepo;

	@Autowired
	private fft_battleground.repo.repository.EloHistoryRepo eloHistoryRepo;

	@ApiIgnore
	@PostMapping("/regrade")
	public @ResponseBody ResponseEntity<GenericResponse<RegradeResult>> regrade(
			@RequestHeader(name = "X-Backfill-Key", required = false) String key,
			@RequestParam(name = "from", required = true) Long fromEpochMs,
			@RequestParam(name = "to", required = true) Long toEpochMs,
			@RequestParam(name = "reset", required = false, defaultValue = "false") Boolean reset) {
		if(StringUtils.isEmpty(this.eloBackfillKey) || !this.constantTimeMatch(this.eloBackfillKey, key)) {
			return GenericResponse.<RegradeResult>createGenericResponseEntity(null, "unauthorized", HttpStatus.UNAUTHORIZED);
		}
		if(fromEpochMs == null || toEpochMs == null || fromEpochMs >= toEpochMs) {
			return GenericResponse.<RegradeResult>createGenericResponseEntity(null, "invalid window", HttpStatus.BAD_REQUEST);
		}
		//sanity: plausible epoch-ms (not ns/us glue), bounded span, not far future
		long min = 1000000000000L; // 2001-09
		long max = System.currentTimeMillis() + 86400000L;
		long digits = String.valueOf(fromEpochMs).length();
		if(fromEpochMs < min || toEpochMs > max || toEpochMs - fromEpochMs > 2L * 366 * 86400 * 1000
				|| digits < 12 || digits > 15) {
			return GenericResponse.<RegradeResult>createGenericResponseEntity(null,
					"window out of range (epoch-ms expected: " + fromEpochMs + " .. " + toEpochMs + ")", HttpStatus.BAD_REQUEST);
		}
		if(Boolean.TRUE.equals(reset)) {
			//full recalibration: wipe both tables, replay under current math
			this.bettorEloRepo.deleteAll();
			this.eloHistoryRepo.deleteAll();
		}
		return GenericResponse.createGenericResponseEntity(this.eloRegradeService.regrade(fromEpochMs, toEpochMs));
	}

	private boolean constantTimeMatch(String expected, String provided) {
		if(provided == null) {
			return false;
		}
		return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), provided.getBytes(StandardCharsets.UTF_8));
	}

}
