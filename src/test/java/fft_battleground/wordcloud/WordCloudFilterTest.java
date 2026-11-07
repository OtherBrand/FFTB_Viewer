package fft_battleground.wordcloud;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * The R3a exclusion matrix, unit-pinned: system username, !-commands, URLs,
 * pure-numeric, emote/symbol-only — and domain vocabulary deliberately kept.
 */
public class WordCloudFilterTest {

	@Test
	public void systemMessagesAreExcluded() {
		assertFalse(WordCloudFilter.shouldIngest("fftbattleground", "The current track is: Test Song."));
		assertFalse(WordCloudFilter.shouldIngest("FFTBattleground", "anything at all"));
	}

	@Test
	public void commandsAreExcluded() {
		assertFalse(WordCloudFilter.shouldIngest("someplayer", "!bet 100 red"));
		assertFalse(WordCloudFilter.shouldIngest("someplayer", "!fight Squire"));
		assertFalse(WordCloudFilter.shouldIngest("someplayer", "  !skills"));
	}

	@Test
	public void urlsAreExcluded() {
		assertFalse(WordCloudFilter.shouldIngest("someplayer", "check https://example.com/thing"));
		assertFalse(WordCloudFilter.shouldIngest("someplayer", "www.example.com lol"));
	}

	@Test
	public void blankInputIsExcluded() {
		assertFalse(WordCloudFilter.shouldIngest(null, "hello"));
		assertFalse(WordCloudFilter.shouldIngest("someplayer", ""));
		assertFalse(WordCloudFilter.shouldIngest("someplayer", "   "));
	}

	@Test
	public void normalChatterPasses() {
		assertTrue(WordCloudFilter.shouldIngest("someplayer", "that champion fight was wild"));
	}

	@Test
	public void normalizeLowercasesStripsPunctuationAndSplits() {
		List<String> words = WordCloudFilter.normalize("Gil, gil, GIL! The champion's Squire...");
		assertEquals(List.of("gil", "gil", "gil", "champion", "squire"), words);
	}

	@Test
	public void normalizeDropsShortNumericAndStopwords() {
		//numbers are cloud noise even inside real sentences (spec: pure numbers excluded)
		List<String> words = WordCloudFilter.normalize("i am 100 sure it is a KEKW moment 42");
		assertEquals(List.of("sure", "kekw", "moment"), words);
	}

	@Test
	public void normalizeDropsPureSymbolsAndEmotesToEmpty() {
		assertTrue(WordCloudFilter.normalize("!!! :) :O <3 !!!").isEmpty());
		assertTrue(WordCloudFilter.normalize("123 456 789").isEmpty());
	}

	@Test
	public void domainVocabularyIsKept() {
		List<String> words = WordCloudFilter.normalize("champion gil yellow white red squire");
		assertEquals(List.of("champion", "gil", "yellow", "white", "red", "squire"), words);
	}

}
