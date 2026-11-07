package fft_battleground.botland.llm;

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
 * R7 Phase 1 shadow-mode predictions (RELEASE-PLAN spec): one row per evaluated
 * cycle — the LLM's pick and confidence, its stated reasons, and the full cost/
 * telemetry block. Feeds the weekly accuracy query (LLM vs advisor vs market).
 */
@Entity
@Table(name = "llm_predictions", indexes = {
		@Index(columnList = "tournament_id", name = "llm_pred_tournament_idx"),
		@Index(columnList = "create_date_time", name = "llm_pred_created_idx") })
@Data
public class LlmPrediction {

	@Id
	@SequenceGenerator(name = "llm_prediction_generator", sequenceName = "llm_prediction_seq")
	@GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "llm_prediction_generator")
	@Column(name = "llm_prediction_id", nullable = false)
	private Long llmPredictionId;

	@Column(name = "tournament_id")
	private Long tournamentId;

	@Column(name = "left_team")
	private String leftTeam;

	@Column(name = "right_team")
	private String rightTeam;

	/** the team the model picked (resolved to the enum name; null on unparseable output) */
	@Column(name = "pick")
	private String pick;

	@Column(name = "confidence")
	private Integer confidence;

	/** the model's own reasons, verbatim JSON array */
	@Column(name = "reasons", columnDefinition = "TEXT")
	private String reasons;

	@Column(name = "model")
	private String model;

	@Column(name = "prompt_tokens")
	private Long promptTokens;

	@Column(name = "completion_tokens")
	private Long completionTokens;

	@Column(name = "latency_ms")
	private Long latencyMs;

	@Column(name = "cost_est")
	private Double costEstimate;

	/** the serialized match state actually shown to the model — replayability + audit */
	@Column(name = "match_state_json", columnDefinition = "TEXT")
	private String matchStateJson;

	@CreationTimestamp
	@Column(name = "create_date_time")
	private Date created;

}
