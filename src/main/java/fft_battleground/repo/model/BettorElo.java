package fft_battleground.repo.model;

import java.util.Date;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.Index;
import javax.persistence.Table;
import javax.persistence.Transient;

import org.hibernate.annotations.UpdateTimestamp;

import fft_battleground.util.hibernate.BooleanConverter;

import lombok.Data;

/**
 * Bet ELO (R3b): pick-skill rating per bettor, decoupled from gil — one pick
 * per match is one update, stake-agnostic. Forward-only from launch: every
 * bettor starts at 1500 (no backfill). Bots are included and flagged.
 */
@Entity
@Table(name = "bettor_elo", indexes = { @Index(columnList = "is_bot", name = "bettor_elo_bot_idx") })
@Data
public class BettorElo {

	public static final double STARTING_ELO = 1500.0;
	public static final int PROVISIONAL_MATCHES = 20;

	/** lowercased player name, the codebase-wide natural key */
	@Id
	@Column(name = "player", nullable = false, length = 100)
	private String player;

	@Column(name = "elo", nullable = false)
	private Double elo;

	@Column(name = "pick_wins", nullable = false)
	private Integer pickWins;

	@Column(name = "pick_losses", nullable = false)
	private Integer pickLosses;

	//explicit converter: two boolean converters are autoApply=true and conflict on plain attributes
	@javax.persistence.Convert(converter = BooleanConverter.class)
	@Column(name = "is_bot", nullable = false)
	private boolean isBot;

	@UpdateTimestamp
	@Column(name = "last_update")
	private Date lastUpdate;

	public BettorElo() {}

	public BettorElo(String player, boolean isBot) {
		this.player = player;
		this.elo = STARTING_ELO;
		this.pickWins = 0;
		this.pickLosses = 0;
		this.isBot = isBot;
	}

	@Transient
	public boolean isProvisional() {
		return this.pickWins + this.pickLosses < PROVISIONAL_MATCHES;
	}

	@Transient
	public int getMatches() {
		return this.pickWins + this.pickLosses;
	}

}
