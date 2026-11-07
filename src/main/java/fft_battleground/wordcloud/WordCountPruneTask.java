package fft_battleground.wordcloud;

import java.time.LocalDate;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;

/**
 * Growth control (R3a): single-occurrence words older than 30 days carry no
 * cloud value — pruned nightly in the 04:17 slot alongside the backups.
 */
@Component
@Slf4j
public class WordCountPruneTask {

	@Autowired
	private WordCountRepository wordCountRepository;

	@Scheduled(cron = "0 17 4 * * ?")
	public void prune() {
		try {
			int removed = this.wordCountRepository.pruneSingleOccurrenceBefore(LocalDate.now().minusDays(30));
			if(removed > 0) {
				log.info("word count prune removed {} stale single-occurrence rows", removed);
			}
		} catch(Exception e) {
			log.error("word count prune failed", e);
		}
	}

}
