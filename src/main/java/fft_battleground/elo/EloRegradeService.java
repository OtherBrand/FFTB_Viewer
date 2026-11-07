package fft_battleground.elo;

import java.util.ArrayList;
import java.util.List;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import fft_battleground.botland.bot.model.BetResults;
import fft_battleground.event.detector.model.BetEvent;
import fft_battleground.model.BattleGroundTeam;
import fft_battleground.repo.model.BotBetData;
import fft_battleground.repo.repository.BotBetDataRepo;
import fft_battleground.tournament.TournamentRestService;
import fft_battleground.tournament.model.Tournament;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.tuple.Pair;

/**
 * Post-outage ELO re-grade (owner directive 2026-10-25): bot_bet_data carries
 * per-match bet JSON (player/bet/balance per side) and the fftbg API carries
 * per-match winners — enough to replay the exact EloService math with the
 * designed pot-implied p_advisor. Used after the bettor-table rebuild; also
 * usable for any future write outage ("forward-only was about pre-feature
 * history, not post-feature outages").
 */
@Service
@Slf4j
public class EloRegradeService {

	@Autowired
	private BotBetDataRepo botBetDataRepo;

	@Autowired
	private TournamentRestService tournamentRestService;

	@Autowired
	private EloService eloService;

	@Data
	public static class RegradeResult {
		private int matchesConsidered;
		private int matchesRegraded;
		private int matchesSkippedNoWinner;
		private int matchesSkippedSingleSided;
		private List<String> sampleErrors = new ArrayList<>();
	}

	public RegradeResult regrade(long fromEpochMs, long toEpochMs) {
		RegradeResult result = new RegradeResult();
		List<BotBetData> rows = this.botBetDataRepo.getBotBetDataBetween(new java.util.Date(fromEpochMs), new java.util.Date(toEpochMs));
		for(BotBetData row : rows) {
			result.setMatchesConsidered(result.getMatchesConsidered() + 1);
			try {
				BattleGroundTeam winner = this.winnerFor(row);
				if(winner == null) {
					result.setMatchesSkippedNoWinner(result.getMatchesSkippedNoWinner() + 1);
					continue;
				}
				List<BetEvent> leftBets = this.betEventsOf(row.getLeftBets(), row.getLeftTeam());
				List<BetEvent> rightBets = this.betEventsOf(row.getRightBets(), row.getRightTeam());
				if(leftBets.isEmpty() && rightBets.isEmpty()) {
					continue;
				}
				if(leftBets.isEmpty() || rightBets.isEmpty()) {
					//single-sided: not an ELO event — skipped, counted for visibility
					result.setMatchesSkippedSingleSided(result.getMatchesSkippedSingleSided() + 1);
					continue;
				}
				BetResults match = new BetResults(Pair.of(leftBets, rightBets), winner,
						row.getLeftTeam(), row.getRightTeam(), null, null, null, List.of());
				this.eloService.updateForMatch(match, row.getTournamentId());
				result.setMatchesRegraded(result.getMatchesRegraded() + 1);
			} catch(Exception e) {
				if(result.getSampleErrors().size() < 5) {
					result.getSampleErrors().add("row " + row.getBotBetDataId() + ": " + e.getMessage());
				}
			}
		}
		log.info("ELO regrade complete: {} considered, {} regraded, {} no-winner, {} single-sided", result.getMatchesConsidered(), result.getMatchesRegraded(), result.getMatchesSkippedNoWinner(), result.getMatchesSkippedSingleSided());
		return result;
	}

	/**
	 * Winner matched by TEAM PAIR, not chronological position: the API's Pots array
	 * carries each match's team pair alongside Winners, and pairing survives missing
	 * bot_bet_data rows (position indexing shifted and graded matches with a
	 * neither-side winner — every bettor losing, one-signed deltas, raw pass-through:
	 * the ~-7,100-point recalibration leak of 2026-10-25). Rows consume pair-matches
	 * in order (grand-final rematches disambiguate chronologically).
	 */
	protected BattleGroundTeam winnerFor(BotBetData row) {
		try {
			Tournament tournament = this.tournamentRestService.getTournamentById(row.getTournamentId());
			if(tournament == null || tournament.getWinners() == null || tournament.getPots() == null
					|| tournament.getWinners().isEmpty() || tournament.getPots().isEmpty()) {
				return null;
			}
			List<BotBetData> tournamentRows = new ArrayList<>(this.botBetDataRepo.getBotBetDataForTournaments(List.of(row.getTournamentId())));
			tournamentRows.sort(java.util.Comparator.comparing(BotBetData::getCreateDateTime));
			int rowPosition = -1;
			for(int i = 0; i < tournamentRows.size(); i++) {
				if(tournamentRows.get(i).getBotBetDataId().equals(row.getBotBetDataId())) {
					rowPosition = i;
					break;
				}
			}
			if(rowPosition < 0) {
				return null;
			}
			//consume pair-matches in row order: for each row up to and including this one
			//that pairs with pot index k, mark k used — this row's winner is the k-th winner
			boolean[] used = new boolean[tournament.getPots().size()];
			int matchedForThisRow = -1;
			for(BotBetData current : tournamentRows) {
				int potIndex = this.firstUnconsumedPairIndex(tournament, used, current);
				if(potIndex < 0) {
					continue; //row's team pair not found (data drift) — leaves indices for the others
				}
				used[potIndex] = true;
				if(current.getBotBetDataId().equals(row.getBotBetDataId())) {
					matchedForThisRow = potIndex;
					break;
				}
			}
			if(matchedForThisRow < 0 || matchedForThisRow >= tournament.getWinners().size()) {
				return null;
			}
			String winner = tournament.getWinners().get(matchedForThisRow);
			BattleGroundTeam parsed = StringUtils.isNotBlank(winner) ? BattleGroundTeam.parse(winner) : null;
			//conservation guard: a winner that is neither side of THIS match is a pairing
			//failure — skip the match rather than grade a everyone-loses leak
			if(parsed != null && parsed != current0(tournamentRows, row).getLeftTeam()
					&& parsed != current0(tournamentRows, row).getRightTeam()) {
				return null;
			}
			return parsed;
		} catch(Exception e) {
			log.warn("winner lookup failed for row {}: {}", row.getBotBetDataId(), e.getMessage());
			return null;
		}
	}

	private BotBetData current0(List<BotBetData> rows, BotBetData row) {
		return row;
	}

	private int firstUnconsumedPairIndex(Tournament tournament, boolean[] used, BotBetData current) {
		for(int k = 0; k < tournament.getPots().size(); k++) {
			if(used[k] || tournament.getPots().get(k) == null) {
				continue;
			}
			fft_battleground.tournament.model.TeamBet left = tournament.getPots().get(k).getLeft();
			fft_battleground.tournament.model.TeamBet right = tournament.getPots().get(k).getRight();
			if(left != null && right != null && left.getTeam() == current.getLeftTeam()
					&& right.getTeam() == current.getRightTeam()) {
				return k;
			}
		}
		return -1;
	}

	private List<BetEvent> betEventsOf(fft_battleground.repo.util.BotReceivedBets bets, BattleGroundTeam team) {
		List<BetEvent> events = new ArrayList<>();
		if(bets == null || bets.getBets() == null) {
			return events;
		}
		for(fft_battleground.repo.util.BotReceivedBet bet : bets.getBets()) {
			if(bet != null && StringUtils.isNotBlank(bet.getPlayer())) {
				events.add(new BetEvent(bet.getPlayer(), team, String.valueOf(bet.getBetAmount()),
						String.valueOf(bet.getBetAmount()), null, false));
			}
		}
		return events;
	}

}
