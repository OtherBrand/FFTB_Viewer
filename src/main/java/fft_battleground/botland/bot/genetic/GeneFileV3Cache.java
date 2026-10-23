package fft_battleground.botland.bot.genetic;

import org.apache.commons.lang3.tuple.Pair;

import fft_battleground.botland.bot.exception.BotConfigException;
import fft_battleground.botland.bot.genetic.model.GeneTrainerV2BotData;

/**
 * Gene files from the Rust V3 trainer (gene-trainer-3). V3 files share the
 * V2 section model — the differences are which genes exist (9 side colors
 * with no LEFT/RIGHT, no BraveFaith shared multiplier, bet bounds 1..1000,
 * a real generation number) — handled by the null-safe gene lookups and
 * scored by {@link fft_battleground.botland.bot.GeneticBotV3}.
 */
public class GeneFileV3Cache extends GeneFileCache<GeneTrainerV2BotData> {

	@Override
	protected Class<GeneTrainerV2BotData> getCacheType() {
		return GeneTrainerV2BotData.class;
	}

	@Override
	public GeneTrainerV2BotData getGeneData(String filename) throws BotConfigException {
		GeneFile<GeneTrainerV2BotData> geneFile = this.loadGeneDataFromFile(filename);

		GeneTrainerV2BotData data = geneFile.getData();
		if (this.genefileWithMostRecentData == null || this.genefileWithMostRecentData.getLeft().before(geneFile.getFileDate())) {
			this.genefileWithMostRecentData = Pair.of(geneFile.getFileDate(), geneFile.getFilename());
		}

		return data;
	}

	@Override
	protected String baseFolder() {
		return "GeneFiles/GeneTrainerV3";
	}

	@Override
	public GeneFile<GeneTrainerV2BotData> loadGeneDataFromFile(String filename) throws BotConfigException {
		GeneFile<GeneTrainerV2BotData> botData = super.loadGeneDataFromFile(filename);
		botData.getData().init();
		return botData;
	}
}
