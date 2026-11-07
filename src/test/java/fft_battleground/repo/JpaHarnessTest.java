package fft_battleground.repo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Date;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.TestPropertySource;

import fft_battleground.repo.model.BettorElo;
import fft_battleground.repo.model.FighterElo;
import fft_battleground.repo.model.BotHourlyData;
import fft_battleground.repo.model.Bots;
import fft_battleground.repo.model.EloHistory;
import fft_battleground.repo.model.PlayerRecord;
import fft_battleground.repo.repository.BettorEloRepo;
import fft_battleground.repo.repository.BotsHourlyDataRepo;
import fft_battleground.repo.repository.BotsRepo;
import fft_battleground.repo.repository.EloHistoryRepo;
import fft_battleground.repo.repository.PlayerRecordRepo;

/**
 * The embedded-Postgres JPA harness (one @DataJpaTest context = ONE embedded
 * instance — a second class sharing the dataDir would hit the PG file lock):
 * the capital-V lookup regression against real JPQL, plus repo-layer
 * roundtrips (the RepManagerTest revival, modernized). Isolation: port 5433,
 * own database + dataDir — the dev instance on 5432/pgdata is untouched,
 * and create-drop scopes to FFT_TEST only.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {
		"fft_battleground.embeddedPostgres=true",
		"fft_battleground.embeddedPostgres.port=5433",
		"fft_battleground.embeddedPostgres.database=FFT_TEST",
		"fft_battleground.embeddedPostgres.dataDir=target/test-pgdata"
})
public class JpaHarnessTest {

	@Autowired
	private BotsRepo botsRepo;

	@Autowired
	private BotsHourlyDataRepo botsHourlyDataRepo;

	@Autowired
	private PlayerRecordRepo playerRecordRepo;

	@Autowired
	private BettorEloRepo bettorEloRepo;

	@Autowired
	private EloHistoryRepo eloHistoryRepo;

	@Test
	public void botRecordLookupIgnoreCase() {
		this.botsRepo.saveAndFlush(new Bots("genebotV3-Test-Bot", "10-25-2026", 200));

		assertNotNull(this.botsRepo.getBotByDateStringAndName("10-25-2026", "genebotv3-test-bot"),
				"lowercase lookup must find the mixed-case row (the capital-V bug)");
		assertNotNull(this.botsRepo.getBotByDateStringAndName("10-25-2026", "GENEBOTV3-TEST-BOT"),
				"uppercase lookup must find the mixed-case row");
		assertNotNull(this.botsRepo.getBotByDateStringAndName("10-25-2026", "genebotV3-Test-Bot"),
				"exact-case lookup must still work");
		assertNull(this.botsRepo.getBotByDateStringAndName("10-25-2026", "genebotv3-other-bot"),
				"different bots must not match");
	}

	@Test
	public void botHourlyLookupIgnoreCase() {
		BotHourlyData mixedCaseHourly = new BotHourlyData("genebotV3-Test-Bot", 500);
		this.botsHourlyDataRepo.saveAndFlush(mixedCaseHourly);
		int hour = mixedCaseHourly.getHourValue();

		BotHourlyData found = this.botsHourlyDataRepo.getBotHourlyDataForBotAndCurrentTime("genebotv3-test-bot", hour);
		assertNotNull(found, "lowercase hourly lookup must find the mixed-case row (the frozen-hourly bug)");
		assertEquals(Integer.valueOf(500), found.getBalance());
	}

	@Test
	public void playerRecordRoundtrip() {
		PlayerRecord record = new PlayerRecord();
		record.setPlayer("roundtripplayer");
		record.setWins(3);
		record.setLosses(4);
		record.setFightWins(5);
		record.setFightLosses(6);
		this.playerRecordRepo.saveAndFlush(record);

		Optional<PlayerRecord> found = this.playerRecordRepo.findById("roundtripplayer");
		assertTrue(found.isPresent(), "player record must read back by its natural key");
		assertEquals(Integer.valueOf(3), found.get().getWins());
		assertEquals(Integer.valueOf(4), found.get().getLosses());
	}

	@Test
	public void bettorEloRoundtripAndDerivedLookup() {
		this.bettorEloRepo.saveAndFlush(new BettorElo("eloplayer", true));
		this.bettorEloRepo.saveAndFlush(new BettorElo("otherplayer", false));

		List<BettorElo> found = this.bettorEloRepo.findByPlayerIn(List.of("eloplayer", "otherplayer", "missing"));
		assertEquals(2, found.size(), "findByPlayerIn returns only stored players");
		BettorElo eloPlayer = found.stream().filter(e -> e.getPlayer().equals("eloplayer")).findFirst().get();
		assertEquals(1500.0, eloPlayer.getElo(), 1e-9, "new bettors start at 1500");
		assertTrue(eloPlayer.isBot(), "bot flag roundtrips");
		assertTrue(eloPlayer.isProvisional(), "fresh bettor is provisional (<20 matches)");
	}

	@Test
	public void eloHistoryOrdersAscendingByTime() {
		String player = "historyplayer";
		List<EloHistory> entries = new java.util.ArrayList<>();
		for(int i = 0; i < 5; i++) {
			entries.add(new EloHistory(player, (long) i, 12.0, 1500.0 + i, 1512.0 + i));
		}
		this.eloHistoryRepo.saveAll(entries);
		this.eloHistoryRepo.flush();

		//@CreationTimestamp regenerates on INSERT — set distinct times via UPDATE, where it stays put
		for(int i = 0; i < entries.size(); i++) {
			entries.get(i).setCreateDateTime(new Date(1_000_000L * i + 500L));
		}
		this.eloHistoryRepo.saveAll(entries);
		this.eloHistoryRepo.flush();

		List<EloHistory> ordered = this.eloHistoryRepo.findByPlayerOrderByCreateDateTimeAsc(player);
		assertEquals(5, ordered.size());
		for(int i = 1; i < ordered.size(); i++) {
			assertTrue(ordered.get(i).getCreateDateTime().after(ordered.get(i - 1).getCreateDateTime()),
					"ascending order must hold");
		}
	}

	@Autowired
	private fft_battleground.repo.repository.FighterEloRepo fighterEloRepo;

	@Test
	public void rankedQueriesExcludeProvisionalAndOrderByElo() {
		//bettor: one established, one provisional
		BettorElo established = new BettorElo("rankedbetter", false);
		established.setPickWins(15);
		established.setPickLosses(10);
		established.setElo(1800.0);
		BettorElo provisional = new BettorElo("provisionalbetter", false);
		provisional.setPickWins(2);
		provisional.setPickLosses(1);
		provisional.setElo(2100.0); //high elo but under 20 matches
		this.bettorEloRepo.saveAll(List.of(established, provisional));

		List<BettorElo> bettorRanked = this.bettorEloRepo.findRanked(20, org.springframework.data.domain.PageRequest.of(0, 100));
		assertEquals(1, bettorRanked.size(), "provisional bettors stay off the ranked board");
		assertEquals("rankedbetter", bettorRanked.get(0).getPlayer());

		//fighter: same query semantics on the mirrored table
		FighterElo fighter = new FighterElo("rankedfighter", false);
		fighter.setFightWins(20);
		fighter.setFightLosses(5);
		fighter.setElo(1750.0);
		FighterElo provisionalFighter = new FighterElo("provisionalfighter", false);
		provisionalFighter.setFightWins(1);
		provisionalFighter.setFightLosses(0);
		this.fighterEloRepo.saveAll(List.of(fighter, provisionalFighter));

		List<FighterElo> fighterRanked = this.fighterEloRepo.findRanked(20, org.springframework.data.domain.PageRequest.of(0, 100));
		assertEquals(1, fighterRanked.size(), "provisional fighters stay off the ranked board");
		assertEquals("rankedfighter", fighterRanked.get(0).getPlayer());
	}

}
