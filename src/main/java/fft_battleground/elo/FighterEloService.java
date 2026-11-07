package fft_battleground.elo;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import fft_battleground.botland.bot.model.BetResults;
import fft_battleground.dump.cache.set.BotCache;
import fft_battleground.event.detector.model.TeamInfoEvent;
import fft_battleground.model.BattleGroundTeam;
import fft_battleground.repo.model.BettorElo;
import fft_battleground.repo.model.FighterElo;
import fft_battleground.repo.model.FighterEloHistory;
import fft_battleground.repo.model.PlayerRecord;
import fft_battleground.repo.repository.FighterEloHistoryRepo;
import fft_battleground.repo.repository.FighterEloRepo;
import lombok.extern.slf4j.Slf4j;

/**
 * Fighter ELO (BACKLOG spec, forward-only): combat-skill rating per player,
 * updated on fight results beside the bet-ELO path — the rosters come from
 * the team-info metadata, S follows the match winner, K progression matches
 * bet ELO (40 first 10, then 24).
 *
 * EXPECTATION BASIS — prior today, oracle later: E is the ELO-standard field
 * expectation between the two sides' average fighter ratings. The oracle's
 * composition probability (pModelWinProb machinery) plugs in at
 * expectedScoreForSide() when it ships: blend it exactly like
 * EloService.expectedScore blends p_advisor with p_field. Beating a
 * composition-favored opponent will then earn more, per the spec.
 */
@Service
@Slf4j
public class FighterEloService {

	//ORACLE PLUG-IN POINT: when the stateless oracle lands, blend its composition
	//probability here (0.7·p_oracle + 0.3·field, mirroring EloService.expectedScore)
	protected static final double ORACLE_WEIGHT_TODAY = 0.0;

	@Autowired
	private FighterEloRepo fighterEloRepo;

	@Autowired
	private FighterEloHistoryRepo fighterEloHistoryRepo;

	@Autowired
	private BotCache botCache;

	@Transactional
	public void updateForMatch(BetResults results, Long tournamentId) {
		List<PlayerRecord> leftFighters = this.metadataOf(results, BattleGroundTeam.LEFT);
		List<PlayerRecord> rightFighters = this.metadataOf(results, BattleGroundTeam.RIGHT);
		if(leftFighters.isEmpty() && rightFighters.isEmpty()) {
			return;
		}

		Map<String, BattleGroundTeam> fighterSides = new HashMap<>();
		for(PlayerRecord fighter : leftFighters) {
			fighterSides.put(fighter.getPlayer(), BattleGroundTeam.LEFT);
		}
		for(PlayerRecord fighter : rightFighters) {
			fighterSides.put(fighter.getPlayer(), BattleGroundTeam.RIGHT);
		}
		if(fighterSides.isEmpty()) {
			return;
		}

		Map<String, FighterElo> ratings = this.loadRatings(fighterSides.keySet());
		double avgLeft = this.averageElo(fighterSides, BattleGroundTeam.LEFT, ratings);
		double avgRight = this.averageElo(fighterSides, BattleGroundTeam.RIGHT, ratings);

		BattleGroundTeam winner = results.getWinningTeam();
		List<FighterEloHistory> history = new ArrayList<>();
		for(Map.Entry<String, BattleGroundTeam> fighter : fighterSides.entrySet()) {
			FighterElo rating = ratings.get(fighter.getKey());
			boolean leftSide = fighter.getValue() == BattleGroundTeam.LEFT;
			double expected = this.expectedScoreForSide(leftSide, avgLeft, avgRight);
			double actual = EloMath.actualScore(leftSide ? results.getLeftTeam() : results.getRightTeam(), winner);
			double delta = EloMath.ratingDelta(expected, actual, rating.getMatches());
			double before = rating.getElo();
			double after = before + delta;
			rating.setElo(after);
			if(actual >= 1.0) {
				rating.setFightWins(rating.getFightWins() + 1);
			} else if(actual <= 0.0) {
				rating.setFightLosses(rating.getFightLosses() + 1);
			} // tie: neither count moves
			history.add(new FighterEloHistory(rating.getPlayer(), tournamentId, delta, before, after));
		}

		this.fighterEloRepo.saveAll(ratings.values());
		this.fighterEloHistoryRepo.saveAll(history);
		log.info("updated fighter ELO for {} fighters (winner: {})", fighterSides.size(),
				winner != null ? BattleGroundTeam.getTeamName(winner) : "tie");
	}

	/** the prior: pure field expectation between the sides' average ratings */
	protected double expectedScoreForSide(boolean leftSide, double avgLeft, double avgRight) {
		double pField = EloMath.fieldExpectation(leftSide ? avgLeft : avgRight, leftSide ? avgRight : avgLeft);
		return EloMath.clamp(ORACLE_WEIGHT_TODAY * pField + (1.0 - ORACLE_WEIGHT_TODAY) * pField, 0.01, 0.99);
	}

	protected List<PlayerRecord> metadataOf(BetResults results, BattleGroundTeam side) {
		if(results.getTeamData() == null) {
			return List.of();
		}
		TeamInfoEvent teamInfo = side == BattleGroundTeam.LEFT ? results.getTeamData().getLeftTeamData()
				: results.getTeamData().getRightTeamData();
		if(teamInfo == null || teamInfo.getMetaData() == null) {
			return List.of();
		}
		return teamInfo.getMetaData().stream()
				.filter(record -> record != null && StringUtils.isNotBlank(record.getPlayer()))
				.collect(java.util.stream.Collectors.toList());
	}

	protected Map<String, FighterElo> loadRatings(Set<String> players) {
		Map<String, FighterElo> ratings = new HashMap<>();
		Set<String> bots = this.botCache != null && this.botCache.getSet() != null ? this.botCache.getSet() : new HashSet<>();
		for(FighterElo existing : this.fighterEloRepo.findByPlayerIn(players)) {
			ratings.put(existing.getPlayer(), existing);
		}
		for(String player : players) {
			if(!ratings.containsKey(player)) {
				boolean isBot = bots.contains(player);
				FighterElo created = new FighterElo(player, isBot);
				ratings.put(player, this.fighterEloRepo.saveAndFlush(created));
			}
		}
		return ratings;
	}

	protected double averageElo(Map<String, BattleGroundTeam> fighterSides, BattleGroundTeam side, Map<String, FighterElo> ratings) {
		return fighterSides.entrySet().stream()
				.filter(entry -> entry.getValue() == side)
				.mapToDouble(entry -> ratings.get(entry.getKey()).getElo())
				.average().orElse(BettorElo.STARTING_ELO);
	}

}
