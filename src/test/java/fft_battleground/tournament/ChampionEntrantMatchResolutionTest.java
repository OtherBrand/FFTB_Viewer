package fft_battleground.tournament;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import fft_battleground.controller.response.model.tournaments.TournamentHistoryModels.MatchRecord;
import fft_battleground.controller.response.model.tournaments.TournamentHistoryModels.TournamentDetail;
import fft_battleground.model.BattleGroundTeam;
import fft_battleground.tournament.model.Pot;
import fft_battleground.tournament.model.TeamBet;
import fft_battleground.tournament.model.Tournament;
import fft_battleground.tournament.model.TournamentInfo;
import fft_battleground.tournament.TournamentRestService;

/**
 * Owner rule (2026-11-03): the champion bracket entrant resolves to the real
 * team in match rows too — sides AND winner — with championEntrant=true so
 * the UI renders '(Champion)' and the bracket identity is not lost.
 */
public class ChampionEntrantMatchResolutionTest {

	private Pot pot(BattleGroundTeam left, BattleGroundTeam right) {
		Pot pot = new Pot();
		TeamBet leftBet = new TeamBet();
		ReflectionTestUtils.setField(leftBet, "team", left);
		ReflectionTestUtils.setField(leftBet, "odds", 1.5);
		ReflectionTestUtils.setField(leftBet, "Bets", List.of());
		TeamBet rightBet = new TeamBet();
		ReflectionTestUtils.setField(rightBet, "team", right);
		ReflectionTestUtils.setField(rightBet, "odds", 2.5);
		ReflectionTestUtils.setField(rightBet, "Bets", List.of());
		ReflectionTestUtils.setField(pot, "left", leftBet);
		ReflectionTestUtils.setField(pot, "right", rightBet);
		return pot;
	}

	private TournamentInfo info(long id, String... winners) {
		TournamentInfo tournamentInfo = new TournamentInfo();
		ReflectionTestUtils.setField(tournamentInfo, "ID", id);
		ReflectionTestUtils.setField(tournamentInfo, "Winners", List.of(winners));
		return tournamentInfo;
	}

	@Test
	public void championEntrantMatchResolvesToTheIncomingRealTeamWithFlag() throws Exception {
		//scan: yellow won the previous tournament; THIS tournament's bracket has the
		//champion entrant (as 'champion') beating white, then losing the final to blue
		TournamentHistoryService service = new TournamentHistoryService();
		ReflectionTestUtils.setField(service, "scanCache",
				List.of(this.info(1000L, "yellow"), this.info(2000L, "blue")));
		ReflectionTestUtils.setField(service, "scanLoadedAt", System.currentTimeMillis());

		Tournament detail = new Tournament();
		ReflectionTestUtils.setField(detail, "ID", 2000L);
		ReflectionTestUtils.setField(detail, "Winners", List.of("champion", "blue"));
		ReflectionTestUtils.setField(detail, "pots", List.of(
				this.pot(BattleGroundTeam.CHAMPION, BattleGroundTeam.WHITE),
				this.pot(BattleGroundTeam.CHAMPION, BattleGroundTeam.BLUE)));
		ReflectionTestUtils.setField(detail, "Teams", null);
		TournamentRestService restService = mock(TournamentRestService.class);
		when(restService.getTournamentById(any(Long.class))).thenReturn(detail);
		ReflectionTestUtils.setField(service, "tournamentRestService", restService);

		TournamentDetail result = service.detail(2000L);

		List<MatchRecord> matches = result.getMatches(); // descending: final first
		assertEquals(2, matches.size());

		MatchRecord finalMatch = matches.get(0);
		assertEquals(BattleGroundTeam.YELLOW, finalMatch.getTeam1(), "entrant side resolves to incoming yellow");
		assertEquals(BattleGroundTeam.BLUE, finalMatch.getTeam2());
		assertEquals(BattleGroundTeam.BLUE, finalMatch.getWinner());
		assertEquals(Boolean.TRUE, finalMatch.getChampionEntrant(), "flag marks the entrant match");

		MatchRecord opener = matches.get(1);
		assertEquals(BattleGroundTeam.YELLOW, opener.getTeam1());
		assertEquals(BattleGroundTeam.WHITE, opener.getTeam2());
		assertEquals(BattleGroundTeam.YELLOW, opener.getWinner(), "literal 'champion' winner resolves to yellow");
		assertTrue(Boolean.TRUE.equals(opener.getChampionEntrant()));
		assertNull(null);
	}
}
