package fft_battleground.forecast;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import fft_battleground.model.BattleGroundTeam;
import fft_battleground.tournament.model.Unit;
import lombok.Data;

/**
 * Request/response shapes for POST /api/matches/forecast (advisor v2):
 * the live page POSTs the full match composition once both TEAM_INFOs are
 * complete; the oracle answers statelessly.
 */
public class ForecastModels {

	/**
	 * Deserialization DTO for the posted match info — the event class has no
	 * default constructor and 500s Jackson; the oracle needs only the map.
	 */
	@Data
	@JsonIgnoreProperties(ignoreUnknown = true)
	public static class MatchInfoDto {
		private Integer mapNumber;
		private String mapName;
	}

	/** the request body */
	@Data
	@JsonIgnoreProperties(ignoreUnknown = true)
	public static class ForecastRequest {
		private Long tournamentId;
		private MatchInfoDto matchInfo;
		private BattleGroundTeam team1;
		private BattleGroundTeam team2;
		private List<Unit> team1Units;
		private List<Unit> team2Units;
	}

	/** the response payload */
	@Data
	public static class ForecastResponse {
		private Long tournamentId;
		private BattleGroundTeam pick;
		//explicit names: Lombok's getPWin1 serializes as "PWin1" under JavaBeans rules
		//(two leading capitals keep their case) — the contract says pWin1/pWin2
		@com.fasterxml.jackson.annotation.JsonProperty("pWin1")
		private Double pWin1;
		@com.fasterxml.jackson.annotation.JsonProperty("pWin2")
		private Double pWin2;
		/** probability margin of the pick (pWin_pick - pWin_other) */
		private Double leadsBy;
		private List<ForecastFactor> factors;
		private String modelVersion;
	}

	/** one attribution term: top weight x value contributors, signed */
	@Data
	public static class ForecastFactor {
		private String name;
		/** which team the term favors (team1 | team2) */
		private BattleGroundTeam favors;
		/** |weight x value| — comparable across terms */
		private double magnitude;
	}

}
