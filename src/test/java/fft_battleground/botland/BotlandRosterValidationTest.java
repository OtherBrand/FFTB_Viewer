package fft_battleground.botland;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import fft_battleground.botland.bot.ArbitraryBot;
import fft_battleground.botland.bot.BetCountBot;
import fft_battleground.botland.bot.BraveFaithBot;
import fft_battleground.botland.bot.DataBetBot;
import fft_battleground.botland.bot.GeneticBot;
import fft_battleground.botland.bot.GeneticBotV2;
import fft_battleground.botland.bot.GeneticBotV3;
import fft_battleground.botland.bot.OddsBot;
import fft_battleground.botland.bot.ReferenceBot;
import fft_battleground.botland.bot.TeamValueBot;
import fft_battleground.botland.bot.UnitAwareBot;
import fft_battleground.botland.bot.genetic.GeneFileV1Cache;
import fft_battleground.botland.bot.genetic.GeneFileV2Cache;
import fft_battleground.botland.bot.genetic.GeneFileV3Cache;
import fft_battleground.botland.bot.util.BetterBetBot;
import fft_battleground.botland.model.BotData;
import fft_battleground.botland.model.BotNames;
import fft_battleground.model.BattleGroundTeam;
import lombok.extern.slf4j.Slf4j;

/**
 * Roster validation: every shipped Botland.xml entry must map to a bot class,
 * construct, and initialize with its own params — the build-time guard for
 * the broken-roster-entry class (the Oct-23 personality crash died at exactly
 * this boundary, and a broken entry otherwise only surfaces as per-cycle
 * init failures in prod).
 */
@Slf4j
public class BotlandRosterValidationTest {

	private static final Integer STARTING_BALANCE = 1000;

	@Test
	public void everyRosterEntryConstructsAndInitializes() {
		List<BotData> roster = new SecondaryBotConfig("Botland.xml").parseXmlFile();
		//guard against a stale shadow copy replacing the real roster on the classpath
		assertTrue(roster.size() > 50, "suspiciously small roster: " + roster.size() + " entries");

		List<String> failures = new ArrayList<>();
		for(BotData entry : roster) {
			try {
				BetterBetBot bot = this.constructBot(entry.getClassname());
				bot.setName(entry.getName());
				bot.initParams(entry.getParams());
			} catch(Exception e) {
				failures.add(entry.getName() + " (" + entry.getClassname() + "): "
						+ e.getClass().getSimpleName() + ": " + e.getMessage());
			}
		}
		assertTrue(failures.isEmpty(),
				"roster entries failed construction/init — " + String.join("; ", failures));
	}

	@Test
	public void rosterNamesAreUnique() {
		//getBotDataMap() builds a toMap with no merge function: a duplicate id would
		//throw IllegalStateException at runtime on /botland
		List<BotData> roster = new SecondaryBotConfig("Botland.xml").parseXmlFile();
		Set<String> seen = new HashSet<>();
		List<String> duplicates = new ArrayList<>();
		for(BotData entry : roster) {
			if(!seen.add(entry.getName().toLowerCase())) {
				duplicates.add(entry.getName());
			}
		}
		assertTrue(duplicates.isEmpty(), "duplicate roster ids: " + String.join(", ", duplicates));
	}

	private BetterBetBot constructBot(String classname) {
		BattleGroundTeam left = BattleGroundTeam.YELLOW;
		BattleGroundTeam right = BattleGroundTeam.WHITE;
		BotNames botName = BotNames.parseBotname(classname);
		if(botName == null) {
			throw new IllegalArgumentException("unknown classname: " + classname);
		}
		switch(botName) {
		case BETCOUNT: return new BetCountBot(STARTING_BALANCE, left, right);
		case DATA: return new DataBetBot(STARTING_BALANCE, left, right);
		case ODDS: return new OddsBot(STARTING_BALANCE, left, right);
		case ARBITRARY: return new ArbitraryBot(STARTING_BALANCE, left, right);
		case GENE: return new GeneticBot(STARTING_BALANCE, left, right, new GeneFileV1Cache());
		case GENE_V2: return new GeneticBotV2(STARTING_BALANCE, left, right, new GeneFileV2Cache(), Set.of(), null);
		case GENE_V3: return new GeneticBotV3(STARTING_BALANCE, left, right, new GeneFileV3Cache(), Set.of(), null);
		case BRAVEFAITH: return new BraveFaithBot(STARTING_BALANCE, left, right);
		case TEAMVALUE: return new TeamValueBot(STARTING_BALANCE, left, right);
		case UNIT: return new UnitAwareBot(STARTING_BALANCE, left, right, new GeneFileV1Cache());
		case REFERENCE: return new ReferenceBot(STARTING_BALANCE, left, right);
		default: throw new IllegalArgumentException("unhandled bot class: " + classname);
		}
	}

}
