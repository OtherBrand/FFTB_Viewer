package fft_battleground.botland.bot.genetic.model;

import java.io.Serializable;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import fft_battleground.botland.bot.exception.BotConfigException;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class BotGenome implements Serializable {
	/**
	 * 
	 */
	private static final long serialVersionUID = 8669765184330928045L;
	
	private MapGeneAttributes mapGeneAttributes;
	private BraveFaithAttributes braveFaithAttributes;
	private PotGeneAttributes potGeneAttributes;
	private PlayerDataGeneAttributes playerDataGeneAttributes;
	private SideGeneAttributes sideGeneAttributes;
	private BetGeneAttributes betGeneAttributes;
	/** V3 policy curve genes (Oct 23 2026); null in V2 and older V3 files. */
	private PolicyGeneAttributes policyGeneAttributes;
	private MissingGeneAttributes missingGeneAttributes;

	public void init() throws BotConfigException {
		List<GeneAttributes> geneAttributes = java.util.Arrays.asList(this.mapGeneAttributes, this.braveFaithAttributes,
			this.potGeneAttributes, this.playerDataGeneAttributes, this.sideGeneAttributes, this.betGeneAttributes,
			this.policyGeneAttributes, this.missingGeneAttributes);
		for(GeneAttributes geneAttribute : geneAttributes) {
			if(geneAttribute != null) {
				geneAttribute.init();
			}
		}

	}
}
