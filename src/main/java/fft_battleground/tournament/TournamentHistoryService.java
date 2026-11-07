package fft_battleground.tournament;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import fft_battleground.controller.response.model.tournaments.TournamentHistoryModels.ForecastStats;
import fft_battleground.exception.TournamentApiException;
import fft_battleground.controller.response.model.tournaments.TournamentHistoryModels.MatchRecord;
import fft_battleground.controller.response.model.tournaments.TournamentHistoryModels.ReignRecord;
import fft_battleground.controller.response.model.tournaments.TournamentHistoryModels.TournamentDetail;
import fft_battleground.controller.response.model.tournaments.TournamentHistoryModels.TournamentSummary;
import fft_battleground.model.BattleGroundTeam;
import fft_battleground.tournament.model.Pot;
import fft_battleground.tournament.model.Team;
import fft_battleground.tournament.model.TeamBet;
import fft_battleground.tournament.model.Tournament;
import fft_battleground.tournament.model.TournamentInfo;
import fft_battleground.controller.response.model.tournaments.TournamentHistoryModels.UnitRecord;
import fft_battleground.tournament.model.Unit;
import fft_battleground.util.HttpClientFactory;
import lombok.extern.slf4j.Slf4j;

/**
 * Tournament + champion history views (frozen contract 2026-10-25). The
 * tournament scan (list endpoint pages, ~17 calls) is cached and refreshed
 * on a slow cadence — crowns/streaks/reigns all derive from it; per-tournament
 * detail (rosters/pots/odds) is fetched per id through the existing rest
 * service. Forecast stats serve holdout-first from the trainer's backfill
 * artifact shipped in resources.
 */
@Service
@Slf4j
public class TournamentHistoryService {

	protected static final long SCAN_TTL_MILLIS = 6 * 60 * 60 * 1000L;
	private static final String LIST_URI = "https://fftbg.com/api/tournaments?limit=100";

	@Autowired
	private TournamentRestService tournamentRestService;

	@org.springframework.beans.factory.annotation.Autowired
	private fft_battleground.forecast.WinProbModel winProbModel;

	/** v2 history is 120MB — ships as a file on disk beside the WAR, not inside it
	 *  (raw or .gz, auto-detected); the classpath v1 file remains the last-resort
	 *  fallback so a missing external file degrades stats rather than breaking them */
	@org.springframework.beans.factory.annotation.Value("${fft_battleground.forecastHistory.path:/opt/fftbviewer/models/forecast-history.json}")
	private String forecastHistoryPath = "/opt/fftbviewer/models/forecast-history.json";

	private volatile ForecastStats cachedForecastStats;
	private volatile boolean forecastStatsParsed = false;

	private final RestTemplate restTemplate = HttpClientFactory.createRestTemplate();
	private final ObjectMapper jsonMapper = new ObjectMapper();

	private volatile List<TournamentInfo> scanCache;
	private volatile long scanLoadedAt = 0;

	public List<TournamentSummary> recentSummaries(int page, int pageSize) {
		List<TournamentInfo> scan = this.scan();
		List<TournamentInfo> descending = scan.stream()
				.sorted(Comparator.comparingLong(TournamentInfo::getID).reversed())
				.collect(Collectors.toList());
		Map<Long, BattleGroundTeam> resolution = this.resolvedChampions(this.ascending(scan));
		Map<String, Integer> careerCrowns = this.careerCrowns(scan, resolution);

		int from = Math.max(0, page * pageSize);
		int to = Math.min(descending.size(), from + pageSize);
		List<TournamentSummary> summaries = new ArrayList<>();
		for(int i = from; i < to; i++) {
			summaries.add(this.summaryFor(descending, i, careerCrowns, resolution));
		}
		return summaries;
	}

