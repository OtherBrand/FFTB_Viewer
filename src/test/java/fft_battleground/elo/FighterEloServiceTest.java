package fft_battleground.elo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.apache.commons.lang3.tuple.Pair;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import fft_battleground.botland.bot.model.BetResults;
import fft_battleground.dump.cache.set.BotCache;
import fft_battleground.event.detector.model.TeamInfoEvent;
import fft_battleground.model.BattleGroundTeam;
import fft_battleground.botland.bot.model.TeamData;
import fft_battleground.repo.model.FighterElo;
import fft_battleground.repo.model.FighterEloHistory;
import fft_battleground.repo.model.PlayerRecord;
import fft_battleground.repo.repository.FighterEloHistoryRepo;
import fft_battleground.repo.repository.FighterEloRepo;

/**
 * Fighter ELO behavior: rosters come from team metadata, new fighters start
 * at 1500 (forward-only), win/loss accounting follows the match winner, and
 * both sides' fighters move on every match.
 */
public class FighterEloServiceTest {

	private PlayerRecord fighter(String name) {
		PlayerRecord record = new PlayerRecord();
		record.setPlayer(name);
		record.setWins(0);
		record.setLosses(0);
		record.setFightWins(0);
		record.setFightLosses(0);
		return record;
	}

	private TeamInfoEvent teamOf(BattleGroundTeam team, List<PlayerRecord> roster) {
		TeamInfoEvent event = new TeamInfoEvent(team, List.of());
		event.setMetaData(roster);
		return event;
	}

	private FighterEloService buildService(List<FighterElo> savedRatings, List<FighterEloHistory> savedHistory) {
		FighterEloService service = new FighterEloService();
		FighterEloRepo fighterEloRepo = mock(FighterEloRepo.class);
		FighterEloHistoryRepo historyRepo = mock(FighterEloHistoryRepo.class);
		BotCache botCache = mock(BotCache.class);
		when(fighterEloRepo.findByPlayerIn(anyCollection())).thenReturn(List.of());
		when(fighterEloRepo.saveAndFlush(any(FighterElo.class))).thenAnswer(invocation -> invocation.getArgument(0));
		when(fighterEloRepo.saveAll(anyCollection())).thenAnswer(invocation -> {
			savedRatings.addAll(new ArrayList<>((java.util.Collection<FighterElo>) invocation.getArgument(0)));
			return savedRatings;
		});
		when(historyRepo.saveAll(anyCollection())).thenAnswer(invocation -> {
			savedHistory.addAll(new ArrayList<>((java.util.Collection<FighterEloHistory>) invocation.getArgument(0)));
			return savedHistory;
		});
		when(botCache.getSet()).thenReturn(Set.of());
		ReflectionTestUtils.setField(service, "fighterEloRepo", fighterEloRepo);
		ReflectionTestUtils.setField(service, "fighterEloHistoryRepo", historyRepo);
		ReflectionTestUtils.setField(service, "botCache", botCache);
		return service;
	}

	@Test
	public void winnerSideFightersGainAndCountWins() {
		List<FighterElo> ratings = new ArrayList<>();
		List<FighterEloHistory> history = new ArrayList<>();
		FighterEloService service = this.buildService(ratings, history);

		TeamData teamData = new TeamData(
				this.teamOf(BattleGroundTeam.YELLOW, List.of(this.fighter("winner1"), this.fighter("winner2"))),
				this.teamOf(BattleGroundTeam.WHITE, List.of(this.fighter("loser1"))));
		BetResults results = new BetResults(Pair.of(List.of(), List.of()), BattleGroundTeam.YELLOW,
				BattleGroundTeam.YELLOW, BattleGroundTeam.WHITE, null, null, teamData, List.of());

		service.updateForMatch(results, 7L);

		Map<String, FighterElo> byPlayer = ratings.stream().collect(Collectors.toMap(FighterElo::getPlayer, r -> r));
		assertEquals(3, byPlayer.size());
		assertEquals(1, byPlayer.get("winner1").getFightWins());
		assertEquals(0, byPlayer.get("winner1").getFightLosses());
		assertEquals(0, byPlayer.get("loser1").getFightWins());
		assertEquals(1, byPlayer.get("loser1").getFightLosses());

		Map<String, Double> deltas = history.stream()
				.collect(Collectors.toMap(FighterEloHistory::getPlayer, FighterEloHistory::getDelta));
		//fresh rosters are symmetric at 1500v1500 → E=0.5 → winners gain, loser loses equally
		assertEquals(-deltas.get("loser1"), deltas.get("winner1"), 1e-9);
		assertEquals(deltas.get("winner1"), deltas.get("winner2"), 1e-9);
		assertEquals(1500.0, byPlayer.get("winner1").getElo() - deltas.get("winner1"), 1e-9);
	}

	@Test
	public void missingTeamDataIsASilentNoOp() {
		List<FighterElo> ratings = new ArrayList<>();
		FighterEloService service = this.buildService(ratings, new ArrayList<>());
		BetResults results = new BetResults(Pair.of(List.of(), List.of()), BattleGroundTeam.YELLOW,
				BattleGroundTeam.YELLOW, BattleGroundTeam.WHITE, null, null, null, List.of());
		service.updateForMatch(results, 7L);
		assertTrue(ratings.isEmpty());
	}

	private static void assertTrue(boolean condition) {
		org.junit.jupiter.api.Assertions.assertTrue(condition);
	}

}
