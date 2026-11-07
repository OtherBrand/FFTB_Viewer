package fft_battleground.botland.bot.genetic.model;

import java.io.Serializable;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import fft_battleground.botland.bot.exception.BotConfigException;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Data
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
@Slf4j
public class GeneTrainerV2BotData implements Serializable {
	/**
	 * 
	 */
	private static final long serialVersionUID = -8240185947691080322L;

	@JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd HH:mm a z")
	private Date creationDate;
	
	private List<BotPlacement> botLeaderboard;
	private int matchesAnalyzed;
	private long gilResult;
	private long perfectGil;
	private int geneCount;
	private int generation;
	private List<Percentile> percentiles;
	private BotGenome genome;
	
	@JsonIgnore
	private transient Map<Integer, Integer> percentileMap;
	
	public void init() throws BotConfigException {
		this.genome.init();
		this.initPercentileMap();
	}
	
	private void initPercentileMap() {
		if(percentiles != null) {
			this.percentileMap = this.percentiles.stream().collect(Collectors.toMap(percentile -> percentile.getKey(), percentile -> percentile.getRoundedPercentileValue()));
		} else {
			log.warn("no percentiles data present, not generating map");
		}
	}
}
