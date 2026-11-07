package fft_battleground.irc;

import com.gikk.twirk.events.TwirkListener;
import com.gikk.twirk.types.twitchMessage.TwitchMessage;
import com.gikk.twirk.types.users.TwitchUser;

import fft_battleground.model.ChatMessage;
import fft_battleground.util.Router;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class TwirkChatListenerAdapter implements TwirkListener {
	
    private Router<ChatMessage> chatMessageRouter;
    private String channel;
    private fft_battleground.metrics.StatusService statusService;

    public TwirkChatListenerAdapter(Router<ChatMessage> chatMessageRouter, String channel,
            fft_battleground.metrics.StatusService statusService) {
        this.chatMessageRouter = chatMessageRouter;
        this.channel = channel;
        this.statusService = statusService;
    }
    
    public TwirkChatListenerAdapter(Router<ChatMessage> chatMessageRouter, String channel) {
    	this.chatMessageRouter = chatMessageRouter;
    	this.channel = channel;
    }
	
	@Override
	public void onPrivMsg( TwitchUser sender, TwitchMessage message) {
		if(this.statusService != null) {
			this.statusService.markIrcMessageReceived();
		}
		//carry the Twitch-native emote codes so word-cloud ingestion can drop them exactly
		java.util.Set<String> emoteCodes = new java.util.HashSet<>();
		if(message.hasEmotes()) {
			for(com.gikk.twirk.types.emote.Emote emote : message.getEmotes()) {
				emoteCodes.add(emote.getPattern());
			}
		}
		this.chatMessageRouter.sendDataToQueues(new ChatMessage(this.channel, sender.getUserName(), message.getContent(), sender.isSub(), emoteCodes));
	}

}
