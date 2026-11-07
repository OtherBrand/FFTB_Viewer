package fft_battleground.irc;

import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import javax.annotation.PostConstruct;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.gikk.twirk.Twirk;

import fft_battleground.CoreApplication;
import fft_battleground.discord.WebhookManager;
import fft_battleground.exception.IrcConnectionException;
import lombok.Getter;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
public class IrcChatbotThread extends Thread {

    @Value("${irc.channel}")
    private String channel;
    
    @Autowired
	private WebhookManager errorWebhookManager;
    
    @Autowired
    private IrcInitialConnectManager ircInitialConnectManager;
	
    @Autowired
    private Twirk ircChatBot;
    
    @Autowired
    private DisconnectListener disconnectListener;
    
    //ObjectProvider: CoreApplication <-> IrcChatbotThread mutual injection predates
    //Boot 2.6's circular-reference prohibition; the reference is only used at runtime
    //(shutdown on fatal IRC failure), never during construction
    @Autowired
    private org.springframework.beans.factory.ObjectProvider<CoreApplication> coreApplicationProvider;
    
    //should remain locked if IRC connection fails
    @Getter 
    private Lock ircConnectionSuccessfulLock = new ReentrantLock();
    
    @PostConstruct
    private void setup() {
    	this.ircChatBot.addIrcListener(this.disconnectListener);
    }

    public IrcChatbotThread() {
        super(IrcChatbotThread.class.getName());
    }

    @SneakyThrows
    public void run() {
        log.info("Starting IrcChatbotThread");
        log.info("Joining channel: " + "#" + this.channel);
        try {
	        boolean startupSuccessful = this.ircInitialConnectManager.connectToIrc(this.ircChatBot);
        } catch(IrcConnectionException e) {
        	log.error(e.getMessage(), e);
        	this.errorWebhookManager.sendShutdownNotice(e, e.getMessage());
        	this.coreApplicationProvider.getIfAvailable().shutdownServer(e);
        }
    }

    public void killBot() {
        this.ircChatBot.close();
    }

}