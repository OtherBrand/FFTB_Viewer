package fft_battleground.scheduled;

import java.util.Date;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import fft_battleground.botland.BetBotFactory;
import fft_battleground.discord.WebhookManager;
import fft_battleground.repo.repository.BotsHourlyDataRepo;
import lombok.extern.slf4j.Slf4j;

/**
 * Roster persistence self-check: while matches are running, every visible
 * roster bot should record bot_hourly_data updates (each bot bets and gets
 * credited every cycle). A visible bot with no update while its peers are
 * updating is the frozen-sims bug class (e.g. the capital-V lookup miss,
 * 2026-10-24) — previously visible only as a journal silence.
 */
@Component
@Slf4j
public class RosterPersistenceCheckTask {

	protected static final long ACTIVITY_WINDOW_MILLIS = 60 * 60 * 1000L; // trailing hour

	@Autowired
	private BetBotFactory betBotFactory;

	@Autowired
	private BotsHourlyDataRepo botsHourlyDataRepo;

	@Autowired
	private WebhookManager errorWebhookManager;

	@Scheduled(initialDelay = 30 * 60 * 1000L, fixedDelay = 30 * 60 * 1000L)
	public void checkRosterPersistence() {
		try {
			Set<String> visibleBots = this.betBotFactory.getVisibleBotNames();
			if(visibleBots.isEmpty()) {
				return; // no roster loaded (early boot) — nothing to check
			}

			Date cutoff = new Date(System.currentTimeMillis() - ACTIVITY_WINDOW_MILLIS);
			Set<String> recentlyUpdated = this.botsHourlyDataRepo.getRecentlyUpdatedPlayers(cutoff).stream()
					.collect(Collectors.toSet());
			if(recentlyUpdated.isEmpty()) {
				//nobody updated: the stream is dark or idle, not a persistence gap
				log.debug("no bot_hourly_data activity in the trailing hour; skipping roster persistence check");
				return;
			}

			Set<String> missing = visibleBots.stream()
					.filter(name -> !recentlyUpdated.contains(name))
					.collect(Collectors.toSet());
			if(missing.isEmpty()) {
				return;
			}

			String missingNames = missing.stream().sorted().collect(Collectors.joining(", "));
			String message = "Roster persistence self-check: " + missing.size() + " visible bot(s) with no bot_hourly_data update in the trailing hour: "
					+ missingNames;
			log.error(message);
			this.errorWebhookManager.sendException(new Exception(message), "roster persistence self-check");
		} catch(Exception e) {
			log.error("roster persistence check failed", e);
		}
	}

}
