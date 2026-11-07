package fft_battleground.forecast;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.ObjectMapper;

import fft_battleground.forecast.ForecastModels.ForecastFactor;
import fft_battleground.forecast.ForecastModels.ForecastRequest;
import fft_battleground.forecast.ForecastModels.ForecastResponse;
import fft_battleground.model.BattleGroundTeam;
import fft_battleground.repo.model.AdvisorMatchLog;
import fft_battleground.repo.repository.AdvisorMatchLogRepo;
import fft_battleground.tournament.classifier.UnitAttributeClassifier;
import fft_battleground.tournament.classifier.V2UnitAttributeClassifier;
import fft_battleground.tournament.model.Unit;
import lombok.extern.slf4j.Slf4j;

/**
 * The stateless match oracle (advisor v2): builds the trainer's exact
 * feature vector from the posted composition, scores the win-prob model,
 * and returns pick + probabilities + top attribution terms. Every served
 * verdict is logged to advisor_match_log (tournament id + payload) so
 * ELO/backfill continue without any server-side trigger machinery —
 * the race-prone explain-roster chain is retired.
 */
@Service
@Slf4j
public class ForecastService {

	protected static final int TOP_FACTORS = 8;

	@Autowired
	private WinProbModel model;

	@Autowired
	private AdvisorMatchLogRepo advisorMatchLogRepo;

	@Autowired
	private fft_battleground.tournament.TournamentService tournamentService;

	@Autowired
	private fft_battleground.metrics.StatusService statusService;

	@Autowired
	private fft_battleground.metrics.PipelineErrorReporter pipelineErrorReporter;

	private final UnitAttributeClassifier classifier = new V2UnitAttributeClassifier();
	private final ObjectMapper jsonMapper = new ObjectMapper();

	public ForecastResponse forecast(ForecastRequest request) {
		if(!this.model.isLoaded()) {
			return null; // controller serves 503
		}

		Map<String, Double> features = this.buildFeatures(request);
		double pWin1 = this.model.probabilityLeft(features);
		double pWin2 = 1.0 - pWin1;

		//the live page carries no tournament id — attribute server-side so the
		//match log (ELO/backfill continuity) never silently skips
		Long tournamentId = request.getTournamentId() != null ? request.getTournamentId() : this.currentTournamentId();

		ForecastResponse response = new ForecastResponse();
		response.setTournamentId(tournamentId);
		response.setPick(pWin1 >= pWin2 ? request.getTeam1() : request.getTeam2());
		response.setPWin1(pWin1);
		response.setPWin2(pWin2);
		response.setLeadsBy(Math.abs(pWin1 - pWin2));
		response.setFactors(this.topFactors(request, features));
		response.setModelVersion(this.model.getModelVersion());

		this.persistVerdict(tournamentId, response);
		if(this.statusService != null) {
			this.statusService.markForecastLogged();
		}
		log.info("forecast served: tournament={} pick={} pWin1={} model={}",
				tournamentId, response.getPick(), pWin1, response.getModelVersion());
		return response;
	}

	protected Long currentTournamentId() {
		try {
			fft_battleground.tournament.model.Tournament current = this.tournamentService.getCurrentTournament();
			return current != null ? current.getID() : null;
		} catch(Exception e) {
			return null;
		}
	}

	/** fftb-forecast features.rs, replicated exactly */
	protected Map<String, Double> buildFeatures(ForecastRequest request) {
		Map<String, Double> features = new HashMap<>();
		List<Unit> team1 = request.getTeam1Units() != null ? request.getTeam1Units() : List.of();
		List<Unit> team2 = request.getTeam2Units() != null ? request.getTeam2Units() : List.of();

		//attr:<name> count differences: left +1, right -1 (HashMap.merge multiplies naturally)
		for(Unit unit : team1) {
			for(String attribute : this.classifier.getUnitGeneAbilityElements(unit)) {
				features.merge("attr:" + attribute, 1.0, Double::sum);
			}
		}
		for(Unit unit : team2) {
			for(String attribute : this.classifier.getUnitGeneAbilityElements(unit)) {
				features.merge("attr:" + attribute, -1.0, Double::sum);
			}
		}

		//stat-sum differences
		features.put("agg:brave", this.sumOf(team1, Unit::getBrave) - this.sumOf(team2, Unit::getBrave));
		features.put("agg:faith", this.sumOf(team1, Unit::getFaith) - this.sumOf(team2, Unit::getFaith));
		features.put("agg:raidBoss", (double) (this.countRaidBosses(team1) - this.countRaidBosses(team2)));

		//map one-hot: the trainer's double-bump of the two side genes nets 2.0
		if(request.getMatchInfo() != null && request.getMatchInfo().getMapNumber() != null) {
			features.put("map:" + request.getMatchInfo().getMapNumber(), 2.0);
		}

		//side one-hot on the LEFT (label) color
		if(request.getTeam1() != null) {
			features.put("side:" + this.colorGeneName(request.getTeam1()), 1.0);
		}
		return features;
	}

	protected List<ForecastFactor> topFactors(ForecastRequest request, Map<String, Double> features) {
		BattleGroundTeam team1 = request.getTeam1();
		BattleGroundTeam team2 = request.getTeam2();
		List<ForecastFactor> factors = new ArrayList<>();
		for(Map.Entry<String, Double> feature : features.entrySet()) {
			double contribution = this.model.contribution(feature.getKey(), feature.getValue() != null ? feature.getValue() : 0.0);
			if(contribution == 0.0) {
				continue;
			}
			ForecastFactor factor = new ForecastFactor();
			factor.setName(feature.getKey());
			factor.setFavors(contribution > 0 ? team1 : team2);
			factor.setMagnitude(Math.abs(contribution));
			factors.add(factor);
		}
		factors.sort(Comparator.comparingDouble(ForecastFactor::getMagnitude).reversed());
		return new ArrayList<>(factors.subList(0, Math.min(TOP_FACTORS, factors.size())));
	}

	protected void persistVerdict(Long tournamentId, ForecastResponse response) {
		if(tournamentId == null) {
			return;
		}
		try {
			this.advisorMatchLogRepo.saveAndFlush(new AdvisorMatchLog(tournamentId, this.jsonMapper.writeValueAsString(response)));
		} catch(Exception e) {
			log.error("failed to persist forecast verdict", e);
			if(this.pipelineErrorReporter != null) {
				this.pipelineErrorReporter.reportPipelineFailure("forecast-logging", "verdict for tournament " + tournamentId + " not persisted", e);
			}
		}
	}

	private double sumOf(List<Unit> units, java.util.function.ToDoubleFunction<Unit> extractor) {
		return units.stream().mapToDouble(extractor).sum();
	}

	private int countRaidBosses(List<Unit> units) {
		return (int) units.stream().filter(Unit::isRaidBoss).count();
	}

	/** proper-case color names, matching the trainer's gene_name() ("Red", "Champion", ...) */
	protected String colorGeneName(BattleGroundTeam team) {
		String name = team.name();
		return Character.toUpperCase(name.charAt(0)) + name.substring(1).toLowerCase();
	}

}
