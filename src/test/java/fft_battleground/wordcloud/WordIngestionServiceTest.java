package fft_battleground.wordcloud;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import fft_battleground.repo.model.WordCountKey;

/**
 * Buffer semantics: ingest() accumulates globally AND per player without
 * touching the repository; flush() drains the buffer into one batched
 * applyIncrements call and clears it (second flush is a no-op).
 */
public class WordIngestionServiceTest {

	private WordIngestionService buildService(WordCountRepository repository) {
		WordIngestionService service = new WordIngestionService();
		ReflectionTestUtils.setField(service, "wordCountRepository", repository);
		return service;
	}

	@Test
	public void ingestBuffersGloballyAndPerPlayerThenFlushBatchesOnce() {
		WordCountRepository repository = mock(WordCountRepository.class);
		WordIngestionService service = this.buildService(repository);

		service.ingest("Chatterbox", "gil gil champion");
		service.ingest("chatterbox", "champion");
		service.ingest("fftbattleground", "system words should never count");
		service.ingest("someone", "!bet 100 red");

		service.flush();

		ArgumentCaptor<Map<WordCountKey, Integer>> wordsCaptor = ArgumentCaptor.forClass(Map.class);
		ArgumentCaptor<Map<String, Integer>> playersCaptor = ArgumentCaptor.forClass(Map.class);
		verify(repository).applyIncrements(wordsCaptor.capture(), playersCaptor.capture());

		Map<WordCountKey, Integer> words = wordsCaptor.getValue();
		assertEquals(Integer.valueOf(2), countOf(words, "global", "gil"), "global gil count");
		assertEquals(Integer.valueOf(2), countOf(words, "global", "champion"), "global champion count");
		assertEquals(Integer.valueOf(2), countOf(words, "player:chatterbox", "gil"), "player gil count");
		assertEquals(Integer.valueOf(2), countOf(words, "player:chatterbox", "champion"), "player champion count");
		assertEquals(Integer.valueOf(2), playersCaptor.getValue().get("chatterbox"), "one count per ingested message");

		//buffer drained: a second flush with no new ingestion is a no-op
		service.flush();
		verify(repository, org.mockito.Mockito.times(1)).applyIncrements(anyMap(), anyMap());
	}

	@Test
	public void flushWithEmptyBufferNeverTouchesStorage() {
		WordCountRepository repository = mock(WordCountRepository.class);
		WordIngestionService service = this.buildService(repository);
		service.flush();
		verify(repository, never()).applyIncrements(anyMap(), anyMap());
	}

	@Test
	public void emoteOnlyMessagesDoNotCountTowardThePlayerThreshold() {
		WordCountRepository repository = mock(WordCountRepository.class);
		WordIngestionService service = this.buildService(repository);

		service.ingest("quietone", "!!! :) :O");
		service.flush();

		//nothing buffered means storage is never touched at all
		verify(repository, never()).applyIncrements(anyMap(), anyMap());
	}

	@Test
	public void twitchNativeEmoteTokensAreDroppedForThatMessage() {
		WordCountRepository repository = mock(WordCountRepository.class);
		WordIngestionService service = this.buildService(repository);

		//the tag says KEKW and CiGrip are emotes IN THIS MESSAGE — exact drops, no dictionary needed
		service.ingest("someplayer", "KEKW that champion CiGrip gil", Set.of("KEKW", "CiGrip"));
		service.flush();

		ArgumentCaptor<Map<WordCountKey, Integer>> wordsCaptor = ArgumentCaptor.forClass(Map.class);
		verify(repository).applyIncrements(wordsCaptor.capture(), anyMap());
		Map<WordCountKey, Integer> words = wordsCaptor.getValue();
		assertEquals(Integer.valueOf(1), countOf(words, "global", "champion"), "normal words kept");
		assertEquals(Integer.valueOf(1), countOf(words, "global", "gil"), "normal words kept");
		assertEquals(0, countOf(words, "global", "kekw"), "tagged emote dropped");
		assertEquals(0, countOf(words, "global", "cigrip"), "tagged emote dropped");
	}

	@Test
	public void dictionaryEmotesAreDroppedAcrossAllMessages() {
		WordCountRepository repository = mock(WordCountRepository.class);
		WordIngestionService service = this.buildService(repository);
		EmoteDictionaryService dictionary = mock(EmoteDictionaryService.class);
		when(dictionary.isEmote(org.mockito.ArgumentMatchers.anyString())).thenReturn(false);
		when(dictionary.isEmote("zrehplar")).thenReturn(true); // FFZ emote, no IRC tag ever carries it
		ReflectionTestUtils.setField(service, "emoteDictionary", dictionary);

		service.ingest("someplayer", "ZrehplaR realword", Set.of());
		service.flush();

		ArgumentCaptor<Map<WordCountKey, Integer>> wordsCaptor = ArgumentCaptor.forClass(Map.class);
		verify(repository).applyIncrements(wordsCaptor.capture(), anyMap());
		assertEquals(0, countOf(wordsCaptor.getValue(), "global", "zrehplar"), "dictionary emote dropped");
		assertEquals(1, countOf(wordsCaptor.getValue(), "global", "realword"), "non-emote kept");
	}

	@Test
	public void allEmoteMessagesDoNotCountTowardTheThreshold() {
		WordCountRepository repository = mock(WordCountRepository.class);
		WordIngestionService service = this.buildService(repository);

		service.ingest("emotespammer", "KEKW CiGrip", Set.of("KEKW", "CiGrip"));
		service.flush();

		//every token dropped -> nothing buffered -> storage untouched, no message count
		verify(repository, never()).applyIncrements(anyMap(), anyMap());
	}

	private Integer countOf(Map<WordCountKey, Integer> words, String scope, String word) {
		return words.entrySet().stream()
				.filter(entry -> entry.getKey().getScope().equals(scope) && entry.getKey().getWord().equals(word))
				.map(Map.Entry::getValue).findFirst().orElse(0);
	}

}
