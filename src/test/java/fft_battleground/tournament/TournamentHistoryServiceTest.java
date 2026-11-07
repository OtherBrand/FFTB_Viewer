package fft_battleground.tournament;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import fft_battleground.controller.response.model.tournaments.TournamentHistoryModels.ForecastStats;
import fft_battleground.controller.response.model.tournaments.TournamentHistoryModels.ReignRecord;
import fft_battleground.controller.response.model.tournaments.TournamentHistoryModels.TournamentSummary;
import fft_battleground.model.BattleGroundTeam;
import fft_battleground.tournament.model.TournamentInfo;

/**
 * History derivations against a synthetic scan (no network): reign runs and
 * their boundaries, career crowns and streaks, retainedNext semantics, and
 * the holdout-first forecast stats.
 */
public class TournamentHistoryServiceTest {

	private TournamentInfo info(long id, String... winners) {
		TournamentInfo tournamentInfo = new TournamentInfo();
		ReflectionTestUtils.setField(tournamentInfo, "ID", id);
		ReflectionTestUtils.setField(tournamentInfo, "Winners", List.of(winners));
		return tournamentInfo;
	}

	/** chronological: yellow x2, blue x1, yellow x3 (current) */
	private TournamentHistoryService serviceWithScan(TournamentInfo... scan) {
		TournamentHistoryService service = new TournamentHistoryService();
		ReflectionTestUtils.setField(service, "scanCache", List.of(scan));
		ReflectionTestUtils.setField(service, "scanLoadedAt", System.currentTimeMillis());
		return service;
	}

	@Test
	public void reignsDeriveAsChampionRunsCurrentFirst() {
		TournamentHistoryService service = this.serviceWithScan(
				this.info(1000L, "yellow", "yellow"),
				this.info(2000L, "yellow", "blue"),
				this.info(3000L, "blue", "blue"),
				this.info(4000L, "yellow", "yellow"),
				this.info(5000L, "purple", "yellow"),
				this.info(6000L, "yellow", "yellow"));

		List<ReignRecord> reigns = service.reigns();

		assertEquals(3, reigns.size());
		//current first: the yellow run ending at 6000
		ReignRecord current = reigns.get(0);
		assertEquals(BattleGroundTeam.YELLOW, current.getTeam());
		assertEquals("4000", current.getReignId());
		assertEquals(Long.valueOf(4000L), current.getStartedAt());
		assertNull(current.getEndedAt(), "current reign has no end");
		assertEquals(2, current.getDefenses(), "3 tournaments = first title + 2 defenses");
		assertEquals(6, current.getMatches());
		assertEquals(1, current.getCrownCount(), "yellow had 1 title before this reign (1000; 2000 went to blue)");
		assertNull(current.getAvgBackerElo(), "historical/forward-only ELO degrades to null");

		//the blue reign: started 3000, ended at the next reign's start 4000
		ReignRecord blue = reigns.stream().filter(r -> r.getTeam() == BattleGroundTeam.BLUE).findFirst().get();
		assertEquals(Long.valueOf(2000L), blue.getStartedAt(), "blue ran 2000-3000 (2000 final went to blue)");
		assertEquals(Long.valueOf(4000L), blue.getEndedAt());
		assertEquals(1, blue.getDefenses(), "blue ran 2 tournaments: first title + 1 defense");
	}

	@Test
	public void summariesCarryCrownsStreaksAndRetainedNext() {
		TournamentHistoryService service = this.serviceWithScan(
				this.info(1000L, "yellow", "yellow"),
				this.info(2000L, "yellow", "blue"),
				this.info(3000L, "blue", "blue"),
				this.info(4000L, "yellow", "yellow"));

		List<TournamentSummary> summaries = service.recentSummaries(0, 10);

		assertEquals(4, summaries.size());
		TournamentSummary newest = summaries.get(0); // id 4000
		assertEquals("4000", newest.getTournamentId());
		assertEquals(BattleGroundTeam.YELLOW, newest.getChampion());
		assertEquals(Integer.valueOf(2), newest.getChampionCrowns(), "yellow career = 1000 + 4000 (2000 was blue last-winner)");
		assertEquals(Integer.valueOf(1), newest.getConsecutiveTitles(), "blue interrupted the streak");
		assertNull(newest.getRetainedNext(), "newest tournament: unknown by design");

		TournamentSummary second = summaries.get(1); // id 3000, blue
		assertEquals(Boolean.FALSE, second.getRetainedNext(), "yellow took the next one");
		TournamentSummary third = summaries.get(2); // id 2000, blue (last winner of the final)
		assertEquals(BattleGroundTeam.BLUE, third.getChampion());
		assertEquals(Integer.valueOf(2), third.getConsecutiveTitles(), "mid-streak rows carry the full run (2000+3000)");
		assertEquals(Boolean.TRUE, third.getRetainedNext(), "blue retained at 3000 after 2000");
	}

	@Test
	public void forecastStatsServeHoldoutFirstFromTheArtifact() {
		//the real artifact ships in resources — holdout is a strict subset of all predictions
		TournamentHistoryService service = new TournamentHistoryService();
		ForecastStats stats = service.forecastStats();

		assertNotNull(stats);
		assertTrue(stats.getMatchesScored() > 0, "holdout rows present");
		assertTrue(stats.getHits() > 0);
		assertTrue(stats.getHitRate() > 0.5 && stats.getHitRate() < 0.7, "holdout accuracy band ~61.5%");
		assertTrue(stats.getInSampleMatches() > stats.getMatchesScored(), "in-sample flagged separately");
		assertNotNull(stats.getModelVersion());
	}

}
