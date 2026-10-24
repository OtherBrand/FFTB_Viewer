package fft_battleground.controller;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;

import fft_battleground.discord.WebhookManager;
import fft_battleground.exception.BattleGroundException;
import fft_battleground.exception.CacheMissException;
import fft_battleground.util.GenericResponse;

@ControllerAdvice
public class ExceptionController {

	@Autowired
	private WebhookManager noisyWebhookManager;

	//the frontend for these endpoints is an SPA, so return JSON error bodies instead of Thymeleaf view names
	//(the old templates no longer exist in this repo, which turned every handled error into a secondary 500)

	@ExceptionHandler(BattleGroundException.class)
	public ResponseEntity<GenericResponse<String>> battleGroundExceptionHandler(BattleGroundException e) {
		this.noisyWebhookManager.sendException(e, "error accessing controllers");
		return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
				.body(new GenericResponse<String>(e.getMessage(), null));
	}

	@ExceptionHandler(CacheMissException.class)
	public ResponseEntity<GenericResponse<String>> cacheMissExceptionHandler(CacheMissException e) {
		this.noisyWebhookManager.sendException(e, "error accessing cache");
		//the String-only constructor leaves the key null (e.g. MusicController), so fall back to the message
		String detail = e.getKey() != null ? e.getKey().toString() : e.getMessage();
		return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
				.body(new GenericResponse<String>("cache not ready: " + detail, null));
	}

}
