package fft_battleground.repo.model;

import java.util.Date;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;
import javax.persistence.Id;
import javax.persistence.Index;
import javax.persistence.SequenceGenerator;
import javax.persistence.Table;

import org.hibernate.annotations.CreationTimestamp;

import lombok.Data;

/**
 * Per-match Fighter ELO deltas for the player-page combat-rating chart,
 * mirroring elo_history.
 */
@Entity
@Table(name = "fighter_elo_history", indexes = { @Index(columnList = "player", name = "fighter_elo_history_player_idx") })
@Data
public class FighterEloHistory {

	@Id
	@SequenceGenerator(name = "fighter_elo_history_generator", sequenceName = "fighter_elo_history_seq")
	@GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "fighter_elo_history_generator")
	@Column(name = "fighter_elo_history_id", nullable = false)
	private Long fighterEloHistoryId;

	@Column(name = "player", nullable = false, length = 100)
	private String player;

	@Column(name = "tournament_id")
	private Long tournamentId;

	@Column(name = "delta", nullable = false)
	private Double delta;

	@Column(name = "rating_before", nullable = false)
	private Double ratingBefore;

	@Column(name = "rating_after", nullable = false)
	private Double ratingAfter;

	@CreationTimestamp
	@Column(name = "create_date_time")
	private Date createDateTime;

	public FighterEloHistory() {}

	public FighterEloHistory(String player, Long tournamentId, Double delta, Double ratingBefore, Double ratingAfter) {
		this.player = player;
		this.tournamentId = tournamentId;
		this.delta = delta;
		this.ratingBefore = ratingBefore;
		this.ratingAfter = ratingAfter;
	}

}
