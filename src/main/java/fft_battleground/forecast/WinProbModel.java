package fft_battleground.forecast;

import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

/**
 * The trainer's win-probability model (winprob-v1.json): logistic regression
 * over the attribute-difference feature space, temperature pre-folded into
 * the weights — scoring is plain sigmoid(intercept + Σ weight·value).
 * Features and conventions are EXACTLY fftb-forecast's features.rs:
 * attr:<name> count differences (left +1 / right -1), agg:brave/faith/raidBoss
 * stat-sum differences, map:<n> one-hot (2.0 — the trainer double-bumps the
 * two side genes), side:<LeftColor> one-hot on the label side.
 */
@Component
@Slf4j
public class WinProbModel {

	@Getter
	private String modelVersion = "unloaded";
	@Getter
	private double intercept = 0.0;
	private final Map<String, Double> weights = new HashMap<>();

	public WinProbModel() {
		//v2 (full-history, 1069 features) is the served model; v1 stays as the
		//fallback so a missing v2 degrades instead of disabling forecasts
		this.load("models/winprob-v2.json");
		if(!this.isLoaded()) {
			this.load("models/winprob-v1.json");
		}
	}

	protected WinProbModel(String resourcePath) {
		this.load(resourcePath);
	}

	/** test fixture: an empty model (isLoaded() == false) with settable fields */
	public static WinProbModel unloaded() {
		return new WinProbModel("models/does-not-exist.json");
	}

	protected void load(String resourcePath) {
		try (InputStream in = new ClassPathResource(resourcePath).getInputStream()) {
			JsonNode root = new ObjectMapper().readTree(in);
			this.modelVersion = root.path("model").asText("unknown") + "/v" + root.path("version").asInt(0);
			this.intercept = root.path("intercept").asDouble(0.0);
			this.weights.clear();
			for(JsonNode weight : root.path("weights")) {
				this.weights.put(weight.path("name").asText(), weight.path("weight").asDouble(0.0));
			}
			log.info("win-prob model loaded: {} features, version {}, intercept {}",
					this.weights.size(), this.modelVersion, this.intercept);
		} catch(Exception e) {
			log.error("failed to load win-prob model from {} — forecasts will serve 503", resourcePath, e);
			this.weights.clear();
		}
	}

	public boolean isLoaded() {
		return !this.weights.isEmpty();
	}

	/** plain linear score over the feature multiset (values may repeat/multiply) */
	public double linearScore(Map<String, Double> features) {
		double score = this.intercept;
		for(Map.Entry<String, Double> feature : features.entrySet()) {
			Double weight = this.weights.get(feature.getKey());
			if(weight != null && feature.getValue() != null && feature.getValue() != 0.0) {
				score += weight * feature.getValue();
			}
		}
		return score;
	}

	/** P(left wins) — sigmoid of the linear score */
	public double probabilityLeft(Map<String, Double> features) {
		return 1.0 / (1.0 + Math.exp(-this.linearScore(features)));
	}

	/** signed contribution of one feature (weight x value); 0 when the model has no such feature */
	public double contribution(String name, double value) {
		Double weight = this.weights.get(name);
		return weight != null ? weight * value : 0.0;
	}

}
