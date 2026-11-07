package fft_battleground.controller.response.model;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * Bet ELO card data for the player page (R3b): pick-skill rating decoupled
 * from gil. Unrated players show the 1500 start with provisional=true.
 */
@Data
@AllArgsConstructor
public class BettorEloSummary {

	private double elo;
	private int pickWins;
	private int pickLosses;
	private boolean isBot;
	private boolean provisional;
	private int matches;

	public static BettorEloSummary unrated() {
		return new BettorEloSummary(1500.0, 0, 0, false, true, 0);
	}

}
