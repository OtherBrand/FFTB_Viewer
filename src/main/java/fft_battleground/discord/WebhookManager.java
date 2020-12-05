package fft_battleground.discord;

import java.time.Instant;
import java.util.Date;

import org.apache.commons.lang3.exception.ExceptionUtils;
import org.springframework.transaction.annotation.Transactional;

import club.minnced.discord.webhook.WebhookClient;
import club.minnced.discord.webhook.send.WebhookEmbed;
import club.minnced.discord.webhook.send.WebhookEmbed.EmbedTitle;
import club.minnced.discord.webhook.send.WebhookEmbedBuilder;
import fft_battleground.repo.model.ErrorMessageEntry;
import fft_battleground.repo.repository.ErrorMessageEntryRepo;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class WebhookManager {
	private static final String errorUrlPath = "/error/stacktrace/";
	
	private String hostname;
	private WebhookClient client;
	private ErrorMessageEntryRepo errorMessageEntryRepo;
	
	public WebhookManager(String webhookUrl, String hostname, ErrorMessageEntryRepo errorMessageEntryRepo) {
		this.client = WebhookClient.withUrl(webhookUrl);
		this.hostname = hostname;
		this.errorMessageEntryRepo = errorMessageEntryRepo;
	}
	
	public void sendMessage(String message) {
		this.client.send(message);
	}
	
	public void sendException(Exception e) {
		this.sendException(e, "");
	}
	
	public void sendException(Exception e, String description) {
		this.sendExceptionEmbed(e, description, EmbedColor.BLUE.getColorCode());
	}
	
	public void sendShutdownNotice(Exception e, String description) {
		this.sendExceptionEmbed(e, description, EmbedColor.RED.getColorCode());
	}
	
	protected void sendExceptionEmbed(Exception e, String description, int colorCode) {
		String errorMessage = ExceptionUtils.getMessage(e);
		ErrorMessageEntry errorMessageEntry = this.writeNewErrorMessage(e);
		String url = this.generateUrl(errorMessageEntry.getErrorMessageId());
		String embedDescription = errorMessage + "\n" + description;
		EmbedTitle title = new EmbedTitle("Exception found in Viewer: #" + errorMessageEntry.getErrorMessageId().toString(), url);
		WebhookEmbed embed= new WebhookEmbedBuilder().setColor(colorCode).setTitle(title)
				.setDescription(embedDescription).setTimestamp(Instant.ofEpochMilli(new Date().getTime()))
				.build();
		this.client.send(embed);
	}
	
	protected String generateUrl(Long id) {
		String url = "https://" + this.hostname + errorUrlPath + id.toString();
		return url;
	}
	
	@Transactional
	protected ErrorMessageEntry writeNewErrorMessage(Throwable e) {
		ErrorMessageEntry entry = new ErrorMessageEntry(e);
		entry = this.errorMessageEntryRepo.saveAndFlush(entry);
		
		return entry;
	}
	

}

enum EmbedColor {
	BLUE(0x166FF5),
	RED(0xFF0000),
	GREEN(0x00FF3C);
	
	private int colorCode;
	
	private EmbedColor(int colorCode) {
		this.colorCode = colorCode;
	}
	
	public int getColorCode() {
		int result = this.colorCode;
		return result;
	}
}
