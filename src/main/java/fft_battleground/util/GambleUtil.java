package fft_battleground.util;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.math.NumberUtils;

import fft_battleground.event.detector.model.BetEvent;
import fft_battleground.exception.NotANumberBetException;
import fft_battleground.repo.model.PlayerRecord;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public class GambleUtil {
	
	public static final Integer MINIMUM_BET = 100;
	public static final Integer SUBSCRIBER_MINIMUM_BET = 200;
	public static final Short DEFAULT_REMAINING_EXP = 100;
	public static final Integer MAX_BET = 1000;
	
	public static Float bettingOdds(Integer thisTeamAmount, Integer otherTeamAmount) {
		//a zero/null own pot cannot set a price — guard to 1.0. (The old try/catch was dead code:
		//the float cast makes this float division, which returns Infinity instead of throwing.)
		if(thisTeamAmount == null || thisTeamAmount <= 0) {
			return 1f;
		}
		return (float) otherTeamAmount / thisTeamAmount;
	}
	
	public static Float bettingPercentage(Integer thisTeamAmount, Integer otherTeamAmount) {
		Float odds = 1f;
		try {
			odds = ((float) thisTeamAmount)/((float) thisTeamAmount + (float) otherTeamAmount);
		} catch(ArithmeticException e) {
			odds = 1f;
		}
		
		return odds;
	}
	
	public static Integer getAmountUpdateFromBet(Integer betAmount, Float bettingOdds, boolean win) {
		Integer newAmount = 0;
		if(win) {
			newAmount = (int) (bettingOdds * betAmount);
		} else {
			newAmount = (-1) * betAmount;
		}
		
		return newAmount;
	}
	
	public static Integer getBetAmountFromBetString(PlayerRecord player, BetEvent event) throws NotANumberBetException {
		Integer value = 0;
		switch(event.getBetType()) {
		case VALUE:
			try {
			value = Integer.valueOf(event.getBetAmount());
			} catch(NumberFormatException e) {
				value = 0;
			}
			if(event.isAllinbutFlag()) {
				value = player.getLastKnownAmount() - value;
			}
			break;
		case PERCENTAGE:
			String filteredValue = StringUtils.remove(event.getBetAmount(), '%');
			Integer lastKnownAmount = player.getLastKnownAmount();
			if(lastKnownAmount != null && NumberUtils.isCreatable(filteredValue)) {
				BigDecimal percentage = new BigDecimal(filteredValue);
				value = BigDecimal.valueOf(lastKnownAmount).multiply(percentage).multiply(BigDecimal.valueOf(0.01f)).setScale(0, RoundingMode.HALF_UP).intValue();
				if(event.isAllinbutFlag()) {
					value = lastKnownAmount - value;
				}
			}
			break;
		case ALLIN:
			if(player.getLastKnownAmount() != null) {
				value = player.getLastKnownAmount();
			} else if(player.getLastKnownLevel() != null) {
				value = getMinimumBetForLevel(player.getLastKnownLevel(), player.prestigeLevel(), event.getIsSubscriber());
			} else {
				value = getMinimumBetForLevel((short)0, player.prestigeLevel(), event.getIsSubscriber());
			}
			break;
		case HALF:
			if(player.getLastKnownAmount() != null) {
				value = player.getLastKnownAmount()/2;
			} else if(player.getLastKnownLevel() != null) {
				value = getMinimumBetForLevel(player.getLastKnownLevel(), player.prestigeLevel(), event.getIsSubscriber());
			} else {
				value = getMinimumBetForLevel((short)0, player.prestigeLevel(), event.getIsSubscriber());
			}
			//no need to implement allinbut code here, since allinbut half is the same as bet half.
			break;
		case FLOOR:
			if(player.getLastKnownLevel() != null) {
				value = getMinimumBetForLevel(player.getLastKnownLevel(), player.prestigeLevel(), event.getIsSubscriber());
			} else {
				value = getMinimumBetForLevel((short)0, player.prestigeLevel(), event.getIsSubscriber());
			}
			if(StringUtils.endsWith(event.getBetAmount(), "f")) {
				String cleanedBetText = StringUtils.remove(event.getBetAmount(), "f");
				if(event.isAllinbutFlag()) {
					cleanedBetText = StringUtils.remove(cleanedBetText, "ab");
				}
				
				if(StringUtils.equalsIgnoreCase(cleanedBetText, "")) {
					/*
					 * if we've removed both "ab" and "f" and come up with a blank string, we need to add a 1 since its implied.
					 * This covers bets like "bet f red" and maybe "bet abf red"
					 */
					cleanedBetText = "1";  
				}
				
				BigDecimal multiplier = null;
				try {
					multiplier = new BigDecimal(cleanedBetText);
				} catch(NumberFormatException e) {
					throw new NotANumberBetException(e);
				}
				value = BigDecimal.valueOf(value).multiply(multiplier).setScale(0, RoundingMode.HALF_UP).intValue();
			}
			if(event.isAllinbutFlag()) {
				value = player.getLastKnownAmount() - value;
			}
			break;
		default:
			break;
		}
		
		if(value < 0) {
			log.warn("player had bet value below zero {}", player.getPlayer());
		}
		
		return value;
	}
	
	public static Integer getMinimumBetForLevel(Short level, boolean isSubscriber) {
		Integer result = getMinimumBetForLevel(level, 0, isSubscriber);
		return result;
	}
	
	public static Integer getMinimumBetForLevel(Short level, Integer prestigeLevel, boolean isSubscriber) {
		Integer minimumBet = null;
		if(level != null && level <= 100) {
			minimumBet = (100 * prestigeLevel) + (4 * level) + getMinimumBetForBettor(isSubscriber);
		} else if(level != null && level > 100) {
			minimumBet = (100 * prestigeLevel) + (4 * 100) + getMinimumBetForBettor(isSubscriber);
		} else {
			minimumBet = (100 * prestigeLevel) + getMinimumBetForBettor(isSubscriber);
		}
		return minimumBet;
	}
	

	
	public static Integer getMinimumBetForBettor(boolean isSubscriber) {
		Integer result = isSubscriber ? SUBSCRIBER_MINIMUM_BET : MINIMUM_BET;
		return result;
	}
	
	public static String cleanString(String str) {
		String result = str;
		result = StringUtils.lowerCase(result);
		result = StringUtils.replace(result, ",", "");
		result = StringUtils.trim(result);
		return result;
	}
	
	public static int calculatePercentile(Float leftScore, Float rightScore, Map<Integer, Integer> percentiles) {
		if(percentiles == null || percentiles.isEmpty()) {
			return 50;
		}
		float leftScoreVal = leftScore != null ? leftScore.floatValue() : 0f;
		float rightScoreVal = rightScore != null ? rightScore.floatValue() : 0f;
		int scoreDifference = (int) Math.abs(leftScoreVal - rightScoreVal);
		int percentile = 0;
		for(int i = 1; i < 100 && percentiles.get(i) < scoreDifference; i++) {
			percentile = i;
		}
		return percentile;
	}
	
}
