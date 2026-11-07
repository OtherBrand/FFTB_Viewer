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
 * The advisor's final per-match snapshot (R3b): persisted at result time so
 * the ELO expected-score blend has a durable p_advisor source — and so a
 * future backfill of ELO over history has the same inputs today's matches
 * have. recommendation_json holds the full AdvisorRecommendation payload.
 */
@Entity
@Table(name = "advisor_match_log", indexes = { @Index(columnList = "tournament_id", name = "advisor_match_tournament_idx") })
@Data
public class AdvisorMatchLog {

	@Id
	@SequenceGenerator(name = "advisor_match_log_generator", sequenceName = "advisor_match_log_seq")
	@GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "advisor_match_log_generator")
	@Column(name = "advisor_match_log_id", nullable = false)
	private Long advisorMatchLogId;

	@Column(name = "tournament_id")
	private Long tournamentId;

	@Column(name = "recommendation_json", columnDefinition = "TEXT")
	private String recommendationJson;

	@CreationTimestamp
	@Column(name = "create_date_time")
	private Date createDateTime;

	public AdvisorMatchLog() {}

	public AdvisorMatchLog(Long tournamentId, String recommendationJson) {
		this.tournamentId = tournamentId;
		this.recommendationJson = recommendationJson;
	}

}
