package fft_battleground.elo;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.commons.lang3.tuple.Pair;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import fft_battleground.botland.bot.model.BetResults;
import fft_battleground.event.detector.model.BetEvent;
import fft_battleground.model.BattleGroundTeam;
import fft_battleground.repo.model.BettorElo;
import fft_battleground.repo.repository.BettorEloRepo;
import fft_battleground.repo.repository.EloHistoryRepo;

/**
 * One-shot audit (run manually): replays the ACTUAL post-launch prod corpus
 * (fetched to elo-corpus.json from the public APIs) through the live
 * EloService math with in-memory persistence, printing per-match ΣΔ and the
 * final population mean. Conservation must hold match-by-match.
 */
public class EloCorpusReplayAudit {

	@Test
	public void replayProdCorpusAndPrintConservation() throws Exception {
		byte[] raw = Files.readAllBytes(Paths.get("elo-corpus.json"));
		JsonNode root = new ObjectMapper().readTree(raw);
		JsonNode winnersByTid = root.path("winners");
		JsonNode potsByTid = root.path("pots");

		// in-memory persistence via Mockito answers
		Map<String, BettorElo> table = new HashMap<>();
		List<double[]> conservationPerMatch = new ArrayList<>();
		List<String> matchLog = new ArrayList<>();

		BettorEloRepo bettorRepo = org.mockito.Mockito.mock(BettorEloRepo.class);
		org.mockito.Mockito.when(bettorRepo.findByPlayerIn(org.mockito.ArgumentMatchers.anyCollection()))
			.thenAnswer(inv -> {
				java.util.Collection<String> players = inv.getArgument(0);
				List<BettorElo> found = new ArrayList<>();
				for(String p : players) if(table.containsKey(p)) found.add(table.get(p));
				return found;
			});
		org.mockito.Mockito.when(bettorRepo.saveAndFlush(org.mockito.ArgumentMatchers.any(BettorElo.class)))
			.thenAnswer(inv -> { BettorElo e = inv.getArgument(0); table.put(e.getPlayer(), e); return e; });
		org.mockito.Mockito.when(bettorRepo.saveAll(org.mockito.ArgumentMatchers.anyCollection()))
			.thenAnswer(inv -> {
				for(Object o : (java.util.Collection<?>) inv.getArgument(0)) {
					BettorElo e = (BettorElo) o; table.put(e.getPlayer(), e);
				}
				return new ArrayList<>(table.values());
			});
		EloHistoryRepo historyRepo = org.mockito.Mockito.mock(EloHistoryRepo.class);
		org.mockito.Mockito.when(historyRepo.saveAll(org.mockito.ArgumentMatchers.anyCollection()))
			.thenAnswer(inv -> {
				double sum = 0;
				for(Object o : (java.util.Collection<?>) inv.getArgument(0)) {
					sum += ((fft_battleground.repo.model.EloHistory) o).getDelta();
				}
				conservationPerMatch.add(new double[]{sum, 0});
				return null;
			});

		EloService service = new EloService();
		org.springframework.test.util.ReflectionTestUtils.setField(service, "bettorEloRepo", bettorRepo);
		org.springframework.test.util.ReflectionTestUtils.setField(service, "eloHistoryRepo", historyRepo);

		int graded = 0, singleSided = 0;
		for(JsonNode row : root.path("rows")) {
			long tid = row.path("tournamentId").asLong();
			JsonNode winners = winnersByTid.path(String.valueOf(tid));
			List<BetEvent> left = this.betsOf(row.path("leftBets"), row.path("leftTeam").asText());
			List<BetEvent> right = this.betsOf(row.path("rightBets"), row.path("rightTeam").asText());
			if(left.isEmpty() && right.isEmpty()) continue;
			if(left.isEmpty() || right.isEmpty()) { singleSided++; continue; }
			//index by position within tournament: count rows of same tid in corpus order
			//rows arrive in createDateTime order from the repo query equivalent; approximate via counting
			//— for the audit, winner = winners[countOfPriorRowsForThisTid % winners.size()]
			//pair-consumption mapping, mirroring the fixed service: rows in corpus order
			//consume matching pot indices; winner = winners[matchedIndex]; reject neither-side
			BattleGroundTeam winner = this.pairMatchedWinner(root, row, tid);
			if(winner == null) continue;
			BetResults match = new BetResults(Pair.of(left, right), winner,
					BattleGroundTeam.parse(row.path("leftTeam").asText()),
					BattleGroundTeam.parse(row.path("rightTeam").asText()), null, null, null, List.of());
			int beforeCount = conservationPerMatch.size();
			service.updateForMatch(match, tid);
			if(conservationPerMatch.size() > beforeCount) {
				graded++;
				double sum = conservationPerMatch.get(conservationPerMatch.size() - 1)[0];
				if(Math.abs(sum) > 1e-6) {
					matchLog.add(String.format("LEAK tid=%d row=%d sumDelta=%.6f", tid, row.path("botBetDataId").asLong(), sum));
				}
			}
		}

		double total = table.values().stream().mapToDouble(BettorElo::getElo).sum();
		System.out.println("AUDIT graded=" + graded + " singleSided=" + singleSided + " rated=" + table.size()
				+ " populationTotal=" + total + " expected=" + (1500.0 * table.size())
				+ " mean=" + (table.isEmpty() ? 0 : total / table.size()));
		for(String leak : matchLog) System.out.println("AUDIT " + leak);
		Assertions.assertTrue(table.size() > 0, "corpus must produce rated bettors");
		Assertions.assertEquals(1500.0 * table.size(), total, 1.0,
				"population must be conserved: expected 1500*N, got " + total);
	}

