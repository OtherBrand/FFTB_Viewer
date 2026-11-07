package fft_battleground.controller;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseBody;

import fft_battleground.metrics.StatusService;
import fft_battleground.util.GenericResponse;
import springfox.documentation.annotations.ApiIgnore;

/**
 * Public-lite operational status (IRC, roster init, betting freshness,
 * dump fetch). Actuator /health stays locked down; this exposes only the
 * four viewer-specific indicators, no memory/paths/env details.
 */
@Controller
@RequestMapping("/api/status")
@ApiIgnore
public class StatusController {

	@Autowired
	private StatusService statusService;

	@ApiIgnore
	@GetMapping
	public @ResponseBody ResponseEntity<GenericResponse<StatusService.ViewerStatus>> getStatus() {
		return GenericResponse.createGenericResponseEntity(this.statusService.getStatus());
	}

}
