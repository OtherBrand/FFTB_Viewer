package fft_battleground.irc;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;

import fft_battleground.discord.WebhookManager;
import fft_battleground.exception.IrcConnectionException;
import fft_battleground.util.BattlegroundRetryState;

/**
 * The 8-hour silent-death outage (2026-10-26): a reconnect that fails must
 * END THE PROCESS so systemd restarts — never return quietly. A blocked
 * reconnect holds under the watchdog (exits at its timeout in prod).
 */
public class DisconnectListenerTest {

	private static class TestableListener extends DisconnectListener {
		final AtomicBoolean exited = new AtomicBoolean(false);

		TestableListener() {
			super();
			org.springframework.test.util.ReflectionTestUtils.setField(this, "twirk",
					new com.gikk.twirk.TwirkBuilder("#fftbattleground", "test", "test").build());
			org.springframework.test.util.ReflectionTestUtils.setField(this, "errorWebhookManager", mock(WebhookManager.class));
			org.springframework.test.util.ReflectionTestUtils.setField(this, "ircReconnectManager", mock(IrcReconnectManager.class));
		}

		@Override
		protected void exitApplication() {
			this.exited.set(true); // test seam: no real System.exit
		}
	}

	@Test
	public void failedReconnectExitsInsteadOfReturningQuietly() throws fft_battleground.exception.IrcConnectionException {
		TestableListener listener = new TestableListener();
		IrcReconnectManager manager = mock(IrcReconnectManager.class);
		doThrow(new IrcConnectionException("dead")).when(manager)
				.retryConnection(any(com.gikk.twirk.Twirk.class), any(BattlegroundRetryState.class));
		org.springframework.test.util.ReflectionTestUtils.setField(listener, "ircReconnectManager", manager);

		listener.onDisconnect();

		assertTrue(listener.exited.get(), "final reconnect failure must exit for systemd restart");
	}

	@Test
	public void blockedReconnectHoldsUnderWatchdogWithoutPrematureExit() throws Exception {
		TestableListener listener = new TestableListener();
		IrcReconnectManager manager = mock(IrcReconnectManager.class);
		//the observed failure mode: connect() blocks on a half-dead socket
		doAnswer(inv -> {
			Thread.sleep(60_000L);
			return null;
		}).when(manager).retryConnection(any(com.gikk.twirk.Twirk.class), any(BattlegroundRetryState.class));
		org.springframework.test.util.ReflectionTestUtils.setField(listener, "ircReconnectManager", manager);

		Thread listenerThread = new Thread(listener::onDisconnect);
		listenerThread.setDaemon(true);
		listenerThread.start();
		listenerThread.join(5_000L);
		assertTrue(listenerThread.isAlive(), "blocked reconnect stays held (prod watchdog exits at 10min)");
		assertFalse(listener.exited.get(), "no premature exit while the watchdog is still counting");
	}

	@Test
	public void successfulReconnectDoesNotExit() throws Exception {
		TestableListener listener = new TestableListener();
		Thread listenerThread = new Thread(listener::onDisconnect);
		listenerThread.setDaemon(true);
		listenerThread.start();
		listenerThread.join(10_000L);
		assertFalse(listener.exited.get(), "clean reconnect must keep running");
		assertFalse(listenerThread.isAlive(), "onDisconnect returns after success");
	}
}
