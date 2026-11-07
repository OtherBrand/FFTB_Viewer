package fft_battleground.controller.response.model;

import lombok.Data;

/**
 * One ranked row of the Bettor/Fighter ELO leaderboards. Provisional
 * bettors/fighters (<20 matches) are excluded from rankings by design —
 * the ranked query window enforces it.
 */
@Data
public class EloLeaderboardEntry {

	private int rank;
	private String player;
	private double elo;
	private int wins;
	private int losses;
	private int matches;
	private boolean isBot;

}