	public TournamentDetail detail(Long tournamentId) throws TournamentApiException {
		Tournament tournament = this.tournamentRestService.getTournamentById(tournamentId);
		if(tournament == null) {
			return null;
		}
		List<TournamentInfo> scan = this.scan();
		List<TournamentInfo> descending = scan.stream()
				.sorted(Comparator.comparingLong(TournamentInfo::getID).reversed())
				.collect(Collectors.toList());
		int index = -1;
		for(int i = 0; i < descending.size(); i++) {
			if(descending.get(i).getID().equals(tournamentId)) {
				index = i;
				break;
			}
		}
		Map<Long, BattleGroundTeam> resolution = this.resolvedChampions(this.ascending(scan));
		TournamentSummary summary = index >= 0
				? this.summaryFor(descending, index, this.careerCrowns(scan, resolution), resolution)
				: this.summaryFromTournament(tournament, resolution);

		TournamentDetail detail = new TournamentDetail();
		detail.setSummary(summary);
		detail.setMatches(this.matchRecords(tournament,
				this.incomingChampion(tournament.getID(), resolution)));
		detail.setRosters(this.rosters(tournament, resolution));
		return detail;
	}

	public List<ReignRecord> reigns() {
		List<TournamentInfo> ascending = this.ascending(this.scan());
		Map<Long, BattleGroundTeam> resolution = this.resolvedChampions(ascending);
		Map<String, Integer> careerCrowns = this.careerCrowns(this.scan(), resolution);

		List<ReignRecord> reigns = new ArrayList<>();
		int runStart = 0;
		while(runStart < ascending.size()) {
			BattleGroundTeam champion = this.resolvedChampionOf(ascending.get(runStart), resolution);
			if(champion == null) {
				runStart++;
				continue;
			}
			int runEnd = runStart;
			int matches = 0;
			while(runEnd < ascending.size() && this.resolvedChampionOf(ascending.get(runEnd), resolution) == champion) {
				matches += this.matchCountOf(ascending.get(runEnd));
				runEnd++;
			}
			ReignRecord reign = new ReignRecord();
			reign.setReignId(String.valueOf(ascending.get(runStart).getID()));
			reign.setTeam(champion);
			reign.setStartedAt(ascending.get(runStart).getID());
			reign.setEndedAt(runEnd < ascending.size() ? ascending.get(runEnd).getID() : null);
			reign.setDefenses(runEnd - runStart - 1); // titles beyond the first are defenses
			reign.setMatches(matches);
			reign.setAvgBackerElo(null); // forward-only ELO: null for historical reigns
			reign.setCrownCount(this.crownsBefore(ascending, runStart, champion, careerCrowns));
			reigns.add(reign);
			runStart = runEnd;
		}

		//current first
		reigns.sort(Comparator.comparing(ReignRecord::getStartedAt, Comparator.nullsLast(Comparator.reverseOrder())));
		return reigns;
	}

	public List<UnitRecord> reignRoster(String reignId) throws TournamentApiException {
		//derive the run directly: the roster wanted is the champion's units as of
		//the LAST tournament of the reign (endedAt is the NEXT reign's start)
		List<TournamentInfo> ascending = this.ascending(this.scan());
		Map<Long, BattleGroundTeam> resolution = this.resolvedChampions(ascending);
		Long finalTournamentId = null;
		BattleGroundTeam champion = null;
		for(int i = 0; i < ascending.size(); i++) {
			BattleGroundTeam team = this.resolvedChampionOf(ascending.get(i), resolution);
			if(team == null) {
				continue;
			}
			int runEnd = i;
			while(runEnd < ascending.size() && this.resolvedChampionOf(ascending.get(runEnd), resolution) == team) {
				runEnd++;
			}
			if(String.valueOf(ascending.get(i).getID()).equals(reignId)) {
				champion = team;
				finalTournamentId = ascending.get(runEnd - 1).getID(); // the run's true last tournament
				break;
			}
			i = runEnd - 1;
		}
		if(finalTournamentId == null || champion == null) {
			return null;
		}
		Tournament tournament = this.tournamentRestService.getTournamentById(finalTournamentId);
		if(tournament == null) {
			return null;
		}
		//the defending champion enters the bracket under the champion entrant key —
		//its units are the real team roster even though the reign identity is resolved
		Team roster = tournament.getTeams().getTeamByBattleGroundTeam(BattleGroundTeam.CHAMPION);
		if(roster == null || roster.getUnits() == null) {
			roster = tournament.getTeams().getTeamByBattleGroundTeam(champion);
		}
		return roster != null && roster.getUnits() != null
				? roster.getUnits().stream().map(UnitRecord::from).collect(Collectors.toList())
				: List.of();
	}

