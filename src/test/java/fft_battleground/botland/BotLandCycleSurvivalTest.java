package fft_battleground.botland;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import fft_battleground.event.detector.model.BettingBeginsEvent;
import fft_battleground.model.BattleGroundTeam;

/**
 * Timer-thread death (2026-10-26): a personality NPE after a caught bet
 * failure killed Timer-3 permanently. The cycle must survive ANY throwable,
 * and flavor must skip on null scores rather than die.
 */
public class BotLandCycleSurvivalTest {

	@Test
	public void runSurvivesAThrowingPrimaryAndStillCompletes() {
		BettingBeginsEvent begin = new BettingBeginsEvent(BattleGroundTeam.RED, BattleGroundTeam.BLUE);
		BotLand land = new BotLand(1000, begin, List.of(), Set.of(), null);

		//primary: call() throws AND personality throws (the Timer-3 sequence)
		AtomicBoolean personalityCalled = new AtomicBoolean(false);
		fft_battleground.botland.bot.util.BetterBetBot primary =
				new fft_battleground.botland.bot.util.BetterBetBot(1000, BattleGroundTeam.RED, BattleGroundTeam.BLUE) {
			@Override
			public fft_battleground.botland.bot.model.Bet call() {
				throw new RuntimeException("MissingTeamInfo simulation");
			}

			@Override
			public fft_battleground.botland.personality.model.PersonalityResponse generatePersonalityResponse() {
				personalityCalled.set(true);
				throw new NullPointerException("null scores simulation");
			}

			@Override
			public String getName() {
				return "doomed-primary";
			}

			@Override
			protected fft_battleground.botland.bot.model.Bet generateBetAmount(java.lang.Float left, java.lang.Float right,
					BattleGroundTeam team) {
				return null;
			}

			@Override
			public void initParams(java.util.Map<String, fft_battleground.botland.model.BotParam> params) {
			}

			@Override
			public void init() {
			}

			@Override
			protected java.lang.Float generateLeftScore() {
				throw new RuntimeException("no eval");
			}

			@Override
			protected java.lang.Float generateRightScore() {
				throw new RuntimeException("no eval");
			}

			@Override
			public String getDateFormat() {
				return "test";
			}

			@Override
			public void setName(String name) {
			}
		};
		ReflectionTestUtils.setField(land, "primaryBot", primary);
		ReflectionTestUtils.setField(land, "subordinateBots", List.of());
		//errorWebhookManager/statusService null is fine — the guard null-checks them

		land.run(); // must not throw

		assertTrue(personalityCalled.get(), "the personality path ran (guarded)");
		//reaching here at all is the assertion: the cycle completed without propagating
	}

	@Test
	public void personalitySkipsOnNullScores() {
		fft_battleground.botland.personality.PersonalityModule facts =
				new fft_battleground.botland.personality.FactsPersonality();
		fft_battleground.botland.personality.model.PersonalityResponse response =
				facts.getPersonalityResponse("bot", null, BattleGroundTeam.RED, null, BattleGroundTeam.BLUE, java.util.Map.of());
		assertTrue(response == null, "null scores must skip flavor, not NPE");
	}
}
