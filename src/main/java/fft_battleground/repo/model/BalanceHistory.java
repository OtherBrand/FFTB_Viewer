package fft_battleground.repo.model;

import java.sql.Timestamp;
import java.util.Date;

import javax.persistence.Cacheable;
import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.EnumType;
import javax.persistence.Enumerated;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;
import javax.persistence.Id;
import javax.persistence.Index;
import javax.persistence.SequenceGenerator;
import javax.persistence.Table;

import org.hibernate.annotations.Cache;
import org.hibernate.annotations.CacheConcurrencyStrategy;
import org.hibernate.annotations.CreationTimestamp;

import com.fasterxml.jackson.annotation.JsonIgnore;

import fft_battleground.event.detector.model.BalanceEvent;
import fft_battleground.repo.util.BalanceType;
import fft_battleground.repo.util.BalanceUpdateSource;
import lombok.Data;

@Entity
@Table(name = "balance_history", indexes = {
		@Index(columnList = "player,create_timestamp", name = "balance_history_player_ts_idx")})
@Cacheable
@Cache(usage = CacheConcurrencyStrategy.READ_WRITE)
@Data
public class BalanceHistory {
	
    @Id
	@SequenceGenerator(name="balance_history_generator", sequenceName = "balance_history_seq")
	@GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "balance_history_generator")
    @Column(name = "balance_history_id", nullable = false)
    @JsonIgnore
	private Long balanceHistoryId;
    
    @Column(name = "player", nullable = false)
	private String player;
    
    @Column(name="balance", nullable = false)
	private Integer balance;
    
    @Column(name="balance_change", nullable=true)
    private Integer balanceChange;
    
    @JsonIgnore
    @Column(name="type", nullable = false, length=10)
    @Enumerated(EnumType.STRING)
	private BalanceType type;
    
    @JsonIgnore
    @Column(name="update_source", nullable=false, length=10)
    @Enumerated(EnumType.STRING)
    private BalanceUpdateSource updateSource;
	
	@CreationTimestamp
	private Timestamp create_timestamp;
	
	public BalanceHistory() {}
	
	public BalanceHistory(String player, Integer balance, BalanceType type, BalanceUpdateSource updateSource) {
		this.player = player;
		this.balance = balance;
		this.type = type;
		this.updateSource = updateSource;
	}
	
    public BalanceHistory(BalanceEvent event) {
		this.player = event.getPlayer();
		this.balance = event.getAmount();
		this.balanceChange = event.getBalanceChange();
		this.type = event.getBalancetype();
		this.updateSource = event.getBalanceUpdateSource();
	}
    
	public BalanceHistory(String playerName, int amount, Date date) {
		this.player = playerName;
		this.balance = amount;
		this.create_timestamp = new Timestamp(date.getTime());
	}
}
