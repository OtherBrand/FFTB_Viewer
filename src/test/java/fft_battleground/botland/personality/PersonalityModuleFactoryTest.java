package fft_battleground.botland.personality;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import org.junit.jupiter.api.Test;

import fft_battleground.botland.bot.exception.BotConfigException;
import fft_battleground.model.BattleGroundTeam;
import fft_battleground.botland.personality.model.PersonalityResponse;
import lombok.extern.slf4j.Slf4j;

/**
 * PersonalityModuleFactory seam: known names resolve to renderable modules,
 * unknown names throw the config exception (the class the Oct-23 incident
 * taught us to surface), and a rendered response carries a usable string.
 */
@Slf4j
public class PersonalityModuleFactoryTest {

	@Test
	public void factsPersonalityRendersAResponse() throws BotConfigException {
		PersonalityModuleFactory factory = new PersonalityModuleFactory();
		PersonalityModule module = factory.getPersonalityModuleByName("facts");

		//production shape: the full 1..100 bet table (percentile lookups miss on partial maps)
		Map<Integer, Integer> percentiles = new java.util.HashMap<>();
		for(int i = 1; i <= 100; i++) {
			percentiles.put(i, 100 + i * 10);
		}

		assertNotNull(module, "the facts personality must resolve");
		PersonalityResponse response = module.getPersonalityResponse("testbot", 1200f, BattleGroundTeam.YELLOW,
				900f, BattleGroundTeam.WHITE, percentiles);
		assertNotNull(response, "facts must render for a real score pair");
		assertNotNull(response.getResponse());
		assertTrue(!response.getResponse().isEmpty(), "rendered personality text must be non-empty");
	}

	@Test
	public void unknownPersonalityThrowsTheConfigException() {
		PersonalityModuleFactory factory = new PersonalityModuleFactory();
		assertThrows(BotConfigException.class, () -> factory.getPersonalityModuleByName("nosuchpersonality"));
	}

	@Test
	public void botResponsesAccumulateForTheBotlandPage() {
		PersonalityModuleFactory factory = new PersonalityModuleFactory();
		factory.addBotResponse("somebot", "I feel lucky");
		assertTrue(factory.getLastBotPersonalityResponses().containsKey("somebot"));
	}

}