	private BattleGroundTeam pairMatchedWinner(JsonNode root, JsonNode row, long tid) {
		JsonNode winners = root.path("winners").path(String.valueOf(tid));
		JsonNode pots = root.path("pots").path(String.valueOf(tid));
		if(!winners.isArray() || !pots.isArray() || winners.size() == 0) return null;
		boolean[] used = new boolean[pots.size()];
		//consume prior rows of the same tournament first (corpus is chronologically ordered)
		for(JsonNode prior : root.path("rows")) {
			if(prior.path("tournamentId").asLong() != tid) continue;
			int k = this.firstPairIndex(pots, used, prior);
			if(k < 0) continue;
			used[k] = true;
			if(prior.path("botBetDataId").asLong() == row.path("botBetDataId").asLong()) {
				if(k >= winners.size()) return null;
				String w = winners.get(k).asText();
				BattleGroundTeam parsed = (w == null || w.isBlank()) ? null : BattleGroundTeam.parse(w);
				String lt = row.path("leftTeam").asText(), rt = row.path("rightTeam").asText();
				if(parsed != null && parsed != BattleGroundTeam.parse(lt) && parsed != BattleGroundTeam.parse(rt)) {
					return null; //neither-side winner: pairing failure, skip
				}
				return parsed;
			}
		}
		return null;
	}

	private int firstPairIndex(JsonNode pots, boolean[] used, JsonNode row) {
		String lt = row.path("leftTeam").asText(), rt = row.path("rightTeam").asText();
		for(int k = 0; k < pots.size(); k++) {
			if(used[k]) continue;
			JsonNode pair = pots.get(k);
			if(pair.get(0) != null && pair.get(1) != null
					&& pair.get(0).asText().equalsIgnoreCase(lt) && pair.get(1).asText().equalsIgnoreCase(rt)) {
				return k;
			}
		}
		return -1;
	}

	private List<BetEvent> betsOf(JsonNode betsJson, String teamStr) {
		List<BetEvent> events = new ArrayList<>();
		BattleGroundTeam team = BattleGroundTeam.parse(teamStr);
		for(JsonNode b : betsJson.path("bets")) {
			events.add(new BetEvent(b.path("player").asText(), team,
					String.valueOf(b.path("bet").asInt()), String.valueOf(b.path("bet").asInt()), null, false));
		}
		return events;
	}

}
