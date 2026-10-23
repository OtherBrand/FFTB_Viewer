package fft_battleground.botland.bot.genetic.model;

import java.io.Serializable;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@EqualsAndHashCode(callSuper=true)
public class BraveFaithAttributes 
extends GeneAttributes
implements Serializable {
	/**
	 * 
	 */
	private static final long serialVersionUID = -3291209177591407908L;
	
	public static final String BRAVE_KEY = "Brave";
	public static final String FAITH_KEY = "Faith";
	public static final String BRAVEFAITH_KEY = "BraveFaith";
	public static final String RAIDBOSS_KEY = "RaidBoss";
	
	/** Null-safe lookups: a gene absent from the file (V3 files carry no
	 *  BraveFaith shared multiplier) contributes 0 instead of NPEing the
	 *  bet cycle. */
	public Double brave() {
		return this.attributeNameMap.getOrDefault(BRAVE_KEY, 0.0d);
	}

	public Double faith() {
		return this.attributeNameMap.getOrDefault(FAITH_KEY, 0.0d);
	}

	public Double braveFaith() {
		return this.attributeNameMap.getOrDefault(BRAVEFAITH_KEY, 0.0d);
	}

	public Double raidBoss() {
		return this.attributeNameMap.getOrDefault(RAIDBOSS_KEY, 0.0d);
	}
}
