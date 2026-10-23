package fft_battleground.botland.bot.genetic.model;

import java.io.Serializable;
import java.util.Map;
import java.util.stream.Collectors;

import com.fasterxml.jackson.annotation.JsonIgnore;

import fft_battleground.model.BattleGroundTeam;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@EqualsAndHashCode(callSuper=true)
public class SideGeneAttributes 
extends GeneAttributes
implements Serializable {

	/**
	 * 
	 */
	private static final long serialVersionUID = -8090830343214187826L;
	
	@JsonIgnore
	protected transient Map<BattleGroundTeam, Double> teamAttributeMap;
	
	@Override
	public void init() {
		this.teamAttributeMap = this.geneAttributes.getAttributes().stream()
			.collect(Collectors.toMap(this::getTeamKeyFromGeneAttributePair, GeneAttributePair::getValue));
	}
	
	/** Null-safe: teams without a gene in the file (V3 files carry no
	 *  LEFT/RIGHT side genes) contribute 0 instead of NPEing. */
	public Double getAttributeByTeam(BattleGroundTeam team) {
		return this.teamAttributeMap.getOrDefault(team, 0.0d);
	}
	
	private BattleGroundTeam getTeamKeyFromGeneAttributePair(GeneAttributePair geneAttribute) {
		return BattleGroundTeam.parse(geneAttribute.getKey());
	}
}
