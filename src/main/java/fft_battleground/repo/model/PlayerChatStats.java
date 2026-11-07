package fft_battleground.repo.model;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.Table;

import org.hibernate.annotations.UpdateTimestamp;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Date;

/**
 * Per-player chat message counter (R3a): backs the ≥20-message threshold
 * before a player word cloud is served (privacy / mass-testing guard).
 */
@Entity
@Table(name = "player_chat_stats")
@Data
@NoArgsConstructor
public class PlayerChatStats {

	@Id
	@Column(name = "player", length = 100, nullable = false)
	private String player;

	@Column(name = "message_count", nullable = false)
	private Integer messageCount;

	@UpdateTimestamp
	@Column(name = "last_update")
	private Date lastUpdate;

	public PlayerChatStats(String player, Integer messageCount) {
		this.player = player;
		this.messageCount = messageCount;
	}

}
