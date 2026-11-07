package fft_battleground.forecast;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseBody;

import fft_battleground.forecast.ForecastModels.ForecastRequest;
import fft_battleground.forecast.ForecastModels.ForecastResponse;
import fft_battleground.util.GenericResponse;
import springfox.documentation.annotations.ApiIgnore;

/**
 * POST /api/matches/forecast — the stateless advisor-v2 oracle. The live
 * page POSTs the full match composition once both TEAM_INFOs are complete;
 * there is no server-side trigger machinery (the explain-roster chain this
 * replaces was retired 2026-10-25).
 */
@Controller
@RequestMapping("/api/matches")
@ApiIgnore
public class ForecastController {

	@Autowired
	private ForecastService forecastService;

	@ApiIgnore
	@PostMapping("/forecast")
	public @ResponseBody ResponseEntity<GenericResponse<ForecastResponse>> forecast(@RequestBody ForecastRequest request) {
		ForecastResponse response = this.forecastService.forecast(request);
		if(response == null) {
			return GenericResponse.<ForecastResponse>createGenericResponseEntity(null, "forecast model unavailable", HttpStatus.SERVICE_UNAVAILABLE);
		}
		return GenericResponse.createGenericResponseEntity(response);
	}

}
