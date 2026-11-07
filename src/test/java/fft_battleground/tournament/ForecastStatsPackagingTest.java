package fft_battleground.tournament;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.util.zip.GZIPOutputStream;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import fft_battleground.controller.response.model.tournaments.TournamentHistoryModels.ForecastStats;

/**
 * v2 history packaging (2026-11-03): 120MB ships as an external file beside the
 * WAR (raw or .gz auto-detected), parsed ONCE and cached — never per request,
 * never inside the 300MB-bloated WAR. Classpath v1 stays the last-resort fallback.
 */
public class ForecastStatsPackagingTest {

	private void writeHistory(File file, boolean gzip, int holdout, int holdoutHits, int inSample, int inSampleHits)
			throws Exception {
		StringBuilder json = new StringBuilder("{\"model\":\"logistic-v2-fullhistory\",\"model_version\":2,\"predictions\":[");
		for(int i = 0; i < holdout; i++) {
			if(i > 0) json.append(",");
			json.append("{\"correct\":").append(i < holdoutHits).append(",\"in_sample\":false}");
		}
		for(int i = 0; i < inSample; i++) {
			json.append(",{\"correct\":").append(i < inSampleHits).append(",\"in_sample\":true}");
		}
		json.append("]}");
		OutputStreamWriter writer = new OutputStreamWriter(gzip
				? new GZIPOutputStream(new FileOutputStream(file))
				: new FileOutputStream(file), java.nio.charset.StandardCharsets.UTF_8);
		writer.write(json.toString());
		writer.close();
	}

	@Test
	public void externalFileIsPreferredAndParsedOnce() throws Exception {
		File external = File.createTempFile("forecast-history", ".json");
		this.writeHistory(external, false, 10, 7, 5, 4);

		TournamentHistoryService service = new TournamentHistoryService();
		ReflectionTestUtils.setField(service, "forecastHistoryPath", external.getAbsolutePath());

		ForecastStats first = service.forecastStats();
		assertEquals(10, first.getMatchesScored(), "external file wins over classpath");
		assertEquals(7, first.getHits());
		assertEquals(5, first.getInSampleMatches());
		assertEquals(0.7, first.getHitRate(), 1e-9);

		//cache: second call returns the same object even after the file disappears
		assertTrue(external.delete());
		ForecastStats second = service.forecastStats();
		assertTrue(first == second, "parsed once, cached thereafter");
	}

	@Test
	public void gzipVariantIsAutoDetected() throws Exception {
		File gz = File.createTempFile("forecast-history", ".json.gz");
		this.writeHistory(gz, true, 8, 5, 0, 0);

		TournamentHistoryService service = new TournamentHistoryService();
		ReflectionTestUtils.setField(service, "forecastHistoryPath",
				gz.getAbsolutePath().substring(0, gz.getAbsolutePath().length() - 3)); // .json (gz beside it)

		ForecastStats stats = service.forecastStats();
		assertEquals(8, stats.getMatchesScored(), "path .json resolves sibling .json.gz");
	}

	@Test
	public void missingExternalFallsBackToClasspath() {
		TournamentHistoryService service = new TournamentHistoryService();
		ReflectionTestUtils.setField(service, "forecastHistoryPath",
				System.getProperty("java.io.tmpdir") + "/definitely-not-here.json");

		ForecastStats stats = service.forecastStats();
		assertNotNull(stats);
		assertTrue(stats.getMatchesScored() > 0, "classpath v1 artifact serves the fallback");
	}
}
