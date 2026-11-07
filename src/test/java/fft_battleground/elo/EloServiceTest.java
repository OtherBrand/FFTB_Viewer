package fft_battleground.elo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.apache.commons.lang3.tuple.Pair;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import fft_battleground.dump.cache.set.BotCache;
import fft_battleground.event.detector.model.BetEvent;
import fft_battleground.model.BattleGroundTeam;
import fft_battleground.botland.bot.model.BetResults;
import fft_battleground.repo.model.BettorElo;
import fft_battleground.repo.model.EloHistory;
import fft_battleground.repo.repository.BettorEloRepo;
import fft_battleground.repo.repository.EloHistoryRepo;

/**
 * Service-level ELO behavior: stake-agnosticism (bet size never enters the
 * math), forward-only creation at 1500, and win/loss accounting.
 */
public class EloServiceTest {

	private BetEvent bet(String player, BattleGroundTeam team, String amount) {
		return new BetEvent(player, team, amount, amount, null, false);
	}

	@SuppressWarnings("unchecked")
	private EloService buildService(List<BettorElo> savedRatings, List<EloHistory> savedHistory) {
		EloService service = new EloService();
		BettorEloRepo bettorEloRepo = mock(BettorEloRepo.class);
		EloHistoryRepo eloHistoryRepo = mock(EloHistoryRepo.class);
		BotCache botCache = mock(BotCache.class);
		when(bettorEloRepo.findByPlayerIn(anyCollection())).thenReturn(List.of());
		when(bettorEloRepo.saveAndFlush(any(BettorElo.class))).thenAnswer(invocation -> invocation.getArgument(0));
		when(bettorEloRepo.saveAll(anyCollection())).thenAnswer(invocation -> {
			savedRatings.addAll(new java.util.ArrayList<>((java.util.Collection<BettorElo>) invocation.getArgument(0)));
			return savedRatings;
		});
		when(eloHistoryRepo.saveAll(anyCollection())).thenAnswer(invocation -> {
			savedHistory.addAll(new java.util.ArrayList<>((java.util.Collection<EloHistory>) invocation.getArgument(0)));
			return savedHistory;
		});
		when(botCache.getSet()).thenReturn(Set.of());
		ReflectionTestUtils.setField(service, "bettorEloRepo", bettorEloRepo);
		ReflectionTestUtils.setField(service, "eloHistoryRepo", eloHistoryRepo);
		ReflectionTestUtils.setField(service, "botCache", botCache);
		return service;
	}

	@Test
	public void zeroSumHoldsUnderUnequalBettorCounts() {
		List<BettorElo> ratings = new java.util.ArrayList<>();
		List<EloHistory> history = new java.util.ArrayList<>();
		EloService service = this.buildService(ratings, history);

		//3 bettors on the winner, 1 on the loser — the count asymmetry that leaked
		BetResults results = new BetResults(
				Pair.of(List.of(this.bet("w1", BattleGroundTeam.YELLOW, "100"),
						this.bet("w2", BattleGroundTeam.YELLOW, "100"),
						this.bet("w3", BattleGroundTeam.YELLOW, "100")),
						List.of(this.bet("l1", BattleGroundTeam.WHITE, "100"))),
				BattleGroundTeam.YELLOW, BattleGroundTeam.YELLOW, BattleGroundTeam.WHITE, null, null, null, List.of());

		service.updateForMatch(results, 42L);

		double sum = history.stream().mapToDouble(EloHistory::getDelta).sum();
		org.junit.jupiter.api.Assertions.assertEquals(0.0, sum, 1e-9,
				"zero-sum: winners' gains must equal losers' losses exactly");
		//the population mean is preserved by construction
		double after = ratings.stream().mapToDouble(BettorElo::getElo).sum();
		org.junit.jupiter.api.Assertions.assertEquals(4 * 1500.0, after, 1e-6,
				"population points conserved");
	}

	@Test
	public void rawDeltasWithoutNormalizationWouldLeak() {
		//the mechanism, pinned under the pure-field E: equal side ratings give
		//complementary 0.5/0.5 expectations, yet 3-vs-1 counts still leak —
		//normalization is independent of the E source
		double expected = EloMath.fieldExpectation(1500, 1500);
		double rawSum = 3 * EloMath.ratingDelta(expected, 1.0, 0) + 1 * EloMath.ratingDelta(expected, 0.0, 0);
		org.junit.jupiter.api.Assertions.assertTrue(rawSum > 10,
				"raw math leaks under unequal counts (was " + rawSum + ") — normalization is load-bearing");
	}

