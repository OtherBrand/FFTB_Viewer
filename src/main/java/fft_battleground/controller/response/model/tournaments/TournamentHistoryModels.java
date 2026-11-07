package fft_battleground.controller.response.model.tournaments;

import java.util.List;
import java.util.Map;

import fft_battleground.model.BattleGroundTeam;
import fft_battleground.tournament.model.Unit;
import lombok.Data;

/**
 * Tournament + champion view DTOs (frozen contract 2026-10-25): per-match
 * unit lineups are optional-absent (the API serves final rosters only —
 * trainer composition artifact is the v2 unlock), owner/level/fallen and
 * reignStats are optional-absent, avgBackerElo is null for historical reigns
 * (ELO is forward-only).
 */
public class TournamentHistoryModels {

	@Data
	public static class TournamentSummary {
		private String tournamentId;
		private Long date; // epoch-ms (the API's id IS the start epoch)
		private Integer matchCount;
		private BattleGroundTeam champion;
		private BattleGroundTeam runnerUp;
		private Integer championCrowns; // career titles for that team
		private Boolean completed; // false = live/unresolved (UI defaults to latest COMPLETED)
		private Integer consecutiveTitles; // the streak this win extends
		private Boolean retainedNext; // null = current/unknown
	}

	@Data
	public static class MatchRecord {
		private Integer matchNumber;
		private BattleGroundTeam team1;
		private BattleGroundTeam team2;
		private BattleGroundTeam winner;
		private Double odds1;
		private Double odds2;
		private Long pot;
		/** true when this match involved the defending-champion bracket entrant: sides and
		 *  winner are resolved to the real team; render '(Champion)' as the qualifier */
		private Boolean championEntrant;
		//forecast {pick, pWin1, pWin2, hit} arrives with the backfill serve — optional by design
	}

	@Data
	public static class TournamentDetail {
		private TournamentSummary summary;
		private List<MatchRecord> matches; // descending (final first)
		/** final rosters per participating team — clean UnitRecords, no leaked strings */
		private Map<String, List<UnitRecord>> rosters;
	}

	@Data
	public static class ReignRecord {
		private String reignId;
		private BattleGroundTeam team;
		private Long startedAt;
		private Long endedAt; // null = current reign
		private Integer defenses;
		private Integer matches;
		private Integer avgBackerElo; // null for historical reigns (ELO forward-only)
		private Integer crownCount; // career titles entering this reign
	}

	/**
	 * Clean unit serialization for the roster surfaces: the Unit DTO's
	 * getUnitInfoString() leaks a dash-delimited string into every JSON
	 * payload (Jackson auto-serializes the getter) — this DTO serves exactly
	 * the structured fields, nothing else. owner/level/fallen remain
	 * optional-absent per the frozen contract.
	 */
	@Data
	public static class UnitRecord {
		private String name;
		private String gender;
		private String sign; // zodiac — carried through per the owner addendum
		private Integer brave;
		private Integer faith;
		private String className;
		private String actionSkill;
		private String reactionSkill;
		private String moveSkill;
		private String mainhand;
		private String offhand;
		private String head;
		private String armor;
		private String accessory;
		private List<String> classSkills;

		public static UnitRecord from(fft_battleground.tournament.model.Unit unit) {
			UnitRecord record = new UnitRecord();
			record.setName(unit.getName());
			record.setGender(unit.getGender() != null ? unit.getGender().toString() : null);
			record.setSign(unit.getSign());
			record.setBrave(unit.getBrave() != null ? unit.getBrave().intValue() : null);
			record.setFaith(unit.getFaith() != null ? unit.getFaith().intValue() : null);
			record.setClassName(unit.getClassName());
			record.setActionSkill(unit.getActionSkill());
			record.setReactionSkill(unit.getReactionSkill());
			record.setMoveSkill(unit.getMoveSkill());
			record.setMainhand(unit.getMainhand());
			record.setOffhand(unit.getOffhand());
			record.setHead(unit.getHead());
			record.setArmor(unit.getArmor());
			record.setAccessory(unit.getAccessory());
			record.setClassSkills(unit.getClassSkills());
			return record;
		}
	}

	@Data
	public static class ForecastStats {
		private int matchesScored; // holdout only, per the honesty convention
		private int hits;
		private double hitRate;
		private String modelVersion;
		private Integer inSampleMatches; // flagged, not headlined
		private Integer inSampleHits;
	}

}
