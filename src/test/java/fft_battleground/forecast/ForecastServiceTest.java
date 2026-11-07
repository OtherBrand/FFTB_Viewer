package fft_battleground.forecast;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;

import java.util.List;
import java.util.Map;

import org.apache.commons.lang3.tuple.Pair;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import fft_battleground.forecast.ForecastModels.ForecastRequest;
import fft_battleground.forecast.ForecastModels.ForecastResponse;
import fft_battleground.model.BattleGroundTeam;
import fft_battleground.repo.model.AdvisorMatchLog;
import fft_battleground.repo.repository.AdvisorMatchLogRepo;
import fft_battleground.tournament.model.Unit;

/**
 * Oracle behavior against a tiny hand-built model: probabilities sum to one,
 * symmetric comps sit at 0.5, the feature conventions match fftb-forecast
 * exactly (attr diffs, agg sums, map 2.0, side one-hot), and every served
 * verdict lands in advisor_match_log with the model version.
 */
public class ForecastServiceTest {

	private WinProbModel tinyModel() {
		//attr:Fire strongly favors left; side:Yellow mildly favors left
		WinProbModel model = WinProbModel.unloaded();
		ReflectionTestUtils.setField(model, "modelVersion", "test/v1");
		ReflectionTestUtils.setField(model, "intercept", 0.0);
		Map<String, Double> weights = Map.of(
				"attr:Fire", 0.5,
				"agg:brave", 0.01,
				"map:1", 0.05,
				"side:Yellow", 0.1,
				"side:White", -0.1);
		Map<String, Double> internal = (Map<String, Double>) ReflectionTestUtils.getField(model, "weights");
		internal.putAll(weights);
		return model;
	}

	private fft_battleground.forecast.ForecastModels.MatchInfoDto matchInfoDto(int mapNumber) {
		fft_battleground.forecast.ForecastModels.MatchInfoDto dto = new fft_battleground.forecast.ForecastModels.MatchInfoDto();
		dto.setMapNumber(mapNumber);
		return dto;
	}

	private Unit unit(String className, int brave, int faith, boolean raidBoss) {
		Unit unit = new Unit();
		unit.setClassName(className);
		unit.setBrave((short) brave);
		unit.setFaith((short) faith);
		unit.setRaidBoss(raidBoss);
		return unit;
	}

	private ForecastService buildService(WinProbModel model, AdvisorMatchLogRepo logRepo) {
		return this.buildService(model, logRepo, null);
	}

	private ForecastService buildService(WinProbModel model, AdvisorMatchLogRepo logRepo, Long serverTournamentId) {
		ForecastService service = new ForecastService();
		ReflectionTestUtils.setField(service, "model", model);
		ReflectionTestUtils.setField(service, "advisorMatchLogRepo", logRepo);
		if(serverTournamentId != null) {
			fft_battleground.tournament.TournamentService tournamentService =
					mock(fft_battleground.tournament.TournamentService.class);
			fft_battleground.tournament.model.Tournament tournament =
					mock(fft_battleground.tournament.model.Tournament.class);
			when(tournament.getID()).thenReturn(serverTournamentId);
			when(tournamentService.getCurrentTournament()).thenReturn(tournament);
			ReflectionTestUtils.setField(service, "tournamentService", tournamentService);
		}
		return service;
	}

	@Test
	public void symmetricCompositionSitsAtFifty() {
		AdvisorMatchLogRepo logRepo = mock(AdvisorMatchLogRepo.class);
		ForecastService service = this.buildService(this.tinyModel(), logRepo);

		ForecastRequest request = new ForecastRequest();
		request.setTournamentId(99L);
		request.setTeam1(BattleGroundTeam.RED);
		request.setTeam2(BattleGroundTeam.BLUE);
		request.setTeam1Units(List.of(this.unit("Squire", 60, 55, false)));
		request.setTeam2Units(List.of(this.unit("Squire", 60, 55, false)));
		//map 99 carries no weight in the tiny model — truly symmetric fixture
		request.setMatchInfo(matchInfoDto(99));

		ForecastResponse response = service.forecast(request);

		assertNotNull(response);
		assertEquals(0.5, response.getPWin1(), 1e-9, "identical comps with symmetric side weights cancel");
		assertEquals(0.5, response.getPWin2(), 1e-9);
		assertNotNull(response.getPick(), "ties still pick a side (>= comparison)");
		assertEquals(0.0, response.getLeadsBy(), 1e-9);
	}

