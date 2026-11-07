package fft_battleground.tournament;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.File;
import java.io.FileInputStream;
import java.util.zip.GZIPInputStream;

import org.junit.jupiter.api.Test;

/**
 * The definitive local reproduction the advisor asked for: stream the EXACT
 * staged v2 artifact (120MB .gz) and verify the real aggregates — holdout
 * ~63.01% over ~110k, in-sample flagged larger, 553,866 total. Runs only when
 * the staged file is present on this machine; skipped silently elsewhere.
 */
public class StreamingForecastStatsRealFileTest {

	private static final File STAGED = new File("C:/Projects/FFTBViewer-deploy/models/forecast-history.json.gz");

	@Test
	public void realV2HistoryStreamsToCorrectAggregates() throws Exception {
		assumeTrue(STAGED.isFile(), "staged v2 history not present — skipping");

		long start = System.currentTimeMillis();
		StreamingForecastStats.Result result;
		try (java.io.InputStream in = new GZIPInputStream(new FileInputStream(STAGED))) {
			result = StreamingForecastStats.reduce(in, new com.fasterxml.jackson.databind.ObjectMapper());
		}
		long elapsed = System.currentTimeMillis() - start;

		int total = result.holdoutScored + result.inSampleScored;
		assertEquals(553866, total, "every prediction counted");
		assertEquals(110773, result.holdoutScored, "holdout split per trainer contract");
		double holdoutRate = (double) result.holdoutHits / result.holdoutScored;
		assertTrue(holdoutRate > 0.625 && holdoutRate < 0.635,
				"holdout ~63.01%, was " + holdoutRate);
		assertTrue(result.inSampleScored > result.holdoutScored, "in-sample flagged larger");
		assertEquals("logistic-v2-fullhistory", result.model);
		assertEquals(2, result.modelVersion);
		System.out.println("STREAM-AUDIT parsed " + total + " predictions in " + elapsed + "ms, holdout=" + holdoutRate);
	}
}
