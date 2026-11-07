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
import fft_battleground.event.detector.model.BetEvent;
import fft_battleground.model.BattleGroundTeam;
import fft_battleground.repo.model.BettorElo;
import fft_battleground.repo.model.EloHistory;
import fft_battleground.repo.repository.BettorEloRepo;
import fft_battleground.repo.repository.EloHistoryRepo;
import lombok.extern.slf4j.Slf4j;

/**
 * Bet ELO updates (R3b), computed in the BetResults path at match end: one
 * stake-agnostic update per bettor per match, forward-only from launch.
 * Bots participate and carry is_bot. The advisor's final snapshot for the
 * match is persisted to advisor_match_log alongside (the future-backfill
 * source for p_advisor).
 */
@Service
@Slf4j
public class EloService {

	@Autowired
	private BettorEloRepo bettorEloRepo;

	@Autowired
	private EloHistoryRepo eloHistoryRepo;

	@Autowired
	private BotCache botCache;


	@Transactional
	public void updateForMatch(BetResults results, Long tournamentId) {
		List<BetEvent> leftBets = results.getBets() != null ? results.getBets().getLeft() : List.of();
		List<BetEvent> rightBets = results.getBets() != null ? results.getBets().getRight() : List.of();
		if(leftBets.isEmpty() && rightBets.isEmpty()) {
			return;
		}

		Map<String, BattleGroundTeam> bettorSides = this.collectBettors(leftBets, rightBets);
		if(bettorSides.isEmpty()) {
			return;
		}
		//single-sided matches do not grade: no opposition means no one was out-picked,
		//and losses with no winners to absorb them would leak population points (the
		//1378-mean residual leak of the first recalibration)
		boolean anyLeft = bettorSides.containsValue(BattleGroundTeam.LEFT);
		boolean anyRight = bettorSides.containsValue(BattleGroundTeam.RIGHT);
		if(!anyLeft || !anyRight) {
			log.info("skipping single-sided match ({} bettors all on one side) — not an ELO event", bettorSides.size());
			return;
		}

		//current ratings; missing bettors start at 1500 (forward-only launch semantics)
		Map<String, BettorElo> ratings = this.loadRatings(bettorSides.keySet());

		double avgLeft = this.averageElo(bettorSides, BattleGroundTeam.LEFT, ratings);
		double avgRight = this.averageElo(bettorSides, BattleGroundTeam.RIGHT, ratings);

		BattleGroundTeam winner = results.getWinningTeam();
		//phase 1: raw deltas (shared side expectations, per-bettor K)
		List<String> bettorOrder = new ArrayList<>(bettorSides.keySet());
		double[] deltas = new double[bettorOrder.size()];
		double[] actuals = new double[bettorOrder.size()];
		for(int i = 0; i < bettorOrder.size(); i++) {
			String player = bettorOrder.get(i);
			boolean leftSide = bettorSides.get(player) == BattleGroundTeam.LEFT;
			//pure field curve: the classic two-team expectation over the sides' average
			//ratings — beating well-rated opposition earns more, purely relative skill
			double expected = EloMath.clamp(
					EloMath.fieldExpectation(leftSide ? avgLeft : avgRight, leftSide ? avgRight : avgLeft), 0.01, 0.99);
			//sides are logical (LEFT/RIGHT) — score against the real team they map to
			BattleGroundTeam sideTeam = leftSide ? results.getLeftTeam() : results.getRightTeam();
			double actual = EloMath.actualScore(sideTeam, winner);
			deltas[i] = EloMath.ratingDelta(expected, actual, ratings.get(player).getMatches());
			actuals[i] = actual;
		}
		//phase 2: zero-sum normalization — winners' gains equal losers' losses exactly
		EloMath.zeroSumNormalize(deltas);

		List<EloHistory> history = new ArrayList<>();
		for(int i = 0; i < bettorOrder.size(); i++) {
			String player = bettorOrder.get(i);
			BettorElo rating = ratings.get(player);
			double before = rating.getElo();
			double after = before + deltas[i];
			rating.setElo(after);
			if(actuals[i] >= 1.0) {
				rating.setPickWins(rating.getPickWins() + 1);
			} else if(actuals[i] <= 0.0) {
				rating.setPickLosses(rating.getPickLosses() + 1);
			} // tie: neither count moves
			history.add(new EloHistory(player, tournamentId, deltas[i], before, after));
		}

		this.bettorEloRepo.saveAll(ratings.values());
		this.eloHistoryRepo.saveAll(history);
		log.info("updated bettor ELO for {} bettors (winner: {})", bettorSides.size(),
				winner != null ? BattleGroundTeam.getTeamName(winner) : "tie");
	}

	/** lowercased player -> side they bet on (latest bet wins; re-bets replace, never both sides) */
	protected Map<String, BattleGroundTeam> collectBettors(List<BetEvent> leftBets, List<BetEvent> rightBets) {
		Map<String, BattleGroundTeam> bettors = new HashMap<>();
		for(BetEvent bet : leftBets) {
			if(bet != null && bet.getPlayer() != null) {
				bettors.put(StringUtils.lowerCase(bet.getPlayer()), BattleGroundTeam.LEFT);
			}
		}
		for(BetEvent bet : rightBets) {
			if(bet != null && bet.getPlayer() != null) {
				bettors.put(StringUtils.lowerCase(bet.getPlayer()), BattleGroundTeam.RIGHT);
			}
		}
		return bettors;
	}

	protected Map<String, BettorElo> loadRatings(Set<String> players) {
		Map<String, BettorElo> ratings = new HashMap<>();
		Set<String> bots = this.botCache != null && this.botCache.getSet() != null ? this.botCache.getSet() : new HashSet<>();
		for(BettorElo existing : this.bettorEloRepo.findByPlayerIn(players)) {
			ratings.put(existing.getPlayer(), existing);
		}
		for(String player : players) {
			if(!ratings.containsKey(player)) {
				boolean isBot = bots.contains(player);
				BettorElo created = new BettorElo(player, isBot);
				ratings.put(player, this.bettorEloRepo.saveAndFlush(created));
			}
		}
		return ratings;
	}

	protected double averageElo(Map<String, BattleGroundTeam> bettorSides, BattleGroundTeam side, Map<String, BettorElo> ratings) {
		return bettorSides.entrySet().stream()
				.filter(entry -> entry.getValue() == side)
				.mapToDouble(entry -> ratings.get(entry.getKey()).getElo())
				.average().orElse(EloMath.STARTING_ELO);
	}



}
