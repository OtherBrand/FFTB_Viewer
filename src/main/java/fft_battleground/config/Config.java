package fft_battleground.config;

import java.util.List;
import java.util.Timer;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.retry.annotation.EnableRetry;
import org.springframework.scheduling.annotation.EnableScheduling;

import com.gikk.twirk.Twirk;
import com.gikk.twirk.TwirkBuilder;

import fft_battleground.discord.WebhookManager;
import fft_battleground.event.EventDetector;
import fft_battleground.event.detector.AllegianceDetector;
import fft_battleground.event.detector.BadBetDetector;
import fft_battleground.event.detector.BalanceDetector;
import fft_battleground.event.detector.BetDetector;
import fft_battleground.event.detector.BetInfoEventDetector;
import fft_battleground.event.detector.BettingBeginsDetector;
import fft_battleground.event.detector.BettingEndsDetector;
import fft_battleground.event.detector.BonusDetector;
import fft_battleground.event.detector.BuySkillDetector;
import fft_battleground.event.detector.DontFightDetector;
import fft_battleground.event.detector.FightBeginsDetector;
import fft_battleground.event.detector.FightEntryDetector;
import fft_battleground.event.detector.GiftSkillDetector;
import fft_battleground.event.detector.HypeDetector;
import fft_battleground.event.detector.LevelUpDetector;
import fft_battleground.event.detector.MusicDetector;
import fft_battleground.event.detector.OtherPlayerBalanceDetector;
import fft_battleground.event.detector.OtherPlayerExpDetector;
import fft_battleground.event.detector.OtherPlayerInvalidFightCombinationDetector;
import fft_battleground.event.detector.OtherPlayerInvalidFightEntryClassDetector;
import fft_battleground.event.detector.OtherPlayerInvalidFightEntrySexDetector;
import fft_battleground.event.detector.OtherPlayerInvalidFightEntryTournamentStartedDetector;
import fft_battleground.event.detector.OtherPlayerSkillOnCooldownDetector;
import fft_battleground.event.detector.OtherPlayerUnownedSkillDetector;
import fft_battleground.event.detector.PlayerSkillDetector;
import fft_battleground.event.detector.PortraitEventDetector;
import fft_battleground.event.detector.PrestigeAscensionDetector;
import fft_battleground.event.detector.ResultEventDetector;
import fft_battleground.event.detector.RiserSkillWinDetector;
import fft_battleground.event.detector.SkillDropDetector;
import fft_battleground.event.detector.SkillWinEventDetector;
import fft_battleground.image.model.Images;
import fft_battleground.event.detector.OtherPlayerSnubEventDetector;
import fft_battleground.irc.TwirkChatListenerAdapter;
import fft_battleground.model.ChatMessage;
import fft_battleground.repo.repository.ErrorMessageEntryRepo;
import fft_battleground.util.Router;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;


@Configuration
@EnableScheduling
@EnableRetry
@Slf4j
public class Config {


	@Bean
	@SneakyThrows
    public Twirk ircChatBot(@Value("${irc.username}") String username, @Value("${irc.password}") String password, @Value("${irc.channel}") String channel, 
    		Router<ChatMessage> chatMessageRouter, fft_battleground.metrics.StatusService statusService) {
		
		final Twirk twirk = new TwirkBuilder("#" +channel, username, password)
								.build();				//Create the Twirk object
		
		twirk.addIrcListener(new TwirkChatListenerAdapter(chatMessageRouter, channel, statusService));
		
		return twirk;
	}
	
	@Bean
	public Images images() {
		log.debug("loading images");
		Resource imagesJsonFileResource = new ClassPathResource("/images.json");
		String portraitLocation = "/static/img/portraits/";
		String browserBaseUrl = "/images/portraits/";
		Images images = new Images(imagesJsonFileResource, portraitLocation, browserBaseUrl);
		
		return images;
	}
	
	@Bean
	public List<EventDetector<?>> detectors(@Value("${irc.username}") String username) {
		return List.of(new EventDetector<?>[]{
			new BetDetector(), new LevelUpDetector(), new ResultEventDetector(), new SkillWinEventDetector(), new RiserSkillWinDetector(),
			new BalanceDetector(username), new OtherPlayerBalanceDetector(), new PlayerSkillDetector(), new MusicDetector(),
			new BettingBeginsDetector(), new AllegianceDetector(), new BetInfoEventDetector(), new SkillDropDetector(), new DontFightDetector(),
			new BettingEndsDetector(), new BadBetDetector(), new BuySkillDetector(), new PortraitEventDetector(), new OtherPlayerSkillOnCooldownDetector(),
			new FightEntryDetector(), new FightBeginsDetector(), new OtherPlayerInvalidFightCombinationDetector(), new OtherPlayerInvalidFightEntryClassDetector(),
			new OtherPlayerUnownedSkillDetector(), new OtherPlayerExpDetector(), new GiftSkillDetector(), new PrestigeAscensionDetector(), new OtherPlayerSnubEventDetector(),
			new OtherPlayerInvalidFightEntrySexDetector(), new OtherPlayerInvalidFightEntryTournamentStartedDetector(), new BonusDetector(), new HypeDetector()
		});
	}
	
	@Bean
	public MeterRegistry meterRegistry() {
		return new SimpleMeterRegistry();
	}
	
	@Bean
	public WebhookManager errorWebhookManager(@Value("${errorWebhookUrl}") String webhookUrl, @Value("${hostnameUrl}") String hostname, @Autowired ErrorMessageEntryRepo errorMessageEntryRepo) {
		WebhookManager errorWebhookManager = new WebhookManager(webhookUrl, hostname, errorMessageEntryRepo);
		return errorWebhookManager;
	}
	
	@Bean
	public WebhookManager ascensionWebhookManager(@Value("${ascensionWebhookUrl}") String webhookUrl, @Value("${hostnameUrl}") String hostname, @Autowired ErrorMessageEntryRepo errorMessageEntryRepo) {
		WebhookManager errorWebhookManager = new WebhookManager(webhookUrl, hostname, errorMessageEntryRepo);
		return errorWebhookManager;
	}
	
	@Bean
	public WebhookManager noisyWebhookManager(@Value("${noisyWebhookUrl}")  String webhookUrl, @Value("${hostnameUrl}") String hostname, @Autowired ErrorMessageEntryRepo errorMessageEntryRepo) {
		WebhookManager noisyWebhookManager = new WebhookManager(webhookUrl, hostname, errorMessageEntryRepo);
		return noisyWebhookManager;
	}
	
	@Bean
	public Timer battlegroundCacheTimer() {
		return new Timer();
	}
}
