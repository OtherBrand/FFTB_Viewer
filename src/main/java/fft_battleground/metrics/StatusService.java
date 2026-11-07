package fft_battleground.metrics;

import java.util.Date;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.gikk.twirk.Twirk;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;

/**
 * Live operational status for the public-lite /api/status endpoint: IRC
 * connection, roster-init health of the last betting cycle, betting
 * freshness, and dump-fetch health. Components are computed on demand from
 * timestamps recorded by the systems themselves. Actuator /health stays
 * locked down — this exposes only the four viewer-specific indicators.
 */
@Service
@Slf4j
public class StatusService {

	public static final String STATUS_UP = "UP";
	public static final String STATUS_DOWN = "DOWN";
	public static final String STATUS_UNKNOWN = "UNKNOWN";

	protected static final long BETTING_FRESH_WINDOW_MILLIS = 60 * 60 * 1000L;
	protected static final long DUMP_FETCH_FRESH_WINDOW_MILLIS = 48 * 60 * 60 * 1000L;
	protected static final long ELO_GRADING_STALE_MILLIS = 30 * 60 * 1000L;
	protected static final long WORD_FLUSH_STALE_MILLIS = 10 * 60 * 1000L;
	protected static final long FORECAST_STALE_MILLIS = 30 * 60 * 1000L;
	protected static final long IRC_MESSAGE_STALE_MILLIS = 15 * 60 * 1000L;
	protected static final long BOTLAND_CYCLE_STALE_MILLIS = 15 * 60 * 1000L;

	@Autowired(required = false)
	private Twirk twirk;

	@Value("${useIrc:true}")
	private String useIrc;

	private volatile Date lastBettingBegins;
	private volatile int lastCycleInitFailures = -1; // -1 = no cycle since boot
	private volatile Date lastRosterInitCycle;
	private volatile Date lastDumpFetchSuccess;
	private volatile Date lastDumpFetchFailure;
	private volatile Date lastEloGraded;
	private volatile Date lastWordFlush;
	private volatile Date lastForecastLogged;
	private volatile Date lastIrcMessageReceived;
	private volatile Date lastBotlandCycle;
	private volatile Date lastBotlandCycleFailure;

	public void markBettingBegins() {
		this.lastBettingBegins = new Date();
	}

	public void recordRosterInitCycle(int failures) {
		this.lastCycleInitFailures = failures;
		this.lastRosterInitCycle = new Date();
	}

	public void markDumpFetchSuccess() {
		this.lastDumpFetchSuccess = new Date();
	}

	public void markDumpFetchFailure() {
		this.lastDumpFetchFailure = new Date();
	}

	public void markEloGraded() {
		this.lastEloGraded = new Date();
	}

	public void markWordFlush() {
		this.lastWordFlush = new Date();
	}

	public void markForecastLogged() {
		this.lastForecastLogged = new Date();
	}

	public void markIrcMessageReceived() {
		this.lastIrcMessageReceived = new Date();
	}

	public void markBotlandCycle() {
		this.lastBotlandCycle = new Date();
	}

	public void markBotlandCycleFailed() {
		this.lastBotlandCycleFailure = new Date();
	}

	public ViewerStatus getStatus() {
		ViewerStatus status = new ViewerStatus();
		status.setIrc(this.ircStatus());
		status.setRosterInit(this.rosterInitStatus());
		status.setBettingFreshness(this.bettingFreshnessStatus());
		status.setDumpFetch(this.dumpFetchStatus());
		status.setDataPipelines(this.dataPipelinesStatus());
		status.setBotlandLiveness(this.botlandLivenessStatus());
		status.setGeneratedAt(new Date());
		return status;
	}

	protected ComponentStatus ircStatus() {
		boolean ircEnabled = this.useIrc != null && this.useIrc.equalsIgnoreCase("true");
		if(!ircEnabled) {
			return new ComponentStatus(STATUS_UNKNOWN, "irc disabled");
		}
		//isConnected() reports UP on a half-dead socket (the 8-hour outage of 2026-10-26
		//showed "connected" the whole time) — message freshness is the ground truth
		boolean connected = this.isTwirkConnected();
		String freshness = this.lastIrcMessageReceived != null
				? "last message " + describeAge(this.ageOf(this.lastIrcMessageReceived)) + " ago"
				: "no message since boot";
		if(connected && this.isIrcMessageFresh()) {
			return new ComponentStatus(STATUS_UP, "connected as " + this.twirkNick() + ", " + freshness);
		}
		boolean streamLive = this.lastBettingBegins != null
				&& System.currentTimeMillis() - this.lastBettingBegins.getTime() <= BETTING_FRESH_WINDOW_MILLIS;
		if(streamLive || !connected) {
			return new ComponentStatus(STATUS_DOWN,
					(connected ? "socket connected but stale — " : "not connected — ") + freshness);
		}
		return new ComponentStatus(STATUS_UNKNOWN, "connected, " + freshness + " (stream dark)");
	}

	//seams: Twirk is final and unmockable — tests override these instead
	protected boolean isTwirkConnected() {
		return this.twirk != null && this.twirk.isConnected();
	}

	protected String twirkNick() {
		return this.twirk != null ? this.twirk.getNick() : "unknown";
	}

	protected boolean isIrcMessageFresh() {
		return this.lastIrcMessageReceived != null
				&& System.currentTimeMillis() - this.lastIrcMessageReceived.getTime() <= IRC_MESSAGE_STALE_MILLIS;
	}

