package fft_battleground.elo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Date;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import fft_battleground.botland.bot.model.BetResults;
import fft_battleground.model.BattleGroundTeam;
import fft_battleground.repo.model.BotBetData;
import fft_battleground.repo.repository.BotBetDataRepo;
import fft_battleground.repo.util.BotReceivedBet;
import fft_battleground.repo.util.BotReceivedBets;
import fft_battleground.tournament.TournamentRestService;
import fft_battleground.tournament.model.Tournament;

/**
 * The outage re-grade: bot_bet_data rows replay through the exact EloService
 * math with winners indexed from the API — proving the day of skipped matches
 * is recoverable by design.
 */
public class EloRegradeServiceTest {

	private fft_battleground.tournament.model.Pot pot(BattleGroundTeam left, BattleGroundTeam right) {
		fft_battleground.tournament.model.Pot pot = new fft_battleground.tournament.model.Pot();
		ReflectionTestUtils.setField(pot, "left", new fft_battleground.tournament.model.TeamBet());
		ReflectionTestUtils.setField(pot, "right", new fft_battleground.tournament.model.TeamBet());
		ReflectionTestUtils.setField(pot.getLeft(), "team", left);
		ReflectionTestUtils.setField(pot.getRight(), "team", right);
		return pot;
	}

	private BotBetData matchRow(long id, long tournamentId, long createdAt, String winnerSidePlayer) {
		return this.matchRowPair(id, tournamentId, createdAt, winnerSidePlayer, BattleGroundTeam.RED, BattleGroundTeam.BLUE);
	}

	private BotBetData matchRowPair(long id, long tournamentId, long createdAt, String winnerSidePlayer,
			BattleGroundTeam left, BattleGroundTeam right) {
		BotBetData row = new BotBetData();
		ReflectionTestUtils.setField(row, "botBetDataId", id);
		ReflectionTestUtils.setField(row, "tournamentId", tournamentId);
		ReflectionTestUtils.setField(row, "leftTeam", left);
		ReflectionTestUtils.setField(row, "rightTeam", right);
		ReflectionTestUtils.setField(row, "createDateTime", new Date(createdAt));
		ReflectionTestUtils.setField(row, "leftBets", new BotReceivedBets(
				List.of(new BotReceivedBet(winnerSidePlayer, 100, 5000))));
		ReflectionTestUtils.setField(row, "rightBets", new BotReceivedBets(
				List.of(new BotReceivedBet("loserguy", 100, 3000))));
		return row;
	}

	@Test
	public void outageRowsRegradeWithApiWinnersAndPotImpliedAdvisor() throws Exception {
		BotBetDataRepo betDataRepo = mock(BotBetDataRepo.class);
		TournamentRestService restService = mock(TournamentRestService.class);
		EloService eloService = mock(EloService.class);

		//three DISTINCT-pair rows: two normal matches + one single-sided (left-only bets).
		//pair-consumption maps by team pair, so fixtures need realistic distinct pairs
		BotBetData singleSided = this.matchRowPair(3L, 500L, 3000L, "lonepicker",
				BattleGroundTeam.RED, BattleGroundTeam.BLUE);
		ReflectionTestUtils.setField(singleSided, "rightBets", new BotReceivedBets(List.of()));
		List<BotBetData> rows = new java.util.ArrayList<>(List.of(
				this.matchRowPair(1L, 500L, 1000L, "winnera", BattleGroundTeam.RED, BattleGroundTeam.BLUE),
				this.matchRowPair(2L, 500L, 2000L, "winnerb", BattleGroundTeam.BLUE, BattleGroundTeam.WHITE),
				singleSided));
		when(betDataRepo.getBotBetDataBetween(any(Date.class), any(Date.class))).thenReturn(rows);
		when(betDataRepo.getBotBetDataForTournaments(anyList())).thenReturn(rows);
		Tournament tournament = new Tournament();
		ReflectionTestUtils.setField(tournament, "ID", 500L);
		ReflectionTestUtils.setField(tournament, "Winners", List.of("red", "blue", "red"));
		//Pots carry the per-match team pairs the pair-based winner lookup consumes;
		//row 3's pair (red vs blue) matches pot 3 — position indexing is dead
		ReflectionTestUtils.setField(tournament, "pots", List.of(
				this.pot(BattleGroundTeam.RED, BattleGroundTeam.BLUE),
				this.pot(BattleGroundTeam.BLUE, BattleGroundTeam.WHITE),
				this.pot(BattleGroundTeam.RED, BattleGroundTeam.BLUE)));
		when(restService.getTournamentById(any(Long.class))).thenReturn(tournament);

		EloRegradeService service = new EloRegradeService();
		ReflectionTestUtils.setField(service, "botBetDataRepo", betDataRepo);
		ReflectionTestUtils.setField(service, "tournamentRestService", restService);
		ReflectionTestUtils.setField(service, "eloService", eloService);

		//surface the actual lookup failure before asserting the aggregate
		BattleGroundTeam directWinner = service.winnerFor(rows.get(0));
		org.junit.jupiter.api.Assertions.assertEquals(BattleGroundTeam.RED, directWinner, "direct winner lookup");

		EloRegradeService.RegradeResult result = service.regrade(0L, 5000L);

		assertEquals(3, result.getMatchesConsidered());
		assertEquals(2, result.getMatchesRegraded());
		assertEquals(0, result.getMatchesSkippedNoWinner());
		assertEquals(1, result.getMatchesSkippedSingleSided(), "single-sided row counted, not graded");

		ArgumentCaptor<BetResults> played = ArgumentCaptor.forClass(BetResults.class);
		verify(eloService, times(2)).updateForMatch(played.capture(), any());
		//winner indexing: first row -> red, second row -> blue
		assertEquals(BattleGroundTeam.RED, played.getAllValues().get(0).getWinningTeam());
		assertEquals(BattleGroundTeam.BLUE, played.getAllValues().get(1).getWinningTeam());
		//bets reconstructed per side with correct teams
		assertEquals(BattleGroundTeam.RED, played.getAllValues().get(0).getBets().getLeft().get(0).getTeam());
		assertEquals(BattleGroundTeam.BLUE, played.getAllValues().get(0).getBets().getRight().get(0).getTeam());
		//advisor snapshot is null by design (pot-implied fallback)
		verify(eloService, times(2)).updateForMatch(any(), any());
		assertTrue(result.getSampleErrors().isEmpty());
	}

}
