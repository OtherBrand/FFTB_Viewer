package fft_battleground.controller;

import java.time.LocalDate;
import java.util.List;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

import fft_battleground.util.GenericResponse;
import fft_battleground.wordcloud.WordCountRepository;
import fft_battleground.wordcloud.WordCountRepository.WordCountEntry;
import fft_battleground.wordcloud.WordIngestionService;
import springfox.documentation.annotations.ApiIgnore;

/**
 * Word cloud endpoint (R3a): GET /chat/words?scope=global|player&player=X
 * &period=day|week|alltime — top 200 {word, count}. Player clouds require
 * ≥20 lifetime ingested messages (privacy / mass-testing guard): below the
 * threshold the data is empty and the message says why.
 */
@Controller
@RequestMapping("/chat/words")
@ApiIgnore
public class ChatWordsController {

	protected static final int TOP_WORDS_LIMIT = 200;
	protected static final int PLAYER_MESSAGE_THRESHOLD = 20;

	@Autowired
	private WordCountRepository wordCountRepository;

	@ApiIgnore
	@GetMapping
	public @ResponseBody ResponseEntity<GenericResponse<List<WordCountEntry>>> getWords(
			@RequestParam(name = "scope", required = false, defaultValue = "global") String scope,
			@RequestParam(name = "player", required = false) String player,
			@RequestParam(name = "period", required = false, defaultValue = "week") String period) {

		String effectiveScope;
		if("player".equalsIgnoreCase(scope)) {
			if(StringUtils.isBlank(player)) {
				return GenericResponse.<List<WordCountEntry>>createGenericResponseEntity(null,
						"scope=player requires a player parameter", HttpStatus.BAD_REQUEST);
			}
			String lowerPlayer = StringUtils.lowerCase(StringUtils.trim(player));
			int lifetime = this.wordCountRepository.lifetimeMessageCount(lowerPlayer);
			if(lifetime < PLAYER_MESSAGE_THRESHOLD) {
				return GenericResponse.createGenericResponseEntity(List.of(),
						"player below " + PLAYER_MESSAGE_THRESHOLD + " message threshold", HttpStatus.OK);
			}
			effectiveScope = WordIngestionService.PLAYER_SCOPE_PREFIX + lowerPlayer;
		} else {
			effectiveScope = WordIngestionService.GLOBAL_SCOPE;
		}

		LocalDate since;
		if("day".equalsIgnoreCase(period)) {
			since = LocalDate.now();
		} else if("alltime".equalsIgnoreCase(period)) {
			since = LocalDate.of(1970, 1, 1);
		} else {
			since = LocalDate.now().minusDays(7); // week default
		}

		List<WordCountEntry> entries = this.wordCountRepository.topWords(effectiveScope, since, TOP_WORDS_LIMIT);
		return GenericResponse.createGenericResponseEntity(entries);
	}

}
