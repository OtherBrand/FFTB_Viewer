package fft_battleground.dump.service;

import java.text.DecimalFormat;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TimeZone;

import org.apache.commons.lang3.StringUtils;
import org.hibernate.Hibernate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import fft_battleground.controller.response.model.PlayerData;
import fft_battleground.dump.cache.map.ClassBonusCache;
import fft_battleground.dump.cache.map.PrestigeSkillsCache;
import fft_battleground.dump.cache.map.SkillBonusCache;
import fft_battleground.dump.cache.map.leaderboard.ExpRankLeaderboardByPlayer;
import fft_battleground.dump.cache.map.leaderboard.PlayerLeaderboardCache;
import fft_battleground.dump.cache.set.BotCache;
import fft_battleground.exception.CacheMissException;
import fft_battleground.exception.TournamentApiException;
import fft_battleground.image.model.Images;
import fft_battleground.repo.model.PlayerRecord;
import fft_battleground.repo.model.PlayerSkills;
import fft_battleground.repo.model.PrestigeSkills;
import fft_battleground.repo.repository.PlayerRecordRepo;
import fft_battleground.reports.BetPercentileReportGenerator;
import fft_battleground.reports.FightPercentileReportGenerator;
import fft_battleground.tournament.TournamentService;
import fft_battleground.util.GambleUtil;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

@Service
@Slf4j
public class PlayerPageDataServiceImpl implements PlayerPageDataService {
	
	@Autowired
	@Getter private TournamentService tournamentService;
	
	@Autowired
	@Getter private PlayerRecordRepo playerRecordRepo;
	
	@Autowired
	private GlobalGiServiceImpl globalGilUtil;
	
	@Autowired
	@Getter private BetPercentileReportGenerator betPercentileReportGenerator;
	
	@Autowired
	@Getter private FightPercentileReportGenerator fightPercentileReportGenerator;
	
	@Autowired
	private Images images;
	
	@Autowired
	private PrestigeSkillsCache prestigeSkillsCache;
	
	@Autowired
	private ClassBonusCache classBonusCache;
	
	@Autowired
	private SkillBonusCache skillBonusCache;
	
	@Autowired
	private BotCache botCache;

	@Autowired
	private fft_battleground.repo.repository.BettorEloRepo bettorEloRepo;

	@Autowired
	private fft_battleground.repo.repository.FighterEloRepo fighterEloRepo;

	@Autowired
	private fft_battleground.skill.PrestigeProgressService prestigeProgressService;
	
	@Autowired
	private PlayerLeaderboardCache playerLeaderboardCache;
	
	@Autowired
	private ExpRankLeaderboardByPlayer expRankLeaderboardByPlayer;
	
