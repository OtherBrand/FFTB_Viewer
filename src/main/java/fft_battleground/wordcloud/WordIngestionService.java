package fft_battleground.wordcloud;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import fft_battleground.repo.model.WordCountKey;
import lombok.extern.slf4j.Slf4j;

/**
 * Chat word ingestion (R3a): the EventParser drop path feeds ingest() here;
 * buffering is lock-free (ConcurrentHashMap) so the parser thread never
 * blocks, and a scheduled flush applies the deltas as one batched upsert
 * every 30s. Words are counted globally AND per player.
 */
@Service
@Slf4j
public class WordIngestionService {

	public static final String GLOBAL_SCOPE = "global";
	public static final String PLAYER_SCOPE_PREFIX = "player:";

	@Autowired
	private WordCountRepository wordCountRepository;

	@Autowired
	private EmoteDictionaryService emoteDictionary;

	@Autowired
	private fft_battleground.metrics.StatusService statusService;

	@Autowired
	private fft_battleground.metrics.PipelineErrorReporter pipelineErrorReporter;

	//buffers: key -> pending delta; swapped on flush under the flush lock
	private final Map<WordCountKey, Integer> wordBuffer = new ConcurrentHashMap<>();
	private final Map<String, Integer> playerMessageBuffer = new ConcurrentHashMap<>();

	public void ingest(String username, String message) {
		this.ingest(username, message, java.util.Set.of());
	}

	/** full form: messageEmoteCodes are the Twitch-native emote codes from the IRC tag */
	public void ingest(String username, String message, java.util.Set<String> messageEmoteCodes) {
		try {
			if(!WordCloudFilter.shouldIngest(username, message)) {
				return;
			}
			Set<String> taggedEmotes = messageEmoteCodes != null
					? messageEmoteCodes.stream().map(StringUtils::lowerCase).collect(Collectors.toSet())
					: Set.of();
			List<String> words = WordCloudFilter.normalize(message).stream()
					.filter(word -> !taggedEmotes.contains(word)) // exact for THIS message — zero false positives
					.filter(word -> this.emoteDictionary == null || !this.emoteDictionary.isEmote(word)) // third-party emotes
					.collect(Collectors.toList());
			if(words.isEmpty()) {
				return; //emote/symbol-only
			}
			String player = StringUtils.lowerCase(StringUtils.trim(username));
			LocalDate today = LocalDate.now();
			for(String word : words) {
				this.bump(this.wordBuffer, WordCountKey.forScope(GLOBAL_SCOPE, word, today), 1);
				this.bump(this.wordBuffer, WordCountKey.forScope(PLAYER_SCOPE_PREFIX + player, word, today), 1);
			}
			//message threshold counter: one per ingested message with real words
			this.bump(this.playerMessageBuffer, player, 1);
		} catch(Exception e) {
			log.debug("word ingestion skipped a message: {}", e.getMessage());
		}
	}

	@Scheduled(initialDelay = 30_000L, fixedDelay = 30_000L)
	public void flush() {
		Map<WordCountKey, Integer> words;
		Map<String, Integer> players;
		synchronized(this) {
			if(this.wordBuffer.isEmpty() && this.playerMessageBuffer.isEmpty()) {
				return;
			}
			words = new HashMap<>(this.wordBuffer);
			players = new HashMap<>(this.playerMessageBuffer);
			this.wordBuffer.keySet().removeAll(words.keySet());
			this.playerMessageBuffer.keySet().removeAll(players.keySet());
		}
		try {
			this.wordCountRepository.applyIncrements(words, players);
			if(this.statusService != null) {
				this.statusService.markWordFlush();
			}
		} catch(Exception e) {
			log.error("word count flush failed; buffering deltas will be lost this cycle", e);
			if(this.pipelineErrorReporter != null) {
				this.pipelineErrorReporter.reportPipelineFailure("word-ingestion", "flush failed (" + words.size() + " words lost this cycle)", e);
			}
		}
	}

	private <K> void bump(Map<K, Integer> map, K key, int amount) {
		map.merge(key, amount, Integer::sum);
	}

}
