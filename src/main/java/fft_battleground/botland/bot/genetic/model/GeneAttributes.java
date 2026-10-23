package fft_battleground.botland.bot.genetic.model;

import java.io.Serializable;
import java.util.HashMap;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import fft_battleground.botland.bot.exception.BotConfigException;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import lombok.Data;
import lombok.NoArgsConstructor;

@Slf4j
@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class GeneAttributes implements Serializable {
	/**
	 * 
	 */
	private static final long serialVersionUID = -1539905136661247962L;

	protected GeneAttributeContainer geneAttributes;
	
	@JsonIgnore
	protected transient Map<String, Double> attributeNameMap;
	
	public void init() throws BotConfigException {
		this.attributeNameMap = this.buildAttributeNameMap();
	}
	
	public Double getGene(String attributeName) {
		return this.attributeNameMap.get(attributeName);
	}
	
	protected Map<String, Double> buildAttributeNameMap() {
		//duplicate keys in a hand-edited or legacy gene file must not kill the bot load (V1's April
		//failure mode was exactly this toMap); first-wins with a warning, matching GenericPairing's policy
		Map<String, Double> map = new HashMap<>();
		for(GeneAttributePair pair : this.geneAttributes.getAttributes()) {
			Double previous = map.putIfAbsent(pair.getKey(), pair.getValue());
			if(previous != null) {
				log.warn("Duplicate gene key [{}]: keeping value [{}] over [{}]", pair.getKey(), previous, pair.getValue());
			}
		}
		return map;
	}
}
