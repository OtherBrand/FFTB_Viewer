package fft_battleground.botland.llm;

import java.util.ArrayList;
import java.util.List;

import org.apache.commons.lang3.tuple.Pair;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import fft_battleground.event.detector.model.MatchInfoEvent;
import fft_battleground.model.BattleGroundTeam;
import fft_battleground.tournament.model.Tournament;
import fft_battleground.tournament.model.Unit;
import lombok.extern.slf4j.Slf4j;

/**
 * R7 Phase 1: flattens the live match state into a compact, readable JSON
 * summary for the LLM prompt — the textual cousin of ForecastService's numeric
 * feature vector (same source state, two representations, directly comparable
 * in the weekly accuracy query). Token discipline: unit skills are compressed
 * to names, stats to the fields a predictor uses; nothing decorative ships.
 */
@Service
@Slf4j
public class MatchStateSerializer {

	private final ObjectMapper mapper = new ObjectMapper();

	public String serialize(MatchInfoEvent matchInfo, BattleGroundTeam left, BattleGroundTeam right,
			Pair<List<Unit>, List<Unit>> unitsBySide, Pair<Integer, Integer> potTotals,
			Pair<Integer, Integer> bettorCounts, Tournament tournament) {
		try {
			ObjectNode root = this.mapper.createObjectNode();
			root.put("map", matchInfo != null && matchInfo.getMapName() != null ? matchInfo.getMapName() : "unknown");
			root.put("mapNumber", matchInfo != null && matchInfo.getMapNumber() != null ? matchInfo.getMapNumber() : -1);

			if(tournament != null && tournament.getID() != null) {
				root.put("tournamentId", tournament.getID());
				Integer streak = tournament.getChampionStreak();
				if(streak != null) {
					root.put("championStreak", streak);
				}
			}

			root.set("left", this.sideNode(left, unitsBySide != null ? unitsBySide.getLeft() : List.of(),
					potTotals != null ? potTotals.getLeft() : 0,
					bettorCounts != null ? bettorCounts.getLeft() : 0));
			root.set("right", this.sideNode(right, unitsBySide != null ? unitsBySide.getRight() : List.of(),
					potTotals != null ? potTotals.getRight() : 0,
					bettorCounts != null ? bettorCounts.getRight() : 0));

			//implied odds from the live pot (parimutuel): other/own is the profit multiplier;
			//ship the win-share form the model can reason over directly
			int leftPot = potTotals != null ? potTotals.getLeft() : 0;
			int rightPot = potTotals != null ? potTotals.getRight() : 0;
			int total = leftPot + rightPot;
			if(total > 0) {
				ObjectNode market = root.putObject("market");
				market.put("leftWinShare", round3((double) leftPot / total));
				market.put("rightWinShare", round3((double) rightPot / total));
				market.put("leftOdds", leftPot > 0 ? round2((double) rightPot / leftPot + 1.0) : 0.0);
				market.put("rightOdds", rightPot > 0 ? round2((double) leftPot / rightPot + 1.0) : 0.0);
			}

			return this.mapper.writeValueAsString(root);
		} catch(Exception e) {
			log.warn("match state serialization failed: {}", e.getMessage());
			return "{}";
		}
	}

	private ObjectNode sideNode(BattleGroundTeam team, List<Unit> units, int pot, int bettors) {
		ObjectNode side = this.mapper.createObjectNode();
		side.put("team", team != null ? team.name() : "unknown");
		side.put("pot", pot);
		side.put("bettors", bettors);
		ArrayNode unitArray = side.putArray("units");
		for(Unit unit : units) {
			if(unit == null) {
				continue;
			}
			ObjectNode u = unitArray.addObject();
			u.put("name", unit.getName());
			u.put("class", unit.getClassName());
			u.put("brave", unit.getBrave());
			u.put("faith", unit.getFaith());
			u.put("zodiac", unit.getSign());
			if(unit.isRaidBoss()) {
				u.put("raidBoss", true);
			}
			List<String> skills = new ArrayList<>();
			addIfPresent(skills, unit.getActionSkill());
			addIfPresent(skills, unit.getReactionSkill());
			addIfPresent(skills, unit.getMoveSkill());
			if(unit.getClassSkills() != null) {
				skills.addAll(unit.getClassSkills());
			}
			if(!skills.isEmpty()) {
				u.put("skills", String.join(", ", skills));
			}
			List<String> gear = new ArrayList<>();
			addIfPresent(gear, unit.getMainhand());
			addIfPresent(gear, unit.getOffhand());
			addIfPresent(gear, unit.getHead());
			addIfPresent(gear, unit.getArmor());
			addIfPresent(gear, unit.getAccessory());
			if(!gear.isEmpty()) {
				u.put("gear", String.join(", ", gear));
			}
		}
		return side;
	}

	private static void addIfPresent(List<String> list, String value) {
		if(value != null && !value.isBlank()) {
			list.add(value);
		}
	}

	private static double round3(double v) {
		return Math.round(v * 1000.0) / 1000.0;
	}

	private static double round2(double v) {
		return Math.round(v * 100.0) / 100.0;
	}
}
