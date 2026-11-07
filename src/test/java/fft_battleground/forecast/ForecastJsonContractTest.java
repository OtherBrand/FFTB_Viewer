package fft_battleground.forecast;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

import fft_battleground.forecast.ForecastModels.ForecastResponse;
import fft_battleground.model.BattleGroundTeam;

/**
 * Pins the SERIALIZED JSON contract (the getter-level tests missed the
 * JavaBeans naming trap: getPWin1 serializes as "PWin1", so the UI's
 * pWin1 key found nothing — "the percentages are missing").
 */
public class ForecastJsonContractTest {

	@Test
	public void jsonCarriesTheContractKeysExactly() throws Exception {
		ForecastResponse response = new ForecastResponse();
		response.setTournamentId(7L);
		response.setPick(BattleGroundTeam.RED);
		response.setPWin1(0.512);
		response.setPWin2(0.488);
		response.setLeadsBy(0.024);
		response.setFactors(List.of());
		response.setModelVersion("logistic-v1/v1");

		String json = new ObjectMapper().writeValueAsString(response);
		assertTrue(json.contains("\"pWin1\":0.512"), "pWin1 must serialize under the contract key — was: " + json);
		assertTrue(json.contains("\"pWin2\":0.488"), "pWin2 must serialize under the contract key — was: " + json);
		assertTrue(!json.contains("PWin1"), "the JavaBeans PWin1 variant must not appear — was: " + json);
	}

}
