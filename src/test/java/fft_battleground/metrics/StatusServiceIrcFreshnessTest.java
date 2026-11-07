package fft_battleground.metrics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Date;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

import fft_battleground.metrics.StatusService.ComponentStatus;

/**
 * The 8-hour outage showed isConnected()==true on a dead socket — message
 * freshness is the ground truth: stale while the stream is live = DOWN.
 */
public class StatusServiceIrcFreshnessTest {

	private StatusService service(boolean connected, Date lastMessage, Date lastBetting) {
		StatusService service = new StatusService() {
			@Override
			protected boolean isTwirkConnected() {
				return connected;
			}

			@Override
			protected String twirkNick() {
				return "datadrivenbot";
			}
		};
		ReflectionTestUtils.setField(service, "useIrc", "true");
		if(lastMessage != null) {
			ReflectionTestUtils.setField(service, "lastIrcMessageReceived", lastMessage);
		}
		if(lastBetting != null) {
			ReflectionTestUtils.setField(service, "lastBettingBegins", lastBetting);
		}
		return service;
	}

	@Test
	public void staleSocketWhileStreamLiveIsDown() {
		StatusService service = this.service(true,
				new Date(System.currentTimeMillis() - 30 * 60 * 1000L), // 30min quiet
				new Date()); // betting began now = live
		ComponentStatus status = service.ircStatus();
		assertEquals("DOWN", status.getStatus(), "half-dead socket must read DOWN, not UP");
		assertTrue(status.getDetail().contains("stale"), "detail explains the staleness: " + status.getDetail());
	}

	@Test
	public void freshMessagesAreUp() {
		StatusService service = this.service(true, new Date(), new Date());
		assertEquals("UP", service.ircStatus().getStatus());
	}

	@Test
	public void quietWhileDarkIsUnknownNotDown() {
		StatusService service = this.service(true,
				new Date(System.currentTimeMillis() - 60 * 60 * 1000L), // 1h quiet
				new Date(System.currentTimeMillis() - 3 * 60 * 60 * 1000L)); // stream dark 3h
		ComponentStatus status = service.ircStatus();
		assertEquals("UNKNOWN", status.getStatus(), "dark-stream quiet must not page");
		assertTrue(status.getDetail().contains("dark"), status.getDetail());
	}

	@Test
	public void genuinelyDisconnectedIsDownEvenWhenDark() {
		StatusService service = this.service(false, null,
				new Date(System.currentTimeMillis() - 3 * 60 * 60 * 1000L));
		assertEquals("DOWN", service.ircStatus().getStatus());
	}
}
