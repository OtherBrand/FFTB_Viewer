package fft_battleground.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;

import fft_battleground.controller.response.model.EloLeaderboardEntry;
import fft_battleground.repo.model.BettorElo;
import fft_battleground.repo.model.FighterElo;
import fft_battleground.repo.repository.BettorEloRepo;
import fft_battleground.repo.repository.FighterEloRepo;
import fft_battleground.util.GenericResponse;

/**
 * Leaderboard endpoint contract: rank ordering follows elo DESC, bots are
 * flagged, and the limit parameter is bounded.
 */
public class EloLeaderboardEndpointTest {

	@Test
	public void bettorLeaderboardRanksByEloDescendingAndFlagsBots() {
		EloLeaderboardController controller = new EloLeaderboardController();
		BettorEloRepo repo = mock(BettorEloRepo.class);
		BettorElo top = new BettorElo("tophuman", false);
		top.setElo(1900.0);
		top.setPickWins(30);
		top.setPickLosses(10);
		BettorElo second = new BettorElo("somebot", true);
		second.setElo(1700.0);
		second.setPickWins(25);
		second.setPickLosses(15);
		when(repo.findRanked(anyInt(), any(PageRequest.class))).thenReturn(List.of(top, second));
		ReflectionTestUtils.setField(controller, "bettorEloRepo", repo);

		ResponseEntity<GenericResponse<List<EloLeaderboardEntry>>> response = controller.bettorLeaderboard(50, null);

		List<EloLeaderboardEntry> entries = response.getBody().getData();
		assertEquals(2, entries.size());
		assertEquals(1, entries.get(0).getRank());
		assertEquals("tophuman", entries.get(0).getPlayer());
		assertEquals(1900.0, entries.get(0).getElo(), 1e-9);
		assertEquals(40, entries.get(0).getMatches());
		assertEquals(2, entries.get(1).getRank());
		assertTrue(entries.get(1).isBot(), "bot flag rides the leaderboard for UI badging");
		Mockito.verify(repo).findRanked(Mockito.eq(20), Mockito.eq(PageRequest.of(0, 50)));
	}

	@Test
	public void fighterLeaderboardUsesFightRecords() {
		EloLeaderboardController controller = new EloLeaderboardController();
		FighterEloRepo repo = mock(FighterEloRepo.class);
		FighterElo fighter = new FighterElo("brawler", false);
		fighter.setElo(1650.0);
		fighter.setFightWins(22);
		fighter.setFightLosses(18);
		when(repo.findRanked(anyInt(), any(PageRequest.class))).thenReturn(List.of(fighter));
		ReflectionTestUtils.setField(controller, "fighterEloRepo", repo);

		ResponseEntity<GenericResponse<List<EloLeaderboardEntry>>> response = controller.fighterLeaderboard(null, null);

		EloLeaderboardEntry entry = response.getBody().getData().get(0);
		assertEquals("brawler", entry.getPlayer());
		assertEquals(22, entry.getWins());
		assertEquals(18, entry.getLosses());
	}

	@Test
	public void limitDefaultsToEveryoneAndBoundedHigh() {
		EloLeaderboardController controller = new EloLeaderboardController();
		assertEquals(10000, controller.boundedLimit(null), "default = everyone");
		assertEquals(10000, controller.boundedLimit(0), "nonsense = everyone");
		assertEquals(25, controller.boundedLimit(25));
		assertEquals(50000, controller.boundedLimit(1000000), "ceiling raised for full-population serving");
	}

	@Test
	public void minMatchesDefaultsToTheProvisionalBoundary() {
		EloLeaderboardController controller = new EloLeaderboardController();
		assertEquals(20, controller.effectiveMinMatches(null), "default = the engaged threshold");
		assertEquals(5, controller.effectiveMinMatches(5));
		assertEquals(20, controller.effectiveMinMatches(-1), "negative falls back to default");
	}

}