	public ForecastStats forecastStats() {
		//120MB / 553k predictions: parse ONCE (first request after boot), never per-call
		if(this.forecastStatsParsed) {
			return this.cachedForecastStats;
		}
		synchronized(this) {
			if(this.forecastStatsParsed) {
				return this.cachedForecastStats;
			}
			this.cachedForecastStats = this.parseForecastStats();
			this.forecastStatsParsed = true;
			return this.cachedForecastStats;
		}
	}

	private ForecastStats parseForecastStats() {
		ForecastStats stats = new ForecastStats();
		java.io.InputStream in = null;
		String source = "unavailable";
		try {
			java.io.File external = new java.io.File(this.forecastHistoryPath);
			if(!external.isFile() && !this.forecastHistoryPath.endsWith(".gz")) {
				java.io.File gz = new java.io.File(this.forecastHistoryPath + ".gz");
				if(gz.isFile()) {
					external = gz;
				}
			}
			if(external.isFile()) {
				source = "external";
				in = new java.io.FileInputStream(external);
				if(external.getName().endsWith(".gz")) {
					in = new java.util.zip.GZIPInputStream(in);
				}
			} else {
				org.springframework.core.io.ClassPathResource classpath =
						new org.springframework.core.io.ClassPathResource("forecast-history.json");
				if(classpath.exists()) {
					source = "classpath";
					in = classpath.getInputStream();
				}
			}
			if(in == null) {
				stats.setModelVersion(this.servedModelVersion("unavailable"));
				return stats;
			}
			//STREAM (never readTree): 120MB of JSON as a tree is ~2-4GB of JsonNodes and
			//OOMs the 3.8GB prod box — the grand-window zeroed-stats bug was exactly that
			//OOM caught and served as empty aggregates under the v2 modelVersion label
			StreamingForecastStats.Result reduced = StreamingForecastStats.reduce(in, this.jsonMapper);
			stats = new ForecastStats();
			stats.setMatchesScored(reduced.holdoutScored);
			stats.setHits(reduced.holdoutHits);
			stats.setHitRate(reduced.holdoutScored > 0 ? (double) reduced.holdoutHits / reduced.holdoutScored : 0.0);
			stats.setInSampleMatches(reduced.inSampleScored);
			stats.setInSampleHits(reduced.inSampleHits);
			stats.setModelVersion(this.servedModelVersion(
					reduced.model + "/v" + reduced.modelVersion));
			log.info("forecast stats loaded from {} ({} holdout / {} in-sample predictions)",
					source, reduced.holdoutScored, reduced.inSampleScored);
		} catch(OutOfMemoryError oom) {
			log.error("forecast history too large to parse in-heap; serving unavailable", oom);
			stats = new ForecastStats();
			stats.setModelVersion(this.servedModelVersion("unavailable"));
		} catch(Exception e) {
			log.warn("forecast history unavailable: {}", e.getMessage());
			stats = new ForecastStats();
			stats.setModelVersion(this.servedModelVersion("unavailable"));
		} finally {
			if(in != null) {
				try { in.close(); } catch(Exception ignored) {}
			}
		}
		return stats;
	}

	private ForecastStats reduceForecastStats(JsonNode root, String source) {
		ForecastStats stats = new ForecastStats();
		int holdoutScored = 0, holdoutHits = 0, inSampleScored = 0, inSampleHits = 0;
		//stream the array from the tree without materializing 553k nodes as objects
		for(JsonNode prediction : root.path("predictions")) {
			boolean correct = prediction.path("correct").asBoolean(false);
			if(prediction.path("in_sample").asBoolean(false)) {
				inSampleScored++;
				if(correct) {
					inSampleHits++;
				}
			} else {
				holdoutScored++;
				if(correct) {
					holdoutHits++;
				}
			}
		}
		stats.setMatchesScored(holdoutScored);
		stats.setHits(holdoutHits);
		stats.setHitRate(holdoutScored > 0 ? (double) holdoutHits / holdoutScored : 0.0);
		stats.setInSampleMatches(inSampleScored);
		stats.setInSampleHits(inSampleHits);
		stats.setModelVersion(this.servedModelVersion(
				root.path("model").asText(root.path("header").path("model").asText("unknown"))
						+ "/v" + root.path("model_version").asInt(root.path("header").path("model_version").asInt(0))));
		log.info("forecast stats loaded from {} ({} predictions)", source, holdoutScored + inSampleScored);
		return stats;
	}

