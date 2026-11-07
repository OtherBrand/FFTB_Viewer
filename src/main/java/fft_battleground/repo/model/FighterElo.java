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
 * Fighter ELO (BACKLOG, forward-only from launch): combat-skill rating per
 * player — their units' collective fighting — mirroring BettorElo. Updated
 * on fight results beside the bet-ELO path; expectation basis is the
 * field-ELO curve today, with the oracle's composition probability as the
 * documented upgrade point (beat a favored opponent, earn more).
 */
@Entity
@Table(name = "fighter_elo", indexes = { @Index(columnList = "is_bot", name = "fighter_elo_bot_idx") })
@Data
public class FighterElo {

	public static final double STARTING_ELO = 1500.0;
	public static final int PROVISIONAL_MATCHES = 20;

	@Id
	@Column(name = "player", nullable = false, length = 100)
	private String player;

	@Column(name = "elo", nullable = false)
	private Double elo;

	@Column(name = "fight_wins", nullable = false)
	private Integer fightWins;

	@Column(name = "fight_losses", nullable = false)
	private Integer fightLosses;

	@javax.persistence.Convert(converter = BooleanConverter.class)
	@Column(name = "is_bot", nullable = false)
	private boolean isBot;

	@UpdateTimestamp
	@Column(name = "last_update")
	private Date lastUpdate;

	public FighterElo() {}

	public FighterElo(String player, boolean isBot) {
		this.player = player;
		this.elo = STARTING_ELO;
		this.fightWins = 0;
		this.fightLosses = 0;
		this.isBot = isBot;
	}

	@Transient
	public boolean isProvisional() {
		return this.fightWins + this.fightLosses < PROVISIONAL_MATCHES;
	}

	@Transient
	public int getMatches() {
		return this.fightWins + this.fightLosses;
	}

}
