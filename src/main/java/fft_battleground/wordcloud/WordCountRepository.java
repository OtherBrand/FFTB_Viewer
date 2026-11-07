package fft_battleground.wordcloud;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import fft_battleground.repo.model.PlayerChatStats;
import fft_battleground.repo.model.WordCountKey;

/**
 * Storage hatch for the word cloud (R3a owner decision, 2026-10-24): stays
 * in Postgres, but ALL access goes through this interface so a future store
 * swap is one class. PostgresWordCountRepository is the only implementation.
 */
public interface WordCountRepository {

	/** apply buffered deltas in one transaction: upsert word counts + bump player message counters */
	public void applyIncrements(Map<WordCountKey, Integer> wordDeltas, Map<String, Integer> playerMessageDeltas);

	/** top (word, total) pairs for a scope since a date, largest first, capped */
	public List<WordCountEntry> topWords(String scope, LocalDate since, int limit);

	/** lifetime ingested message count for a player (0 when unknown) */
	public int lifetimeMessageCount(String player);

	/** growth control: drop count<=1 rows older than the cutoff; returns rows removed */
	public int pruneSingleOccurrenceBefore(LocalDate cutoff);

	/** emote hygiene: remove all rows for the given words across every scope; returns rows removed */
	public int purgeWords(java.util.Collection<String> words);

	/** simple {word, count} pair served by the endpoint */
	public static class WordCountEntry {
		public final String word;
		public final long count;

		public WordCountEntry(String word, long count) {
			this.word = word;
			this.count = count;
		}
	}

}