	private String servedModelVersion(String fallback) {
		try {
			return this.winProbModel != null && this.winProbModel.isLoaded()
					? this.winProbModel.getModelVersion() : fallback;
		} catch(Exception e) {
			return fallback;
		}
	}

	//--- scan ---

	protected List<TournamentInfo> scan() {
		List<TournamentInfo> cached = this.scanCache;
		if(cached != null && System.currentTimeMillis() - this.scanLoadedAt < SCAN_TTL_MILLIS) {
			return cached;
		}
		synchronized(this) {
			if(this.scanCache != null && System.currentTimeMillis() - this.scanLoadedAt < SCAN_TTL_MILLIS) {
				return this.scanCache;
			}
			List<TournamentInfo> all = new ArrayList<>();
			try {
				Long before = null;
				for(int page = 0; page < 40; page++) { // hard cap ~4000 tournaments
					String uri = LIST_URI + (before != null ? "&before=" + before : "");
					TournamentInfo[] batch = this.restTemplate.getForObject(uri, TournamentInfo[].class);
					if(batch == null || batch.length == 0) {
						break;
					}
					all.addAll(List.of(batch));
					before = batch[batch.length - 1].getID();
					if(batch.length < 100) {
						break;
					}
				}
				this.scanCache = all;
				this.scanLoadedAt = System.currentTimeMillis();
				log.info("tournament history scan loaded {} tournaments", all.size());
			} catch(Exception e) {
				log.error("tournament history scan failed; serving stale cache if present", e);
				if(this.scanCache != null) {
					return this.scanCache;
				}
			}
			return this.scanCache != null ? this.scanCache : List.of();
		}
	}

	//--- derivations ---

	/**
	 * Alias resolution (champion-page bug, 2026-11-02): FFTBG reports retained
	 * titles under the LITERAL winner 'champion' instead of the defending team
	 * real color — aggregating as one fake Champion team with thousands of
	 * titles. Resolution: walk chronologically; a real winner founds/redirects
	 * the streak; 'champion' rows inherit the current streak team; a different
	 * real winner starts the next streak (and is the dethroner BY NAME).
	 */
	protected Map<Long, BattleGroundTeam> resolvedChampions(List<TournamentInfo> ascendingScan) {
		Map<Long, BattleGroundTeam> resolved = new HashMap<>();
		BattleGroundTeam currentReal = null;
		for(TournamentInfo info : ascendingScan) {
			BattleGroundTeam raw = this.championOf(info);
			if(raw == null) {
				continue;
			}
			if(raw != BattleGroundTeam.CHAMPION) {
				currentReal = raw;
			}
			resolved.put(info.getID(), currentReal != null ? currentReal : raw);
		}
		return resolved;
	}

	private List<TournamentInfo> ascending(List<TournamentInfo> scan) {
		return scan.stream().sorted(Comparator.comparingLong(TournamentInfo::getID)).collect(Collectors.toList());
	}

	protected BattleGroundTeam resolvedChampionOf(TournamentInfo info, Map<Long, BattleGroundTeam> resolution) {
		BattleGroundTeam resolved = resolution.get(info.getID());
		return resolved != null ? resolved : this.championOf(info);
	}

	protected BattleGroundTeam championOf(TournamentInfo info) {
		if(info.getWinners() == null) {
			return null;
		}
		for(int i = info.getWinners().size() - 1; i >= 0; i--) {
			String winner = info.getWinners().get(i);
			if(StringUtils.isNotBlank(winner) && !"null".equalsIgnoreCase(winner)) {
				return BattleGroundTeam.parse(winner);
			}
		}
		return null;
	}

	protected int matchCountOf(TournamentInfo info) {
		return info.getWinners() != null ? info.getWinners().size() : 0;
	}

	/** career title count per champion team across the whole scan */
	protected Map<String, Integer> careerCrowns(List<TournamentInfo> scan, Map<Long, BattleGroundTeam> resolution) {
		Map<String, Integer> crowns = new HashMap<>();
		for(TournamentInfo info : scan) {
			BattleGroundTeam champion = this.resolvedChampionOf(info, resolution);
			if(champion != null) {
				crowns.merge(champion.name(), 1, Integer::sum);
			}
		}
		return crowns;
	}