	@Test
	public void attributeAdvantageDrivesThePickAndFactors() {
		AdvisorMatchLogRepo logRepo = mock(AdvisorMatchLogRepo.class);
		ForecastService service = this.buildService(this.tinyModel(), logRepo);

		ForecastRequest request = new ForecastRequest();
		request.setTournamentId(100L);
		request.setTeam1(BattleGroundTeam.YELLOW);
		request.setTeam2(BattleGroundTeam.WHITE);
		request.setTeam1Units(List.of(this.unit("Fire", 70, 50, false)));
		request.setTeam2Units(List.of(this.unit("Squire", 50, 50, false)));
		request.setMatchInfo(matchInfoDto(1));

		ForecastResponse response = service.forecast(request);

		assertEquals(BattleGroundTeam.YELLOW, response.getPick(), "attr:Fire + side:Yellow + brave edge all favor team1");
		//exact arithmetic: attr:Fire 0.5x1 + brave 0.01x20 + map:1 0.05x2 + side:Yellow 0.1 = 0.9
		assertEquals(1.0 / (1.0 + Math.exp(-0.9)), response.getPWin1(), 1e-9);
		//attribution: the strongest factor is the Fire advantage favoring team1
		assertEquals("attr:Fire", response.getFactors().get(0).getName());
		assertEquals(BattleGroundTeam.YELLOW, response.getFactors().get(0).getFavors());
		assertTrue(response.getFactors().stream().allMatch(f -> f.getFavors() == BattleGroundTeam.YELLOW),
				"every term in this fixture favors team1");

		//verdict logged with tournament id + model version in the payload
		ArgumentCaptor<AdvisorMatchLog> logCaptor = ArgumentCaptor.forClass(AdvisorMatchLog.class);
		verify(logRepo).saveAndFlush(logCaptor.capture());
		assertEquals(Long.valueOf(100L), logCaptor.getValue().getTournamentId());
		assertTrue(logCaptor.getValue().getRecommendationJson().contains("test/v1"),
				"modelVersion rides the logged payload");
	}

	@Test
	public void featureConventionsMatchTheTrainerExactly() {
		ForecastService service = this.buildService(this.tinyModel(), mock(AdvisorMatchLogRepo.class));

		ForecastRequest request = new ForecastRequest();
		request.setTeam1(BattleGroundTeam.YELLOW);
		request.setTeam2(BattleGroundTeam.WHITE);
		request.setTeam1Units(List.of(this.unit("Fire", 10, 0, true), this.unit("Fire", 20, 0, false)));
		request.setTeam2Units(List.of(this.unit("Ice", 5, 0, false)));
		request.setMatchInfo(matchInfoDto(7));

		Map<String, Double> features = service.buildFeatures(request);

		assertEquals(2.0, features.get("attr:Fire"), "attr counts difference: 2 left - 0 right");
		assertEquals(-1.0, features.get("attr:Ice"), "right-side attr counts negative");
		assertEquals(25.0, features.get("agg:brave"), "brave sums: (10+20) - 5");
		assertEquals(1.0, features.get("agg:raidBoss"), "raid boss count difference");
		assertEquals(2.0, features.get("map:7"), "map one-hot nets 2.0 (trainer double-bump)");
		assertEquals(1.0, features.get("side:Yellow"), "side one-hot on the LEFT color, proper case");
		assertNull(features.get("side:White"), "only the label side is one-hot");
	}

	@Test
	public void nullTournamentIdIsAttributedServerSide() {
		AdvisorMatchLogRepo logRepo = mock(AdvisorMatchLogRepo.class);
		ForecastService service = this.buildService(this.tinyModel(), logRepo, 555L);

		ForecastRequest request = new ForecastRequest();
		request.setTeam1(BattleGroundTeam.RED);
		request.setTeam2(BattleGroundTeam.BLUE);
		request.setTeam1Units(List.of(this.unit("Squire", 60, 55, false)));
		request.setTeam2Units(List.of(this.unit("Squire", 60, 55, false)));

		ForecastResponse response = service.forecast(request);

		//server-side attribution: response stamped + verdict logged despite the null request id
		assertEquals(Long.valueOf(555L), response.getTournamentId());
		ArgumentCaptor<AdvisorMatchLog> logCaptor = ArgumentCaptor.forClass(AdvisorMatchLog.class);
		verify(logRepo).saveAndFlush(logCaptor.capture());
		assertEquals(Long.valueOf(555L), logCaptor.getValue().getTournamentId());
	}

	@Test
	public void unloadedModelServesNullFor503() {
		ForecastService service = this.buildService(WinProbModel.unloaded(), mock(AdvisorMatchLogRepo.class));
		ForecastRequest request = new ForecastRequest();
		request.setTeam1(BattleGroundTeam.RED);
		request.setTeam2(BattleGroundTeam.BLUE);
		assertNull(service.forecast(request), "unloaded model -> controller serves 503");
	}

}
