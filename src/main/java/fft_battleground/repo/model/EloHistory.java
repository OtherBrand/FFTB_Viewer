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
 * Per-match ELO deltas for the player-page rating chart (R3b).
 */
@Entity
@Table(name = "elo_history", indexes = { @Index(columnList = "player", name = "elo_history_player_idx") })
@Data
public class EloHistory {

	@Id
	@SequenceGenerator(name = "elo_history_generator", sequenceName = "elo_history_seq")
	@GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "elo_history_generator")
	@Column(name = "elo_history_id", nullable = false)
	private Long eloHistoryId;

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

	public EloHistory() {}

	public EloHistory(String player, Long tournamentId, Double delta, Double ratingBefore, Double ratingAfter) {
		this.player = player;
		this.tournamentId = tournamentId;
		this.delta = delta;
		this.ratingBefore = ratingBefore;
		this.ratingAfter = ratingAfter;
	}

}
