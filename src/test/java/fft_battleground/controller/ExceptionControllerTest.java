package fft_battleground.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;

import fft_battleground.discord.WebhookManager;
import fft_battleground.exception.CacheMissException;
import fft_battleground.util.GenericResponse;

/**
 * Regression for the null-key NPE (2026-10-23): the String-only
 * CacheMissException constructor leaves getKey() null, and the handler's
 * e.getKey().toString() turned the intended 503-JSON into a raw 500.
 */
public class ExceptionControllerTest {

	@Test
	public void stringOnlyCacheMissReturnsCleanServiceUnavailable() {
		ExceptionController controller = new ExceptionController();
		WebhookManager webhook = mock(WebhookManager.class);
		ReflectionTestUtils.setField(controller, "noisyWebhookManager", webhook);

		CacheMissException exception = new CacheMissException("No cache data for music playlist!");
		ResponseEntity<GenericResponse<String>> response = controller.cacheMissExceptionHandler(exception);

		assertEquals(503, response.getStatusCodeValue());
		assertNotNull(response.getBody());
		assertNotNull(response.getBody().getMessage());
		assertTrue(response.getBody().getMessage().contains("cache not ready"),
				"message was: " + response.getBody().getMessage());
		assertTrue(response.getBody().getMessage().contains("No cache data"),
				"message was: " + response.getBody().getMessage());
	}

}
