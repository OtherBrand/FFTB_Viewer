package fft_battleground.irc;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.gikk.twirk.Twirk;
import com.gikk.twirk.events.TwirkListener;

import fft_battleground.discord.WebhookManager;
import fft_battleground.exception.IrcConnectionException;
import fft_battleground.util.BattlegroundRetryState;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
public class DisconnectListener  implements TwirkListener {

	@Autowired
	private Twirk twirk;
	
	@Autowired
	private WebhookManager errorWebhookManager;
	
	@Autowired
	private IrcReconnectManager ircReconnectManager;
	
	private static final String errorAvertedMessageFormat = "IRC Disconnect averted with count %o";
	private static final String criticalErrorMessageFormat = "Critical error, unable to reconnect to IRC despite %o attempts";
	
	public DisconnectListener() {}
	
	public DisconnectListener(Twirk twirk, WebhookManager errorWebhookManager) {
		this.twirk = twirk;
		this.errorWebhookManager = errorWebhookManager;
	}
	
	//the reconnect runs on a watchman thread: a connect() that blocks forever on a
	//half-dead socket (the 2026-10-26 outage: one attempt at 17:17, then 8 silent hours)
	//must end in a process exit so systemd restarts us — boots reconnect on first attempt
	protected static final long RECONNECT_WATCHDOG_MILLIS = 10 * 60 * 1000L;

	@Override
	public void onDisconnect() {
		//Twitch might sometimes disconnects us from chat. If so, try to reconnect.
		final BattlegroundRetryState state = new BattlegroundRetryState();
		final Thread reconnectThread = new Thread(() -> {
			try {
				this.ircReconnectManager.retryConnection(this.twirk, state);
				String message = String.format(errorAvertedMessageFormat, state.getRetryCount());
				this.errorWebhookManager.sendMessage(message);
			}
			catch (IrcConnectionException e) {
				this.terminateAfterFailedReconnect(e, state);
			}
			catch (Throwable t) {
				this.terminateAfterFailedReconnect(new IrcConnectionException(t.getMessage() != null ? t.getMessage() : "reconnect threw " + t.getClass().getSimpleName()), state);
			}
		}, "irc-reconnect");
		reconnectThread.setDaemon(true);
		reconnectThread.start();
		try {
			reconnectThread.join(RECONNECT_WATCHDOG_MILLIS);
			if(reconnectThread.isAlive()) {
				log.error("IRC reconnect blocked for over {} minutes — exiting for systemd restart",
						RECONNECT_WATCHDOG_MILLIS / 60000);
				this.errorWebhookManager.sendShutdownNotice(
						new IrcConnectionException("reconnect watchdog expired"), "IRC reconnect blocked; restarting");
				this.exitApplication();
			}
		} catch(InterruptedException ie) {
			Thread.currentThread().interrupt();
		}
	}

	protected void terminateAfterFailedReconnect(IrcConnectionException e, BattlegroundRetryState state) {
		try {
			this.twirk.close();
		} catch(Throwable ignored) {}
		String message = String.format(criticalErrorMessageFormat, state.getRetryCount());
		log.error(message, e);
		this.errorWebhookManager.sendShutdownNotice(e, message);
		this.exitApplication();
	}

	/**
	 * Seam for tests: prod halts so systemd restarts (every boot reconnects on attempt one).
	 * halt() not exit(): the zombie incident of 2026-10-31, definitively root-caused from
	 * the thread dump — Twirk fires onDisconnect while HOLDING its own monitor, so our
	 * callback's System.exit ran shutdown hooks that need that same monitor back: circular
	 * wait, eternal half-dead JVM (Tomcat gone, orphans alive, systemd 'active'). halt()
	 * never enters the hook sequence — the lock-holding thread simply dies with the JVM.
	 * RULE: never exit from inside a callback that may hold external locks.
	 */
	protected void exitApplication() {
		Runtime.getRuntime().halt(1);
	}
	
}
