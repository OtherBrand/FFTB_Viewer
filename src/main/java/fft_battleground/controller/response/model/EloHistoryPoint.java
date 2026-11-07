package fft_battleground.controller.response.model;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * One point of the player-page ELO history chart (R4): the rating after a
 * match, at that match's completion time. Ascending, trailing window.
 */
@Data
@AllArgsConstructor
public class EloHistoryPoint {

	private long timestamp; // epoch-ms

	private double elo;

}