	private int crownsBefore(List<TournamentInfo> ascending, int reignStart, BattleGroundTeam champion, Map<String, Integer> careerCrowns) {
		//titles won strictly before this reign began
		int before = 0;
		for(int i = 0; i < reignStart; i++) {
			if(this.championOf(ascending.get(i)) == champion) {
				before++;
			}
		}
		return before;
	}

	private TournamentSummary summaryFor(List<TournamentInfo> descending, int index, Map<String, Integer> careerCrowns,
			Map<Long, BattleGroundTeam> resolution) {
		TournamentInfo info = descending.get(index);
		BattleGroundTeam champion = this.resolvedChampionOf(info, resolution);
		TournamentSummary summary = new TournamentSummary();
		summary.setTournamentId(String.valueOf(info.getID()));
		summary.setDate(info.getID());
		summary.setMatchCount(this.matchCountOf(info));
		summary.setChampion(champion);
		summary.setRunnerUp(null); // final-match loser needs the detail payload; filled lazily by the page
		summary.setChampionCrowns(champion != null ? careerCrowns.getOrDefault(champion.name(), 0) : null);
		summary.setCompleted(info.getComplete() != null ? info.getComplete() : Boolean.TRUE);
		//the streak CONTAINING this row: expand both directions (a mid-streak row
		//belongs to the whole run, not just the titles older than it)
		int streak = 0;
		if(champion != null) {
			int older = index;
			while(older < descending.size() && this.resolvedChampionOf(descending.get(older), resolution) == champion) {
				streak++;
				older++;
			}
			int newer = index - 1;
			while(newer >= 0 && this.resolvedChampionOf(descending.get(newer), resolution) == champion) {
				streak++;
				newer--;
			}
		}
		summary.setConsecutiveTitles(streak);
		summary.setRetainedNext(index > 0 ? this.resolvedChampionOf(descending.get(index - 1), resolution) == champion : null);
		return summary;
	}

	private TournamentSummary summaryFromTournament(Tournament tournament, Map<Long, BattleGroundTeam> resolution) {
		TournamentSummary summary = new TournamentSummary();
		summary.setTournamentId(String.valueOf(tournament.getID()));
		summary.setDate(tournament.getID());
		summary.setMatchCount(tournament.getWinners() != null ? tournament.getWinners().size() : 0);
		BattleGroundTeam rawChampion = this.finalChampion(tournament);
		summary.setChampion(rawChampion == BattleGroundTeam.CHAMPION && tournament.getID() != null
				? resolution.getOrDefault(tournament.getID(), rawChampion) : rawChampion);
		summary.setRunnerUp(this.finalRunnerUp(tournament));
		summary.setChampionCrowns(null);
		summary.setConsecutiveTitles(null);
		summary.setRetainedNext(null);
		summary.setCompleted(tournament.getWinners() != null && tournament.getWinners().stream()
				.noneMatch(w -> StringUtils.isBlank(w) || "null".equalsIgnoreCase(w)));
		return summary;
	}

	private BattleGroundTeam finalChampion(Tournament tournament) {
		if(tournament.getWinners() == null || tournament.getWinners().isEmpty()) {
			return null;
		}
		String winner = tournament.getWinners().get(tournament.getWinners().size() - 1);
		return StringUtils.isNotBlank(winner) ? BattleGroundTeam.parse(winner) : null;
	}

	private BattleGroundTeam finalRunnerUp(Tournament tournament) {
		BattleGroundTeam champion = this.finalChampion(tournament);
		if(champion == null || tournament.getPots() == null || tournament.getPots().isEmpty()) {
			return null;
		}
		Pot finalPot = tournament.getPots().get(tournament.getPots().size() - 1);
		TeamBet left = finalPot != null ? finalPot.getLeft() : null;
		TeamBet right = finalPot != null ? finalPot.getRight() : null;
		if(left == null || right == null) {
			return null;
		}
		return left.getTeam() == champion ? right.getTeam() : left.getTeam();
	}

