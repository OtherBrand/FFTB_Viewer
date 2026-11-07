package fft_battleground.tournament;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import fft_battleground.controller.response.model.tournaments.TournamentHistoryModels.ReignRecord;
import fft_battleground.controller.response.model.tournaments.TournamentHistoryModels.TournamentSummary;
import fft_battleground.model.BattleGroundTeam;
import fft_battleground.tournament.model.TournamentInfo;

/**
 * The champion-alias bug (2026-11-02): FFTBG reports retained titles under the
 * literal winner 'champion' — aggregating as one fake Champion team (2,312
 * 'titles' in prod). Resolution walks chronologically: real winner founds the
 * streak, 'champion' inherits, different real winner dethrones BY NAME.
 */
public class ChampionAliasResolutionTest {

	private TournamentInfo info(long id, String... winners) {
		TournamentInfo tournamentInfo = new TournamentInfo();
		ReflectionTestUtils.setField(tournamentInfo, "ID", id);
		ReflectionTestUtils.setField(tournamentInfo, "Winners", List.of(winners));
		return tournamentInfo;
	}

	private TournamentHistoryService serviceWithScan(TournamentInfo... scan) {
		TournamentHistoryService service = new TournamentHistoryService();
		ReflectionTestUtils.setField(service, "scanCache", List.of(scan));
		ReflectionTestUtils.setField(service, "scanLoadedAt", System.currentTimeMillis());
		return service;
	}

	@Test
	public void championAliasRunCollapsesIntoTheFoundingTeamsReign() {
		//yellow wins, then three defenses recorded as literal 'champion', then blue dethrones
		TournamentHistoryService service = this.serviceWithScan(
				this.info(1000L, "yellow"),
				this.info(2000L, "champion"),
				this.info(3000L, "champion"),
				this.info(4000L, "champion"),
				this.info(5000L, "blue"));

		List<ReignRecord> reigns = service.reigns();

		//exactly TWO reigns: Yellow (4 titles) then Blue (current) — never a fake Champion reign
		assertEquals(2, reigns.size(), "reigns: " + reigns);
		ReignRecord blue = reigns.get(0); // current first
		assertEquals(BattleGroundTeam.BLUE, blue.getTeam());
		assertNull(blue.getEndedAt(), "blue is current");

		ReignRecord yellow = reigns.get(1);
		assertEquals(BattleGroundTeam.YELLOW, yellow.getTeam(), "alias defenses resolve to the founder");
		assertEquals("1000", yellow.getReignId());
		assertEquals(3, yellow.getDefenses(), "4 tournaments = founding title + 3 defenses");
		assertEquals(Long.valueOf(5000L), yellow.getEndedAt(), "dethroned by blue BY NAME (its reign start)");
	}

	@Test
	public void summariesCarryResolvedIdentityAndStreaks() {
		TournamentHistoryService service = this.serviceWithScan(
				this.info(1000L, "yellow"),
				this.info(2000L, "champion"),
				this.info(3000L, "champion"),
				this.info(4000L, "blue"));

		List<TournamentSummary> summaries = service.recentSummaries(0, 10);

		//newest first: 4000 blue, 3000 yellow(resolved), 2000 yellow(resolved), 1000 yellow
		assertEquals(4, summaries.size());
		TournamentSummary aliasDefense = summaries.get(1); // id 3000, raw winner 'champion'
		assertEquals(BattleGroundTeam.YELLOW, aliasDefense.getChampion(), "alias row shows the real team");
		assertEquals(Integer.valueOf(3), aliasDefense.getConsecutiveTitles(), "streak spans the alias run: 1000-3000 = 3 tournaments");
	}

	@Test
	public void careerCrownsNeverAggregateUnderTheFakeChampionIdentity() {
		TournamentHistoryService service = this.serviceWithScan(
				this.info(1000L, "yellow"),
				this.info(2000L, "champion"),
				this.info(3000L, "champion"),
				this.info(4000L, "blue"),
				this.info(5000L, "champion"));

		List<TournamentSummary> summaries = service.recentSummaries(0, 10);
		//blue's retained defense (5000, alias) counts toward BLUE's career crowns: 2
		TournamentSummary blueLatest = summaries.get(0);
		assertEquals(BattleGroundTeam.BLUE, blueLatest.getChampion());
		assertEquals(Integer.valueOf(2), blueLatest.getChampionCrowns(), "alias retention credits the real team");
		TournamentSummary yellowRow = summaries.stream()
				.filter(sum -> sum.getChampion() == BattleGroundTeam.YELLOW).findFirst().get();
		assertEquals(Integer.valueOf(3), yellowRow.getChampionCrowns(), "yellow: 1000-3000 all resolve to it");
		//and no summary anywhere claims CHAMPION as champion
		assertEquals(0, summaries.stream()
				.filter(sum -> sum.getChampion() == BattleGroundTeam.CHAMPION).count(),
				"the fake identity never surfaces");
	}
}
