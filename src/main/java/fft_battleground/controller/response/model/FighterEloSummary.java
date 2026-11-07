package fft_battleground.controller.response.model;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * Fighter ELO card data for the player page: combat rating sibling to
 * BettorEloSummary (same tabset pattern). Unrated players show the 1500
 * start with provisional=true.
 */
@Data
@AllArgsConstructor
public class FighterEloSummary {

	private double elo;
	private int fightWins;
	private int fightLosses;
	private boolean isBot;
	private boolean provisional;
	private int matches;

	public static FighterEloSummary unrated() {
		return new FighterEloSummary(1500.0, 0, 0, false, true, 0);
	}

}