	/** the champion entrant of tournament T is the RESOLVED champion of the tournament before T */
	protected BattleGroundTeam incomingChampion(Long tournamentId, Map<Long, BattleGroundTeam> resolution) {
		if(tournamentId == null || resolution.isEmpty()) {
			return BattleGroundTeam.CHAMPION;
		}
		Long previousId = null;
		for(Long id : resolution.keySet()) {
			if(id < tournamentId && (previousId == null || id > previousId)) {
				previousId = id;
			}
		}
		return previousId != null ? resolution.get(previousId) : BattleGroundTeam.CHAMPION;
	}

	private List<MatchRecord> matchRecords(Tournament tournament, BattleGroundTeam incoming) {
		List<MatchRecord> records = new ArrayList<>();
		if(tournament.getPots() == null) {
			return records;
		}
		int count = tournament.getPots().size();
		for(int i = count - 1; i >= 0; i--) { // descending: final first
			Pot pot = tournament.getPots().get(i);
			TeamBet left = pot != null ? pot.getLeft() : null;
			TeamBet right = pot != null ? pot.getRight() : null;
			if(left == null || right == null) {
				continue;
			}
			MatchRecord record = new MatchRecord();
			//champion-entrANT resolution (owner rule 2026-11-03): sides and winner carry the
			//real team; the flag lets the UI render the '(Champion)' qualifier so the bracket
			//identity is not lost
			boolean entrant = false;
			BattleGroundTeam team1 = left.getTeam();
			BattleGroundTeam team2 = right.getTeam();
			if(team1 == BattleGroundTeam.CHAMPION) {
				team1 = incoming;
				entrant = true;
			}
			if(team2 == BattleGroundTeam.CHAMPION) {
				team2 = incoming;
				entrant = true;
			}
			record.setMatchNumber(i + 1);
			record.setTeam1(team1);
			record.setTeam2(team2);
			BattleGroundTeam winner = tournament.getWinners() != null && i < tournament.getWinners().size()
					&& StringUtils.isNotBlank(tournament.getWinners().get(i))
					? BattleGroundTeam.parse(tournament.getWinners().get(i)) : null;
			if(winner == BattleGroundTeam.CHAMPION) {
				winner = incoming;
				entrant = true;
			}
			record.setWinner(winner);
			record.setChampionEntrant(entrant ? Boolean.TRUE : null);
			record.setOdds1(left.getOdds());
			record.setOdds2(right.getOdds());
			record.setPot(this.potTotal(left) + this.potTotal(right));
			records.add(record);
		}
		return records;
	}

	private long potTotal(TeamBet side) {
		if(side == null || side.getBets() == null) {
			return 0;
		}
		return side.getBets().stream().mapToLong(bet -> bet != null && bet.getValue() != null ? bet.getValue() : 0).sum();
	}

	private Map<String, List<UnitRecord>> rosters(Tournament tournament, Map<Long, BattleGroundTeam> resolution) {
		Map<String, List<UnitRecord>> rosters = new LinkedHashMap<>();
		if(tournament.getTeams() == null) {
			return rosters;
		}
		for(BattleGroundTeam team : new BattleGroundTeam[] { BattleGroundTeam.CHAMPION, BattleGroundTeam.RED,
				BattleGroundTeam.BLUE, BattleGroundTeam.GREEN, BattleGroundTeam.YELLOW, BattleGroundTeam.WHITE,
				BattleGroundTeam.BLACK, BattleGroundTeam.PURPLE, BattleGroundTeam.BROWN }) {
			Team roster = tournament.getTeams().getTeamByBattleGroundTeam(team);
			if(roster != null && roster.getUnits() != null && !roster.getUnits().isEmpty()) {
				//the champion bracket entrant carries the DEFENDING team units — key it
				//by the resolved real color so the alias never leaks as a fake identity
				String key = team == BattleGroundTeam.CHAMPION && tournament.getID() != null
						? this.colorName(resolution.getOrDefault(tournament.getID(), team))
						: this.colorName(team);
				rosters.put(key, roster.getUnits().stream().map(UnitRecord::from).collect(Collectors.toList()));
			}
		}
		return rosters;
	}

	private String colorName(BattleGroundTeam team) {
		String name = team.name();
		return Character.toUpperCase(name.charAt(0)) + name.substring(1).toLowerCase();
	}

}
