package fft_battleground.botland.bot.genetic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import fft_battleground.botland.bot.genetic.model.GeneTrainerV2BotData;

/**
 * Pins the V3 gene-file contract (Rust trainer, gene-trainer-3) through the
 * real loader: V2 section model, clean-semantics section contents, null-safe
 * lookups for the genes V3 files omit, and parsed bet bounds.
 */
public class GeneFileV3CacheTest {

	@Test
	public void v3WinnerFileLoadsThroughTheV2Model() throws fft_battleground.botland.bot.exception.BotConfigException {
		GeneFileV3Cache cache = new GeneFileV3Cache();
		GeneTrainerV2BotData data = cache.getGeneData("winner10-18-2026.txt");

		assertNotNull(data.getGenome());
		assertNotNull(data.getGenome().getMapGeneAttributes());
		assertNotNull(data.getGenome().getMissingGeneAttributes());

		// 128 maps x 2 sides.
		assertEquals(256, data.getGenome().getMapGeneAttributes().getGeneAttributes().getAttributes().size());
		// Clean semantics: 9 color side genes (no LEFT/RIGHT), 3 braveFaith
		// genes (no shared BraveFaith multiplier).
		assertEquals(9, data.getGenome().getSideGeneAttributes().getGeneAttributes().getAttributes().size());
		assertEquals(3, data.getGenome().getBraveFaithAttributes().getGeneAttributes().getAttributes().size());
		// Full 100-entry bet table with the V3 bounds.
		assertEquals(100, data.getGenome().getBetGeneAttributes().getGeneAttributes().getAttributes().size());
		assertEquals(Double.valueOf(1.0d), data.getGenome().getBetGeneAttributes().getMinValue());
		assertEquals(Double.valueOf(1000.0d), data.getGenome().getBetGeneAttributes().getMaxValue());

		// Null-safe lookups: genes absent from V3 files contribute 0.
		assertEquals(Double.valueOf(0.0d), data.getGenome().getBraveFaithAttributes().braveFaith());

		// The whole bet table is addressable (V2 clamped at 99).
		assertNotNull(data.getGenome().getBetGeneAttributes().getValue(1));
		assertNotNull(data.getGenome().getBetGeneAttributes().getValue(100));

		// V3 writes the real generation number.
		assertTrue(data.getGeneration() > 0);
	}
}