	@Override
	@Transactional(readOnly = true)
	public PlayerData getDataForPlayerPage(String playerName, TimeZone timezone) throws CacheMissException, TournamentApiException {
		PlayerData playerData = new PlayerData();
		String id = StringUtils.trim(StringUtils.lowerCase(playerName));
		Optional<PlayerRecord> maybePlayer = this.playerRecordRepo.findById(id);
		if(maybePlayer.isPresent()) {
			
			PlayerRecord record = maybePlayer.get();
			Hibernate.initialize(maybePlayer.get().getPlayerSkills());
			Hibernate.initialize(maybePlayer.get().getPrestigeSkills());
			for(PlayerSkills playerSkill : record.getPlayerSkills()) {
				playerSkill.setMetadata(StringUtils.replace(this.tournamentService.getCurrentTips().getUserSkill().get(playerSkill.getSkill()), "\"", ""));
			}
			for(PrestigeSkills prestigeSkill: record.getPrestigeSkills()) {
				prestigeSkill.setMetadata(StringUtils.replace(this.tournamentService.getCurrentTips().getUserSkill().get(prestigeSkill.getSkill()), "\"", ""));
			}
			playerData.setPlayerRecord(record);
			
			boolean isBot = this.botCache.contains(record.getPlayer());
			playerData.setBot(isBot);
			
			if(StringUtils.isNotBlank(record.getPortrait())) {
				String portrait = record.getPortrait();
				String portraitUrl = this.images.getPortraitByName(portrait, record.getAllegiance());
				playerData.setPortraitUrl(portraitUrl);
			}
			if(StringUtils.isBlank(record.getPortrait()) || playerData.getPortraitUrl() == null) {
				if(playerData.isBot()) {
					playerData.setPortraitUrl(this.images.getPortraitByName("Steel Giant"));
				} else {
					playerData.setPortraitUrl(this.images.getPortraitByName("Ramza"));
				}
			}
			
			DecimalFormat df = new DecimalFormat("0.00");
			Double betRatio = ((double) 1 + record.getWins())/((double)1+ record.getWins() + record.getLosses());
			Double fightRatio = ((double)1 + record.getFightWins())/((double) record.getFightWins() + record.getFightLosses());
			String betRatioString = df.format(betRatio);
			String fightRatioString = df.format(fightRatio);
			playerData.setBetRatio(betRatioString);
			playerData.setFightRatio(fightRatioString);
			Integer betPercentile = this.getBetPercentile(betRatio);
			Integer fightPercentile = this.getFightPercentile(fightRatio);
			playerData.setBetPercentile(betPercentile);
			playerData.setFightPercentile(fightPercentile);

			//bet ELO card: unrated players show the 1500 start, provisional
			fft_battleground.repo.model.BettorElo bettorElo = this.bettorEloRepo.findById(record.getPlayer()).orElse(null);
			playerData.setElo(bettorElo != null
					? new fft_battleground.controller.response.model.BettorEloSummary(bettorElo.getElo(), bettorElo.getPickWins(),
							bettorElo.getPickLosses(), bettorElo.isBot(), bettorElo.isProvisional(), bettorElo.getMatches())
					: fft_battleground.controller.response.model.BettorEloSummary.unrated());

			//prestige checklist: missing skills before prestige (universe mirrors SkillUtils)
			playerData.setPrestigeProgress(this.prestigeProgressService.progressFor(record.getPlayer(),
					record.getPlayerSkills() != null
							? record.getPlayerSkills().stream().map(ps -> ps != null ? ps.getSkill() : null).collect(java.util.stream.Collectors.toList())
							: java.util.List.of()));

			//fighter ELO card: same shape, combat rating
			fft_battleground.repo.model.FighterElo fighterEloRow = this.fighterEloRepo.findById(record.getPlayer()).orElse(null);
			playerData.setFighterElo(fighterEloRow != null
					? new fft_battleground.controller.response.model.FighterEloSummary(fighterEloRow.getElo(), fighterEloRow.getFightWins(),
							fighterEloRow.getFightLosses(), fighterEloRow.isBot(), fighterEloRow.isProvisional(), fighterEloRow.getMatches())
					: fft_battleground.controller.response.model.FighterEloSummary.unrated());
			
			boolean containsPrestige = false;
			int prestigeLevel = 0;
			Set<String> prestigeSkills = null;
			if(this.prestigeSkillsCache.get(playerName) != null) {
				prestigeSkills = new HashSet<>(this.prestigeSkillsCache.get(playerName));
				prestigeLevel = prestigeSkills.size();
				containsPrestige = true;
			}
			
			playerData.setContainsPrestige(containsPrestige);
			playerData.setPrestigeLevel(prestigeLevel);
			playerData.setPrestigeSkills(prestigeSkills);
			playerData.setExpRank(this.expRankLeaderboardByPlayer.get(record.getPlayer()));
			
			DecimalFormat format = new DecimalFormat("##.#########");
			String percentageOfTotalGil = format.format(this.globalGilUtil.percentageOfGlobalGil(record.getLastKnownAmount()) * (double)100);
			playerData.setPercentageOfGlobalGil(percentageOfTotalGil);
			
			if(record.getLastActive() != null) {
				playerData.setTimezoneFormattedDateString(this.createDateStringWithTimezone(timezone, record.getLastActive()));
			}
			if(record.getLastFightActive() != null) {
				playerData.setTimezoneFormattedLastFightActiveDateString(this.createDateStringWithTimezone(timezone, record.getLastFightActive()));
			}
			
			Integer leaderboardRank = this.getLeaderboardPosition(playerName);
			playerData.setLeaderboardPosition(leaderboardRank);
			
			Set<String> classBonuses = this.classBonusCache.get(playerName);
			playerData.setClassBonuses(classBonuses);
			
			Set<String> skillBonuses = this.skillBonusCache.get(playerName);
			playerData.setSkillBonuses(skillBonuses);
		} else {
			playerData = new PlayerData();
			playerData.setNotFound(true);
			playerData.setPlayerRecord(new PlayerRecord());
			playerData.getPlayerRecord().setPlayer(GambleUtil.cleanString(playerName));
		}
			
		return playerData;
	}
	

	@Override
	public Integer getBetPercentile(Double ratio) throws CacheMissException {
		Map<Integer, Double> betPercentiles = this.betPercentileReportGenerator.getReport();

		Integer result = null;
		for (Map.Entry<Integer, Double> entry: betPercentiles.entrySet()) {
			Double currentPercentile = entry.getValue();
			try {
				if (ratio < currentPercentile) {
					Integer key = Integer.valueOf(entry.getKey());
					result = key - 1;
					break;
				}
			}catch(NullPointerException e) {
				log.error("NullPointerException caught", e);
			} catch(ClassCastException e) {
				log.error("ClassCast exception caught", e);
			}
		}

		return result;
	}

	@Override
	public Integer getFightPercentile(Double ratio) throws CacheMissException {
		Map<Integer, Double> fightPercentiles = this.fightPercentileReportGenerator.getReport();

		Integer result = null;
		for (int i = 0; result == null && i <= 100; i++) {
			Double currentPercentile = fightPercentiles.get(i);
			//the percentile table can be empty (no active players, dark stream); no entry means no percentile
			if (currentPercentile != null && ratio < currentPercentile) {
				result = i - 1;
			}
		}

		return result;
	}
	
	protected String createDateStringWithTimezone(TimeZone zone, Date date) {
		Calendar calendar = Calendar.getInstance();
		calendar.setTime(date);
		SimpleDateFormat sdf = new SimpleDateFormat("MMM dd yyyy");

		//Here you say to java the initial timezone. This is the secret
		sdf.setTimeZone(zone);
		//Will print in UTC
		String result = sdf.format(calendar.getTime());    

		return result;
	}
	
	protected Integer getLeaderboardPosition(String player) {
		String lowercasePlayer = StringUtils.lowerCase(player);
		Integer position = this.playerLeaderboardCache.get(lowercasePlayer);
		return position;
	}
}