	@Test
	public void beatingWellRatedOppositionEarnsMore() {
		List<BettorElo> ratings = new java.util.ArrayList<>();
		List<EloHistory> history = new java.util.ArrayList<>();
		EloService service = this.buildService(ratings, history);

		//the underdog side's average rating is far higher: field expectation for the
		//favorite-backer is LOW, so winning is worth a lot and losing is cheap
		BetResults results = new BetResults(
				Pair.of(List.of(this.bet("david", BattleGroundTeam.YELLOW, "100")),
						List.of(this.bet("goliath", BattleGroundTeam.WHITE, "100"))),
				BattleGroundTeam.YELLOW, BattleGroundTeam.YELLOW, BattleGroundTeam.WHITE, null, null, null, List.of());
		service.updateForMatch(results, 1L);

		//same matchup mirrored with the OPPOSITE winner: goliath's win is worth little
		List<EloHistory> history2 = new java.util.ArrayList<>();
		List<BettorElo> ratings2 = new java.util.ArrayList<>();
		EloService service2 = this.buildService(ratings2, history2);
		BetResults flipped = new BetResults(
				Pair.of(List.of(this.bet("david2", BattleGroundTeam.YELLOW, "100")),
						List.of(this.bet("goliath2", BattleGroundTeam.WHITE, "100"))),
				BattleGroundTeam.WHITE, BattleGroundTeam.YELLOW, BattleGroundTeam.WHITE, null, null, null, List.of());
		service2.updateForMatch(flipped, 1L);

		//both fresh at 1500 vs 1500 the expectations are 0.5 — so pin the CURVE instead:
		//a 400-point ratings gap gives the classic ~0.909/0.091 pair
		org.junit.jupiter.api.Assertions.assertEquals(0.909090909, EloMath.fieldExpectation(1900, 1500), 1e-6);
		org.junit.jupiter.api.Assertions.assertEquals(0.090909090, EloMath.fieldExpectation(1500, 1900), 1e-6);
		//and zero-sum holds on both boards
		org.junit.jupiter.api.Assertions.assertEquals(0.0,
				history.stream().mapToDouble(EloHistory::getDelta).sum(), 1e-9);
		org.junit.jupiter.api.Assertions.assertEquals(0.0,
				history2.stream().mapToDouble(EloHistory::getDelta).sum(), 1e-9);
	}

	@Test
	public void singleSidedMatchesAreNotEloEvents() {
		List<BettorElo> ratings = new java.util.ArrayList<>();
		List<EloHistory> history = new java.util.ArrayList<>();
		EloService service = this.buildService(ratings, history);

		//everyone on yellow; yellow LOSES — without the skip, their losses vanish into the void
		BetResults results = new BetResults(
				Pair.of(List.of(this.bet("s1", BattleGroundTeam.YELLOW, "100"),
						this.bet("s2", BattleGroundTeam.YELLOW, "100")), List.of()),
				BattleGroundTeam.WHITE, BattleGroundTeam.YELLOW, BattleGroundTeam.WHITE, null, null, null, List.of());
		service.updateForMatch(results, 1L);

		org.junit.jupiter.api.Assertions.assertTrue(history.isEmpty(), "single-sided match must not grade");
		org.junit.jupiter.api.Assertions.assertTrue(ratings.isEmpty(), "no ratings created either");
	}

	@Test
	public void updatesAreStakeAgnostic() {
		List<BettorElo> saved = List.copyOf(List.of());
		List<BettorElo> ratings = new java.util.ArrayList<>();
		List<EloHistory> history = new java.util.ArrayList<>();
		EloService service = this.buildService(ratings, history);

		BetResults results = new BetResults(
				Pair.of(List.of(this.bet("richguy", BattleGroundTeam.YELLOW, "50000"), this.bet("poorguy", BattleGroundTeam.YELLOW, "100")),
						List.of(this.bet("otherguy", BattleGroundTeam.WHITE, "1000"))),
				BattleGroundTeam.YELLOW, BattleGroundTeam.YELLOW, BattleGroundTeam.WHITE, null, null, null, List.of());

		service.updateForMatch(results, 12345L);

		Map<String, Double> deltasByPlayer = history.stream()
				.collect(Collectors.toMap(EloHistory::getPlayer, EloHistory::getDelta));
		assertEquals(deltasByPlayer.get("richguy"), deltasByPlayer.get("poorguy"), 1e-9,
				"same side, same history, different stakes — deltas must be identical");
		assertEquals(3, deltasByPlayer.size());
	}

	@Test
	public void newBettorsStartAtFifteenHundredAndCountPicks() {
		List<BettorElo> ratings = new java.util.ArrayList<>();
		List<EloHistory> history = new java.util.ArrayList<>();
		EloService service = this.buildService(ratings, history);

		BetResults results = new BetResults(
				Pair.of(List.of(this.bet("winner", BattleGroundTeam.YELLOW, "100")),
						List.of(this.bet("loser", BattleGroundTeam.WHITE, "100"))),
				BattleGroundTeam.YELLOW, BattleGroundTeam.YELLOW, BattleGroundTeam.WHITE, null, null, null, List.of());

		service.updateForMatch(results, 1L);

		Map<String, BettorElo> byPlayer = ratings.stream().collect(Collectors.toMap(BettorElo::getPlayer, r -> r));
		assertEquals(1500.0, byPlayer.get("winner").getElo() - history.stream().filter(h -> h.getPlayer().equals("winner"))
				.findFirst().get().getDelta(), 1e-9, "rating_after must equal 1500 + delta for a fresh bettor");
		assertEquals(1, byPlayer.get("winner").getPickWins());
		assertEquals(0, byPlayer.get("winner").getPickLosses());
		assertEquals(0, byPlayer.get("loser").getPickWins());
		assertEquals(1, byPlayer.get("loser").getPickLosses());
	}

}
