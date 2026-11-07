package fft_battleground.controller.response.model;

import java.util.Set;

import fft_battleground.repo.model.PlayerRecord;

import lombok.Data;

@Data
public class PlayerData {
	private PlayerRecord playerRecord;
	private String portraitUrl;
	private String fightRatio;
	private String betRatio;
	private boolean containsPrestige = false;
	private boolean bot = false;
	private int prestigeLevel = 0;
	private Integer leaderboardPosition;
	private String timezoneFormattedDateString;
	private String timezoneFormattedLastFightActiveDateString;
	private Integer expRank;
	private String percentageOfGlobalGil;
	private boolean notFound = false;
	private Integer betPercentile;
	private Integer fightPercentile;
	private BettorEloSummary elo;
	private fft_battleground.skill.PrestigeProgressService.PrestigeProgress prestigeProgress;
	private FighterEloSummary fighterElo;
	private Set<String> classBonuses;
	private Set<String> skillBonuses;
	private Set<String> prestigeSkills;
	
	public PlayerData() {}
}