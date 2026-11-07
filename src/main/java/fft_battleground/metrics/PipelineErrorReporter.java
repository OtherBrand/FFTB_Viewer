package fft_battleground.metrics;

import java.util.Date;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import fft_battleground.repo.model.ErrorMessageEntry;
import fft_battleground.repo.repository.ErrorMessageEntryRepo;
import lombok.extern.slf4j.Slf4j;

/**
 * Owner directive (2026-10-25, after the 131-silent-failure ELO outage):
 * data-pipeline catch blocks must reach error_message — journal-only logging
 * made a full-day outage invisible to every monitoring surface. Rows are
 * written DIRECTLY via the repo (webhook-independent: blank webhook URLs in
 * any environment must never suppress persistence). Dedupe: identical
 * messages write the first occurrence + a rollup row every ~30 repeats, so a
 * persistent failure yields ~2 rows/hour, not 131/day.
 */
@Service
@Slf4j
public class PipelineErrorReporter {

	protected static final int ROLLUP_EVERY = 30;

	@Autowired
	private ErrorMessageEntryRepo errorMessageEntryRepo;

	private final Map<String, Counter> recentOccurrences = new ConcurrentHashMap<>();

	protected static class Counter {
		final AtomicInteger repeats = new AtomicInteger(0);
		volatile long firstAt = System.currentTimeMillis();
	}

	public void reportPipelineFailure(String source, String context, Throwable cause) {
		String message = source + (StringUtils.isNotBlank(context) ? ": " + context : "")
				+ (cause != null && cause.getMessage() != null ? " — " + cause.getMessage() : "");
		try {
			Counter counter = this.recentOccurrences.computeIfAbsent(source + "|" + message, key -> new Counter());
			int seen = counter.repeats.incrementAndGet();
			if(seen == 1) {
				counter.firstAt = System.currentTimeMillis();
				this.persist(message + " [first occurrence]");
			} else if(seen % ROLLUP_EVERY == 0) {
				this.persist(message + " [repeating: " + seen + " occurrences since "
						+ new Date(counter.firstAt) + "]");
			}
		} catch(Exception e) {
			//never let failure-reporting itself break the calling pipeline
			log.error("failed to persist pipeline failure report for {}", source, e);
		}
	}

	private void persist(String message) {
		ErrorMessageEntry entry = new ErrorMessageEntry(new Exception(message));
		this.errorMessageEntryRepo.saveAndFlush(entry);
	}

}
