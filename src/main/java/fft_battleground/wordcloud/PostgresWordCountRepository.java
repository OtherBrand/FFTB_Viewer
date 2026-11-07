package fft_battleground.wordcloud;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import fft_battleground.repo.model.PlayerChatStats;
import fft_battleground.repo.model.WordCount;
import fft_battleground.repo.model.WordCountKey;
import fft_battleground.repo.repository.PlayerChatStatsRepo;
import fft_battleground.repo.repository.WordCountJpaRepo;

@Repository
public class PostgresWordCountRepository implements WordCountRepository {

	@Autowired
	private WordCountJpaRepo wordCountRepo;

	@Autowired
	private PlayerChatStatsRepo playerChatStatsRepo;

	@Override
	@Transactional
	public void applyIncrements(Map<WordCountKey, Integer> wordDeltas, Map<String, Integer> playerMessageDeltas) {
		List<WordCount> toSave = new ArrayList<>();
		//group deltas by key so a key appearing in one flush writes once (idempotent upsert)
		Map<WordCountKey, Integer> merged = new HashMap<>(wordDeltas);
		for(Map.Entry<WordCountKey, Integer> entry : merged.entrySet()) {
			WordCount existing = this.wordCountRepo.findById(entry.getKey()).orElse(null);
			if(existing != null) {
				existing.setCount(existing.getCount() + entry.getValue());
				toSave.add(existing);
			} else {
				toSave.add(new WordCount(entry.getKey(), entry.getValue()));
			}
		}
		if(!toSave.isEmpty()) {
			this.wordCountRepo.saveAll(toSave);
		}

		for(Map.Entry<String, Integer> entry : playerMessageDeltas.entrySet()) {
			PlayerChatStats stats = this.playerChatStatsRepo.findById(entry.getKey()).orElse(null);
			if(stats != null) {
				stats.setMessageCount(stats.getMessageCount() + entry.getValue());
				this.playerChatStatsRepo.saveAndFlush(stats);
			} else {
				this.playerChatStatsRepo.saveAndFlush(new PlayerChatStats(entry.getKey(), entry.getValue()));
			}
		}
	}

	@Override
	public List<WordCountEntry> topWords(String scope, LocalDate since, int limit) {
		List<Object[]> rows = this.wordCountRepo.topWordsForScope(scope, since, PageRequest.of(0, limit));
		List<WordCountEntry> entries = new ArrayList<>(rows.size());
		for(Object[] row : rows) {
			entries.add(new WordCountEntry((String) row[0], ((Number) row[1]).longValue()));
		}
		return entries;
	}

	@Override
	public int lifetimeMessageCount(String player) {
		PlayerChatStats stats = this.playerChatStatsRepo.findById(player).orElse(null);
		return stats != null ? stats.getMessageCount() : 0;
	}

	@Override
	@Transactional
	public int pruneSingleOccurrenceBefore(LocalDate cutoff) {
		return this.wordCountRepo.pruneSingleOccurrenceBefore(cutoff);
	}

	@Override
	@Transactional
	public int purgeWords(java.util.Collection<String> words) {
		if(words == null || words.isEmpty()) {
			return 0;
		}
		//chunked: IN-clause sizes stay modest even for a full dictionary
		java.util.List<String> all = new java.util.ArrayList<>(words);
		int removed = 0;
		for(int i = 0; i < all.size(); i += 500) {
			removed += this.wordCountRepo.purgeWords(all.subList(i, Math.min(i + 500, all.size())));
		}
		return removed;
	}

}
