package fft_battleground.botland;

import java.util.ArrayList;
import java.util.List;
import org.apache.commons.lang3.tuple.Pair;

import fft_battleground.botland.bot.GeneticBotV2;
import fft_battleground.botland.bot.model.BetResults;
import fft_battleground.botland.bot.util.BetterBetBot;
import fft_battleground.event.detector.model.BetEvent;
import fft_battleground.event.detector.model.ResultEvent;
import lombok.Data;

@Data
public class BetResultCollector {

	private BotlandHelper helper;
	private BetterBetBot primaryBot;
	private List<BetterBetBot> subordinateBots;
	
	public BetResultCollector(BotlandHelper helper, BetterBetBot primaryBot, List<BetterBetBot> subordinateBots) {
		this.helper = helper;
		this.primaryBot = primaryBot;
		this.subordinateBots = subordinateBots;
	}
	
	public BetResults getResult(ResultEvent event) {
		BetResults results = null;
		
		List<BetterBetBot> subordinateBotsWithResults = new ArrayList<>();
		for(BetterBetBot bot : this.subordinateBots) {
			//evaluation-only (explain) bots never sim-bet: their result exists for the advisor capture only
			boolean evaluationOnly = bot instanceof GeneticBotV2 && ((GeneticBotV2) bot).isExplain();
			if(bot.getResult() != null && !evaluationOnly) {
				subordinateBotsWithResults.add(bot);
			}
		}
		
		Pair<List<BetEvent>, List<BetEvent>> betsBySide = helper.sortBetsBySide();
		results = new BetResults(betsBySide, event.getWinner(), helper.getLeft(), helper.getRight(), helper.getBettingEndsEvent(), 
				helper.getMatchInfo(), helper.getTeamData(), subordinateBotsWithResults);

		
		return results;
	}
	 
}