	protected ComponentStatus rosterInitStatus() {
		if(this.lastRosterInitCycle == null) {
			return new ComponentStatus(STATUS_UNKNOWN, "no betting cycle since boot");
		}
		return this.lastCycleInitFailures == 0
				? new ComponentStatus(STATUS_UP, "0 init failures in the last cycle")
				: new ComponentStatus(STATUS_DOWN, this.lastCycleInitFailures + " init failure(s) in the last cycle");
	}

	protected ComponentStatus bettingFreshnessStatus() {
		if(this.lastBettingBegins == null) {
			return new ComponentStatus(STATUS_UNKNOWN, "no betting has begun since boot");
		}
		long age = System.currentTimeMillis() - this.lastBettingBegins.getTime();
		if(age <= BETTING_FRESH_WINDOW_MILLIS) {
			return new ComponentStatus(STATUS_UP, "last betting began " + describeAge(age) + " ago");
		}
		return new ComponentStatus(STATUS_DOWN, "last betting began " + describeAge(age) + " ago (stream may be dark)");
	}

	protected ComponentStatus dumpFetchStatus() {
		if(this.lastDumpFetchFailure != null
				&& (this.lastDumpFetchSuccess == null || this.lastDumpFetchFailure.after(this.lastDumpFetchSuccess))) {
			return new ComponentStatus(STATUS_DOWN, "last dump fetch failed " + describeAge(this.ageOf(this.lastDumpFetchFailure)) + " ago");
		}
		if(this.lastDumpFetchSuccess == null) {
			return new ComponentStatus(STATUS_UNKNOWN, "no dump fetch since boot");
		}
		long age = this.ageOf(this.lastDumpFetchSuccess);
		if(age <= DUMP_FETCH_FRESH_WINDOW_MILLIS) {
			return new ComponentStatus(STATUS_UP, "last successful fetch " + describeAge(age) + " ago");
		}
		return new ComponentStatus(STATUS_UNKNOWN, "no successful fetch in " + describeAge(age) + " (stream may be dark)");
	}

	protected long ageOf(Date date) {
		return System.currentTimeMillis() - date.getTime();
	}

	protected static String describeAge(long millis) {
		long minutes = millis / 60000L;
		if(minutes < 60) {
			return minutes + "m";
		}
		return (minutes / 60) + "h";
	}

	@Data
	@AllArgsConstructor
	public static class ComponentStatus {
		private String status; // UP | DOWN | UNKNOWN
		private String detail;
	}

	@Data
	public static class ViewerStatus {
		private ComponentStatus irc;
		private ComponentStatus rosterInit;
		private ComponentStatus bettingFreshness;
		private ComponentStatus dumpFetch;
		private DataPipelinesStatus dataPipelines;
		private ComponentStatus botlandLiveness;
		private Date generatedAt;
	}

	/** per-pipeline staleness: red only when stale WHILE the stream is live (betting fresh) */
	protected ComponentStatus botlandLivenessStatus() {
		boolean streamLive = this.lastBettingBegins != null
				&& System.currentTimeMillis() - this.lastBettingBegins.getTime() <= BETTING_FRESH_WINDOW_MILLIS;
		String failure = this.lastBotlandCycleFailure != null
				? " (last cycle failure " + describeAge(this.ageOf(this.lastBotlandCycleFailure)) + " ago)"
				: "";
		if(this.lastBotlandCycle != null
				&& System.currentTimeMillis() - this.lastBotlandCycle.getTime() <= BOTLAND_CYCLE_STALE_MILLIS) {
			return new ComponentStatus(STATUS_UP, "cycle ran " + describeAge(this.ageOf(this.lastBotlandCycle)) + " ago" + failure);
		}
		if(streamLive) {
			return new ComponentStatus(STATUS_DOWN,
					"no botland cycle in " + (this.lastBotlandCycle != null
							? describeAge(this.ageOf(this.lastBotlandCycle)) : "forever") + " while stream is live" + failure);
		}
		return new ComponentStatus(STATUS_UNKNOWN, "idle (stream dark)" + failure);
	}

	@Data
	public static class DataPipelinesStatus {
		private ComponentStatus eloGrading;
		private ComponentStatus wordIngestion;
		private ComponentStatus forecastLogging;
	}

	protected DataPipelinesStatus dataPipelinesStatus() {
		boolean streamLive = this.lastBettingBegins != null
				&& System.currentTimeMillis() - this.lastBettingBegins.getTime() <= BETTING_FRESH_WINDOW_MILLIS;
		DataPipelinesStatus pipelines = new DataPipelinesStatus();
		pipelines.setEloGrading(this.pipelineStatus(this.lastEloGraded, ELO_GRADING_STALE_MILLIS, streamLive, "elo grading"));
		pipelines.setWordIngestion(this.pipelineStatus(this.lastWordFlush, WORD_FLUSH_STALE_MILLIS, streamLive, "word ingestion"));
		pipelines.setForecastLogging(this.pipelineStatus(this.lastForecastLogged, FORECAST_STALE_MILLIS, streamLive, "forecast logging"));
		return pipelines;
	}

	protected ComponentStatus pipelineStatus(Date lastSuccess, long staleWindow, boolean streamLive, String name) {
		if(lastSuccess == null) {
			return new ComponentStatus(STATUS_UNKNOWN, name + " has not run since boot");
		}
		long age = this.ageOf(lastSuccess);
		if(age <= staleWindow) {
			return new ComponentStatus(STATUS_UP, name + " last succeeded " + describeAge(age) + " ago");
		}
		if(streamLive) {
			return new ComponentStatus(STATUS_DOWN, name + " stale " + describeAge(age) + " while stream is live");
		}
		return new ComponentStatus(STATUS_UNKNOWN, name + " idle " + describeAge(age) + " (stream dark)");
	}

}
